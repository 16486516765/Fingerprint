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

/** 网站图标开关的 key（默认开；关闭后不请求 Google favicon 服务，保护隐私） */
private val KEY_FAVICON_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("favicon_enabled")

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

    /** 网站图标开关（默认 true） */
    val faviconEnabled: Flow<Boolean> = appContext.profileDataStore.data
        .map { prefs -> prefs[KEY_FAVICON_ENABLED] ?: true }

    /** 设置网站图标开关 */
    suspend fun setFaviconEnabled(enabled: Boolean) {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_FAVICON_ENABLED] = enabled
        }
    }

    /**
     * 新增 profile，自动分配最小的空闲 slot（0 ~ MAX_PROFILES-1）。
     * slot 用满时抛出 NoFreeSlotException，调用方捕获后提示用户。
     */
    suspend fun addProfile(profile: Profile): Profile {
        var assigned: Profile? = null
        mutate { list ->
            val usedSlots = list.map { it.slot }.toSet()
            val freeSlot = (0 until MAX_PROFILES).firstOrNull { it !in usedSlots }
                ?: throw NoFreeSlotException()
            assigned = profile.copy(slot = freeSlot)
            list + assigned!!
        }
        return assigned!!
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

    /** 读取并解析 JSON，失败返回空列表（不崩溃） */
    private fun readList(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<Profile>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 原子读写：读出 -> 变换 -> 写回 */
    private suspend fun mutate(transform: (List<Profile>) -> List<Profile>) {
        appContext.profileDataStore.edit { prefs ->
            val current = readList(prefs[KEY_PROFILE_LIST])
            prefs[KEY_PROFILE_LIST] = gson.toJson(transform(current))
        }
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
