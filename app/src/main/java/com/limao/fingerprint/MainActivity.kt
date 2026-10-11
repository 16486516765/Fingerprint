package com.limao.fingerprint

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.limao.fingerprint.data.NoFreeSlotException
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import com.limao.fingerprint.data.ProxyCredentialStore
import com.limao.fingerprint.ui.BrowserActivity
import com.limao.fingerprint.ui.ProfileListScreen
import com.limao.fingerprint.ui.ProfileSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 深色主题：纯黑背景 */
private val FingerprintColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0E14),
    primary = Color(0xFF2196F3),
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
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** BUG-4：JSON 损坏时显示恢复选项 */
    private var showCorruptDialog by mutableStateOf(false)
    /** §4.10 运行状态：:wvN 进程存活的 profile id 集合 */
    private var runningProfileIds by mutableStateOf<Set<String>>(emptySet())
    /** §4.10 应用锁：三态——null=检查中（冷启动先显示占位，不闪现主界面），
        true=已验证，false=未验证。冷启动默认 null，必须走完 checkAppLock 才能进。 */
    private var appUnlocked: Boolean? by mutableStateOf(null)
    /** §4.10 应用锁：上次切后台时间（超过 2 分钟回前台要重新验证） */
    private var lastBackgroundTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §4.10 防截屏：根据设置应用 FLAG_SECURE
        lifecycleScope.launch {
            try {
                val secure = ProfileRepository.getInstance(this@MainActivity).secureFlagEnabled
                    .first()
                if (secure) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "FLAG_SECURE 设置失败", e)
            }
        }
        // BUG-4：最先检查 JSON 是否损坏（之前 checkAndBackupCorrupted 从未被调用）
        lifecycleScope.launch {
            try {
                val corrupted = ProfileRepository.getInstance(this@MainActivity)
                    .checkAndBackupCorrupted()
                if (corrupted) {
                    showCorruptDialog = true
                } else {
                    // P0-4：一次性迁移 JSON 里残留的明文代理凭据到加密存储
                    ProfileRepository.getInstance(this@MainActivity)
                        .migrateCredentialsIfNeeded()
                    // §4.3：旧数据补指纹种子 + 设备模板
                    ProfileRepository.getInstance(this@MainActivity)
                        .migrateFpConfigIfNeeded()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "启动检查失败", e)
            }
        }
        setContent {
            FingerprintTheme {
                // §4.10 应用锁：检查中显示黑屏占位；未验证只显示锁定页
                when (appUnlocked) {
                    null -> androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFF000000))
                    )
                    false -> AppLockScreen(onRetry = { showBiometricPrompt() })
                    true -> MainNav()
                }
                if (showCorruptDialog) {
                    CorruptDataDialog(
                        onExport = { exportBackup() },
                        onRestore = { restoreBackup() },
                        onRebuild = { rebuildEmpty() }
                    )
                }
            }
        }
    }

    /** §4.10 应用锁锁定页 */
    @Composable
    private fun AppLockScreen(onRetry: () -> Unit) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0xFF000000)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFF5A6675),
                    modifier = androidx.compose.ui.Modifier.size(72.dp)
                )
                androidx.compose.foundation.layout.Spacer(
                    modifier = androidx.compose.ui.Modifier.height(16.dp)
                )
                androidx.compose.material3.Text(
                    "Fingerprint 已锁定",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 18.sp
                )
                androidx.compose.foundation.layout.Spacer(
                    modifier = androidx.compose.ui.Modifier.height(8.dp)
                )
                androidx.compose.material3.Text(
                    "验证身份以继续",
                    color = androidx.compose.ui.graphics.Color(0xFF9AA4B2),
                    fontSize = 14.sp
                )
                androidx.compose.foundation.layout.Spacer(
                    modifier = androidx.compose.ui.Modifier.height(24.dp)
                )
                androidx.compose.material3.Button(onClick = onRetry) {
                    androidx.compose.material3.Text("验证身份")
                }
            }
        }
    }

    /** BUG-4：导出备份 JSON 到下载目录 */
    private fun exportBackup() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val json = ProfileRepository.getInstance(this@MainActivity).getBackupJson()
                if (json.isNullOrBlank()) {
                    showToast("没有可导出的备份")
                    return@launch
                }
                val file = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "Fingerprint-profile-backup.json"
                )
                file.writeText(json)
                showToast("已导出到下载目录")
            } catch (e: Exception) {
                showToast("导出失败：${e.message}")
            }
        }
    }

    /** BUG-4：从备份恢复 */
    private fun restoreBackup() {
        lifecycleScope.launch {
            try {
                val ok = ProfileRepository.getInstance(this@MainActivity).restoreFromBackup()
                showToast(if (ok) "已从备份恢复" else "备份不可用或已损坏")
                if (ok) showCorruptDialog = false
            } catch (e: Exception) {
                showToast("恢复失败：${e.message}")
            }
        }
    }

    /** BUG-4：清空重建 */
    private fun rebuildEmpty() {
        lifecycleScope.launch {
            try {
                ProfileRepository.getInstance(this@MainActivity).clearAndRebuild()
                showToast("已清空并重建")
                showCorruptDialog = false
            } catch (e: Exception) {
                showToast("重建失败：${e.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** §4.9 导出对话框：输入密码，生成加密字符串，复制到剪贴板 */
    @Composable
    private fun ExportDialog(
        profiles: List<Profile>,
        onDismiss: () -> Unit,
        onExported: () -> Unit
    ) {
        var password by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { androidx.compose.material3.Text("导出全部 Profile") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "用密码加密导出（PBKDF2 + AES-GCM）。\n" +
                            "代理账号密码、浏览记录不会导出，导入后需重新填写。",
                        fontSize = 13.sp,
                        color = Color(0xFF9AA4B2)
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(12.dp)
                    )
                    if (result == null) {
                        androidx.compose.material3.OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { androidx.compose.material3.Text("设置导出密码") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        androidx.compose.material3.Text(
                            "导出成功，已复制到剪贴板，请妥善保存：",
                            fontSize = 13.sp,
                            color = Color(0xFF64B5F6)
                        )
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.height(8.dp)
                        )
                        androidx.compose.material3.Text(
                            result!!.take(60) + "...",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color(0xFF9AA4B2)
                        )
                    }
                }
            },
            confirmButton = {
                if (result == null) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            if (password.length < 4) {
                                Toast.makeText(context, "密码至少 4 位", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            try {
                                val encrypted =
                                    com.limao.fingerprint.data.ProfileExportImport.exportAll(
                                        profiles, password
                                    )
                                result = encrypted
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("profile_export", encrypted))
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context, "导出失败：${e.message}", Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) { androidx.compose.material3.Text("导出并复制") }
                } else {
                    androidx.compose.material3.TextButton(onClick = onExported) {
                        androidx.compose.material3.Text("完成")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text("取消")
                }
            }
        )
    }

    /** §4.9 导入对话框：粘贴加密字符串 + 密码，解密后逐个导入 */
    @Composable
    private fun ImportDialog(
        onDismiss: () -> Unit,
        onImported: () -> Unit
    ) {
        var encrypted by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { androidx.compose.material3.Text("导入 Profile") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "粘贴导出的加密字符串，输入密码解密导入。\n" +
                            "导入的 Profile 会分配新的 slot 和指纹种子。",
                        fontSize = 13.sp,
                        color = Color(0xFF9AA4B2)
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(12.dp)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = encrypted,
                        onValueChange = { encrypted = it },
                        label = { androidx.compose.material3.Text("加密字符串") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(8.dp)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { androidx.compose.material3.Text("密码") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (encrypted.isBlank() || password.isBlank()) {
                            Toast.makeText(context, "请填写完整", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        scope.launch {
                            try {
                                val imported =
                                    com.limao.fingerprint.data.ProfileExportImport.importProfiles(
                                        encrypted, password
                                    )
                                if (imported.isEmpty()) {
                                    Toast.makeText(context, "没有可导入的 Profile", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val repository =
                                    ProfileRepository.getInstance(this@MainActivity)
                                var count = 0
                                for (p in imported) {
                                    try {
                                        // 分配新 ID/slot/指纹种子，避免冲突
                                        val newProfile = p.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            slot = -1,
                                            fpSeed = java.util.UUID.randomUUID().leastSignificantBits,
                                            createdAt = System.currentTimeMillis()
                                        )
                                        repository.addProfile(newProfile)
                                        count++
                                    } catch (e: NoFreeSlotException) {
                                        Toast.makeText(
                                            context,
                                            "已导入 $count 个，slot 已满",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        break
                                    }
                                }
                                Toast.makeText(
                                    context, "成功导入 $count 个 Profile", Toast.LENGTH_SHORT
                                ).show()
                                onImported()
                            } catch (e: IllegalArgumentException) {
                                Toast.makeText(
                                    context, "导入失败：${e.message}", Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) { androidx.compose.material3.Text("导入") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text("取消")
                }
            }
        )
    }

    @Composable
    private fun CorruptDataDialog(
        onExport: () -> Unit,
        onRestore: () -> Unit,
        onRebuild: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { /* 必须三选一，不可点空白关闭 */ },
            title = { androidx.compose.material3.Text("配置数据损坏") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "Profile 配置文件已损坏，原数据已自动备份，请选择处理方式："
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = androidx.compose.ui.Modifier
                            .height(12.dp)
                    )
                    androidx.compose.material3.TextButton(onClick = onRestore) {
                        androidx.compose.material3.Text("从备份恢复（推荐）")
                    }
                    androidx.compose.material3.TextButton(onClick = onExport) {
                        androidx.compose.material3.Text("导出备份 JSON")
                    }
                    androidx.compose.material3.TextButton(onClick = onRebuild) {
                        androidx.compose.material3.Text(
                            "清空重建",
                            color = androidx.compose.ui.graphics.Color(0xFFF87171)
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    override fun onResume() {
        super.onResume()
        // §4.10 应用锁：检查是否需要验证
        checkAppLock()
        // §4.10 运行状态：刷新哪些 :wvN 进程还活着
        refreshRunningProfiles()
        // 回收 :wvN 进程写下的 pending URL 文件，统一由主进程写 DataStore（见问题10）
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val pendingFiles = filesDir.listFiles { f ->
                    f.name.startsWith("pending_url_") && f.name.endsWith(".txt")
                } ?: emptyArray()
                if (pendingFiles.isEmpty()) return@launch
                val repository = ProfileRepository.getInstance(this@MainActivity)
                for (file in pendingFiles) {
                    try {
                        val profileId = file.name
                            .removePrefix("pending_url_")
                            .removeSuffix(".txt")
                        val url = file.readText().trim()
                        if (profileId.isNotBlank() && url.isNotBlank()) {
                            repository.updateLastVisitedUrl(profileId, url)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "处理 pending URL 失败: ${file.name}", e)
                    } finally {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "回收 pending URL 失败", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // §4.10 应用锁：记录切后台时间
        lastBackgroundTime = System.currentTimeMillis()
    }

    /**
     * §4.10 应用锁：已开启且（未验证过 / 冷启动 / 后台超过 2 分钟）时要求验证。
     * 冷启动判断：lastBackgroundTime == 0 表示本进程从未切过后台，
     * 此时内存里的 appUnlocked 是初始值、不可信，必须重新验证。
     * 用 BiometricPrompt（指纹/面容），降级可用设备 PIN/密码。
     */
    private fun checkAppLock() {
        lifecycleScope.launch {
            try {
                val repository = ProfileRepository.getInstance(this@MainActivity)
                val enabled = repository.appLockEnabled.first()
                if (!enabled) {
                    appUnlocked = true
                    // §4.10 跨进程：锁没开就顺手清掉残留文件，避免 :wvN 误拦
                    com.limao.fingerprint.data.AppLockStore.clear(this@MainActivity)
                    return@launch
                }
                val needAuth = appUnlocked != true ||
                    lastBackgroundTime == 0L ||
                    (System.currentTimeMillis() - lastBackgroundTime > 2 * 60 * 1000)
                if (needAuth) {
                    appUnlocked = false
                    showBiometricPrompt()
                } else {
                    appUnlocked = true
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "应用锁检查失败", e)
                appUnlocked = true // 检查失败时放行，避免锁死
            }
        }
    }

    /** §4.10：弹出系统生物识别/设备凭据验证 */
    private fun showBiometricPrompt() {
        try {
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult
                    ) {
                        appUnlocked = true
                        lastBackgroundTime = 0
                        // §4.10 跨进程：把验证成功时间写文件，:wvN 进程读它做宽限判断
                        com.limao.fingerprint.data.AppLockStore.write(
                            this@MainActivity, true, System.currentTimeMillis()
                        )
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // 用户取消或多次失败：留在锁定页，不放行
                        android.util.Log.w("MainActivity", "验证失败: $errString")
                    }

                    override fun onAuthenticationFailed() {
                        // 单次识别失败，可重试，不处理
                    }
                }
            )
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Fingerprint 已锁定")
                .setSubtitle("验证身份以查看你的 Profile")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "生物识别不可用", e)
            appUnlocked = true // 设备不支持时放行，避免锁死
        }
    }

    /**
     * §4.10 运行状态：用 ActivityManager.runningAppProcesses 检查
     * 哪些 :wvN 进程存活，映射回 profile id。
     */
    private fun refreshRunningProfiles() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                val liveSlots = (am.runningAppProcesses ?: emptyList())
                    .mapNotNull { proc ->
                        // 进程名形如 com.limao.fingerprint.debug:wv2
                        proc.processName.substringAfterLast(":").takeIf { it.startsWith("wv") }
                            ?.removePrefix("wv")?.toIntOrNull()
                    }.toSet()
                val repository = ProfileRepository.getInstance(this@MainActivity)
                val profiles = repository.profiles.first()
                val running = profiles.filter { it.slot in liveSlots }.map { it.id }.toSet()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    runningProfileIds = running
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "刷新运行状态失败", e)
            }
        }
    }

    /**
     * §4.10：关闭指定 profile 的 :wvN 进程（列表页"关闭"按钮）。
     */
    private fun killProfileProcess(profileId: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val repository = ProfileRepository.getInstance(this@MainActivity)
                val profile = repository.profiles.first().find { it.id == profileId }
                    ?: return@launch
                val targetSuffix = ":wv${profile.slot}"
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                for (proc in am.runningAppProcesses ?: emptyList()) {
                    if (proc.processName.endsWith(targetSuffix)) {
                        android.os.Process.killProcess(proc.pid)
                        android.util.Log.i("MainActivity", "已关闭 ${proc.processName}")
                        break
                    }
                }
                // 等一会儿再刷新状态
                kotlinx.coroutines.delay(500)
                refreshRunningProfiles()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "关闭进程失败", e)
            }
        }
    }

    @Composable
    private fun MainNav() {
        var screen by remember { mutableStateOf<Screen>(Screen.List) }
        val repository = remember { ProfileRepository.getInstance(this) }
        val profiles by repository.profiles.collectAsState(initial = emptyList())
        val faviconEnabled by repository.faviconEnabled.collectAsState(initial = false)
        val appLockEnabled by repository.appLockEnabled.collectAsState(initial = false)
        val secureFlagEnabled by repository.secureFlagEnabled.collectAsState(initial = false)
        val scope = rememberCoroutineScope()
        // §4.9 导出/导入对话框状态
        var showExportDialog by remember { mutableStateOf(false) }
        var showImportDialog by remember { mutableStateOf(false) }
        // 动效4：正在播放退出动画的 profile id（删除时先播 200ms 动画再真正删数据）
        var exitingProfileId by remember { mutableStateOf<String?>(null) }

        when (val s = screen) {
            is Screen.List -> {
                ProfileListScreen(
                    profiles = profiles,
                    runningProfileIds = runningProfileIds,
                    faviconEnabled = faviconEnabled,
                    onFaviconToggle = { enabled ->
                        scope.launch { repository.setFaviconEnabled(enabled) }
                    },
                    appLockEnabled = appLockEnabled,
                    onAppLockToggle = { enabled ->
                        scope.launch {
                            repository.setAppLockEnabled(enabled)
                            if (enabled) {
                                // §4.10 跨进程：先写文件（verified_at=0 表示从未验证），
                                // 这样 :wvN 进程即使在主进程弹窗被取消时也能正确要求验证
                                com.limao.fingerprint.data.AppLockStore.write(
                                    this@MainActivity, true, 0
                                )
                                // 开启后立即要求验证一次，确保能用
                                appUnlocked = false
                                showBiometricPrompt()
                            } else {
                                // §4.10 跨进程：关闭锁就删文件，:wvN 进程不再拦截
                                com.limao.fingerprint.data.AppLockStore.clear(this@MainActivity)
                            }
                            Toast.makeText(
                                this@MainActivity,
                                if (enabled) "应用锁已开启" else "应用锁已关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    secureFlagEnabled = secureFlagEnabled,
                    onSecureFlagToggle = { enabled ->
                        scope.launch {
                            repository.setSecureFlagEnabled(enabled)
                            Toast.makeText(
                                this@MainActivity,
                                "重启 App 后生效",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onLaunch = { profileId ->
                        val profile = profiles.find { it.id == profileId } ?: return@ProfileListScreen
                        launchBrowser(profile)
                    },
                    onKill = { profileId -> killProfileProcess(profileId) },
                    onEdit = { profileId -> screen = Screen.Settings(profileId) },
                    onAdd = { screen = Screen.Settings(null) },
                    onExportAll = { showExportDialog = true },
                    onImport = { showImportDialog = true },
                    exitingProfileId = exitingProfileId
                )
                // §4.9 导出/导入对话框
                if (showExportDialog) {
                    ExportDialog(
                        profiles = profiles,
                        onDismiss = { showExportDialog = false },
                        onExported = { showExportDialog = false }
                    )
                }
                if (showImportDialog) {
                    ImportDialog(
                        onDismiss = { showImportDialog = false },
                        onImported = { showImportDialog = false }
                    )
                }
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
                    onClone = if (editing == null) null else ({
                        // §4.9 克隆：复制配置，新 ID/slot/fpSeed，不复制 Cookie
                        scope.launch {
                            try {
                                val cloned = editing.copy(
                                    id = java.util.UUID.randomUUID().toString(),
                                    slot = -1,
                                    name = editing.name + "（副本）",
                                    fpSeed = java.util.UUID.randomUUID().leastSignificantBits,
                                    lastVisitedUrl = "",
                                    createdAt = System.currentTimeMillis()
                                )
                                repository.addProfile(cloned)
                                Toast.makeText(
                                    this@MainActivity, "已克隆", Toast.LENGTH_SHORT
                                ).show()
                                screen = Screen.List
                            } catch (e: NoFreeSlotException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Profile 已达上限（6 个），请先删除不用的",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }),
                    onDelete = if (editing == null) null else ({
                        scope.launch {
                            val slot = editing.slot
                            val profileId = editing.id
                            // 动效4：先播 200ms 退出动画，再真正删数据
                            exitingProfileId = profileId
                            screen = Screen.List
                            kotlinx.coroutines.delay(220)
                            // 先删配置
                            repository.deleteProfile(profileId)
                            // 清除加密存储中的代理凭据
                            ProxyCredentialStore.clear(this@MainActivity, profileId)
                            // IO 线程清理数据目录（见问题11）：确认进程退出后再删，
                            // 检查删除结果，失败则标记脏 slot 不再复用
                            val cleanResult = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) {
                                clearWebViewDataDir(slot)
                            }
                            exitingProfileId = null
                            if (cleanResult) {
                                Toast.makeText(
                                    this@MainActivity, "已删除，浏览数据已清除",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "配置已删除，但浏览数据清理失败（slot $slot 已锁定不再分配）",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
            // 动效3：启动浏览器转场（淡入 + 轻微右滑，250ms ease-out）
            overridePendingTransition(R.anim.browser_enter, R.anim.main_exit)
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
     * 清理指定 slot 的 WebView 数据目录（见问题11）。
     * 必须在 IO 线程调用：
     * 1. 结束对应的 :wvN 进程
     * 2. 轮询确认进程确实退出（不用固定 sleep）
     * 3. 递归删除 app_webview_fp_slot_N 目录并检查结果
     * 4. 删除失败则标记脏 slot，addProfile 不再分配
     * @return true 清理成功；false 失败（已标记脏 slot）
     */
    private suspend fun clearWebViewDataDir(slot: Int): Boolean {
        if (slot < 0) return true
        return try {
            val repository = ProfileRepository.getInstance(this)
            // 1. 结束对应的 :wvN 进程
            val targetSuffix = ":wv$slot"
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            var pidToWait: Int? = null
            for (proc in am.runningAppProcesses ?: emptyList()) {
                if (proc.processName.endsWith(targetSuffix)) {
                    pidToWait = proc.pid
                    android.os.Process.killProcess(proc.pid)
                    android.util.Log.i("FingerprintApp", "已结束进程 ${proc.processName}")
                    break
                }
            }
            // 2. 轮询确认进程退出（最多等 3 秒，不用固定 sleep）
            if (pidToWait != null) {
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val stillAlive = am.runningAppProcesses?.any { it.pid == pidToWait } == true
                    if (!stillAlive) break
                    kotlinx.coroutines.delay(200)
                }
            }
            // 3. 删除数据目录并检查结果
            val dir = java.io.File(dataDir, "app_webview_fp_slot_$slot")
            val deleted = if (dir.exists()) {
                val ok = dir.deleteRecursively()
                android.util.Log.i("FingerprintApp", "数据目录 ${dir.path} 删除: $ok")
                ok && !dir.exists()
            } else {
                true // 目录本就不存在，视为成功
            }
            // 4. 失败则标记脏 slot
            if (!deleted) {
                repository.markSlotDirty(slot)
            }
            deleted
        } catch (e: Exception) {
            android.util.Log.w("FingerprintApp", "清理数据目录失败", e)
            try {
                ProfileRepository.getInstance(this).markSlotDirty(slot)
            } catch (_: Exception) { }
            false
        }
    }
}
