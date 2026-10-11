package com.limao.fingerprint.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 文件名 */
private const val DATASTORE_NAME = "fingerprint_profiles"

/** 存 Profile 列表 JSON 的 key */
private val KEY_PROFILE_LIST = stringPreferencesKey("profile_list_json")

/** 凭据迁移完成标记的 key（见问题4） */
private val KEY_CREDENTIALS_MIGRATED =
    androidx.datastore.preferences.core.booleanPreferencesKey("credentials_migrated")

/** 网站图标开关的 key（默认关闭，隐私优先，见问题15） */
private val KEY_FAVICON_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("favicon_enabled")

/** 脏 slot 标记的 key：数据目录清理失败的 slot 不再分配（见问题11） */
private val KEY_DIRTY_SLOTS =
    androidx.datastore.preferences.core.stringSetPreferencesKey("dirty_slots")

/** JSON 损坏备份的 key（见问题12） */
private val KEY_PROFILE_LIST_BACKUP =
    stringPreferencesKey("profile_list_json_backup")

/** 滚动备份的 key：每次成功写入前保存上一份（见 BUG-4） */
private val KEY_PROFILE_LIST_PREV =
    stringPreferencesKey("profile_list_json_prev")

/** JSON 损坏标记的 key */
private val KEY_PROFILE_LIST_CORRUPTED =
    androidx.datastore.preferences.core.booleanPreferencesKey("profile_list_corrupted")

/** 指纹配置迁移完成标记的 key（§4.3：旧数据补 fpSeed） */
private val KEY_FP_MIGRATED =
    androidx.datastore.preferences.core.booleanPreferencesKey("fp_config_migrated")

/** §4.10 应用锁开关的 key（默认关闭） */
private val KEY_APP_LOCK_ENABLED =
    androidx.datastore.preferences.core.booleanPreferencesKey("app_lock_enabled")

/** §4.10 防截屏（FLAG_SECURE）开关的 key（默认关闭） */
private val KEY_SECURE_FLAG_ENABLED =
    androidx.datastore.preferences.core.booleanPreferencesKey("secure_flag_enabled")

/**
 * 最大 profile 数量。
 * 每个 profile 独占一个 :wvN 进程（WebView 是内存大户），6 是手机端的实际上限。
 */
const val MAX_PROFILES = 6

/** slot 用满时抛出，调用方捕获后提示"已达上限" */
class NoFreeSlotException : IllegalStateException("Profile 数量已达上限（$MAX_PROFILES）")

private val Context.profileDataStore: DataStore<Preferences> by
    preferencesDataStore(name = DATASTORE_NAME)

/**
 * Profile 持久化仓库（单例）。
 *
 * 实现：DataStore Preferences 存一整个 List<Profile> 的 Gson JSON。
 * profile 数量小（个位数到几十），全量读写完全够用，不引入 Room。
 */
class ProfileRepository private constructor(private val appContext: Context) {

    private val gson = Gson()
    private val listType = object : TypeToken<List<Profile>>() {}.type

    /** 全部 profile 的观察流，UI 层用 collectAsState 订阅 */
    val profiles: Flow<List<Profile>> = appContext.profileDataStore.data
        .map { prefs -> readList(prefs[KEY_PROFILE_LIST]) }

    /** 网站图标开关（默认 false，隐私优先，见问题15） */
    val faviconEnabled: Flow<Boolean> = appContext.profileDataStore.data
        .map { prefs -> prefs[KEY_FAVICON_ENABLED] ?: false }

    /** 设置网站图标开关 */
    suspend fun setFaviconEnabled(enabled: Boolean) {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_FAVICON_ENABLED] = enabled
        }
    }

    /** §4.10 应用锁开关（默认 false） */
    val appLockEnabled: Flow<Boolean> = appContext.profileDataStore.data
        .map { prefs -> prefs[KEY_APP_LOCK_ENABLED] ?: false }

    /** 设置应用锁开关 */
    suspend fun setAppLockEnabled(enabled: Boolean) {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_APP_LOCK_ENABLED] = enabled
        }
    }

    /** §4.10 防截屏开关（默认 false） */
    val secureFlagEnabled: Flow<Boolean> = appContext.profileDataStore.data
        .map { prefs -> prefs[KEY_SECURE_FLAG_ENABLED] ?: false }

    /** 设置防截屏开关 */
    suspend fun setSecureFlagEnabled(enabled: Boolean) {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_SECURE_FLAG_ENABLED] = enabled
        }
    }

    /**
     * 新增 profile，自动分配最小的空闲 slot（0 ~ MAX_PROFILES-1）。
     * 脏 slot（数据目录清理失败，见问题11）跳过不分配。
     * slot 用满时抛出 NoFreeSlotException，调用方捕获后提示用户。
     */
    suspend fun addProfile(profile: Profile): Profile {
        var assigned: Profile? = null
        val dirtySlots = getDirtySlots()
        mutate { list ->
            val usedSlots = list.map { it.slot }.toSet()
            val freeSlot = (0 until MAX_PROFILES).firstOrNull {
                it !in usedSlots && it !in dirtySlots
            } ?: throw NoFreeSlotException()
            assigned = profile.copy(slot = freeSlot)
            list + assigned!!
        }
        return assigned!!
    }

    /** 获取脏 slot 集合（数据目录清理失败，不再分配） */
    suspend fun getDirtySlots(): Set<Int> {
        val prefs = appContext.profileDataStore.data.first()
        return prefs[KEY_DIRTY_SLOTS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }

    /** 标记 slot 为脏（数据目录清理失败） */
    suspend fun markSlotDirty(slot: Int) {
        appContext.profileDataStore.edit { prefs ->
            val current = prefs[KEY_DIRTY_SLOTS] ?: emptySet()
            prefs[KEY_DIRTY_SLOTS] = current + slot.toString()
        }
        android.util.Log.w("ProfileRepository", "slot $slot 标记为脏，不再分配")
    }

    /** 脏 slot 清理成功后可清除标记（预留） */
    suspend fun clearSlotDirty(slot: Int) {
        appContext.profileDataStore.edit { prefs ->
            val current = prefs[KEY_DIRTY_SLOTS] ?: emptySet()
            prefs[KEY_DIRTY_SLOTS] = current - slot.toString()
        }
    }

    /** 更新（按 id 匹配替换） */
    suspend fun updateProfile(profile: Profile) {
        mutate { list -> list.map { if (it.id == profile.id) profile else it } }
    }

    /**
     * 仅更新 lastVisitedUrl（供 :wvN 进程调用）。
     * 多进程下 DataStore 可能竞写，失败时重试 3 次；最终失败也不抛异常，
     * 只是丢失一次访问记录，不影响核心数据。
     */
    suspend fun updateLastVisitedUrl(id: String, url: String) {
        repeat(3) { attempt ->
            try {
                mutate { list ->
                    list.map { if (it.id == id) it.copy(lastVisitedUrl = url) else it }
                }
                return
            } catch (e: Exception) {
                if (attempt == 2) {
                    android.util.Log.w("ProfileRepository", "updateLastVisitedUrl 失败(已重试)", e)
                } else {
                    kotlinx.coroutines.delay(100L * (attempt + 1))
                }
            }
        }
    }

    /** 删除 profile，其占用的 slot 自动释放（slot 从现存 profile 集合推导） */
    suspend fun deleteProfile(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    /** 按 id 取单个（一次性） */
    suspend fun getProfileById(id: String): Profile? =
        profiles.first().find { it.id == id }

    /** 读取并解析 JSON。区分"空配置"与"损坏"（见问题12）。 */
    private fun readList(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = gson.fromJson<List<Profile>>(json, listType) ?: emptyList()
            // §4.3 兼容旧数据：Gson 对缺失字段填 JVM 默认值
            // （Kotlin 构造器默认值不生效），旧 JSON 没有 fpConfig，
            // 字段会是 null，这里归一化为默认实例避免空指针。
            // fpSeed 缺失时是 0L，正好是"未生成"哨兵值，
            // 由 migrateFpConfigIfNeeded() 补生成。
            list.map { p ->
                @Suppress("SENSELESS_COMPARISON")
                if (p.fpConfig == null) p.copy(fpConfig = FpConfig()) else p
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileRepository", "Profile JSON 解析失败（已标记损坏，不覆盖原数据）", e)
            // 标记损坏，由调用方决定是否备份；这里只返回空列表供 UI 显示
            emptyList()
        }
    }

    /**
     * 检查 JSON 是否损坏（能读出非空字符串但解析失败）。
     * 损坏时把原 JSON 备份到独立 key，不自动覆盖（见问题12）。
     * @return true 已损坏并备份；false 正常或为空
     */
    suspend fun checkAndBackupCorrupted(): Boolean {
        val prefs = appContext.profileDataStore.data.first()
        val raw = prefs[KEY_PROFILE_LIST]
        if (raw.isNullOrBlank()) return false
        val corrupted = try {
            gson.fromJson<List<Profile>>(raw, listType)
            false
        } catch (e: Exception) {
            true
        }
        if (corrupted && prefs[KEY_PROFILE_LIST_CORRUPTED] != true) {
            appContext.profileDataStore.edit { p ->
                // 只备份一次，避免覆盖更早的备份
                if (p[KEY_PROFILE_LIST_BACKUP].isNullOrBlank()) {
                    p[KEY_PROFILE_LIST_BACKUP] = raw
                }
                p[KEY_PROFILE_LIST_CORRUPTED] = true
            }
            android.util.Log.w("ProfileRepository", "Profile JSON 已损坏，原数据已备份")
        }
        return corrupted
    }

    /** 清除损坏标记（用户确认恢复或手动修复后调用） */
    suspend fun clearCorruptedFlag() {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_PROFILE_LIST_CORRUPTED] = false
        }
    }

    /**
     * BUG-4：读取损坏备份的原始 JSON（用于"导出备份"）。
     * @return 备份 JSON，无备份返回 null
     */
    suspend fun getBackupJson(): String? {
        val prefs = appContext.profileDataStore.data.first()
        return prefs[KEY_PROFILE_LIST_BACKUP]
    }

    /**
     * BUG-4：从备份恢复。把备份 JSON 写回主 key 并清除损坏标记。
     * @return true 恢复成功；false 无备份可恢复
     */
    suspend fun restoreFromBackup(): Boolean {
        val prefs = appContext.profileDataStore.data.first()
        val backup = prefs[KEY_PROFILE_LIST_BACKUP]
        if (backup.isNullOrBlank()) return false
        // 先验证备份本身能解析，避免把坏备份写回去
        try {
            gson.fromJson<List<Profile>>(backup, listType)
        } catch (e: Exception) {
            android.util.Log.e("ProfileRepository", "备份 JSON 也损坏，无法恢复", e)
            return false
        }
        appContext.profileDataStore.edit { p ->
            p[KEY_PROFILE_LIST] = backup
            p[KEY_PROFILE_LIST_CORRUPTED] = false
        }
        return true
    }

    /**
     * BUG-4：清空重建。丢弃损坏数据（备份保留），清除标记回到空列表。
     */
    suspend fun clearAndRebuild() {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_PROFILE_LIST] = "[]"
            prefs[KEY_PROFILE_LIST_CORRUPTED] = false
        }
    }

    /** 原子读写：读出 -> 变换 -> 写回（损坏时拒绝覆盖，见问题12） */
    private suspend fun mutate(transform: (List<Profile>) -> List<Profile>) {
        appContext.profileDataStore.edit { prefs ->
            if (prefs[KEY_PROFILE_LIST_CORRUPTED] == true) {
                android.util.Log.w(
                    "ProfileRepository",
                    "JSON 已标记损坏，拒绝写入以防覆盖备份"
                )
                throw IllegalStateException("配置数据损坏已备份，请先恢复后再操作")
            }
            val current = readList(prefs[KEY_PROFILE_LIST])
            val transformed = transform(current)
            // P0-4：永不将明文代理凭据写入 JSON。凭据只存 ProxyCredentialStore。
            val sanitized = transformed.map {
                if (it.proxyUsername.isNotBlank() || it.proxyPassword.isNotBlank()) {
                    it.copy(proxyUsername = "", proxyPassword = "")
                } else it
            }
            // BUG-4：写入前滚动备份上一份，损坏时可回滚
            val oldJson = prefs[KEY_PROFILE_LIST]
            if (!oldJson.isNullOrBlank()) {
                prefs[KEY_PROFILE_LIST_PREV] = oldJson
            }
            prefs[KEY_PROFILE_LIST] = gson.toJson(sanitized)
        }
    }

    /**
     * 一次性迁移（见问题4）：把 JSON 里残留的明文代理凭据搬到加密存储，
     * 成功后清除 JSON 中的明文字段。迁移失败抛异常，不静默回退。
     *
     * 只应在主进程调用一次（MainActivity.onCreate）。
     */
    suspend fun migrateCredentialsIfNeeded() {
        val prefs = appContext.profileDataStore.data.first()
        if (prefs[KEY_CREDENTIALS_MIGRATED] == true) return

        val profiles = readList(prefs[KEY_PROFILE_LIST])
        var migratedCount = 0
        for (p in profiles) {
            if (p.proxyUsername.isNotBlank() || p.proxyPassword.isNotBlank()) {
                // 先写加密存储，成功后再清 JSON（顺序不能反）
                ProxyCredentialStore.save(appContext, p.id, p.proxyUsername, p.proxyPassword)
                // 验证写入成功
                val (u, _) = ProxyCredentialStore.load(appContext, p.id)
                if (u != p.proxyUsername) {
                    throw IllegalStateException("凭据迁移失败：profile ${p.id} 写入验证不通过")
                }
                migratedCount++
            }
        }
        // mutate() 会自动剥离明文字段（见上）
        mutate { list -> list }
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_CREDENTIALS_MIGRATED] = true
        }
        android.util.Log.i("ProfileRepository", "凭据迁移完成，共 $migratedCount 个 profile")
    }

    /**
     * §4.3 一次性迁移：fpSeed==0（旧数据，未生成）的 profile 补生成随机种子，
     * 并抽一套真实设备模板作为指纹配置（避免所有老 profile 指纹雷同）。
     * fpSeed 一旦生成永不改变，保证同一 profile 指纹稳定。
     *
     * 只应在主进程调用一次（MainActivity.onCreate）。
     */
    suspend fun migrateFpConfigIfNeeded() {
        val prefs = appContext.profileDataStore.data.first()
        if (prefs[KEY_FP_MIGRATED] == true) return

        var migratedCount = 0
        // mutate() 会自动剥离明文凭据字段（见上），此处只关心指纹字段
        mutate { list ->
            list.map { p ->
                if (p.fpSeed == 0L) {
                    migratedCount++
                    p.copy(
                        fpSeed = generateFpSeed(),
                        fpConfig = DeviceTemplates.randomTemplate().config
                    )
                } else p
            }
        }
        appContext.profileDataStore.edit { p ->
            p[KEY_FP_MIGRATED] = true
        }
        android.util.Log.i("ProfileRepository", "指纹配置迁移完成，共 $migratedCount 个 profile")
    }

    companion object {
        @Volatile
        private var INSTANCE: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileRepository(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }
}
