package com.limao.fingerprint

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.webkit.WebView

/**
 * 应用入口。
 *
 * 多进程隔离的核心就藏在这里：
 * 每个 profile 运行在独立进程 `:wvN`（N = profile.slot，0-5）。
 * 进程启动时，在创建任何 WebView 之前调用一次
 * WebView.setDataDirectorySuffix("fp_slot_N")，
 * 于是每个 profile 拥有独立的 Cookie jar / localStorage / IndexedDB / 缓存目录。
 *
 * 注意：
 * - setDataDirectorySuffix 每个进程只能调一次，且必须在第一个 WebView 实例化之前，
 *   Application.onCreate 是最早的安全点。
 * - 主进程（不带 :wv 后缀）不调用，保持默认数据目录（主进程不创建 WebView）。
 */
class FingerprintApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先启用崩溃捕获，后续任何异常都能记录
        CrashHandler.init(this)
        val processName = currentProcessName()
        // 形如 com.limao.fingerprint:wv2 -> 取出 slot 数字 2
        val slot = processName
            ?.substringAfterLast(":wv", missingDelimiterValue = "")
            ?.toIntOrNull()
        if (slot != null) {
            try {
                WebView.setDataDirectorySuffix("fp_slot_$slot")
                Log.i(TAG, "WebView 数据目录后缀已设置: fp_slot_$slot（进程 $processName）")
            } catch (e: Exception) {
                Log.e(TAG, "setDataDirectorySuffix 失败", e)
            }
        }
    }

    /**
     * 取当前进程名。
     * API 28+ 有 Application.getProcessName()；minSdk 已提至 28，
     * 低版本分支保留仅作防御，正常不会走到。
     */
    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        @Suppress("DEPRECATION")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val pid = Process.myPid()
        @Suppress("DEPRECATION")
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }

    companion object {
        private const val TAG = "FingerprintApp"
    }
}
