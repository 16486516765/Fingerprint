package com.limao.fingerprint.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.limao.fingerprint.FingerprintTheme
import com.limao.fingerprint.data.AppLockStore
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器 Activity 壳，运行在 `:wvN` 独立进程（N = profile.slot，见 Manifest）。
 *
 * 多进程 DataStore 说明（见问题10）：
 * 本进程只在启动时同步读一次 profile，不再直接写 DataStore。
 * lastVisitedUrl 写到 filesDir 下的 `pending_url_{profileId}.txt`，
 * 主进程在 onResume 时读取并写入 DataStore 后删除文件。
 * 文件名按 profile id 隔离，单写者，无并发问题。
 *
 * §4.10 应用锁：本 Activity 在最近任务里是独立卡片，用户可不经过 MainActivity
 * 直接点进来，所以这里也要做锁检查。跨进程状态走 AppLockStore（普通文件，
 * 每次 fresh read），不用 DataStore（见 BUG-1 的 stale 缓存教训）。
 * 继承 FragmentActivity 是为了用 BiometricPrompt。
 */
open class BrowserActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }

    private var profile by mutableStateOf<Profile?>(null)
    /** 本进程内最后访问的 URL，finish 时写文件回传主进程 */
    private var lastUrl: String = ""

    /**
     * §4.10 应用锁三态：null=检查中（先显示黑屏占位），true=已验证，false=锁定页。
     * 冷启动默认 null，必须走完 checkBrowserLock 才能进。
     */
    private var browserUnlocked: Boolean? by mutableStateOf(null)

    /** 系统验证框弹出期间为 true，避免 onResume 重复检查导致弹窗循环 */
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §4.10 防截屏：根据设置应用 FLAG_SECURE（浏览器进程也要防）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val secure = ProfileRepository.getInstance(this@BrowserActivity).secureFlagEnabled
                    .first()
                if (secure) {
                    withContext(Dispatchers.Main) {
                        window.setFlags(
                            android.view.WindowManager.LayoutParams.FLAG_SECURE,
                            android.view.WindowManager.LayoutParams.FLAG_SECURE
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BrowserActivity", "FLAG_SECURE 设置失败", e)
            }
        }
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        // §4.10 应用锁：先检查锁（onCreate 允许 DataStore 兜底，见 checkBrowserLock）
        checkBrowserLock(allowDataStoreFallback = true)
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
                when (browserUnlocked) {
                    null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF000000))
                    )
                    false -> BrowserLockScreen(
                        onRetry = { showLockPrompt() },
                        onExit = { finish() }
                    )
                    true -> profile?.let {
                        BrowserScreen(
                            profile = it,
                            onUrlChanged = { url -> lastUrl = url },
                            onNavigateBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // §4.10 应用锁：从最近任务卡片点进活进程时只走 onResume，必须重查。
        // 不用 DataStore 兜底（活进程的 DataStore 可能是 stale 缓存，见 BUG-1），
        // 只信 AppLockStore 文件（fresh read）。
        if (!authInProgress) {
            checkBrowserLock(allowDataStoreFallback = false)
        }
    }

    /**
     * §4.10 应用锁检查。
     * @param allowDataStoreFallback 仅 onCreate 传 true：文件缺失时做一次 DataStore
     * 直读兜底（新进程读盘是 fresh 的），覆盖"更新 App 后没进过主进程就直接点
     * 浏览器卡片"的窗口。onResume 传 false，避免读到 stale 缓存。
     */
    private fun checkBrowserLock(allowDataStoreFallback: Boolean) {
        val state = AppLockStore.read(this)
        if (state != null) {
            applyLockState(state)
            return
        }
        if (!allowDataStoreFallback) {
            // onResume 且文件缺失：onCreate 已兜底过，说明锁没开过，直接放行
            browserUnlocked = true
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val enabled = try {
                ProfileRepository.getInstance(this@BrowserActivity).appLockEnabled.first()
            } catch (e: Exception) {
                android.util.Log.w("BrowserActivity", "应用锁兜底读取失败", e)
                false // 新进程读盘失败概率极低；失败则放行，避免误锁
            }
            withContext(Dispatchers.Main) {
                if (enabled) {
                    AppLockStore.write(this@BrowserActivity, true, 0)
                    enterLockedState()
                } else {
                    browserUnlocked = true
                }
            }
        }
    }

    private fun applyLockState(state: AppLockStore.State) {
        if (AppLockStore.needsAuth(state)) {
            enterLockedState()
        } else {
            browserUnlocked = true
        }
    }

    /**
     * 进入锁定态：只有从非锁定态进入时才弹系统验证框；已在锁定页时不重复弹，
     * 避免用户取消验证后陷入弹窗循环（锁定页有"验证身份"/"退出浏览器"按钮）。
     */
    private fun enterLockedState() {
        if (browserUnlocked != false) {
            browserUnlocked = false
            showLockPrompt()
        }
    }

    /** §4.10：弹出系统生物识别/设备凭据验证（与主进程同一套） */
    private fun showLockPrompt() {
        authInProgress = true
        try {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult
                    ) {
                        authInProgress = false
                        // 跨进程：更新验证时间，主进程和其他 :wvN 进程共享宽限
                        AppLockStore.write(
                            this@BrowserActivity, true, System.currentTimeMillis()
                        )
                        browserUnlocked = true
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authInProgress = false
                        android.util.Log.w("BrowserActivity", "验证失败: $errString")
                        // 用户取消或多次失败：留在锁定页，不放行
                    }

                    override fun onAuthenticationFailed() {
                        // 单次识别失败，可重试，不处理
                    }
                }
            )
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Fingerprint 已锁定")
                .setSubtitle("验证身份以继续浏览")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            authInProgress = false
            android.util.Log.w("BrowserActivity", "生物识别不可用", e)
            browserUnlocked = true // 设备不支持时放行，避免锁死（与主进程一致）
        }
    }

    /** §4.10 浏览器进程锁定页（风格与主进程一致，多一个退出按钮避免困住用户） */
    @Composable
    private fun BrowserLockScreen(
        onRetry: () -> Unit,
        onExit: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = Color(0xFF5A6675),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Fingerprint 已锁定",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "验证身份以继续浏览",
                    color = Color(0xFF9AA4B2),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry) {
                    Text("验证身份")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onExit) {
                    Text("退出浏览器")
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

    override fun onDestroy() {
        super.onDestroy()
        // BUG-1：:wvN 进程的 DataStore 是内存缓存，Activity finish 后进程不死，
        // 下次启动复用进程会读到旧配置（代理/UA/凭据全是旧的）。
        // 关闭浏览器时直接结束本进程，下次启动必然是新进程读到最新配置。
        if (isFinishing) {
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (e: Exception) {
                android.util.Log.w("BrowserActivity", "Cookie flush 失败", e)
            }
            // 给 flush 一点时间落盘再杀，避免丢 Cookie
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 400)
        }
    }
}
