package com.limao.fingerprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.limao.fingerprint.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import coil.compose.AsyncImage
import com.limao.fingerprint.CrashHandler
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProxyType

/** 列表页背景：深海军蓝 */
private val BgColor = Color(0xFF0A0E14)

/** 卡片背景：比底稍亮的深灰 */
private val CardColor = Color(0xFF151B24)

/** 强调色：启动按钮 */
private val AccentColor = Color(0xFF2196F3)

/** 运行中胶囊绿 */
private val RunningBlue = Color(0xFF1565C0)

/**
 * Profile 列表页（首页）。
 *
 * @param profiles 全部 profile
 * @param runningProfileIds 正在运行（:wvN 进程存活）的 profile id 集合
 * @param onLaunch 点击"启动"/"进入"：打开该 profile 的浏览器
 * @param onKill 点击"关闭"：结束该 profile 的 :wvN 进程
 * @param onEdit 点击卡片（除按钮外）：进入设置页
 * @param onAdd 点击右上角 +：新建 profile
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    profiles: List<Profile>,
    runningProfileIds: Set<String>,
    faviconEnabled: Boolean,
    onFaviconToggle: (Boolean) -> Unit,
    appLockEnabled: Boolean,
    onAppLockToggle: (Boolean) -> Unit,
    secureFlagEnabled: Boolean,
    onSecureFlagToggle: (Boolean) -> Unit,
    onLaunch: (String) -> Unit,
    onKill: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    /** 正在播放退出动画的 profile id（删除时先播动画再真正移除） */
    exitingProfileId: String? = null
) {
    val context = LocalContext.current
    // 右上角菜单与崩溃日志对话框
    var menuOpen by remember { mutableStateOf(false) }
    var crashLogOpen by remember { mutableStateOf(false) }
    var crashLogText by remember { mutableStateOf("") }
    // 搜索框展开状态与关键字
    var searchOpen by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    // 当前选中的分组筛选，null = 全部
    var selectedGroup by remember { mutableStateOf<String?>(null) }

    // 所有分组及计数
    val groups = remember(profiles) {
        profiles.groupBy { it.group.ifBlank { "未分组" } }
    }

    // 过滤后的列表：先按分组，再按关键字（名称/网址）
    val filtered = remember(profiles, selectedGroup, keyword) {
        profiles
            .filter { selectedGroup == null || (it.group.ifBlank { "未分组" }) == selectedGroup }
            .filter {
                keyword.isBlank() ||
                    it.name.contains(keyword, ignoreCase = true) ||
                    it.homepageUrl.contains(keyword, ignoreCase = true) ||
                    it.lastVisitedUrl.contains(keyword, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
    }

    // 城市夜景背景 + 深色遮罩
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg_city_night),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0E14).copy(alpha = 0.72f))
        )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Fingerprint", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) keyword = ""
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 Profile")
                    }
                    // 右上角菜单：崩溃日志
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("查看崩溃日志") },
                                onClick = {
                                    menuOpen = false
                                    crashLogText = CrashHandler.readLog(context)
                                    crashLogOpen = true
                                }
                            )
                            // §4.9 Profile 导出/导入
                            DropdownMenuItem(
                                text = { Text("导出全部 Profile（加密）") },
                                onClick = {
                                    menuOpen = false
                                    onExportAll()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入 Profile（加密）") },
                                onClick = {
                                    menuOpen = false
                                    onImport()
                                }
                            )
                            // §4.10 应用锁开关
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "应用锁（指纹/密码）",
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = appLockEnabled,
                                            onCheckedChange = { onAppLockToggle(it) }
                                        )
                                    }
                                },
                                onClick = { onAppLockToggle(!appLockEnabled) }
                            )
                            // §4.10 防截屏开关
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "防截屏（最近任务隐藏内容）",
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = secureFlagEnabled,
                                            onCheckedChange = { onSecureFlagToggle(it) }
                                        )
                                    }
                                },
                                onClick = { onSecureFlagToggle(!secureFlagEnabled) }
                            )
                            DropdownMenuItem(
                                text = { Text("提交 Bug 反馈") },
                                onClick = {
                                    menuOpen = false
                                    // 邮件反馈：收件人写死，主题和正文带设备信息模板
                                    val body = """
                                        【问题描述】


                                        【复现步骤】
                                        1.
                                        2.

                                        【崩溃日志】（如有，App 内 ⋮ → 查看崩溃日志 → 导出 .txt 后粘贴）
                                        （无）

                                        ---
                                        App 版本：v1.0
                                        手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                                        Android：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
                                    """.trimIndent()
                                    val emailIntent = android.content.Intent(
                                        android.content.Intent.ACTION_SENDTO
                                    ).apply {
                                        data = android.net.Uri.parse("mailto:fankui@limao71.ccwu.cc")
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "[Fingerprint Bug] ")
                                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                                    }
                                    context.startActivity(emailIntent)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "加载网站图标",
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = faviconEnabled,
                                                onCheckedChange = { onFaviconToggle(it) }
                                            )
                                        }
                                        // P1/P2-15：明确告知隐私影响，默认关闭
                                        Text(
                                            "开启后会向 Google favicon 服务请求图标，" +
                                                "URL 中包含网站域名（隐私优先默认关闭）",
                                            fontSize = 11.sp,
                                            color = Color(0xFF9AA4B2)
                                        )
                                    }
                                },
                                onClick = { onFaviconToggle(!faviconEnabled) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->

        // 崩溃日志对话框
        if (crashLogOpen) {
            AlertDialog(
                onDismissRequest = { crashLogOpen = false },
                title = { Text("崩溃日志") },
                text = {
                    Text(
                        crashLogText,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 复制到剪贴板
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashLogText))
                    }) { Text("复制") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            CrashHandler.clearLog(context)
                            crashLogText = "已清空"
                        }) { Text("清空", color = Color.Red) }
                        TextButton(onClick = {
                            val ok = CrashHandler.exportTxt(context, crashLogText)
                            crashLogText = if (ok) "已导出到下载目录，请查看" else crashLogText
                        }) { Text("导出 .txt") }
                        TextButton(onClick = { crashLogOpen = false }) { Text("关闭") }
                    }
                }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索框（展开时显示）
            if (searchOpen) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜索名称 / 网址", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentColor,
                        unfocusedBorderColor = Color(0xFF2A323E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 分组筛选 chips：全部 + 各分组（带计数）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GroupChip(
                    label = "全部 ${profiles.size}",
                    selected = selectedGroup == null,
                    onClick = { selectedGroup = null }
                )
                groups.forEach { (group, list) ->
                    GroupChip(
                        label = "$group ${list.size}",
                        selected = selectedGroup == group,
                        onClick = {
                            selectedGroup = if (selectedGroup == group) null else group
                        }
                    )
                }
            }

            // 空状态
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = null,
                            tint = Color(0xFF3A4452),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (profiles.isEmpty()) "还没有 Profile\n点右上角 + 创建第一个" else "没有匹配的 Profile",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { index, profile ->
                        // 动效2：列表入场 stagger（alpha 0→1 + 上移 8dp→0，每项延迟 50ms 上限 300ms）。
                        // remember 绑定 key，滚动复用/返回列表时不重播。
                        var enterVisible by remember(profile.id) { mutableStateOf(false) }
                        LaunchedEffect(profile.id) {
                            kotlinx.coroutines.delay((index * 50).coerceAtMost(300).toLong())
                            enterVisible = true
                        }
                        val enterAlpha by animateFloatAsState(
                            targetValue = if (enterVisible) 1f else 0f,
                            animationSpec = tween(250, easing = LinearOutSlowInEasing),
                            label = "enterAlpha"
                        )
                        val enterOffsetY by animateDpAsState(
                            targetValue = if (enterVisible) 0.dp else 8.dp,
                            animationSpec = tween(250, easing = LinearOutSlowInEasing),
                            label = "enterOffsetY"
                        )
                        // 动效4：删除退出（alpha→0 + scale 0.97，200ms ease-out）。
                        // exitingProfileId 置位后播退出动画，动画播完 MainActivity 才真正删数据。
                        AnimatedVisibility(
                            visible = profile.id != exitingProfileId,
                            exit = fadeOut(
                                animationSpec = tween(200, easing = LinearOutSlowInEasing)
                            ) + scaleOut(
                                targetScale = 0.97f,
                                animationSpec = tween(200, easing = LinearOutSlowInEasing)
                            )
                        ) {
                            ProfileCard(
                                profile = profile,
                                isRunning = profile.id in runningProfileIds,
                                faviconEnabled = faviconEnabled,
                                onLaunch = { onLaunch(profile.id) },
                                onKill = { onKill(profile.id) },
                                onEdit = { onEdit(profile.id) },
                                modifier = Modifier.graphicsLayer {
                                    alpha = enterAlpha
                                    translationY = enterOffsetY.toPx()
                                }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    // 底部小字：环境总数（列表不为空时跟在内容后，这里简化为悬浮条太复杂，省略）
    } // Box（背景层）
}

/** 分组筛选 chip */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentColor,
            selectedLabelColor = Color.White,
            containerColor = CardColor,
            labelColor = Color(0xFF9AA4B2)
        ),
        border = null,
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * 单个 Profile 卡片。
 * 左：favicon；中：名称 + 网址；右：状态胶囊 + 网络徽章 + 启动按钮。
 * 点击卡片（按钮除外）进入设置页。
 */
@Composable
private fun ProfileCard(
    profile: Profile,
    isRunning: Boolean,
    faviconEnabled: Boolean,
    onLaunch: () -> Unit,
    onKill: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 动效1：卡片按压缩放（scale 0.97，150ms ease-out）。只动 transform，不动 layout。
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "pressScale"
    )
    Surface(
        color = CardColor.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onEdit
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：网站 favicon（可在菜单关闭，关闭后不请求 Google 服务），加载失败显示默认指纹图标
            val iconUrl = if (faviconEnabled) FaviconHelper.faviconUrlFor(
                FaviconHelper.pickUrl(profile.homepageUrl, profile.lastVisitedUrl)
            ) else null
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF232C38)),
                contentAlignment = Alignment.Center
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = "网站图标",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = "默认图标",
                        tint = Color(0xFF5A6675),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 中间：名称 + 网址
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name.ifBlank { "未命名" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val url = FaviconHelper.pickUrl(profile.homepageUrl, profile.lastVisitedUrl)
                Text(
                    url.ifBlank { "未设置网址" },
                    color = Color(0xFF7A8595),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                // 状态胶囊 + 网络徽章
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(isRunning = isRunning)
                    ProxyBadge(proxyType = profile.proxyType)
                }
            }

            Spacer(Modifier.width(8.dp))

            // 右侧：运行中显示"进入"+"关闭"，未运行显示"启动"
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(if (isRunning) "进入" else "启动", fontSize = 14.sp)
                }
                // §4.10 运行状态：运行中可一键关闭进程
                if (isRunning) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onKill) {
                        Text("关闭", color = Color(0xFFF87171), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 状态胶囊：运行中（绿）/ 未启动（灰） */
@Composable
private fun StatusPill(isRunning: Boolean) {
    val bg = if (isRunning) RunningBlue else Color(0xFF2A323E)
    val text = if (isRunning) "运行中" else "未启动"
    val dot = if (isRunning) Color(0xFF64B5F6) else Color(0xFF6B7684)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

/** 网络徽章：SOCKS5 / HTTP / 直连 */
@Composable
private fun ProxyBadge(proxyType: ProxyType) {
    val label = when (proxyType) {
        ProxyType.NONE -> "直连"
        ProxyType.HTTP -> "HTTP"
        ProxyType.SOCKS5 -> "SOCKS5"
    }
    Text(
        label,
        color = Color(0xFF9AA4B2),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF232C38))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
