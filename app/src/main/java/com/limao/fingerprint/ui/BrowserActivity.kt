package com.limao.fingerprint.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import com.limao.fingerprint.ui.BrowserScreen
import kotlinx.coroutines.launch

/**
 * 浏览器 Activity 壳，运行在 `:wvN` 独立进程（N = profile.slot，见 Manifest）。
 *
 * 注意多进程下的 DataStore：
 * DataStore 不支持多进程并发读写。这里 :wvN 进程只在启动时同步读一次
 * profile（主进程负责写），之后不再跨进程碰 DataStore，避免竞态。
 * profile 为空（被主进程删了）则直接 finish。
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }

    private var profile by mutableStateOf<Profile?>(null)

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
            }
        }
        setContent {
            FingerprintTheme {
                profile?.let {
                    BrowserScreen(
                        profile = it,
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}
