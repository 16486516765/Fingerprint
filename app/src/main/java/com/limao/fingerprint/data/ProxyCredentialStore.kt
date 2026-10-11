package com.limao.fingerprint.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 代理凭据加密存储（Android Keystore）。
 * proxyUsername / proxyPassword 不再以明文存 DataStore，
 * 改存 EncryptedSharedPreferences，与普通配置分离。
 */
object ProxyCredentialStore {

    private const val TAG = "ProxyCredentialStore"
    private const val FILE_NAME = "proxy_credentials"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefs = it }
            } catch (e: Exception) {
                Log.w(TAG, "加密存储初始化失败", e)
                null
            }
        }
    }

    /** 保存某 profile 的代理凭据（key 按 profile id 隔离） */
    fun save(context: Context, profileId: String, username: String, password: String) {
        try {
            prefs(context)?.edit()
                ?.putString("${profileId}_u", username)
                ?.putString("${profileId}_p", password)
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存代理凭据失败", e)
        }
    }

    /** 读取某 profile 的代理凭据，失败返回空 */
    fun load(context: Context, profileId: String): Pair<String, String> {
        return try {
            val p = prefs(context) ?: return "" to ""
            (p.getString("${profileId}_u", "") ?: "") to
                (p.getString("${profileId}_p", "") ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "读取代理凭据失败", e)
            "" to ""
        }
    }

    /** 删除某 profile 的代理凭据 */
    fun clear(context: Context, profileId: String) {
        try {
            prefs(context)?.edit()
                ?.remove("${profileId}_u")
                ?.remove("${profileId}_p")
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "清除代理凭据失败", e)
        }
    }
}
