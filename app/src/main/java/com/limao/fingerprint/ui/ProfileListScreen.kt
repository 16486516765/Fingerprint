package com.limao.fingerprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProxyType

/** 列表页背景：深海军蓝 */
private val BgColor = Color(0xFF0A0E14)

/** 卡片背景：比底稍亮的深灰 */
private val CardColor = Color(0xFF151B24)

/** 强调色：启动按钮 */
private val AccentColor = Color(0xFF2E9E5B)

/** 运行中胶囊绿 */
private val RunningGreen = Color(0xFF1F7A44)

/**
 * Profile 列表页（首页）。
 *
 * @param profiles 全部 profile
 * @param activeProfileId 当前正在浏览器中运行的 profile id（可空）
 * @param onLaunch 点击"启动"：打开该 profile 的浏览器
 * @param onEdit 点击卡片（除按钮外）：进入设置页
 * @param onAdd 点击右上角 +：新建 profile
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    profiles: List<Profile>,
    activeProfileId: String?,
    onLaunch: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit
) {
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

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Fingerprint", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
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
                }
            )
        }
    ) { padding ->
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
                    items(filtered, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            isRunning = profile.id == activeProfileId,
                            onLaunch = { onLaunch(profile.id) },
                            onEdit = { onEdit(profile.id) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    // 底部小字：环境总数（列表不为空时跟在内容后，这里简化为悬浮条太复杂，省略）
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
    onLaunch: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：网站 favicon，加载失败显示默认指纹图标
            val iconUrl = FaviconHelper.faviconUrlFor(
                FaviconHelper.pickUrl(profile.homepageUrl, profile.lastVisitedUrl)
            )
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

            // 右侧：启动按钮
            Button(
                onClick = onLaunch,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(if (isRunning) "进入" else "启动", fontSize = 14.sp)
            }
        }
    }
}

/** 状态胶囊：运行中（绿）/ 未启动（灰） */
@Composable
private fun StatusPill(isRunning: Boolean) {
    val bg = if (isRunning) RunningGreen else Color(0xFF2A323E)
    val text = if (isRunning) "运行中" else "未启动"
    val dot = if (isRunning) Color(0xFF4ADE80) else Color(0xFF6B7684)
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
