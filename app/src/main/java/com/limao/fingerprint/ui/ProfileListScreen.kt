package com.limao.fingerprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.widget.Toast
import coil.compose.AsyncImage
import com.limao.fingerprint.CrashHandler
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProxyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 背景：深海军蓝 */
private val BgColor = Color(0xFF0A0E14)

/** 卡片背景 */
private val CardColor = Color(0xFF151B24)

/** 主紫色（新增环境 / 选中态） */
private val PurplePrimary = Color(0xFF7C6CF5)

/** 深紫（选中胶囊底） */
private val PurpleDark = Color(0xFF3A3350)

/** 启动绿 */
private val AccentGreen = Color(0xFF2E9E5B)

/** 运行中亮绿 */
private val BrightGreen = Color(0xFF4ADE80)

/** 次要文字灰 */
private val TextGray = Color(0xFF9AA4B2)

/** 小标题紫灰 */
private val SubTitlePurple = Color(0xFF8B7CF6)

/**
* Profile 列表页（首页）——按 Canva 移动端设计稿重做。
*
* 结构：顶栏（Fingerprint + 设置/搜索/铃铛/+新增环境）
* → 标题区（WORKSPACE / ENVIRONMENTS + 浏览器环境 + 副标题）
* → 三张统计卡片 → 状态筛选胶囊 → 搜索框
* → 环境列表头（+批量选择）→ 环境卡片列表
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
onClone: (String) -> Unit,
onDelete: (String) -> Unit,
onExportAll: () -> Unit,
onExportSingle: (String) -> Unit,
onImport: () -> Unit
) {
val context = LocalContext.current
// 右上角设置菜单与崩溃日志对话框
var menuOpen by remember { mutableStateOf(false)}
var crashLogOpen by remember { mutableStateOf(false)}
var crashLogText by remember { mutableStateOf("")}
// 搜索关键字（搜索框常驻）
var keyword by remember { mutableStateOf("")}
// 状态筛选：null=全部，true=运行中，false=已停止
var statusFilter by remember { mutableStateOf<Boolean?>(null)}
// 批量选择模式
var batchMode by remember { mutableStateOf(false)}
var selectedIds by remember { mutableStateOf(setOf<String>())}
var batchDeleteConfirm by remember { mutableStateOf(false)}
// 单个删除确认
var deleteConfirmId by remember { mutableStateOf<String?>(null)}

val runningCount = profiles.count { it.id in runningProfileIds}
val stoppedCount = profiles.size - runningCount

// 过滤：先按状态，再按关键字（名称/网址）
val filtered = remember(profiles, statusFilter, keyword, runningProfileIds) {
profiles
.filter {
when (statusFilter) {
null -> true
true -> it.id in runningProfileIds
false -> it.id!in runningProfileIds
}
}
.filter {
keyword.isBlank() ||
it.name.contains(keyword, ignoreCase = true) ||
it.homepageUrl.contains(keyword, ignoreCase = true) ||
it.lastVisitedUrl.contains(keyword, ignoreCase = true)
}
.sortedByDescending { it.createdAt}
}

Scaffold(
containerColor = BgColor,
topBar = {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 10.dp),
verticalAlignment = Alignment.CenterVertically
) {
Text(
"Fingerprint",
color = Color.White,
fontWeight = FontWeight.Bold,
fontSize = 18.sp,
modifier = Modifier.weight(1f)
)
// 设置（原右上角 ⋮ 菜单：崩溃日志/导出导入/应用锁/防截屏/反馈/favicon）
Box {
IconButton(onClick = { menuOpen = true}) {
Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextGray)
}
SettingsMenu(
expanded = menuOpen,
onDismiss = { menuOpen = false},
context = context,
faviconEnabled = faviconEnabled,
onFaviconToggle = onFaviconToggle,
appLockEnabled = appLockEnabled,
onAppLockToggle = onAppLockToggle,
secureFlagEnabled = secureFlagEnabled,
onSecureFlagToggle = onSecureFlagToggle,
onExportAll = onExportAll,
onImport = onImport,
onOpenCrashLog = {
crashLogText = CrashHandler.readLog(context)
crashLogOpen = true
}
)
}
IconButton(onClick = {
Toast.makeText(context, "暂未开放", Toast.LENGTH_SHORT).show()
}) {
Icon(Icons.Filled.Notifications, contentDescription = "通知", tint = TextGray)
}
Spacer(Modifier.width(4.dp))
Button(
onClick = onAdd,
colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
shape = RoundedCornerShape(12.dp),
contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
Spacer(Modifier.width(4.dp))
Text("新增环境", fontSize = 14.sp)
}
}
},
bottomBar = {
// 批量模式底部栏
if (batchMode) {
Surface(
color = CardColor,
modifier = Modifier.fillMaxWidth()
) {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(16.dp),
verticalAlignment = Alignment.CenterVertically
) {
Text(
"已选 ${selectedIds.size} 个",
color = Color.White,
fontSize = 14.sp,
modifier = Modifier.weight(1f)
)
TextButton(onClick = {
batchMode = false
selectedIds = emptySet()
}) { Text("取消", color = TextGray)}
Spacer(Modifier.width(8.dp))
Button(
onClick = { batchDeleteConfirm = true},
enabled = selectedIds.isNotEmpty(),
colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
shape = RoundedCornerShape(12.dp)
) {
Text("删除", fontSize = 14.sp)
}
}
}
}
}
) { padding ->
// 崩溃日志对话框
if (crashLogOpen) {
CrashLogDialog(
crashLogText = crashLogText,
onClear = { crashLogText = "已清空"},
onExport = { crashLogText = it},
onDismiss = { crashLogOpen = false}
)
}
// 单个删除确认
val deleteTarget = deleteConfirmId?.let { id -> profiles.find { it.id == id}}
if (deleteTarget!= null) {
AlertDialog(
onDismissRequest = { deleteConfirmId = null},
title = { Text("删除环境")},
text = {
Text("确定删除「${deleteTarget.name.ifBlank { "未命名"}}」吗？\n该环境的浏览数据（Cookie/缓存）会一并清除。")
},
confirmButton = {
TextButton(onClick = {
deleteConfirmId = null
onDelete(deleteTarget.id)
}) { Text("删除", color = Color(0xFFF87171))}
},
dismissButton = {
TextButton(onClick = { deleteConfirmId = null}) { Text("取消")}
}
)
}
// 批量删除确认
if (batchDeleteConfirm) {
AlertDialog(
onDismissRequest = { batchDeleteConfirm = false},
title = { Text("批量删除")},
text = { Text("确定删除选中的 ${selectedIds.size} 个环境吗？\n它们的浏览数据会一并清除。")},
confirmButton = {
TextButton(onClick = {
batchDeleteConfirm = false
val ids = selectedIds.toList()
selectedIds = emptySet()
batchMode = false
ids.forEach { onDelete(it)}
}) { Text("删除", color = Color(0xFFF87171))}
},
dismissButton = {
TextButton(onClick = { batchDeleteConfirm = false}) { Text("取消")}
}
)
}

LazyColumn(
modifier = Modifier
.fillMaxSize()
.padding(padding),
contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
verticalArrangement = Arrangement.spacedBy(12.dp)
) {
// 标题区
item {
Column {
Text(
"WORKSPACE / ENVIRONMENTS",
color = SubTitlePurple,
fontSize = 11.sp,
fontWeight = FontWeight.Medium,
letterSpacing = 1.sp
)
Spacer(Modifier.height(4.dp))
Text(
"浏览器环境",
color = Color.White,
fontSize = 26.sp,
fontWeight = FontWeight.Bold
)
Spacer(Modifier.height(4.dp))
Text(
"集中管理你的独立浏览器配置，随时掌握运行状态。",
color = TextGray,
fontSize = 13.sp
)
}
}
// 三张统计卡片
item {
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.spacedBy(10.dp)
) {
StatCard(label = "环境总数", value = "${profiles.size}", modifier = Modifier.weight(1f))
StatCard(
label = "运行中",
value = "$runningCount",
valueColor = BrightGreen,
modifier = Modifier.weight(1f)
)
StatCard(label = "已停止", value = "$stoppedCount", modifier = Modifier.weight(1f))
}
}
// 状态筛选胶囊
item {
StatusSegment(
allCount = profiles.size,
runningCount = runningCount,
stoppedCount = stoppedCount,
selected = statusFilter,
onSelect = { statusFilter = it}
)
}
// 搜索框（常驻）
item {
OutlinedTextField(
value = keyword,
onValueChange = { keyword = it},
placeholder = { Text("搜索名称或网址", color = Color.Gray)},
leadingIcon = {
Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Gray)
},
trailingIcon = {
if (keyword.isNotEmpty()) {
IconButton(onClick = { keyword = ""}) {
Icon(Icons.Filled.Close, contentDescription = "清空", tint = Color.Gray)
}
}
},
singleLine = true,
modifier = Modifier.fillMaxWidth(),
colors = OutlinedTextFieldDefaults.colors(
focusedBorderColor = PurplePrimary,
unfocusedBorderColor = Color(0xFF2A323E),
focusedTextColor = Color.White,
unfocusedTextColor = Color.White,
cursorColor = PurplePrimary
),
shape = RoundedCornerShape(12.dp)
)
}
// 列表头
item {
Row(
modifier = Modifier.fillMaxWidth(),
verticalAlignment = Alignment.CenterVertically
) {
Text(
"环境列表",
color = Color.White,
fontSize = 16.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.weight(1f)
)
TextButton(onClick = {
batchMode =!batchMode
selectedIds = emptySet()
}) {
Text(
if (batchMode) "完成" else "批量选择",
color = PurplePrimary,
fontSize = 13.sp
)
}
}
}
// 空状态
if (filtered.isEmpty()) {
item {
Box(
modifier = Modifier
.fillMaxWidth()
.padding(vertical = 48.dp),
contentAlignment = Alignment.Center
) {
Column(horizontalAlignment = Alignment.CenterHorizontally) {
Icon(
Icons.Filled.Fingerprint,
contentDescription = null,
tint = Color(0xFF3A4452),
modifier = Modifier.size(64.dp)
)
Spacer(Modifier.height(12.dp))
Text(
if (profiles.isEmpty()) "还没有环境\n点右上角「新增环境」创建第一个"
else "没有匹配的环境",
color = Color.Gray,
fontSize = 14.sp,
lineHeight = 22.sp
)
}
}
}
} else {
items(filtered, key = { it.id}) { profile ->
val isRunning = profile.id in runningProfileIds
val selected = profile.id in selectedIds
EnvironmentCard(
profile = profile,
isRunning = isRunning,
faviconEnabled = faviconEnabled,
batchMode = batchMode,
selected = selected,
onToggleSelect = {
selectedIds = if (selected) selectedIds - profile.id
else selectedIds + profile.id
},
onLaunch = { onLaunch(profile.id)},
onKill = { onKill(profile.id)},
onEdit = { onEdit(profile.id)},
onClone = { onClone(profile.id)},
onDelete = { deleteConfirmId = profile.id},
onExportSingle = { onExportSingle(profile.id)}
)
}
item { Spacer(Modifier.height(16.dp))}
}
}
}
}

/** 统计小卡片 */
@Composable
private fun StatCard(
label: String,
value: String,
modifier: Modifier = Modifier,
valueColor: Color = Color.White
) {
Surface(
color = CardColor,
shape = RoundedCornerShape(14.dp),
modifier = modifier
) {
Column(modifier = Modifier.padding(14.dp)) {
Text(label, color = TextGray, fontSize = 12.sp)
Spacer(Modifier.height(6.dp))
Text(
value,
color = valueColor,
fontSize = 24.sp,
fontWeight = FontWeight.Bold
)
}
}
}

/**
* 状态筛选：胶囊式分段选择（全部 N / 运行中 N / 已停止 N）。
* @param selected null=全部，true=运行中，false=已停止
*/
@Composable
private fun StatusSegment(
allCount: Int,
runningCount: Int,
stoppedCount: Int,
selected: Boolean?,
onSelect: (Boolean?) -> Unit,
modifier: Modifier = Modifier
) {
Row(
modifier = modifier
.fillMaxWidth()
.clip(RoundedCornerShape(14.dp))
.background(Color(0xFF1A2130))
.padding(4.dp),
horizontalArrangement = Arrangement.spacedBy(4.dp)
) {
SegmentTab(
label = "全部 $allCount",
selected = selected == null,
onClick = { onSelect(null)},
modifier = Modifier.weight(1f)
)
SegmentTab(
label = "运行中 $runningCount",
selected = selected == true,
onClick = { onSelect(true)},
modifier = Modifier.weight(1f)
)
SegmentTab(
label = "已停止 $stoppedCount",
selected = selected == false,
onClick = { onSelect(false)},
modifier = Modifier.weight(1f)
)
}
}

@Composable
private fun SegmentTab(
label: String,
selected: Boolean,
onClick: () -> Unit,
modifier: Modifier = Modifier
) {
Box(
modifier = modifier
.clip(RoundedCornerShape(10.dp))
.background(if (selected) PurplePrimary else Color.Transparent)
.clickable(onClick = onClick)
.padding(vertical = 8.dp),
contentAlignment = Alignment.Center
) {
Text(
label,
color = if (selected) Color.White else TextGray,
fontSize = 13.sp,
fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
)
}
}

/**
* 环境卡片（按设计稿）：
* 左上图标 + 名称 + URL / 右上状态徽章
* 标签 chips（UA 内核 / 平台 / 代理状态）
* 最近使用时间
* 底部操作行：启动 / 编辑 / 复制 / 更多 / 删除
*/
@Composable
private fun EnvironmentCard(
profile: Profile,
isRunning: Boolean,
faviconEnabled: Boolean,
batchMode: Boolean,
selected: Boolean,
onToggleSelect: () -> Unit,
onLaunch: () -> Unit,
onKill: () -> Unit,
onEdit: () -> Unit,
onClone: () -> Unit,
onDelete: () -> Unit,
onExportSingle: () -> Unit
) {
var moreOpen by remember { mutableStateOf(false)}

Surface(
color = CardColor,
shape = RoundedCornerShape(16.dp),
modifier = Modifier
.fillMaxWidth()
.clickable(onClick = { if (batchMode) onToggleSelect() else onEdit()})
) {
Column(modifier = Modifier.padding(14.dp)) {
// 顶行：图标 + 名称/URL + 状态徽章（批量模式下徽章换成复选框）
Row(verticalAlignment = Alignment.Top) {
// 图标
val iconUrl = if (faviconEnabled) FaviconHelper.faviconUrlFor(
FaviconHelper.pickUrl(profile.homepageUrl, profile.lastVisitedUrl)
) else null
Box(
modifier = Modifier
.size(44.dp)
.clip(RoundedCornerShape(12.dp))
.background(Color(0xFF2A3350)),
contentAlignment = Alignment.Center
) {
if (iconUrl!= null) {
AsyncImage(
model = iconUrl,
contentDescription = "网站图标",
modifier = Modifier
.size(28.dp)
.clip(RoundedCornerShape(8.dp)),
contentScale = ContentScale.Fit
)
} else {
Icon(
Icons.Filled.Fingerprint,
contentDescription = "默认图标",
tint = Color(0xFF8B7CF6),
modifier = Modifier.size(24.dp)
)
}
}
Spacer(Modifier.width(10.dp))
// 名称 + URL
Column(modifier = Modifier.weight(1f)) {
Text(
profile.name.ifBlank { "未命名"},
color = Color.White,
fontWeight = FontWeight.Bold,
fontSize = 16.sp,
maxLines = 1,
overflow = TextOverflow.Ellipsis
)
Spacer(Modifier.height(2.dp))
Text(
profile.homepageUrl.ifBlank { "未设置网址"},
color = Color(0xFF7A8595),
fontSize = 12.sp,
maxLines = 1,
overflow = TextOverflow.Ellipsis
)
}
if (batchMode) {
IconButton(onClick = onToggleSelect, modifier = Modifier.size(32.dp)) {
Icon(
if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
contentDescription = "选择",
tint = if (selected) PurplePrimary else TextGray
)
}
} else {
StatusBadge(isRunning = isRunning)
}
}

Spacer(Modifier.height(10.dp))

// 标签 chips：UA 内核 / 平台 / 代理状态
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
TagChip(uaEngineLabel(profile.userAgent))
TagChip(platformLabel(profile.userAgent))
TagChip(proxyLabel(profile.proxyType))
}

Spacer(Modifier.height(10.dp))

// 最近使用 + 操作行
Text(
"最近使用 · " + formatLastUsed(profile.lastUsedAt),
color = Color(0xFF7A8595),
fontSize = 12.sp
)
Spacer(Modifier.height(10.dp))
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
// 启动（运行中则显示"进入"）
TextButton(
onClick = onLaunch,
colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
containerColor = Color(0xFF1E3A2E),
contentColor = BrightGreen
),
shape = RoundedCornerShape(10.dp),
contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
) {
Text(if (isRunning) "进入" else "启动", fontSize = 13.sp)
}
ActionTextButton("编辑", onClick = onEdit)
ActionTextButton("复制", onClick = onClone)
// 更多：溢出菜单
Box {
ActionTextButton("更多", onClick = { moreOpen = true})
DropdownMenu(
expanded = moreOpen,
onDismissRequest = { moreOpen = false}
) {
DropdownMenuItem(
text = { Text("导出此环境（加密）")},
onClick = {
moreOpen = false
onExportSingle()
}
)
if (isRunning) {
DropdownMenuItem(
text = { Text("关闭进程", color = Color(0xFFF87171))},
onClick = {
moreOpen = false
onKill()
}
)
}
}
}
Spacer(Modifier.weight(1f))
TextButton(
onClick = onDelete,
shape = RoundedCornerShape(10.dp),
contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
) {
Text("删除", color = Color(0xFFF87171), fontSize = 13.sp)
}
}
}
}
}

@Composable
private fun ActionTextButton(label: String, onClick: () -> Unit) {
TextButton(
onClick = onClick,
shape = RoundedCornerShape(10.dp),
contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
) {
Text(label, color = TextGray, fontSize = 13.sp)
}
}

/** 状态徽章：运行中（绿）/ 已停止（灰） */
@Composable
private fun StatusBadge(isRunning: Boolean) {
val bg = if (isRunning) Color(0xFF1E3A2E) else Color(0xFF2A323E)
val dot = if (isRunning) BrightGreen else Color(0xFF6B7684)
val text = if (isRunning) "运行中" else "已停止"
Row(
modifier = Modifier
.clip(RoundedCornerShape(20.dp))
.background(bg)
.padding(horizontal = 10.dp, vertical = 5.dp),
verticalAlignment = Alignment.CenterVertically,
horizontalArrangement = Arrangement.spacedBy(5.dp)
) {
Box(
modifier = Modifier
.size(6.dp)
.clip(CircleShape)
.background(dot)
)
Text(
text,
color = if (isRunning) BrightGreen else TextGray,
fontSize = 11.sp
)
}
}

/** 小标签 chip */
@Composable
private fun TagChip(label: String) {
Text(
label,
color = TextGray,
fontSize = 11.sp,
modifier = Modifier
.clip(RoundedCornerShape(8.dp))
.background(Color(0xFF232C38))
.padding(horizontal = 8.dp, vertical = 4.dp)
)
}

/** UA 内核标签：从 UA 字符串推断 */
private fun uaEngineLabel(userAgent: String): String {
if (userAgent.isBlank()) return "系统默认"
return when {
userAgent.contains("EdgA", ignoreCase = true) -> "Edge"
userAgent.contains("Chrome", ignoreCase = true) -> "Chromium"
else -> "Chromium"
}
}

/** 平台标签：从 UA 字符串推断 */
private fun platformLabel(userAgent: String): String {
if (userAgent.isBlank()) return "Android"
return when {
userAgent.contains("Windows", ignoreCase = true) -> "Windows"
userAgent.contains("Mac OS", ignoreCase = true) -> "macOS"
userAgent.contains("Android", ignoreCase = true) -> "Android"
userAgent.contains("Linux", ignoreCase = true) -> "Linux"
else -> "Android"
}
}

/** 代理状态标签 */
private fun proxyLabel(proxyType: ProxyType): String = when (proxyType) {
ProxyType.NONE -> "未配置代理"
ProxyType.HTTP -> "HTTP 代理"
ProxyType.SOCKS5 -> "SOCKS5 代理"
}

/** 最近使用时间格式化：M月d日 HH:mm；0=尚未使用 */
private fun formatLastUsed(timestamp: Long): String {
if (timestamp <= 0L) return "尚未使用"
return try {
SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))
} catch (e: Exception) {
"未知"
}
}

/**
* 右上角设置菜单（原 ⋮ 菜单全部功能保留）：
* 崩溃日志 / 导出全部 / 导入 / 应用锁 / 防截屏 / Bug 反馈 / favicon 开关
*/
@Composable
private fun SettingsMenu(
expanded: Boolean,
onDismiss: () -> Unit,
context: Context,
faviconEnabled: Boolean,
onFaviconToggle: (Boolean) -> Unit,
appLockEnabled: Boolean,
onAppLockToggle: (Boolean) -> Unit,
secureFlagEnabled: Boolean,
onSecureFlagToggle: (Boolean) -> Unit,
onExportAll: () -> Unit,
onImport: () -> Unit,
onOpenCrashLog: () -> Unit
) {
DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
DropdownMenuItem(
text = { Text("查看崩溃日志")},
onClick = {
onDismiss()
onOpenCrashLog()
}
)
DropdownMenuItem(
text = { Text("导出全部环境（加密）")},
onClick = {
onDismiss()
onExportAll()
}
)
DropdownMenuItem(
text = { Text("导入环境（加密）")},
onClick = {
onDismiss()
onImport()
}
)
DropdownMenuItem(
text = {
Row(
verticalAlignment = Alignment.CenterVertically,
modifier = Modifier.fillMaxWidth()
) {
Text("应用锁（指纹/密码）", modifier = Modifier.weight(1f))
Switch(checked = appLockEnabled, onCheckedChange = { onAppLockToggle(it)})
}
},
onClick = { onAppLockToggle(!appLockEnabled)}
)
DropdownMenuItem(
text = {
Row(
verticalAlignment = Alignment.CenterVertically,
modifier = Modifier.fillMaxWidth()
) {
Text("防截屏（最近任务隐藏内容）", modifier = Modifier.weight(1f))
Switch(checked = secureFlagEnabled, onCheckedChange = { onSecureFlagToggle(it)})
}
},
onClick = { onSecureFlagToggle(!secureFlagEnabled)}
)
DropdownMenuItem(
text = { Text("提交 Bug 反馈")},
onClick = {
onDismiss()
val body = """




1.
2.

（如有，App 内设置 → 查看崩溃日志 → 导出.txt 后粘贴）
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
Text("加载网站图标", modifier = Modifier.weight(1f))
Switch(checked = faviconEnabled, onCheckedChange = { onFaviconToggle(it)})
}
Text(
"开启后会向 Google favicon 服务请求图标，" +
"URL 中包含网站域名（隐私优先默认关闭）",
fontSize = 11.sp,
color = Color(0xFF9AA4B2)
)
}
},
onClick = { onFaviconToggle(!faviconEnabled)}
)
}
}

/** 崩溃日志对话框 */
@Composable
private fun CrashLogDialog(
crashLogText: String,
onClear: () -> Unit,
onExport: (String) -> Unit,
onDismiss: () -> Unit
) {
val context = LocalContext.current
AlertDialog(
onDismissRequest = onDismiss,
title = { Text("崩溃日志")},
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
val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
as android.content.ClipboardManager
cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashLogText))
}) { Text("复制")}
},
dismissButton = {
Row {
TextButton(onClick = {
CrashHandler.clearLog(context)
onClear()
}) { Text("清空", color = Color.Red)}
TextButton(onClick = {
val ok = CrashHandler.exportTxt(context, crashLogText)
onExport(if (ok) "已导出到下载目录，请查看" else crashLogText)
}) { Text("导出.txt")}
TextButton(onClick = onDismiss) { Text("关闭")}
}
}
)
}
