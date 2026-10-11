package com.limao.fingerprint.data

import android.content.Context
import java.io.File

/**
 * §4.10 应用锁跨进程状态：主进程写，:wvN 浏览器进程读。
 *
 * 为什么不用 DataStore 做跨进程通信：:wvN 进程的 DataStore 实例是内存缓存
 * （见 BUG-1），主进程改了之后、还活着的 :wvN 进程会读到旧值。普通文件每次
 * readText() 都是 fresh read，没有缓存问题。
 *
 * 文件位置：filesDir（各进程共享同一 app 私有目录，已有 pending_url_ 机制验证过）。
 * 内容：{"enabled":true,"verified_at":1699999999999}
 *   - enabled：应用锁开关
 *   - verified_at：上次通过生物识别/设备凭据验证的时间戳（毫秒）
 *
 * 写者：主进程（验证成功 / 开关切换时）；:wvN 进程在自己弹出的验证成功后也会
 * 更新 verified_at（延长宽限）。写入是小文件覆盖，last-writer-wins 可接受。
 */
object AppLockStore {
    private const val FILE_NAME = "app_lock_state.json"

    /** 宽限期：与 MainActivity 的 2 分钟规则保持一致 */
    const val GRACE_MS = 2 * 60 * 1000L

    data class State(val enabled: Boolean, val verifiedAt: Long)

    /** 每次都是 fresh read，无缓存 */
    fun read(context: Context): State? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return null
            parse(f.readText())
        } catch (e: Exception) {
            android.util.Log.w("AppLockStore", "读取锁状态失败", e)
            null
        }
    }

    fun write(context: Context, enabled: Boolean, verifiedAt: Long) {
        try {
            File(context.filesDir, FILE_NAME)
                .writeText("{\"enabled\":$enabled,\"verified_at\":$verifiedAt}")
        } catch (e: Exception) {
            android.util.Log.w("AppLockStore", "写入锁状态失败", e)
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            android.util.Log.w("AppLockStore", "清除锁状态失败", e)
        }
    }

    /** 是否需要弹出验证：没开锁不拦；宽限期内不拦 */
    fun needsAuth(state: State?): Boolean {
        if (state == null || !state.enabled) return false
        return System.currentTimeMillis() - state.verifiedAt > GRACE_MS
    }

    private fun parse(json: String): State? {
        return try {
            val enabled = json.contains("\"enabled\":true")
            val m = Regex("\"verified_at\":(\\d+)").find(json) ?: return null
            State(enabled, m.groupValues[1].toLong())
        } catch (e: Exception) {
            android.util.Log.w("AppLockStore", "解析锁状态失败: $json", e)
            null
        }
    }
}
