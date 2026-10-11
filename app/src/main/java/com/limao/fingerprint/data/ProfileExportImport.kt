package com.limao.fingerprint.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * §4.9 Profile 加密导出 / 导入。
 *
 * 导出格式：Base64( salt(16) + iv(12) + AES-GCM(password, profile JSON) )
 * 密钥派生：PBKDF2-HmacSHA256，100_000 次迭代，256 位。
 * 凭据（代理用户名密码）不进导出文件，导入后需重新填写。
 */
object ProfileExportImport {

    private const val TAG = "ProfileExportImport"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val gson = Gson()
    private val listType = object : TypeToken<List<Profile>>() {}.type

    /** 导出单个 profile 为加密字符串（凭据剥离） */
    fun exportProfile(profile: Profile, password: String): String {
        // 剥离敏感字段：凭据、lastVisitedUrl（含 token）
        val clean = profile.copy(
            proxyUsername = "",
            proxyPassword = "",
            lastVisitedUrl = ""
        )
        val json = gson.toJson(listOf(clean))
        return encrypt(json, password)
    }

    /** 导出全部 profile 为加密字符串 */
    fun exportAll(profiles: List<Profile>, password: String): String {
        val clean = profiles.map {
            it.copy(
                proxyUsername = "",
                proxyPassword = "",
                lastVisitedUrl = ""
            )
        }
        return encrypt(gson.toJson(clean), password)
    }

    /**
     * 导入：解密后返回 profile 列表（不含凭据）。
     * @throws IllegalArgumentException 密码错误或数据损坏
     */
    fun importProfiles(encrypted: String, password: String): List<Profile> {
        val json = try {
            decrypt(encrypted.trim(), password)
        } catch (e: Exception) {
            throw IllegalArgumentException("解密失败：密码错误或数据损坏")
        }
        return try {
            gson.fromJson<List<Profile>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            throw IllegalArgumentException("数据格式错误：${e.message}")
        }
    }

    private fun encrypt(plain: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = salt + iv + ct
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String, password: String): String {
        val data = Base64.decode(encrypted, Base64.DEFAULT)
        if (data.size < SALT_BYTES + IV_BYTES + 16) {
            throw IllegalArgumentException("数据太短")
        }
        val salt = data.copyOfRange(0, SALT_BYTES)
        val iv = data.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val ct = data.copyOfRange(SALT_BYTES + IV_BYTES, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }
}
