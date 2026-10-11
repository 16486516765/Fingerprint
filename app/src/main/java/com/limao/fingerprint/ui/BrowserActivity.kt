package com.limao.fingerprint.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.limao.fingerprint.FingerprintTheme
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import kotlinx.coroutines.launch

/**
 * 浏览器 Activity 壳，运行在 `:wvN` 独立进程（N = profile.slot，见 Manifest）。
 *
 * 多进程 DataStore 说明（见问题10）：
 * 本进程只在启动时同步读一次 profile，不再直接写 DataStore。
 * lastVisitedUrl 写到 filesDir 下的 `pending_url_{profileId}.txt`，
 * 主进程在 onResume 时读取并写入 DataStore 后删除文件。
 * 文件名按 profile id 隔离，单写者，无并发问题。
 */
open class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }

    private var profile by mutableStateOf<Profile?>(null)
    /** 本进程内最后访问的 URL，finish 时写文件回传主进程 */
    private var lastUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        val repository = ProfileRepository.getInstance(this)
        lifecycleScope.launch {
            val p = repository.getProfileById(profileId)
            if (p == null) {
                finish()
            } else {
                profile = p
                lastUrl = p.lastVisitedUrl
            }
        }
        setContent {
            FingerprintTheme {
                profile?.let {
                    BrowserScreen(
                        profile = it,
                        onUrlChanged = { url -> lastUrl = url },
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }

    override fun finish() {
        // 把最后访问的 URL 写文件，由主进程在 onResume 时回写 DataStore（见问题10）
        val p = profile
        if (p != null && lastUrl.isNotBlank() && lastUrl != p.lastVisitedUrl) {
            try {
                java.io.File(filesDir, "pending_url_${p.id}.txt").writeText(lastUrl)
            } catch (e: Exception) {
                android.util.Log.w("BrowserActivity", "写回 lastUrl 失败", e)
            }
        }
        super.finish()
    }
}
