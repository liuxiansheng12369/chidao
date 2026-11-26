/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * StoreCatalog：目录信封的验签、逐字段校验、持久化与镜像下载。
 * 规则与上游 ColorOS-Live-Lyrics-Bridge 的 CatalogVerifier 对齐：
 * 信封签名通过之前，payload 一个字节都不读；任何一条规则不过，整份目录作废，
 * 不存在"部分可信"的目录。revision 只升不降，落盘在改动使用之前。
 */

package com.liuxiansheng.lyricon.app.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.math.BigInteger

/** 拒绝原因：给开发和用户都能读的细节。 */
data class CatalogRejection(val reason: String, val detail: String)

sealed interface CatalogVerdict {
    data class Accepted(
        val catalog: StoreCatalog,
        val freshness: StoreFreshness,
        val payloadSha256: String,
    ) : CatalogVerdict

    data class Rejected(val rejection: CatalogRejection) : CatalogVerdict
}

/** 已接受的 revision 与它的 payload 指纹；用于堵回滚与同版冲突。 */
data class AcceptedRevision(val revision: Long, val payloadSha256: String)

object StoreCatalogSpec {
    const val ENVELOPE_VERSION = 1
    const val SCHEMA_VERSION = 1
    const val ALGORITHM = "ECDSA_P256_SHA256"
    const val CLOCK_SKEW_SECONDS = 24L * 3600
    const val MAX_VALIDITY_SECONDS = 190L * 24 * 3600
    const val MAX_ENVELOPE_BYTES = 256 * 1024
    const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024
    const val MAX_HOSTS = 16
    const val MAX_ENTRIES = 64
    const val MAX_URLS = 8
    const val MAX_SIGNERS = 4

    val DOMAIN: ByteArray = "CLL-CATALOG-V1\n".toByteArray(StandardCharsets.US_ASCII)

    val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    val APPLICATION_ID = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    val HEX64 = Regex("^[0-9a-f]{64}$")
    val HEX40 = Regex("^[0-9a-f]{40}$")
    val ALL_ZERO = Regex("^0+$")
    val CONTROL = Regex("[\\u0000-\\u0009\\u000b-\\u001f\\u007f]")
}

/**
 * 客户端预置的目录公钥。
 * 前两把与上游客户端内置的一致（Andrea-lyz 的自建目录）；第三把是本项目自己的目录公钥。
 * 目录的签名密钥与 provider 的签名证书是两套东西，别混。
 */
object StoreCatalogTrust {
    val KEYS: Map<String, String> = mapOf(
        "cll-catalog-2026a" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbW5YgxEtOIU7NMZDsWd+oVjdQuCb55eiaSlXGlkN6Pyg8fdGwi9sAzcL+QVRjyb794VHO9fyklXFTto3rjDOEg==",
        "cll-catalog-2026b" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENbTQvHtHeAfGoAEHAAWbUZxhKFKEIuYDlvTvYegpF5U2KOj+98HFv++QZtlfxxDzmmeQGlfVBIqGcW++oXr1nQ==",
        "cll-catalog-liuxiansheng-2026a" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDF7twJY4BQW5rDdW26F+jaLp/WqJNNMChZogA6gytf+xokd4tXzUckxUhTBs4DOsoPpooqiJK9IIeUzl2jiFtg==",
    )

    /** 上游目录所允许的 provider 签名证书指纹（SHA-256 小写十六进制）。 */
    const val RELEASE_CERT_SHA256 = "ff38544ba21922c35989097fe6f2ad0bda434b4d0bb611e016d51d7862d36195"

    /**
     * 本项目自建目录所允许的 provider 签名证书指纹。
     * 即 G:\词岛源码文件夹\签名\lyricprovider-release.jks 的证书，由我们自己的 CI 签名。
     */
    const val OWN_CERT_SHA256 = "3131da2c57fd316fa3beb9a004404572ddd1a7dd96dfcc3866332e7e5fdaadf0"

    private val P256_ORDER = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16
    )

    fun decodeP256(spkiBase64: String): PublicKey {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)))
        require(key is ECPublicKey && key.params.order == P256_ORDER) {
            "目录公钥必须是 ECDSA P-256"
        }
        return key
    }
}

/** 一份目录的信任策略：允许用哪把目录公钥签、允许哪些 provider 证书进目录、条目标到哪个来源。 */
data class CatalogPolicy(
    val keyIds: Set<String>,
    val signers: Set<String>,
    val source: StoreSource = StoreSource.UPSTREAM,
)

object StoreCatalogVerifier {

    private class Reject(val reason: String, val detail: String) : Exception(reason, null, false, false)

    fun verify(
        envelopeBytes: ByteArray,
        nowEpochSeconds: Long,
        lastAccepted: AcceptedRevision?,
        policy: CatalogPolicy,
    ): CatalogVerdict = try {
        acceptOrThrow(envelopeBytes, nowEpochSeconds, lastAccepted, policy)
    } catch (r: Reject) {
        CatalogVerdict.Rejected(CatalogRejection(r.reason, r.detail))
    }

    private fun acceptOrThrow(
        envelopeBytes: ByteArray,
        now: Long,
        lastAccepted: AcceptedRevision?,
        policy: CatalogPolicy,
    ): CatalogVerdict.Accepted {
        if (envelopeBytes.size > StoreCatalogSpec.MAX_ENVELOPE_BYTES) reject("TOO_LARGE", "envelope")
        val envelope = parseObject(envelopeBytes, "MALFORMED_ENVELOPE")
        if (envelope.opt("envelopeVersion") != StoreCatalogSpec.ENVELOPE_VERSION) {
            reject("UNSUPPORTED_ENVELOPE", "envelopeVersion")
        }
        if (envelope.opt("algorithm") != StoreCatalogSpec.ALGORITHM) reject("UNSUPPORTED_ALGORITHM", "algorithm")
        val keyId = string(envelope, "keyId", "MALFORMED_ENVELOPE")
        if (keyId !in policy.keyIds) reject("UNKNOWN_KEY", keyId)
        val keyB64 = StoreCatalogTrust.KEYS[keyId] ?: reject("UNKNOWN_KEY", keyId)
        val payload = base64(envelope, "payload")
        val signature = base64(envelope, "signature")

        val key = try {
            StoreCatalogTrust.decodeP256(keyB64)
        } catch (e: Exception) {
            reject("UNKNOWN_KEY", keyId)
        }
        val signatureValid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(StoreCatalogSpec.DOMAIN)
                update(payload)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) reject("BAD_SIGNATURE", keyId)

        // 以下只读已验签的字节
        val digest = sha256Hex(payload)
        val root = parseObject(payload, "MALFORMED_PAYLOAD")
        if (root.opt("schemaVersion") != StoreCatalogSpec.SCHEMA_VERSION) {
            reject("UNSUPPORTED_SCHEMA", "schemaVersion")
        }
        val revision = long(root, "catalogRevision", "MALFORMED_PAYLOAD")
        val issued = long(root, "issuedAtEpochSeconds", "MALFORMED_PAYLOAD")
        val expires = long(root, "expiresAtEpochSeconds", "MALFORMED_PAYLOAD")
        if (revision < 1) reject("MALFORMED_PAYLOAD", "catalogRevision")
        if (issued <= 0 || expires <= issued || expires - issued > StoreCatalogSpec.MAX_VALIDITY_SECONDS) {
            reject("INVALID_VALIDITY", "issued/expires")
        }
        val channel = string(root, "channel", "MALFORMED_PAYLOAD")
        if (channel != "stable") reject("WRONG_CHANNEL", channel)
        if (now + StoreCatalogSpec.CLOCK_SKEW_SECONDS < issued) reject("NOT_YET_VALID", "issuedAt")
        if (lastAccepted != null) {
            if (revision < lastAccepted.revision) reject("ROLLBACK", "$revision<${lastAccepted.revision}")
            if (revision == lastAccepted.revision && digest != lastAccepted.payloadSha256) {
                reject("REVISION_CONFLICT", "$revision")
            }
        }

        val hostsJson = array(root, "trustedHosts", "MALFORMED_PAYLOAD")
        if (hostsJson.length() !in 1..StoreCatalogSpec.MAX_HOSTS) reject("MALFORMED_PAYLOAD", "trustedHosts")
        val hosts = (0 until hostsJson.length()).map { i ->
            val host = hostsJson.opt(i) as? String
            if (host == null || !StoreCatalogSpec.HOST.matches(host)) {
                reject("MALFORMED_PAYLOAD", "trustedHosts[$i]")
            }
            host
        }.toSet()

        val entriesJson = array(root, "entries", "MALFORMED_PAYLOAD")
        if (entriesJson.length() > StoreCatalogSpec.MAX_ENTRIES) reject("MALFORMED_PAYLOAD", "entries")
        val entries = (0 until entriesJson.length()).map { i ->
            val item = entriesJson.opt(i) as? JSONObject ?: reject("INVALID_ENTRY", "entries[$i]")
            entry(item, "entries[$i]", hosts, policy.signers, policy.source)
        }
        if (entries.map { it.providerId }.toSet().size != entries.size ||
            entries.map { it.applicationId }.toSet().size != entries.size
        ) {
            reject("DUPLICATE_ENTRY", "providerId/applicationId")
        }

        val freshness = if (now > expires + StoreCatalogSpec.CLOCK_SKEW_SECONDS) {
            StoreFreshness.EXPIRED
        } else {
            StoreFreshness.FRESH
        }
        return CatalogVerdict.Accepted(
            StoreCatalog(revision, issued, expires, channel, hosts, entries),
            freshness,
            digest,
        )
    }

    private fun entry(
        json: JSONObject,
        at: String,
        hosts: Set<String>,
        allowedSigners: Set<String>,
        source: StoreSource,
    ): StoreEntry {
        val bad = "INVALID_ENTRY"
        val providerId = string(json, "providerId", bad)
            .also { if (!StoreCatalogSpec.PROVIDER_ID.matches(it)) reject(bad, "$at.providerId") }
        val kind = StoreKind.fromWire(string(json, "kind", bad)) ?: reject(bad, "$at.kind")
        val applicationId = string(json, "applicationId", bad)
            .also { if (!StoreCatalogSpec.APPLICATION_ID.matches(it)) reject(bad, "$at.applicationId") }
        val versionName = string(json, "versionName", bad).also {
            if (it.isBlank() || it.length > 64 || StoreCatalogSpec.CONTROL.containsMatchIn(it)) {
                reject(bad, "$at.versionName")
            }
        }
        val versionCode = long(json, "versionCode", bad).also { if (it < 1) reject(bad, "$at.versionCode") }
        val minSdk = long(json, "minSdk", bad).also { if (it !in 1L..1000L) reject(bad, "$at.minSdk") }
        val minStoreProtocol = long(json, "minStoreProtocol", bad)
            .also { if (it !in 1L..Int.MAX_VALUE.toLong()) reject(bad, "$at.minStoreProtocol") }
        val sourceCommit = string(json, "sourceCommit", bad).also {
            if (!StoreCatalogSpec.HEX40.matches(it) || StoreCatalogSpec.ALL_ZERO.matches(it)) {
                reject(bad, "$at.sourceCommit")
            }
        }
        val notes = when (val raw = json.opt("releaseNotes")) {
            null -> null
            is String -> raw.also {
                if (it.length > 4000 || StoreCatalogSpec.CONTROL.containsMatchIn(it)) reject(bad, "$at.releaseNotes")
            }
            else -> reject(bad, "$at.releaseNotes")
        }
        // 目录里带了展示名就用目录的，没带（老目录、老 CI）回退客户端本地表。
        // 这里不因字段异常整份目录作废：脏值直接忽略，退回本地表，别把整个商店打挂。
        val displayName = (json.opt("displayName") as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 64 && !StoreCatalogSpec.CONTROL.containsMatchIn(it) }
            ?: storeDisplayName(providerId)

        // 图标也跟名字一样由目录下发：脏值/非法主机一律当没有，回退本地图标或首字母块。
        val iconUrl = (json.opt("iconUrl") as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?.takeIf { raw ->
                runCatching {
                    val uri = URI(raw)
                    uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
                        (uri.port == -1 || uri.port == 443) &&
                        uri.host != null && uri.host in hosts
                }.getOrDefault(false)
            }

        val artifactJson = json.opt("artifact") as? JSONObject ?: reject(bad, "$at.artifact")
        val urlsJson = array(artifactJson, "urls", bad)
        if (urlsJson.length() !in 1..StoreCatalogSpec.MAX_URLS) reject(bad, "$at.artifact.urls")
        val urls = (0 until urlsJson.length()).map { i ->
            val raw = urlsJson.opt(i) as? String ?: reject(bad, "$at.artifact.urls[$i]")
            raw.also { checkUrl(it, hosts, "$at.artifact.urls[$i]") }
        }
        val bytes = long(artifactJson, "bytes", bad)
            .also { if (it !in 1L..StoreCatalogSpec.MAX_ARTIFACT_BYTES) reject(bad, "$at.artifact.bytes") }
        val sha256 = string(artifactJson, "sha256", bad).also {
            if (!StoreCatalogSpec.HEX64.matches(it) || StoreCatalogSpec.ALL_ZERO.matches(it)) {
                reject(bad, "$at.artifact.sha256")
            }
        }
        val signersJson = array(artifactJson, "signerSha256", bad)
        if (signersJson.length() !in 1..StoreCatalogSpec.MAX_SIGNERS) reject(bad, "$at.artifact.signerSha256")
        val signers = (0 until signersJson.length()).map { i ->
            val signer = signersJson.opt(i) as? String
            if (signer == null || !StoreCatalogSpec.HEX64.matches(signer) || StoreCatalogSpec.ALL_ZERO.matches(signer)) {
                reject(bad, "$at.artifact.signerSha256[$i]")
            }
            if (signer !in allowedSigners) {
                reject("UNTRUSTED_SIGNER", "$at.artifact.signerSha256[$i]")
            }
            signer
        }.toSet()

        return StoreEntry(
            providerId = providerId,
            kind = kind,
            applicationId = applicationId,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk.toInt(),
            minStoreProtocol = minStoreProtocol.toInt(),
            sourceCommit = sourceCommit,
            releaseNotes = notes,
            artifact = StoreArtifact(urls, bytes, sha256, signers),
            displayName = displayName,
            iconUrl = iconUrl,
            source = source,
        )
    }

    private fun checkUrl(raw: String, hosts: Set<String>, at: String) {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            reject("UNTRUSTED_URL", at)
        }
        val plainHttps = uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443)
        if (!plainHttps || uri.host == null || uri.host !in hosts) reject("UNTRUSTED_URL", at)
    }

    private fun reject(reason: String, detail: String): Nothing = throw Reject(reason, detail)

    private fun parseObject(bytes: ByteArray, reason: String): JSONObject {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            reject(reason, "utf-8")
        }
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            reject(reason, "json")
        }
    }

    private fun string(json: JSONObject, name: String, reason: String): String =
        json.opt(name) as? String ?: reject(reason, name)

    /** 只接受整数形态的数字：org.json 会把字符串和浮点也当数字，这里不放行。 */
    private fun long(json: JSONObject, name: String, reason: String): Long =
        when (val value = json.opt(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> reject(reason, name)
        }

    private fun array(json: JSONObject, name: String, reason: String): JSONArray =
        json.opt(name) as? JSONArray ?: reject(reason, name)

    private fun base64(json: JSONObject, name: String): ByteArray {
        val text = string(json, name, "MALFORMED_ENVELOPE")
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            reject("MALFORMED_ENVELOPE", name)
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** 目录的持久化：revision floor + 原始信封字节，原子写入。 */
class StoreCatalogFile(private val file: File) {

    data class Persisted(val accepted: AcceptedRevision, val envelope: ByteArray)

    fun read(): Persisted? = try {
        val json = JSONObject(file.readText())
        val envelope = Base64.getDecoder().decode(json.getString("envelope"))
        Persisted(
            AcceptedRevision(json.getLong("revision"), json.getString("payloadSha256")),
            envelope,
        )
    } catch (_: Exception) {
        null
    }

    @Throws(IOException::class)
    fun write(value: Persisted) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建 $parent")
        val json = JSONObject()
            .put("revision", value.accepted.revision)
            .put("payloadSha256", value.accepted.payloadSha256)
            .put("envelope", Base64.getEncoder().encodeToString(value.envelope))
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun clear() {
        file.delete()
    }
}

/** 目录下载：只认 HTTPS + 白名单主机，正文超出上限返回 null（交给校验器报 TOO_LARGE）。 */
object CatalogHttp {
    fun download(url: String, hosts: Set<String>): ByteArray? {
        val host = URL(url).host?.lowercase()
        if (host == null || host !in hosts) throw IOException("目录主机不在白名单：$host")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Chidao-Store")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val limit = StoreCatalogSpec.MAX_ENVELOPE_BYTES
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) return null
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}

/** 上游目录：Providers 仓库 store-catalog 分支，jsDelivr 优先，raw GitHub 兜底。 */
object StoreCatalogSource {
    private const val REPOSITORY = "Andrea-lyz/ColorOS-Live-Lyrics-Providers"
    private const val BRANCH = "store-catalog"
    private const val PATH = "catalog/stable.json"

    val urls: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/$REPOSITORY@$BRANCH/$PATH",
        "https://raw.githubusercontent.com/$REPOSITORY/$BRANCH/$PATH",
    )
    val hosts: Set<String> = setOf("cdn.jsdelivr.net", "raw.githubusercontent.com")

    val policy: CatalogPolicy = CatalogPolicy(
        keyIds = setOf("cll-catalog-2026a", "cll-catalog-2026b"),
        signers = setOf(StoreCatalogTrust.RELEASE_CERT_SHA256),
        source = StoreSource.UPSTREAM,
    )

    fun download(url: String): ByteArray? = CatalogHttp.download(url, hosts)
}

/**
 * 本项目第二份自建目录：ColorOS 歌词源镜像仓（Andrea 那批 provider，我们自己编译、自己签名）。
 * 与 LyricProvider 的自建目录并列：同样是我们那把目录公钥、同样只放我们自己的证书，
 * 但 revision 地板各记各的，互不影响。
 */
object StoreColorOsCatalogSource {
    private const val REPOSITORY = "liuxiansheng12369/ColorOS-Live-Lyrics-Providers"
    private const val BRANCH = "store-catalog"
    private const val PATH = "catalog/stable.json"
    private const val GITEE_REPOSITORY = "luminous-brightness/ColorOS-Live-Lyrics-Providers"

    /**
     * 国内优先，逐级回退：Gitee raw（国内直连，不受 CDN 缓存影响）→ jsDelivr → GitHub raw。
     * 三个都拿不到时由上层保留本地缓存的那份目录，商店不至于空白。
     */
    val urls: List<String> = listOf(
        "https://gitee.com/$GITEE_REPOSITORY/raw/$BRANCH/$PATH",
        "https://cdn.jsdelivr.net/gh/$REPOSITORY@$BRANCH/$PATH",
        "https://raw.githubusercontent.com/$REPOSITORY/$BRANCH/$PATH",
    )
    val hosts: Set<String> = setOf(
        "gitee.com", "raw.giteeusercontent.com",
        "cdn.jsdelivr.net", "raw.githubusercontent.com",
    )

    val policy: CatalogPolicy = CatalogPolicy(
        keyIds = setOf("cll-catalog-liuxiansheng-2026a"),
        signers = setOf(StoreCatalogTrust.OWN_CERT_SHA256),
        source = StoreSource.ANDREA,
    )

    fun download(url: String): ByteArray? = CatalogHttp.download(url, hosts)
}

/**
 * 本项目自建目录：LyricProvider 镜像仓的 store-catalog 分支，由我们自己的 CI 生成并签名。
 * 目录公钥是我们自己的那把，只放行我们自己证书签名的 provider；
 * 与上游目录互不影响，任何一方作废都不会牵连另一方。
 */
object StoreSelfCatalogSource {
    private const val REPOSITORY = "liuxiansheng12369/LyricProvider"
    private const val BRANCH = "store-catalog"
    private const val PATH = "catalog/stable.json"
    private const val GITEE_REPOSITORY = "luminous-brightness/LyricProvider"

    /** 国内优先，逐级回退：Gitee raw → jsDelivr → GitHub raw。 */
    val urls: List<String> = listOf(
        "https://gitee.com/$GITEE_REPOSITORY/raw/$BRANCH/$PATH",
        "https://cdn.jsdelivr.net/gh/$REPOSITORY@$BRANCH/$PATH",
        "https://raw.githubusercontent.com/$REPOSITORY/$BRANCH/$PATH",
    )
    val hosts: Set<String> = setOf(
        "gitee.com", "raw.giteeusercontent.com",
        "cdn.jsdelivr.net", "raw.githubusercontent.com",
    )

    val policy: CatalogPolicy = CatalogPolicy(
        keyIds = setOf("cll-catalog-liuxiansheng-2026a"),
        signers = setOf(StoreCatalogTrust.OWN_CERT_SHA256),
        source = StoreSource.TOMAKINO,
    )

    fun download(url: String): ByteArray? = CatalogHttp.download(url, hosts)
}
