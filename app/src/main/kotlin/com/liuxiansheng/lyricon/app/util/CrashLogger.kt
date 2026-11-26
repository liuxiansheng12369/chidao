/*
 * 词岛 新增：崩溃日志落盘。
 *
 * 目的：调试联调时只能靠 logcat 抓崩溃，而 SystemUI 进程崩溃 / App 被系统杀掉后
 * logcat 也就没了。这里把未捕获异常整份写到 App 私有目录，事后直接读取即可。
 *
 * 落点：/data/data/com.liuxiansheng.chidao/files/crash/crash-<时间>.txt
 *       （用 `adb shell run-as com.liuxiansheng.chidao ls files/crash` 直接看）
 *
 * 只作用于本 App 进程；SystemUI 进程（xposed 模块）不装全局处理器，
 * 避免改变系统进程原有的崩溃行为。
 */
package com.liuxiansheng.lyricon.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 20

    @Volatile private var installed = false

    /** 安装全局未捕获异常处理器；重复调用安全（只装一次）。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // 保持系统原有处理（弹窗 / 杀进程），不在崩溃路径上抢行为
            previous?.let { runCatching { it.uncaughtException(thread, throwable) } }
        }
    }

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    /** 按时间倒序，最新的在最前 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 最近一次崩溃全文（没有则 null） */
    fun latestText(context: Context): String? = runCatching {
        list(context).firstOrNull()?.let { if (it.length() <= 2_000_000) it.readText() else null }
    }.getOrNull()

    fun clearAll(context: Context) {
        runCatching { list(context).forEach { it.delete() } }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val body = buildString {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            append("time=").append(stamp).append('\n')
            append("thread=").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("version=").append(versionOf(context)).append('\n')
            append("device=")
                .append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL)
                .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append('\n')
            append(sw.toString())
        }
        file.writeText(body)
        trim(dir)
    }

    private fun versionOf(context: Context): String = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    }.getOrDefault("unknown")

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
