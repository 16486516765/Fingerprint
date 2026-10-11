package com.limao.fingerprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.limao.fingerprint.data.DeviceTemplates
import com.limao.fingerprint.data.FpConfig
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProxyCredentialStore
import com.limao.fingerprint.data.ProxyType
import com.limao.fingerprint.data.UaPresets
import com.limao.fingerprint.data.generateFpSeed

private val BgColor = Color(0xFF000000)
private val CardColor = Color(0xFF151B24)
private val AccentColor = Color(0xFF2196F3)
private val DangerColor = Color(0xFFF87171)

/**
 * Profile 设置页（新建 / 编辑）。
 *
 * @param profile null=新建；非空=编辑（表单回填）
 * @param onSave 点保存：把组装好的 Profile + 代理凭据（明文，仅内存传递）
 *               交给调用方（调用方负责调 repository addProfile 自动分配 slot，
 *               或 updateProfile；凭据由调用方存 ProxyCredentialStore）
 * @param onDelete 删除回调（新建模式传 null，不显示删除按钮）
 * @param onNavigateBack 返回
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    profile: Profile?,
    onSave: (Profile, String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onClone: (() -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val isNew = profile == null
    val context = androidx.compose.ui.platform.LocalContext.current

    // 代理凭据优先从加密存储读（老数据 fallback 到 profile 明文字段，做迁移）
    val savedCreds = remember(profile?.id) {
        if (profile != null) ProxyCredentialStore.load(context, profile.id)
        else "" to ""
    }

    var name by remember { mutableStateOf(profile?.name ?: "") }
    var homepageUrl by remember { mutableStateOf(profile?.homepageUrl ?: "") }
    var group by remember { mutableStateOf(profile?.group?.ifBlank { "未分组" } ?: "未分组") }
    // UA：下拉选中项的显示名；自定义时 customUa 生效
    var uaLabel by remember { mutableStateOf(UaPresets.labelFor(profile?.userAgent ?: "")) }
    var customUa by remember {
        mutableStateOf(
            if (uaLabel == UaPresets.CUSTOM_LABEL) profile?.userAgent ?: "" else ""
        )
    }
    var uaExpanded by remember { mutableStateOf(false) }
    // 代理
    var proxyType by remember { mutableStateOf(profile?.proxyType ?: ProxyType.NONE) }
    var proxyHost by remember { mutableStateOf(profile?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(profile?.proxyPort?.toString() ?: "1080") }
    var proxyUser by remember { mutableStateOf(savedCreds.first) }
    var proxyPass by remember { mutableStateOf(savedCreds.second) }
    // 指纹伪装（§4.3）
    // 初始模板：编辑时按 fpConfig 反查模板，对不上就是"自定义"；新建时随机抽一套
    val initialFp: FpConfig = profile?.fpConfig ?: DeviceTemplates.randomTemplate().config
    var fpSeed by remember {
        mutableStateOf(
            profile?.fpSeed?.takeIf { it != 0L } ?: generateFpSeed()
        )
    }
    var fpTemplateLabel by remember {
        mutableStateOf(
            profile?.fpConfig?.let { DeviceTemplates.findByConfig(it)?.displayName }
                ?: DeviceTemplates.findByConfig(initialFp)?.displayName
                ?: DeviceTemplates.CUSTOM_LABEL
        )
    }
    var fpTemplateExpanded by remember { mutableStateOf(false) }
    var fpScreenW by remember { mutableStateOf(initialFp.screenW.toString()) }
    var fpScreenH by remember { mutableStateOf(initialFp.screenH.toString()) }
    var fpDpr by remember { mutableStateOf(initialFp.dpr.toString()) }
    var fpCores by remember { mutableStateOf(initialFp.cores.toString()) }
    var fpMem by remember { mutableStateOf(initialFp.memGb.toString()) }
    var fpPlatform by remember { mutableStateOf(initialFp.platform) }
    var fpLanguages by remember { mutableStateOf(initialFp.languages.joinToString(", ")) }
    var fpTimezone by remember { mutableStateOf(initialFp.timezone) }
    var fpWebglVendor by remember { mutableStateOf(initialFp.webglVendor) }
    var fpWebglRenderer by remember { mutableStateOf(initialFp.webglRenderer) }
    var fpCanvasNoise by remember { mutableStateOf(initialFp.canvasNoise) }
    var fpWebrtcAllow by remember { mutableStateOf(initialFp.webrtcMode == 1) }
    // 校验与对话框
    var nameError by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isCustomUa = uaLabel == UaPresets.CUSTOM_LABEL
    val isCustomFp = fpTemplateLabel == DeviceTemplates.CUSTOM_LABEL

    /** 把一套模板配置填入表单 */
    fun applyFpTemplate(t: DeviceTemplates.DeviceTemplate) {
        fpTemplateLabel = t.displayName
        val c = t.config
        fpScreenW = c.screenW.toString()
        fpScreenH = c.screenH.toString()
        fpDpr = c.dpr.toString()
        fpCores = c.cores.toString()
        fpMem = c.memGb.toString()
        fpPlatform = c.platform
        fpLanguages = c.languages.joinToString(", ")
        fpTimezone = c.timezone
        fpWebglVendor = c.webglVendor
        fpWebglRenderer = c.webglRenderer
        fpCanvasNoise = c.canvasNoise
        fpWebrtcAllow = c.webrtcMode == 1
    }

    /** 用户手改了任一指纹字段：自动切到"自定义"，避免显示的模板名与实际值不一致 */
    fun markFpCustom() {
        if (!isCustomFp) fpTemplateLabel = DeviceTemplates.CUSTOM_LABEL
    }

    fun doSave() {
        if (name.isBlank()) {
            nameError = true
            return
        }
        val ua = if (isCustomUa) customUa.trim() else {
            UaPresets.presets.find { it.displayName == uaLabel }?.ua ?: ""
        }
        val fpConfig = FpConfig(
            screenW = fpScreenW.toIntOrNull()?.coerceIn(1, 8192) ?: 1080,
            screenH = fpScreenH.toIntOrNull()?.coerceIn(1, 8192) ?: 2400,
            dpr = fpDpr.toFloatOrNull()?.coerceIn(0.5f, 6f) ?: 2.625f,
            cores = fpCores.toIntOrNull()?.coerceIn(1, 32) ?: 8,
            memGb = fpMem.toIntOrNull()?.coerceIn(1, 8) ?: 8,
            platform = fpPlatform.trim().ifBlank { "Linux armv8l" },
            languages = fpLanguages.split(",").map { it.trim() }
                .filter { it.isNotBlank() }.ifEmpty { listOf("en-US", "en") },
            timezone = fpTimezone.trim().ifBlank { "America/New_York" },
            webglVendor = fpWebglVendor.trim().ifBlank { "Google Inc." },
            webglRenderer = fpWebglRenderer.trim(),
            canvasNoise = fpCanvasNoise,
            // UI 暂不暴露 AudioContext 开关，保持原值（默认开）
            audioNoise = profile?.fpConfig?.audioNoise ?: true,
            webrtcMode = if (fpWebrtcAllow) 1 else 0
        )
        val seed = if (fpSeed == 0L) generateFpSeed() else fpSeed
        val base = profile ?: Profile()
        // P0-4：凭据单独传递，不进 Profile JSON
        onSave(
            base.copy(
                name = name.trim(),
                homepageUrl = homepageUrl.trim(),
                group = group.trim().ifBlank { "未分组" },
                userAgent = ua,
                proxyType = proxyType,
                proxyHost = proxyHost.trim(),
                proxyPort = proxyPort.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
                proxyUsername = "",
                proxyPassword = "",
                fpSeed = seed,
                fpConfig = fpConfig
            ),
            proxyUser.trim(),
            proxyPass
        )
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "新建 Profile" else "${profile?.name} - 设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // favicon 预览行
            FaviconPreviewRow(homepageUrl = homepageUrl)

            // 名称（必填）
            FormField(label = "名称 *") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    placeholder = { Text("如：甲骨文主号", color = Color.Gray) },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("名称不能为空", color = DangerColor) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 主页 URL
            FormField(label = "主页 URL") {
                OutlinedTextField(
                    value = homepageUrl,
                    onValueChange = { homepageUrl = it },
                    placeholder = { Text("https://cloud.oracle.com", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }

            // 分组
            FormField(label = "分组") {
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // User-Agent 下拉
            FormField(label = "User-Agent") {
                ExposedDropdownMenuBox(
                    expanded = uaExpanded,
                    onExpandedChange = { uaExpanded = !uaExpanded }
                ) {
                    OutlinedTextField(
                        value = uaLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = uaExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = uaExpanded,
                        onDismissRequest = { uaExpanded = false },
                        modifier = Modifier.background(CardColor)
                    ) {
                        (UaPresets.presets.map { it.displayName } + UaPresets.CUSTOM_LABEL)
                            .forEach { label ->
                                DropdownMenuItem(
                                    text = { Text(label, color = Color.White, fontSize = 14.sp) },
                                    onClick = {
                                        uaLabel = label
                                        uaExpanded = false
                                    }
                                )
                            }
                    }
                }
                if (isCustomUa) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUa,
                        onValueChange = { customUa = it },
                        placeholder = { Text("粘贴自定义 UA", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2
                    )
                }
            }

            // 指纹伪装（§4.3）
            FormField(label = "指纹伪装") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardColor)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 随机种子行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "随机种子：$fpSeed",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { fpSeed = generateFpSeed() }) {
                            Text("重新生成", color = AccentColor, fontSize = 13.sp)
                        }
                    }
                    // 设备模板下拉
                    ExposedDropdownMenuBox(
                        expanded = fpTemplateExpanded,
                        onExpandedChange = { fpTemplateExpanded = !fpTemplateExpanded }
                    ) {
                        OutlinedTextField(
                            value = fpTemplateLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("设备模板", color = Color.Gray, fontSize = 12.sp) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = fpTemplateExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = fieldColors(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = fpTemplateExpanded,
                            onDismissRequest = { fpTemplateExpanded = false },
                            modifier = Modifier.background(CardColor)
                        ) {
                            (DeviceTemplates.all.map { it.displayName } +
                                DeviceTemplates.CUSTOM_LABEL)
                                .forEach { label ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(label, color = Color.White, fontSize = 14.sp)
                                        },
                                        onClick = {
                                            fpTemplateExpanded = false
                                            if (label == DeviceTemplates.CUSTOM_LABEL) {
                                                fpTemplateLabel = label
                                            } else {
                                                DeviceTemplates.all
                                                    .find { it.displayName == label }
                                                    ?.let(::applyFpTemplate)
                                            }
                                        }
                                    )
                                }
                        }
                    }
                    // 随机生成一套
                    Button(
                        onClick = {
                            applyFpTemplate(DeviceTemplates.randomTemplate())
                            fpSeed = generateFpSeed()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232C38)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("随机生成一套", color = Color.White, fontSize = 14.sp)
                    }
                    if (isCustomFp) {
                        // 自定义可编辑项
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FpNumberField(
                                label = "屏幕宽",
                                value = fpScreenW,
                                onChange = { fpScreenW = it; markFpCustom() },
                                modifier = Modifier.weight(1f)
                            )
                            FpNumberField(
                                label = "屏幕高",
                                value = fpScreenH,
                                onChange = { fpScreenH = it; markFpCustom() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FpNumberField(
                                label = "dpr",
                                value = fpDpr,
                                onChange = { fpDpr = it; markFpCustom() },
                                decimal = true,
                                modifier = Modifier.weight(1f)
                            )
                            FpNumberField(
                                label = "核心数",
                                value = fpCores,
                                onChange = { fpCores = it; markFpCustom() },
                                modifier = Modifier.weight(1f)
                            )
                            FpNumberField(
                                label = "内存(GB)",
                                value = fpMem,
                                onChange = { fpMem = it; markFpCustom() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        FpTextField(
                            label = "platform",
                            value = fpPlatform,
                            onChange = { fpPlatform = it; markFpCustom() }
                        )
                        FpTextField(
                            label = "语言（逗号分隔）",
                            value = fpLanguages,
                            onChange = { fpLanguages = it; markFpCustom() }
                        )
                        FpTextField(
                            label = "时区（如 America/New_York）",
                            value = fpTimezone,
                            onChange = { fpTimezone = it; markFpCustom() }
                        )
                        FpTextField(
                            label = "WebGL vendor",
                            value = fpWebglVendor,
                            onChange = { fpWebglVendor = it; markFpCustom() }
                        )
                        FpTextField(
                            label = "WebGL renderer",
                            value = fpWebglRenderer,
                            onChange = { fpWebglRenderer = it; markFpCustom() }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Canvas 噪声",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = fpCanvasNoise,
                                onCheckedChange = { fpCanvasNoise = it; markFpCustom() }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "WebRTC",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (fpWebrtcAllow) "允许" else "禁用",
                                color = Color(0xFF9AA4B2),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Switch(
                                checked = fpWebrtcAllow,
                                onCheckedChange = { fpWebrtcAllow = it; markFpCustom() }
                            )
                        }
                    } else {
                        // 模板摘要
                        Text(
                            "${fpScreenW}×${fpScreenH} · dpr $fpDpr · " +
                                "${fpCores} 核 · ${fpMem}GB · $fpTimezone",
                            color = Color(0xFF9AA4B2),
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        "提示：同一 profile 种子固定，指纹稳定；不同 profile 用不同种子+模板，避免关联。",
                        color = Color(0xFF5A6675),
                        fontSize = 12.sp
                    )
                }
            }

            // 代理类型分段选择
            FormField(label = "代理") {
                val options = listOf(
                    "直连" to ProxyType.NONE,
                    "HTTP" to ProxyType.HTTP,
                    "SOCKS5" to ProxyType.SOCKS5
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (label, type) ->
                        SegmentedButton(
                            selected = proxyType == type,
                            onClick = { proxyType = type },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = options.size
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = AccentColor,
                                activeContentColor = Color.White,
                                inactiveContainerColor = CardColor,
                                inactiveContentColor = Color(0xFF9AA4B2)
                            )
                        ) {
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            // 代理详细配置（非直连时显示）
            if (proxyType != ProxyType.NONE) {
                FormField(label = "代理地址") {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        placeholder = { Text("127.0.0.1", color = Color.Gray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
                FormField(label = "端口") {
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                FormField(label = "用户名（可选）") {
                    OutlinedTextField(
                        value = proxyUser,
                        onValueChange = { proxyUser = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                FormField(label = "密码（可选）") {
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = { proxyPass = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
                // P1-6：ProxyController 的 socks:// 规则不支持用户名密码认证
                //（SOCKS5 认证是协议内协商，WebView 公开接口无此能力）。
                // 带认证的 SOCKS5 明确标注不支持，而不是显示已连接。
                if (proxyType == ProxyType.SOCKS5 &&
                    (proxyUser.isNotBlank() || proxyPass.isNotBlank())
                ) {
                    Text(
                        "⚠ 当前 WebView 接口不支持 SOCKS5 用户名密码认证，" +
                            "填写的账号密码不会生效。如需认证请使用 HTTP 代理。",
                        color = Color(0xFFFB923C),
                        fontSize = 12.sp
                    )
                }
                Text(
                    "提示：代理在 profile 独立进程内生效，一号一代理，互不干扰。",
                    color = Color(0xFF5A6675),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // 保存按钮
            Button(
                onClick = ::doSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            // 删除按钮（仅编辑模式）
            if (!isNew && onDelete != null) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除此 Profile", color = DangerColor, fontSize = 14.sp)
                }
            }

            // §4.9 克隆按钮（仅编辑模式）：复制配置，生成新 fpSeed，不复制 Cookie
            if (!isNew && onClone != null) {
                TextButton(
                    onClick = { onClone() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("克隆此 Profile", color = Color(0xFF9AA4B2), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除二次确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除 Profile", color = Color.White) },
            text = {
                Text(
                    "「${profile?.name}」将被删除，其独立的浏览数据（Cookie、缓存等）也会一并清除，且不可恢复。确定吗？",
                    color = Color(0xFF9AA4B2)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete?.invoke()
                }) {
                    Text("删除", color = DangerColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = Color.White)
                }
            },
            containerColor = CardColor
        )
    }
}

/** favicon 预览行：根据主页 URL 显示将使用的图标 */
@Composable
private fun FaviconPreviewRow(homepageUrl: String) {
    val iconUrl = FaviconHelper.faviconUrlFor(homepageUrl.trim())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF232C38)),
            contentAlignment = Alignment.Center
        ) {
            if (iconUrl != null) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = "网站图标预览",
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = "默认图标",
                    tint = Color(0xFF5A6675),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "图标将自动读取该网站的 favicon",
            color = Color(0xFF9AA4B2),
            fontSize = 13.sp
        )
    }
}

/** 表单字段：label + 内容 */
@Composable
private fun FormField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            color = Color(0xFF9AA4B2),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}

/** 指纹伪装分组内的文本输入框 */
@Composable
private fun FpTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Color.Gray, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = fieldColors(),
        shape = RoundedCornerShape(12.dp)
    )
}

/** 指纹伪装分组内的数字输入框 */
@Composable
private fun FpNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    decimal: Boolean = false,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            onChange(if (decimal) it.filter { c -> c.isDigit() || c == '.' }
            else it.filter { c -> c.isDigit() })
        },
        label = { Text(label, color = Color.Gray, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = fieldColors(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        )
    )
}

/** 深色输入框统一样式 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentColor,
    unfocusedBorderColor = Color(0xFF2A323E),
    errorBorderColor = DangerColor,
    focusedContainerColor = CardColor,
    unfocusedContainerColor = CardColor,
    errorContainerColor = CardColor,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = AccentColor
)
