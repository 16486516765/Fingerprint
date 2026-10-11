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
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProxyCredentialStore
import com.limao.fingerprint.data.ProxyType
import com.limao.fingerprint.data.UaPresets

private val BgColor = Color(0xFF000000)
private val CardColor = Color(0xFF151B24)
private val AccentColor = Color(0xFF2E9E5B)
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
    // 校验与对话框
    var nameError by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isCustomUa = uaLabel == UaPresets.CUSTOM_LABEL

    fun doSave() {
        if (name.isBlank()) {
            nameError = true
            return
        }
        val ua = if (isCustomUa) customUa.trim() else {
            UaPresets.presets.find { it.displayName == uaLabel }?.ua ?: ""
        }
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
                proxyPassword = ""
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
