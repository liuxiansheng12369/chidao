/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * StoreRepository：商店的唯一入口（进程级单例）。
 * 所有目录读写与 revision floor 都在同一把锁里，两个页面不会互相把 floor 踩低。
 * 目录 = 持久化副本 → 验签 → 使用；刷新 = 两个镜像各拉一次，取最高 revision，先落盘再用。
 * 一些细节照上游：过期目录只读、10 分钟内不重复联网、已装包签名不符就不给更新。
 */

package com.liuxiansheng.lyricon.app.store

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File

object StoreRepository {

    /** 十分钟内不重复联网（与上游后台巡检同一档）。 */
    private const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    private val lock = Any()
    private var lastRemoteCheckElapsed = Long.MIN_VALUE

    // 进程内缓存：再进商店先用上次的目录和列表直接铺出来，不等磁盘读、不等联网。
    // 冷启动时 Application 会预热一次 load，用户点进商店通常已经有货。
    @Volatile private var memoState: CatalogState? = null
    @Volatile private var memoItems: List<StoreItem>? = null

    fun cachedState(): CatalogState? = memoState
    fun cachedItems(): List<StoreItem>? = memoItems
    fun rememberUi(state: CatalogState, items: List<StoreItem>) {
        memoState = state
        memoItems = items
    }

    /**
     * 一份目录源。上游目录与自建目录各占一份：
     * 各有自己的镜像地址、信任策略、落盘文件与 revision floor，
     * 任何一方坏掉、回滚或过期都不会牵连另一方。
     */
    private data class Source(
        val label: String,
        val fileName: String,
        val urls: List<String>,
        val policy: CatalogPolicy,
        val download: (String) -> ByteArray?,
    )

    private val sources: List<Source> = listOf(
        Source(
            label = "自建目录",
            fileName = "catalog-self.json",
            urls = StoreSelfCatalogSource.urls,
            policy = StoreSelfCatalogSource.policy,
            download = StoreSelfCatalogSource::download,
        ),
        Source(
            label = "ColorOS 自建目录",
            fileName = "catalog-coloros.json",
            urls = StoreColorOsCatalogSource.urls,
            policy = StoreColorOsCatalogSource.policy,
            download = StoreColorOsCatalogSource::download,
        ),
    )

    data class RefreshResult(
        val ok: Boolean,
        val message: String,
        val state: CatalogState,
    )

    private fun file(context: Context, name: String) = StoreCatalogFile(
        File(File(context.noBackupFilesDir, "store"), name)
    )

    fun autoRefreshDue(): Boolean = synchronized(lock) {
        lastRemoteCheckElapsed == Long.MIN_VALUE ||
            SystemClock.elapsedRealtime() - lastRemoteCheckElapsed >= AUTO_REFRESH_INTERVAL_MS
    }

    /** 读本地已验签的目录；没有或坏了就是 Unavailable。阻塞，放 IO 线程。 */
    fun load(context: Context): CatalogState = synchronized(lock) { loadLocked(context) }

    private fun loadLocked(context: Context): CatalogState {
        val state = merge(
            sources.mapNotNull { source ->
                val persisted = file(context, source.fileName).read() ?: return@mapNotNull null
                when (val verdict =
                    StoreCatalogVerifier.verify(persisted.envelope, nowSeconds(), null, source.policy)) {
                    is CatalogVerdict.Rejected -> null
                    is CatalogVerdict.Accepted -> verdict.catalog to verdict.freshness
                }
            }
        )
        memoState = state
        return state
    }

    /**
     * 把多份已验证的目录合成一份给 UI 用。
     * 过期的那份不参与条目（跟单份目录的规则一致：过期只读、不可安装）；
     * 全都过期时仍列出条目，但整体标成过期，界面只显示已装状态。
     */
    private fun merge(
        catalogs: List<Pair<StoreCatalog, StoreFreshness>>,
    ): CatalogState {
        if (catalogs.isEmpty()) return CatalogState.Unavailable
        val fresh = catalogs.filter { it.second == StoreFreshness.FRESH }
        val used = fresh.ifEmpty { catalogs }
        val freshness = if (fresh.isEmpty()) StoreFreshness.EXPIRED else StoreFreshness.FRESH
        if (used.size == 1) return CatalogState.Current(used[0].first, freshness)

        val entries = LinkedHashMap<String, StoreEntry>()
        for ((catalog, _) in used) {
            for (entry in catalog.entries) {
                // 两家源可以指向同一个播放器（applicationId 相同），必须各留一条：
                // 若只按 applicationId 去重，一家会把另一家的条目整批挤掉
                val key = entry.source.name + "|" + entry.applicationId
                val existing = entries[key]
                if (existing == null || entry.versionCode > existing.versionCode) {
                    entries[key] = entry
                }
            }
        }
        val merged = StoreCatalog(
            revision = used.maxOf { it.first.revision },
            issuedAtEpochSeconds = used.minOf { it.first.issuedAtEpochSeconds },
            expiresAtEpochSeconds = used.minOf { it.first.expiresAtEpochSeconds },
            channel = "stable",
            trustedHosts = used.flatMap { it.first.trustedHosts }.toSet(),
            entries = entries.values.toList(),
        )
        return CatalogState.Current(merged, freshness)
    }

    /** 拉全部镜像（每份目录各拉一遍），取各自最高 revision，成功落盘后再用；全失败回落到本地副本。 */
    fun refresh(context: Context): RefreshResult = synchronized(lock) {
        val accepted = ArrayList<Pair<StoreCatalog, StoreFreshness>>(sources.size)
        var networkFailures = 0
        var totalAttempts = 0
        var rejection: CatalogRejection? = null

        for (source in sources) {
            val store = file(context, source.fileName)
            val floor = store.read()?.accepted
            var best: CatalogVerdict.Accepted? = null
            var bestBytes: ByteArray? = null

            for (url in source.urls) {
                totalAttempts++
                val bytes = try {
                    source.download(url)
                } catch (_: Exception) {
                    networkFailures++
                    continue
                }
                if (bytes == null) {
                    rejection = rejection ?: CatalogRejection("TOO_LARGE", "envelope")
                    continue
                }
                when (val verdict = StoreCatalogVerifier.verify(bytes, nowSeconds(), floor, source.policy)) {
                    is CatalogVerdict.Rejected -> {
                        if (rejection == null) rejection = verdict.rejection
                    }
                    is CatalogVerdict.Accepted -> {
                        val currentBest = best
                        if (currentBest == null || verdict.catalog.revision > currentBest.catalog.revision) {
                            best = verdict
                            bestBytes = bytes
                        }
                    }
                }
            }

            val winner = best
            if (winner != null && bestBytes != null) {
                val needPersist = floor == null || winner.catalog.revision > floor.revision
                if (needPersist) {
                    try {
                        store.write(
                            StoreCatalogFile.Persisted(
                                AcceptedRevision(winner.catalog.revision, winner.payloadSha256),
                                bestBytes,
                            )
                        )
                    } catch (_: Exception) {
                        // 落盘失败就不采用这份新目录，继续用原来的 floor
                    }
                }
                accepted += winner.catalog to winner.freshness
            }
        }
        lastRemoteCheckElapsed = SystemClock.elapsedRealtime()

        val state = merge(accepted)
        memoState = state
        if (state is CatalogState.Current) {
            val revisions = accepted.joinToString("，") { "${it.first.revision}" }
            return@synchronized RefreshResult(
                true,
                if (accepted.size > 1) "目录已更新（rev $revisions）" else "目录已更新至 rev $revisions",
                state,
            )
        }

        val fallback = loadLocked(context)
        val message = when {
            totalAttempts > 0 && networkFailures == totalAttempts -> "网络不可用，连不上目录镜像"
            rejection != null -> "目录校验未通过：${rejection.reason}（${rejection.detail}）"
            else -> "没有可用的目录"
        }
        return@synchronized RefreshResult(false, message, fallback)
    }

    /**
     * 首页角标用：本地目录里有几个已装且可更新的源。
     * 不联网、只读持久化目录 + 一次投影，适合在首页每次进入时算一遍。
     */
    fun updateCount(context: Context): Int = synchronized(lock) {
        val state = loadLocked(context)
        project(context, state).count { it.installState == InstallState.UPDATE_AVAILABLE }
    }

    /**
     * 把目录投影到本机：每个条目的安装状态、可用性、那一行提示。
     * 阻塞 PackageManager 查询，但只是几次 getPackageInfo，开销很小。
     */
    fun project(context: Context, state: CatalogState): List<StoreItem> {
        val current = state as? CatalogState.Current
            ?: return emptyList()
        val deviceSdk = Build.VERSION.SDK_INT
        return current.catalog.entries.map { entry ->
            val installed = installedPackage(context, entry.applicationId)
            val installState = installStateOf(installed, entry)
            val availability = when {
                !current.installable -> StoreAvailability.CATALOG_EXPIRED
                !entry.supportedBy() -> StoreAvailability.NEEDS_NEWER_STORE
                entry.minSdk > deviceSdk -> StoreAvailability.UNSUPPORTED_SDK
                else -> StoreAvailability.AVAILABLE
            }
            val note = when {
                installState == InstallState.SIGNATURE_MISMATCH -> StoreNote.SIGNATURE_MISMATCH
                installState == InstallState.LOCAL_NEWER -> StoreNote.LOCAL_NEWER
                availability == StoreAvailability.NEEDS_NEWER_STORE &&
                    (installState == InstallState.NOT_INSTALLED || installState == InstallState.UPDATE_AVAILABLE) ->
                    StoreNote.NEEDS_NEWER_STORE
                availability == StoreAvailability.UNSUPPORTED_SDK &&
                    (installState == InstallState.NOT_INSTALLED || installState == InstallState.UPDATE_AVAILABLE) ->
                    StoreNote.UNSUPPORTED_SDK
                else -> StoreNote.NONE
            }
            StoreItem(entry, installed, installState, availability, note)
        }
    }

    private fun installStateOf(installed: InstalledPackage?, entry: StoreEntry): InstallState = when {
        installed == null -> InstallState.NOT_INSTALLED
        // 读不到签名就按不符处理，宁可少给一次更新
        installed.signerSha256.isEmpty() ||
            !entry.artifact.signerSha256.containsAll(installed.signerSha256) ->
            InstallState.SIGNATURE_MISMATCH
        installed.versionCode < entry.versionCode -> InstallState.UPDATE_AVAILABLE
        installed.versionCode > entry.versionCode -> InstallState.LOCAL_NEWER
        else -> InstallState.INSTALLED
    }

    @Suppress("DEPRECATION")
    fun installedPackage(context: Context, packageName: String): InstalledPackage? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = try {
            pm.getPackageInfo(packageName, flags)
        } catch (_: Exception) {
            return null
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val signers = signatures.orEmpty()
            .map { StoreCatalogVerifier.sha256Hex(it.toByteArray()) }
            .toSet()
        return InstalledPackage(
            versionName = info.versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            },
            signerSha256 = signers,
        )
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
