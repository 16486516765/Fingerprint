package com.limao.fingerprint

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写到文件，供排查用。
 *
 * 日志位置：getExternalFilesDir(null)/crash.log（卸载 App 会清除）
 * 查看方式：Profile 列表页右上角菜单 -> "查看崩溃日志"
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    const val LOG_FILE_NAME = "crash.log"

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(context.applicationContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "写入崩溃日志失败", e)
            }
            // 交给系统默认处理（弹崩溃框、杀进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "崩溃捕获已启用")
    }

    fun getLogFile(context: Context): File =
        File(context.getExternalFilesDir(null), LOG_FILE_NAME)

    fun readLog(context: Context): String {
        val f = getLogFile(context)
        return if (f.exists()) f.readText() else "暂无崩溃记录"
    }

    fun clearLog(context: Context) {
        getLogFile(context).delete()
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("========== CRASH $time ==========")
        sb.appendLine("线程: ${thread.name}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("异常: ${throwable::class.java.name}: ${throwable.message}")
        sb.appendLine("堆栈:")
        for (el in throwable.stackTrace) {
            sb.appendLine("    at $el")
        }
        var cause = throwable.cause
        while (cause != null) {
            sb.appendLine("Caused by: ${cause::class.java.name}: ${cause.message}")
            for (el in cause.stackTrace) {
                sb.appendLine("    at $el")
            }
            cause = cause.cause
        }
        sb.appendLine()
        getLogFile(context).appendText(sb.toString())
        Log.e(TAG, "崩溃日志已写入", throwable)
    }
}
