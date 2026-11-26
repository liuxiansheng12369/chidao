/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * StoreInstaller：下载 → 校验 → PackageInstaller 会话安装，一次只跑一个。
 * 与上游一致的地方：
 *  - 安装器永远弹系统确认（USER_ACTION_REQUIRED），不做静默安装；
 *  - STATUS_SUCCESS 不算成功，装完还要回查 PackageManager 的版本与签名；
 *  - 会话记录落盘，进程被杀后回来能判断是成功、可重试还是被中断；
 *  - 校验过的文件留着，取消安装后重试不用再下载。
 * 状态机挂在进程级单例上，界面重建不会重来一遍。
 */

package com.liuxiansheng.lyricon.app.store

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object StoreInstaller {

    private const val PROGRESS_INTERVAL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _operations = MutableStateFlow<Map<String, StoreOperation>>(emptyMap())
    val operations: StateFlow<Map<String, StoreOperation>> = _operations.asStateFlow()

    /** 系统安装确认 Intent，UI 拿到后启动一次再清掉。 */
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation: StateFlow<Intent?> = _confirmation.asStateFlow()

    private var lastConfirmation: Intent? = null
    @Volatile private var cancelDownload = false
    /** 已校验完、等"安装未知应用"授权的文件。 */
    private var awaitingPermission: Pair<StoreEntry, File>? = null
    /** 当前安装会话对应的条目，用于回查结果。 */
    private var installing: StoreEntry? = null

    val busy: Boolean get() = _operations.value.values.any { it.active }

    fun operationOf(providerId: String): StoreOperation? = _operations.value[providerId]

    /** 已经有别的操作在跑就返回 false（不排队、不并发）。 */
    fun start(context: Context, item: StoreItem, trustedHosts: Set<String>): Boolean {
        if (!item.available) return false
        val app = context.applicationContext
        synchronized(lock) {
            if (busy) return false
            cancelDownload = false
            awaitingPermission = null
            set(item.providerId, StoreOperation.Downloading(0, item.entry.artifact.bytes))
        }
        scope.launch { guarded(item.providerId) { downloadAndInstall(app, item.entry, trustedHosts) } }
        return true
    }

    fun cancel(context: Context, providerId: String) {
        when (_operations.value[providerId]) {
            is StoreOperation.Downloading -> cancelDownload = true
            StoreOperation.WaitingForUser -> {
                val record = recordFile(context).read() ?: return
                try {
                    context.packageManager.packageInstaller.abandonSession(record.sessionId)
                } catch (_: Exception) {
                    // 会话已经没了，按中断处理
                }
                finish(context, record.providerId, StoreOperation.Failed(StoreFailure.CANCELLED))
            }
            else -> Unit
        }
    }

    fun confirmationLaunched() {
        _confirmation.value = null
    }

    fun reopenConfirmation() {
        lastConfirmation?.let { _confirmation.value = it }
    }

    /** 从系统设置回来后继续安装；不会自己反复弹授权。 */
    fun continueIfAllowed(context: Context) {
        val app = context.applicationContext
        val pending = synchronized(lock) {
            val waiting = awaitingPermission ?: return
            if (!canInstall(app)) return
            awaitingPermission = null
            set(waiting.first.providerId, StoreOperation.Installing)
            waiting
        }
        scope.launch { guarded(pending.first.providerId) { install(app, pending.first, pending.second) } }
    }

    /** 页面恢复时清孤儿会话：装上了算成功，会话还开着就放弃，否则按中断。 */
    fun reconcile(context: Context, state: CatalogState) {
        val app = context.applicationContext
        scope.launch {
            val record = recordFile(app).read() ?: return@launch
            if (_operations.value[record.providerId]?.active == true) return@launch
            guarded(record.providerId) {
                val entry = (state as? CatalogState.Current)?.catalog?.entries
                    ?.firstOrNull { it.providerId == record.providerId }
                val installed = StoreRepository.installedPackage(app, record.applicationId)
                val sessionExists = try {
                    app.packageManager.packageInstaller.getSessionInfo(record.sessionId) != null
                } catch (_: Exception) {
                    false
                }
                when {
                    entry != null && installed != null &&
                        installed.versionCode == record.versionCode &&
                        verifyInstalled(entry, installed) is StoreOperation.Succeeded ->
                        finish(app, record.providerId, StoreOperation.Succeeded(installed.versionName))

                    sessionExists -> {
                        try {
                            app.packageManager.packageInstaller.abandonSession(record.sessionId)
                        } catch (_: Exception) {
                        }
                        finish(app, record.providerId, StoreOperation.Failed(StoreFailure.INTERRUPTED))
                    }

                    else -> finish(app, record.providerId, StoreOperation.Failed(StoreFailure.INTERRUPTED))
                }
            }
        }
    }

    /** 安装器广播回执；不是我们发起的会话一律忽略。 */
    fun onStatus(context: Context, sessionId: Int, status: Int, confirm: Intent?) {
        val app = context.applicationContext
        val record = recordFile(app).read()?.takeIf { it.sessionId == sessionId } ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    finish(app, record.providerId, StoreOperation.Failed(StoreFailure.OTHER))
                    return
                }
                lastConfirmation = confirm
                set(record.providerId, StoreOperation.WaitingForUser)
                _confirmation.value = confirm
            }

            PackageInstaller.STATUS_SUCCESS -> scope.launch {
                guarded(record.providerId) {
                    val entry = installing?.takeIf { it.providerId == record.providerId }
                    val installed = StoreRepository.installedPackage(app, record.applicationId)
                    val result = if (entry != null && entry.versionCode == record.versionCode) {
                        verifyInstalled(entry, installed)
                    } else {
                        StoreOperation.Failed(StoreFailure.NOT_VERIFIED)
                    }
                    finish(app, record.providerId, result)
                }
            }

            else -> finish(app, record.providerId, StoreOperation.Failed(installerFailure(status)))
        }
    }

    private fun downloadAndInstall(app: Context, entry: StoreEntry, trustedHosts: Set<String>) {
        var lastReport = 0L
        val result = StoreDownloader.download(
            context = app,
            entry = entry,
            trustedHosts = trustedHosts,
            isCancelled = { cancelDownload },
            onProgress = { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    set(entry.providerId, StoreOperation.Downloading(done, total))
                }
            },
        )
        val file = when (result) {
            is StoreDownload.Verified -> result.file
            StoreDownload.NetworkFailed -> {
                set(entry.providerId, StoreOperation.Failed(StoreFailure.NETWORK))
                return
            }

            is StoreDownload.Mismatch -> {
                set(entry.providerId, StoreOperation.Failed(StoreFailure.CONTENT_MISMATCH))
                return
            }

            StoreDownload.StorageFailed -> {
                set(entry.providerId, StoreOperation.Failed(StoreFailure.STORAGE))
                return
            }

            StoreDownload.Cancelled -> {
                set(entry.providerId, StoreOperation.Failed(StoreFailure.CANCELLED))
                return
            }
        }

        set(entry.providerId, StoreOperation.Verifying)
        val observed = readArchive(app, file)
        val identityOk = observed != null &&
            observed.packageName == entry.applicationId &&
            observed.versionCode == entry.versionCode &&
            observed.signerSha256.isNotEmpty() &&
            entry.artifact.signerSha256.containsAll(observed.signerSha256)
        if (!identityOk) {
            file.delete()
            set(entry.providerId, StoreOperation.Failed(StoreFailure.IDENTITY_MISMATCH))
            return
        }

        synchronized(lock) {
            if (!canInstall(app)) {
                awaitingPermission = entry to file
                set(entry.providerId, StoreOperation.NeedsInstallPermission)
                return
            }
            set(entry.providerId, StoreOperation.Installing)
        }
        install(app, entry, file)
    }

    private fun install(app: Context, entry: StoreEntry, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(entry.applicationId)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // 永远保留用户确认这一步
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                installing = entry
                recordFile(app).write(
                    InstallRecord(entry.providerId, entry.applicationId, entry.versionCode, sessionId)
                )
                session.commit(statusSender(app, sessionId))
            }
        } catch (e: Exception) {
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) {
                }
            }
            recordFile(app).clear()
            installing = null
            set(
                entry.providerId,
                StoreOperation.Failed(if (e is java.io.IOException) StoreFailure.STORAGE else StoreFailure.BLOCKED)
            )
        }
    }

    private fun finish(app: Context, providerId: String, result: StoreOperation) {
        synchronized(lock) {
            recordFile(app).clear()
            installing = null
            lastConfirmation = null
            _confirmation.value = null
            set(providerId, result)
        }
        if (result is StoreOperation.Succeeded) {
            StoreDownloader.clean(app, keep = null)
        }
    }

    /** 平台抛异常时把操作标成失败，不让整个 App 崩。 */
    private inline fun guarded(providerId: String, block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            set(providerId, StoreOperation.Failed(StoreFailure.OTHER))
        }
    }

    private fun set(providerId: String, operation: StoreOperation) {
        _operations.update { it + (providerId to operation) }
    }

    private fun canInstall(app: Context): Boolean = try {
        app.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    private fun statusSender(app: Context, sessionId: Int): android.content.IntentSender =
        PendingIntent.getBroadcast(
            app,
            sessionId,
            Intent(app, StoreInstallReceiver::class.java).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    /** 把下载好的 APK 拆开看包名、版本、签名者；解析不了返回 null。 */
    private data class ObservedArchive(
        val packageName: String,
        val versionCode: Long,
        val signerSha256: Set<String>,
    )

    private fun readArchive(context: Context, file: File): ObservedArchive? {
        val info = try {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } catch (_: Exception) {
            null
        } ?: return null
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            .map { StoreCatalogVerifier.sha256Hex(it.toByteArray()) }
            .toSet()
        return ObservedArchive(info.packageName, info.longVersionCode, signers)
    }

    /** 成功只认 PackageManager 里看到的：版本号一致且签名在目录允许集合内。 */
    private fun verifyInstalled(entry: StoreEntry, installed: InstalledPackage?): StoreOperation = when {
        installed == null ||
            installed.versionCode != entry.versionCode ||
            installed.signerSha256.isEmpty() ||
            !entry.artifact.signerSha256.containsAll(installed.signerSha256) ->
            StoreOperation.Failed(StoreFailure.NOT_VERIFIED)

        else -> StoreOperation.Succeeded(installed.versionName)
    }

    private fun installerFailure(status: Int): StoreFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> StoreFailure.CANCELLED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> StoreFailure.CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> StoreFailure.INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_STORAGE -> StoreFailure.STORAGE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> StoreFailure.BLOCKED
        PackageInstaller.STATUS_FAILURE_INVALID -> StoreFailure.INVALID
        else -> StoreFailure.OTHER
    }

    private fun recordFile(context: Context) = InstallRecordFile(
        File(File(context.noBackupFilesDir, "store"), "install.json")
    )
}

/** 一个正在开的安装会话，落盘后进程重启还能对上账。 */
data class InstallRecord(
    val providerId: String,
    val applicationId: String,
    val versionCode: Long,
    val sessionId: Int,
)

class InstallRecordFile(private val file: File) {

    fun read(): InstallRecord? = try {
        val json = JSONObject(file.readText())
        InstallRecord(
            json.getString("providerId"),
            json.getString("applicationId"),
            json.getLong("versionCode"),
            json.getInt("sessionId"),
        )
    } catch (_: Exception) {
        null
    }

    fun write(record: InstallRecord) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return }
        val json = JSONObject()
            .put("providerId", record.providerId)
            .put("applicationId", record.applicationId)
            .put("versionCode", record.versionCode)
            .put("sessionId", record.sessionId)
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

/** 只有系统安装器通过我们自己的 PendingIntent 能到这里（未导出）。 */
class StoreInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            null
        }
        StoreInstaller.onStatus(context, sessionId, status, confirm)
    }
}
