package com.limao.fingerprint

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.limao.fingerprint.data.NoFreeSlotException
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import com.limao.fingerprint.data.ProxyCredentialStore
import com.limao.fingerprint.ui.BrowserActivity
import com.limao.fingerprint.ui.ProfileListScreen
import com.limao.fingerprint.ui.ProfileSettingsScreen
import kotlinx.coroutines.launch

/** 深色主题：纯黑背景 */
private val FingerprintColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0E14),
    primary = Color(0xFF2E9E5B),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun FingerprintTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FingerprintColors) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            color = Color(0xFF000000)
        ) {
            content()
        }
    }
}

/** 手写导航：浏览器页由独立进程的 BrowserActivity 承载，不走这里 */
private sealed interface Screen {
    /** Profile 列表（首页） */
    data object List : Screen

    /** 设置页：profileId 为 null 表示新建 */
    data class Settings(val profileId: String?) : Screen
}

/**
 * 主 Activity（跑在默认主进程）：Profile 列表 + 设置页。
 * 点"启动"后按 profile.slot 显式启动对应 :wvN 进程的 BrowserActivity。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P0-4：一次性迁移 JSON 里残留的明文代理凭据到加密存储
        lifecycleScope.launch {
            try {
                ProfileRepository.getInstance(this@MainActivity)
                    .migrateCredentialsIfNeeded()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "凭据迁移失败", e)
                Toast.makeText(
                    this@MainActivity,
                    "代理凭据迁移失败：${e.message}，请检查后重试",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        setContent {
            FingerprintTheme {
                MainNav()
            }
        }
    }

    @Composable
    private fun MainNav() {
        var screen by remember { mutableStateOf<Screen>(Screen.List) }
        val repository = remember { ProfileRepository.getInstance(this) }
        val profiles by repository.profiles.collectAsState(initial = emptyList())
        val faviconEnabled by repository.faviconEnabled.collectAsState(initial = true)
        val scope = rememberCoroutineScope()

        when (val s = screen) {
            is Screen.List -> {
                ProfileListScreen(
                    profiles = profiles,
                    // v1：暂不追踪哪个 profile 正在运行，统一显示"启动"
                    activeProfileId = null,
                    faviconEnabled = faviconEnabled,
                    onFaviconToggle = { enabled ->
                        scope.launch { repository.setFaviconEnabled(enabled) }
                    },
                    onLaunch = { profileId ->
                        val profile = profiles.find { it.id == profileId } ?: return@ProfileListScreen
                        launchBrowser(profile)
                    },
                    onEdit = { profileId -> screen = Screen.Settings(profileId) },
                    onAdd = { screen = Screen.Settings(null) }
                )
            }
            is Screen.Settings -> {
                val editing = profiles.find { it.id == s.profileId }
                ProfileSettingsScreen(
                    profile = editing,
                    onSave = { draft, username, password ->
                        scope.launch {
                            try {
                                if (editing == null) {
                                    // 新建：自动分配 slot
                                    val created = repository.addProfile(draft)
                                    // 代理凭据只存加密存储（P0-4）
                                    ProxyCredentialStore.save(
                                        this@MainActivity, created.id,
                                        username, password
                                    )
                                    Toast.makeText(
                                        this@MainActivity, "Profile 已创建", Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    // 编辑：保留原 slot
                                    repository.updateProfile(draft.copy(slot = editing.slot))
                                    ProxyCredentialStore.save(
                                        this@MainActivity, editing.id,
                                        username, password
                                    )
                                }
                                screen = Screen.List
                            } catch (e: NoFreeSlotException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Profile 已达上限（6 个），请先删除不用的",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onDelete = if (editing == null) null else ({
                        scope.launch {
                            val slot = editing.slot
                            repository.deleteProfile(editing.id)
                            // 清除加密存储中的代理凭据
                            ProxyCredentialStore.clear(this@MainActivity, editing.id)
                            // 清理该 slot 的 WebView 数据目录（Cookie、缓存等）
                            clearWebViewDataDir(slot)
                            screen = Screen.List
                            Toast.makeText(
                                this@MainActivity, "已删除，浏览数据已清除", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }),
                    onNavigateBack = { screen = Screen.List }
                )
            }
        }
    }

    /**
     * 按 profile.slot 启动对应进程的 BrowserActivity 子类。
     * slot 0 -> ui.BrowserActivity（:wv0）
     * slot N -> ui.BrowserActivityN（:wvN）
     *
     * 注意：类名必须用 namespace（com.limao.fingerprint）拼接，不能用运行时 packageName，
     * 因为 debug 构建的 applicationId 带 ".debug" 后缀，而 Manifest 中的 activity
     * 是按 namespace 解析的。用错会导致 ActivityNotFoundException 闪退。
     */
    private fun launchBrowser(profile: Profile) {
        // namespace 写死，不随 applicationIdSuffix 变化
        val namespace = "com.limao.fingerprint"
        val className = if (profile.slot == 0) {
            "$namespace.ui.BrowserActivity"
        } else {
            "$namespace.ui.BrowserActivity${profile.slot}"
        }
        try {
            val intent = Intent()
                .setClassName(packageName, className)
                .putExtra(BrowserActivity.EXTRA_PROFILE_ID, profile.id)
                // 每个 profile 跑独立任务，避免复用已有的 BrowserActivity 任务导致进程没起新
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("FingerprintApp", "启动浏览器失败: $className", e)
            android.widget.Toast.makeText(
                this,
                "启动失败: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 清理指定 slot 的 WebView 数据目录。
     * 先尝试结束对应的 :wvN 进程，再递归删除 app_webview_fp_slot_N 目录。
     */
    private fun clearWebViewDataDir(slot: Int) {
        if (slot < 0) return
        try {
            // 1. 结束对应的 :wvN 进程（避免文件被占用删不干净）
            val targetSuffix = ":wv$slot"
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            for (proc in am.runningAppProcesses ?: emptyList()) {
                if (proc.processName.endsWith(targetSuffix)) {
                    android.os.Process.killProcess(proc.pid)
                    android.util.Log.i("FingerprintApp", "已结束进程 ${proc.processName}")
                    break
                }
            }
            // 给进程一点时间退出
            Thread.sleep(300)
            // 2. 删除数据目录
            val dir = java.io.File(dataDir, "app_webview_fp_slot_$slot")
            if (dir.exists()) {
                val deleted = dir.deleteRecursively()
                android.util.Log.i("FingerprintApp", "数据目录 ${dir.path} 删除: $deleted")
            }
        } catch (e: Exception) {
            android.util.Log.w("FingerprintApp", "清理数据目录失败", e)
        }
    }
}
