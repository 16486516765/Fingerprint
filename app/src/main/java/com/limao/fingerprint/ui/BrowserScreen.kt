package com.limao.fingerprint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.limao.fingerprint.data.Profile
import com.limao.fingerprint.data.ProfileRepository
import com.limao.fingerprint.data.ProxyCredentialStore
import com.limao.fingerprint.data.ProxyType
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF000000)
private val BarColor = Color(0xFF0A0E14)
private val FieldColor = Color(0xFF1A212C)
private val AccentColor = Color(0xFF2196F3)

/** 输入的地址缺 scheme 时自动补 https:// */
private fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.isBlank()) return t
    return if (t.contains("://")) t else "https://$t"
}

/**
 * URL scheme 白名单（见问题16）。
 * 只允许 http/https（+ about:blank），阻止 file/content/intent/javascript 等。
 */
private fun isAllowedScheme(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower == "about:blank"
}

/** 代理设置 UI 状态（见问题2、问题3） */
private sealed interface ProxySetupState {
    /** 尚未设置（WebView 创建中） */
    data object Pending : ProxySetupState
    /** 配置已应用（ProxyController 回调已确认） */
    data object Applied : ProxySetupState
    /** 设置失败（fail-closed，不加载网页） */
    data class Failed(val reason: String) : ProxySetupState
}

/** §4.8 多标签：每个标签一个 WebView，同一进程共享 Cookie/代理 */
private data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val webView: WebView,
    var title: String = "新标签页",
    var url: String = "about:blank"
)

/**
 * 浏览器界面（运行在 :wvN 独立进程，由 BrowserActivity 承载）。
 *
 * @param profile 当前 profile（不可变快照）
 * @param onUrlChanged URL 变化时回调，由 BrowserActivity 回传主进程写入
 *                    （本进程不直接写 DataStore，见问题10）
 * @param onNavigateBack 返回列表（BrowserActivity 负责 finish）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    profile: Profile,
    onUrlChanged: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // §4.8 多标签状态
    var tabs by remember { mutableStateOf<List<BrowserTab>>(emptyList()) }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var showTabSwitcher by remember { mutableStateOf(false) }

    /** 当前活动标签的 WebView（替代原来的单 webView 变量） */
    fun activeWebView(): WebView? = tabs.find { it.id == activeTabId }?.webView
    /** 当前活动标签 */
    fun activeTab(): BrowserTab? = tabs.find { it.id == activeTabId }

    // WebView 是否已创建（第一个标签创建后触发代理设置流程）
    var webViewCreated by remember { mutableStateOf(false) }
    // 地址栏文本
    var urlText by remember { mutableStateOf(profile.lastVisitedUrl.ifBlank { profile.homepageUrl }) }
    // 地址栏是否聚焦：聚焦时不让页面加载回调覆盖用户输入
    var fieldFocused by remember { mutableStateOf(false) }
    // 加载进度 0..1
    var progress by remember { mutableFloatStateOf(0f) }
    // 导航按钮可用状态
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // 代理设置状态：Pending=尚未设置，Applied=配置已应用，Failed=失败（fail-closed，不加载）
    var proxyState by remember { mutableStateOf<ProxySetupState>(ProxySetupState.Pending) }
    // fail-closed 对话框：代理失败时是否显示"仍然直连"选项
    var showProxyFailDialog by remember { mutableStateOf(false) }
    // 用户是否已选择"仍然直连"（绕过 fail-closed）
    var bypassProxyFail by remember { mutableStateOf(false) }
    // 右下角菜单展开
    var menuOpen by remember { mutableStateOf(false) }
    // §4.7 Cookie 导入/导出对话框
    var showCookieImport by remember { mutableStateOf(false) }
    var showCookieExport by remember { mutableStateOf(false) }
    // §4.4(a) 文件上传：暂存 WebView 的回调，picker 返回后触发
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }
    // §4.4(d) 视频全屏：系统回调给的 customView，显示时覆盖全屏
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // §4.4(c) 权限请求确认：暂存请求，弹窗让用户确认
    var permissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var geoPermissionCallback by remember { mutableStateOf<GeolocationPermissions.Callback?>(null) }
    var geoPermissionOrigin by remember { mutableStateOf<String?>(null) }
    // §4.5 下载进度提示
    var downloadMsg by remember { mutableStateOf<String?>(null) }

    val initialUrl = remember {
        profile.lastVisitedUrl.ifBlank { profile.homepageUrl }.ifBlank { "about:blank" }
    }

    // §4.1 WebView 内真实出口检测：代理应用成功后，先在 WebView 里加载
    // api.ipify.org 读出口 IP，再加载用户页面。测的是 WebView 真实出口，
    // 不需要 Java 层直连（旧的 Java 测试已废弃）。
    var egressIp by remember { mutableStateOf<String?>(null) }
    var probingEgress by remember { mutableStateOf(false) }

    /**
     * 代理设置流程（见问题2、问题3）：
     * WebView 创建后，等待 ProxyController 配置真正应用，再 loadUrl。
     * 代理失败时 fail-closed：不加载，弹窗让用户选择"仍然直连"或返回。
     *
     * §4.6：SOCKS5 带用户名时走本地中继（LocalRelay），否则 Chromium
     * 不支持 SOCKS 认证。relay 实例保存，Activity 销毁时 stop。
     */
    var relay by remember { mutableStateOf<LocalRelay?>(null) }
    LaunchedEffect(webViewCreated) {
        if (!webViewCreated) return@LaunchedEffect
        val wv = activeWebView() ?: return@LaunchedEffect
        // §4.6：SOCKS5 + 有用户名 → 本地中继
        val (u, p) = if (profile.proxyType == ProxyType.SOCKS5) {
            ProxyCredentialStore.load(context, profile.id)
        } else {
            "" to ""
        }
        val result: WebViewProxy.ProxySetupResult
        if (profile.proxyType == ProxyType.SOCKS5 && u.isNotBlank()) {
            val (r, newRelay) = WebViewProxy.setProxyViaRelayAsync(
                profile.proxyType, profile.proxyHost, profile.proxyPort, u, p
            )
            result = r
            relay = newRelay
        } else {
            result = WebViewProxy.setProxyAsync(
                profile.proxyType, profile.proxyHost, profile.proxyPort
            )
        }
        when (result) {
            is WebViewProxy.ProxySetupResult.Applied -> {
                proxyState = ProxySetupState.Applied
                // §4.1：先探测 WebView 真实出口，再加载用户页面
                if (profile.proxyType != ProxyType.NONE) {
                    probingEgress = true
                    wv.loadUrl("https://api.ipify.org")
                } else {
                    wv.loadUrl(initialUrl)
                }
            }
            is WebViewProxy.ProxySetupResult.NotSupported -> {
                proxyState = ProxySetupState.Failed("WebView 内核不支持代理覆盖")
                if (profile.proxyType != ProxyType.NONE) showProxyFailDialog = true
                else wv.loadUrl(initialUrl) // 直连 profile：无旧代理可清，直接加载
            }
            is WebViewProxy.ProxySetupResult.InvalidArgs -> {
                proxyState = ProxySetupState.Failed("代理参数无效：${result.reason}")
                showProxyFailDialog = true
            }
            is WebViewProxy.ProxySetupResult.Failed -> {
                proxyState = ProxySetupState.Failed("代理配置失败：${result.reason}")
                showProxyFailDialog = true
            }
        }
    }

    /** 跳转（带 scheme 白名单，见问题16） */
    fun navigate(raw: String) {
        val url = normalizeUrl(raw)
        if (url.isBlank()) return
        if (!isAllowedScheme(url)) {
            Toast.makeText(context, "不支持的链接类型，已阻止", Toast.LENGTH_SHORT).show()
            return
        }
        urlText = url
        activeWebView()?.loadUrl(url)
    }

    /** 页面加载完成：同步地址栏 + 回调 URL（由主进程持久化，见问题10） */
    // 记录上次已回调的 URL，避免重复回调（见问题14）
    var lastReportedUrl by remember { mutableStateOf(profile.lastVisitedUrl) }
    fun onPageDone(url: String?) {
        val wv = activeWebView() ?: return
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
        if (!url.isNullOrBlank()) {
            if (!fieldFocused) urlText = url
            // §4.8：同步活动标签的 URL 和标题
            activeTab()?.let { tab ->
                tab.url = url
                val t = wv.title
                if (!t.isNullOrBlank()) tab.title = t
            }
            if (url != lastReportedUrl && !url.startsWith("about:")) {
                lastReportedUrl = url
                onUrlChanged(url)
            }
        }
    }

    // 返回键：WebView 能后退则后退，否则回列表
    BackHandler {
        val wv = activeWebView()
        if (wv != null && wv.canGoBack()) wv.goBack() else onNavigateBack()
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            Column(modifier = Modifier.background(BarColor)) {
                // 地址栏行：后退 / 前进 / 刷新 / 地址框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { activeWebView()?.goBack() },
                        enabled = canGoBack
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack, "后退",
                            tint = if (canGoBack) Color.White else Color.Gray
                        )
                    }
                    IconButton(
                        onClick = { activeWebView()?.goForward() },
                        enabled = canGoForward
                    ) {
                        Icon(
                            Icons.Filled.ArrowForward, "前进",
                            tint = if (canGoForward) Color.White else Color.Gray
                        )
                    }
                    IconButton(onClick = { activeWebView()?.reload() }) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = Color.White)
                    }
                    // §4.8 多标签：标签数按钮，点击打开切换面板
                    TextButton(onClick = { showTabSwitcher = true }) {
                        Text(
                            "${tabs.size}",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2A323E))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .onFocusChanged { fieldFocused = it.isFocused },
                        placeholder = { Text("输入网址", color = Color.Gray, fontSize = 14.sp) },
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = Color(0xFF2A323E),
                            focusedContainerColor = FieldColor,
                            unfocusedContainerColor = FieldColor,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = AccentColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(urlText) })
                    )
                }
                // 状态条：profile 名称 + 代理徽章
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF64B5F6))
                    )
                    Text(
                        profile.name.ifBlank { "未命名" },
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    ProxyStatusBadge(
                        type = profile.proxyType,
                        state = proxyState,
                        host = profile.proxyHost,
                        port = profile.proxyPort,
                        egressIp = egressIp,
                        onReprobe = {
                            // §4.1：重新探测 WebView 出口
                            probingEgress = true
                            activeWebView()?.loadUrl("https://api.ipify.org")
                        }
                    )
                }
                // 加载进度条
                if (progress in 0.01f..0.999f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = AccentColor,
                        trackColor = Color.Transparent
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                }
            }
        },
        bottomBar = {
            // 底部工具栏：主页 / 菜单
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val home = profile.homepageUrl
                    if (home.isNotBlank()) navigate(home)
                    else Toast.makeText(context, "该 profile 未设置主页", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.Home, "主页", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "进程 :wv${profile.slot} · 数据独立隔离",
                    color = Color(0xFF5A6675),
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, "菜单", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(Color(0xFF151B24))
                    ) {
                        DropdownMenuItem(
                            text = { Text("复制链接", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("url", activeWebView()?.url ?: urlText)
                                )
                                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清除浏览数据（仅本 profile）", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                val wv = activeWebView()
                                if (wv != null) {
                                    wv.clearCache(true)
                                    wv.clearHistory()
                                }
                                // P1-13：等 Cookie 删除回调完成再提示，不提前报成功
                                CookieManager.getInstance().removeAllCookies { success ->
                                    CookieManager.getInstance().flush()
                                    WebStorage.getInstance().deleteAllData()
                                    val msg = if (success) "已清除本 profile 的浏览数据"
                                    else "Cookie 清除可能未完成，其他数据已清除"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        // §4.7 Cookie 导入/导出
                        DropdownMenuItem(
                            text = { Text("导入 Cookie", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                showCookieImport = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出当前站点 Cookie", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                showCookieExport = true
                            }
                        )
                        // §4.3 指纹自检：打开 browserleaks 对比不同 profile
                        DropdownMenuItem(
                            text = { Text("指纹自检（browserleaks）", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                navigate("https://browserleaks.com")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("返回列表", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                onNavigateBack()
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BgColor)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        // 禁止 file/content 访问（见问题16）
                        allowFileAccess = false
                        allowContentAccess = false
                        // §4.8 多标签：允许 JS 开新窗口，由 onCreateWindow 接管
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    // 自定义 UA（非空才覆盖）。§4.3：UA 模板里的 {WV} 替换为
                    // WebView 实际版本，避免写死的 Chrome/120 暴露。
                    if (profile.userAgent.isNotBlank()) {
                        settings.userAgentString = resolveUa(profile.userAgent)
                    }
                    // BUG-7：第三方 Cookie 默认禁用会导致 SSO 登录、验证码、
                    // 支付 iframe 等失效，默认开启
                    android.webkit.CookieManager.getInstance().let { cm ->
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this@apply, true)
                    }
                    // §4.3 指纹伪装：在 loadUrl 之前注册 DOCUMENT_START_SCRIPT，
                    // 每个页面/iframe 最早执行。包含 WebRTC 禁用、Canvas 噪声、
                    // WebGL 伪装、navigator/screen/时区伪装。
                    if (FingerprintSpoof.isSupported()) {
                        try {
                            val seed = profile.fpSeed.takeIf { it != 0L }
                                ?: System.currentTimeMillis()
                            val script = FingerprintSpoof.buildScript(seed, profile.fpConfig)
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                this@apply,
                                script,
                                setOf("*")
                            )
                            android.util.Log.i(
                                "BrowserScreen",
                                "指纹伪装脚本已注入 (seed=$seed)"
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("BrowserScreen", "指纹脚本注入失败", e)
                        }
                    } else {
                        android.util.Log.w(
                            "BrowserScreen",
                            "WebView 不支持 DOCUMENT_START_SCRIPT，指纹伪装未生效"
                        )
                    }
                    // 注意：代理设置不在这里做。ProxyController 是异步的，
                    // 由 LaunchedEffect(webViewCreated) 等待配置应用后再 loadUrl
                    //（见问题2、问题3）。这里只创建 WebView。
                    // BUG-10：代理认证失败保护。账号密码错误时代理返回 407，
                    // WebView 会再次回调，无计数器会导致无限重试甚至触发
                    // 代理侧封禁。第二次起直接 cancel 并标记失败。
                    var authTried = 0
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: Bitmap?
                        ) {
                            progress = 0.05f
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 1f
                            // §4.1：出口探测页加载完成，读 IP 后再加载用户页面
                            if (probingEgress && url?.startsWith("https://api.ipify.org") == true) {
                                view?.evaluateJavascript("document.body.innerText") { raw ->
                                    val ip = raw?.trim('"')?.trim()
                                    if (!ip.isNullOrBlank()) {
                                        egressIp = ip
                                    }
                                    probingEgress = false
                                    // 探测完成，加载用户真正的页面
                                    view.loadUrl(initialUrl)
                                }
                                return
                            }
                            onPageDone(url)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?, url: String?, isReload: Boolean
                        ) {
                            view?.let {
                                canGoBack = it.canGoBack()
                                canGoForward = it.canGoForward()
                            }
                        }

                        // 站内跳转留在 WebView 里，不抛给外部浏览器。
                        // 重定向同样走 scheme 白名单（见问题16）。
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (!isAllowedScheme(url)) {
                                Toast.makeText(
                                    context,
                                    "已阻止不支持的链接类型",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return true // 拦截，不加载
                            }
                            return false
                        }

                        // 代理认证：
                        // 1) §4.6 本地中继：host=="127.0.0.1" 时用 ("fp", token)
                        // 2) 普通 HTTP 代理：只在认证 host 与配置的代理 host 精确
                        //    一致时才自动提交凭据（见问题5）。删除了宽松的 endsWith
                        //    子域名匹配，防止钓鱼网站伪造认证框套取代理账号。
                        // 注意：HTTP 代理认证走这里；SOCKS5 的用户名密码是协议内
                        // 协商，ProxyController 不支持，无认证时走 socks5://，
                        // 有认证时走本地中继（见 §4.6）。
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            // §4.6：本地中继的认证（127.0.0.1）
                            val activeRelay = relay
                            if (host == "127.0.0.1" && activeRelay != null) {
                                handler?.proceed("fp", activeRelay.token)
                                return
                            }
                            val isProxyAuth = host != null &&
                                profile.proxyHost.isNotBlank() &&
                                profile.proxyType == ProxyType.HTTP &&
                                host.equals(profile.proxyHost, ignoreCase = true)
                            if (isProxyAuth) {
                                // BUG-10：失败保护，第二次回调说明上次的凭据被拒绝
                                if (authTried++ >= 1) {
                                    handler?.cancel()
                                    proxyState = ProxySetupState.Failed("代理认证失败")
                                    return
                                }
                                // 只从加密存储读（明文字段已迁移清空，见问题4）
                                val (u, p) = ProxyCredentialStore.load(ctx, profile.id)
                                if (u.isNotBlank()) {
                                    handler?.proceed(u, p)
                                } else {
                                    handler?.cancel()
                                }
                            } else {
                                // 非代理认证一律不自动提交，防凭据泄露
                                handler?.cancel()
                            }
                        }

                        // v1 不做证书拦截，交给系统默认处理
                        override fun onReceivedSslError(
                            view: WebView?, handler: SslErrorHandler?, error: SslError?
                        ) {
                            handler?.cancel()
                        }

                        // BUG-12：主 frame 出错时显示自定义错误页（带重试按钮），
                        // 而不是白屏。代理中途掉线（PROXY/TUNNEL 错误）时把
                        // proxyState 置 Failed，避免徽章还显示"已配置"误导。
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && view != null) {
                                val desc = error?.description?.toString() ?: "未知错误"
                                val code = error?.errorCode ?: 0
                                if (desc.contains("PROXY", ignoreCase = true) ||
                                    desc.contains("TUNNEL", ignoreCase = true)
                                ) {
                                    proxyState = ProxySetupState.Failed("代理连接中断：$desc")
                                }
                                val url = request.url?.toString() ?: ""
                                view.loadDataWithBaseURL(
                                    null,
                                    errorHtml(desc, code, url),
                                    "text/html", "utf-8", null
                                )
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true && view != null) {
                                val code = errorResponse?.statusCode ?: 0
                                // 407 代理认证失败：标记代理状态（BUG-10 的计数器已在
                                // onReceivedHttpAuthRequest 处理，这里只显示错误页）
                                if (code == 407) {
                                    proxyState = ProxySetupState.Failed("代理认证失败（407）")
                                }
                                if (code >= 400) {
                                    val url = request.url?.toString() ?: ""
                                    view.loadDataWithBaseURL(
                                        null,
                                        errorHtml("HTTP $code", code, url),
                                        "text/html", "utf-8", null
                                    )
                                }
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        // §4.4(a) 文件上传：<input type=file> 弹出系统文件选择器
                        override fun onShowFileChooser(
                            view: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?
                        ): Boolean {
                            // 取消上一次未完成的回调，避免泄漏卡死
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = callback
                            try {
                                val intent = params?.createIntent()
                                    ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                    }
                                filePickerLauncher.launch(intent)
                            } catch (e: Exception) {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = null
                                Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        // BUG-6：JS 弹窗。WebView 默认吞掉 alert/confirm/prompt，
                        // 这里用系统对话框实现。注意必须最终调 confirm()/cancel()，
                        // 否则页面 JS 线程卡死。
                        override fun onJsAlert(
                            view: WebView?, url: String?, message: String?, result: JsResult?
                        ): Boolean {
                            android.app.AlertDialog.Builder(context)
                                .setMessage(message)
                                .setPositiveButton("确定") { _, _ -> result?.confirm() }
                                .setOnCancelListener { result?.cancel() }
                                .show()
                            return true
                        }

                        override fun onJsConfirm(
                            view: WebView?, url: String?, message: String?, result: JsResult?
                        ): Boolean {
                            android.app.AlertDialog.Builder(context)
                                .setMessage(message)
                                .setPositiveButton("确定") { _, _ -> result?.confirm() }
                                .setNegativeButton("取消") { _, _ -> result?.cancel() }
                                .setOnCancelListener { result?.cancel() }
                                .show()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?, url: String?, message: String?,
                            defaultValue: String?, result: JsPromptResult?
                        ): Boolean {
                            val input = EditText(context).apply { setText(defaultValue ?: "") }
                            android.app.AlertDialog.Builder(context)
                                .setMessage(message)
                                .setView(input)
                                .setPositiveButton("确定") { _, _ ->
                                    result?.confirm(input.text.toString())
                                }
                                .setNegativeButton("取消") { _, _ -> result?.cancel() }
                                .setOnCancelListener { result?.cancel() }
                                .show()
                            return true
                        }

                        // §4.4(c) 摄像头/麦克风权限：先弹确认框，用户同意后再授权，
                        // 按 profile 记忆选择（简单起见：每次都问）
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null) return
                            permissionRequest = request
                        }

                        // §4.4(c) 地理定位：先弹确认。§4.3 要求按出口给假坐标或拒绝，
                        // 这里先做"确认后允许真实定位"，假坐标在第 4 步指纹层接入
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            geoPermissionOrigin = origin
                            geoPermissionCallback = callback
                        }

                        // §4.4(d) 视频全屏
                        override fun onShowCustomView(
                            view: View?, callback: CustomViewCallback?
                        ) {
                            fullscreenView = view
                            fullscreenCallback = callback
                        }

                        override fun onHideCustomView() {
                            fullscreenView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                        }
                    }
                    // §4.5 下载：不用系统 DownloadManager（它在系统进程发请求，
                    // 不走 per-profile 代理也拿不到隔离 Cookie）。自己用
                    // HttpURLConnection 经代理下载，带上 Cookie 和 UA，
                    // 写入 MediaStore.Downloads。
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val msg = downloadFile(
                                context, this@apply, url, userAgent,
                                contentDisposition, mimeType, profile
                            )
                            downloadMsg = msg
                        }
                    }
                    // 注意：不在这里 loadUrl。等 LaunchedEffect 确认代理配置
                    // 应用后（或用户选择"仍然直连"）再加载（见问题2、问题3）。
                    // §4.8：第一个标签
                    val firstTab = BrowserTab(webView = this)
                    tabs = listOf(firstTab)
                    activeTabId = firstTab.id
                    webViewCreated = true
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                // §4.6：关闭本地中继，不留监听端口
                relay?.stop()
                relay = null
                // §4.8：销毁所有标签的 WebView
                tabs.forEach { it.webView.destroy() }
                tabs = emptyList()
            }
        )
            // §4.4(d) 视频全屏覆盖层
            fullscreenView?.let { fv ->
                AndroidView(
                    factory = {
                        FrameLayout(it).apply {
                            addView(
                                fv,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }
    }

    // §4.4(c) 摄像头/麦克风权限确认
    permissionRequest?.let { req ->
        AlertDialog(
            onDismissRequest = {
                req.deny()
                permissionRequest = null
            },
            title = { Text("网站请求权限") },
            text = {
                Text("“${profile.name}”中的页面请求使用：\n${req.resources.joinToString("\n")}\n\n是否允许？")
            },
            confirmButton = {
                TextButton(onClick = {
                    req.grant(req.resources)
                    permissionRequest = null
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    req.deny()
                    permissionRequest = null
                }) { Text("拒绝") }
            }
        )
    }

    // §4.4(c) 地理定位权限确认
    val geoCb = geoPermissionCallback
    val geoOrigin = geoPermissionOrigin
    if (geoCb != null && geoOrigin != null) {
        AlertDialog(
            onDismissRequest = {
                geoCb.invoke(geoOrigin, false, false)
                geoPermissionCallback = null
                geoPermissionOrigin = null
            },
            title = { Text("网站请求定位") },
            text = { Text("页面想获取你的地理位置，是否允许？") },
            confirmButton = {
                TextButton(onClick = {
                    geoCb.invoke(geoOrigin, true, false)
                    geoPermissionCallback = null
                    geoPermissionOrigin = null
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    geoCb.invoke(geoOrigin, false, false)
                    geoPermissionCallback = null
                    geoPermissionOrigin = null
                }) { Text("拒绝") }
            }
        )
    }

    // §4.5 下载完成提示
    downloadMsg?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            downloadMsg = null
        }
    }

    // §4.7 Cookie 导入对话框
    if (showCookieImport) {
        var cookieText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCookieImport = false },
            title = { Text("导入 Cookie") },
            text = {
                Column {
                    Text(
                        "粘贴 Cookie（支持 EditThisCookie JSON / Netscape 格式 / name=value; 格式），将写入当前站点。",
                        fontSize = 13.sp,
                        color = Color(0xFF9AA4B2)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        placeholder = { Text("粘贴 Cookie…", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ Cookie 等同账号凭据，请确认来源可信",
                        fontSize = 12.sp,
                        color = Color(0xFFFBBF24)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = activeWebView()?.url ?: urlText
                    val count = importCookies(context, url, cookieText)
                    showCookieImport = false
                    Toast.makeText(
                        context,
                        if (count > 0) "已导入 $count 条 Cookie" else "未识别到有效 Cookie",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieImport = false }) { Text("取消") }
            }
        )
    }

    // §4.7 Cookie 导出对话框（当前站点）
    if (showCookieExport) {
        val url = activeWebView()?.url ?: urlText
        val cookies = remember(url) {
            CookieManager.getInstance().getCookie(url) ?: ""
        }
        AlertDialog(
            onDismissRequest = { showCookieExport = false },
            title = { Text("当前站点 Cookie") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "⚠️ 导出的 Cookie 等同账号凭据，请勿泄露",
                        fontSize = 12.sp,
                        color = Color(0xFFFBBF24)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cookies.ifBlank { "（当前站点无 Cookie）" },
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (cookies.isNotBlank()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("cookies", cookies))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    showCookieExport = false
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieExport = false }) { Text("关闭") }
            }
        )
    }

    // §4.8 多标签切换器
    if (showTabSwitcher) {
        AlertDialog(
            onDismissRequest = { showTabSwitcher = false },
            title = { Text("标签页（${tabs.size}）") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    tabs.forEach { tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeTabId = tab.id
                                    // 同步地址栏到新活动标签
                                    urlText = tab.url.ifBlank { urlText }
                                    showTabSwitcher = false
                                }
                                .background(
                                    if (tab.id == activeTabId) Color(0xFF1A212C)
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tab.title.ifBlank { "新标签页" },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    tab.url.ifBlank { "about:blank" },
                                    color = Color(0xFF7A8595),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 关闭标签（至少保留一个）
                            if (tabs.size > 1) {
                                TextButton(onClick = {
                                    // TODO: 完整多标签时实现关闭
                                    Toast.makeText(context, "多标签完整版开发中", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("✕", color = Color(0xFFF87171))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTabSwitcher = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // fail-closed 对话框（见问题3）：代理配置失败时默认阻止加载，
    // 只有用户明确点"仍然直连"才放行。
    if (showProxyFailDialog && !bypassProxyFail) {
        val reason = (proxyState as? ProxySetupState.Failed)?.reason ?: "代理配置失败"
        AlertDialog(
            onDismissRequest = { /* 必须明确选择，不能点空白取消 */ },
            title = { Text("代理配置失败") },
            text = {
                Text(
                    "$reason\n\n为保护你的网络出口不被意外暴露，已阻止加载网页。" +
                        "你可以返回检查代理配置，或选择仍然直连访问。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bypassProxyFail = true
                    showProxyFailDialog = false
                    proxyState = ProxySetupState.Applied // 标记为用户放行
                    activeWebView()?.loadUrl(initialUrl)
                }) { Text("仍然直连") }
            },
            dismissButton = {
                TextButton(onClick = { onNavigateBack() }) { Text("返回") }
            }
        )
    }
}

/** 状态条右端的代理徽章，点击出诊断 + WebView 出口 IP（见 §4.1） */
@Composable
private fun ProxyStatusBadge(
    type: ProxyType,
    state: ProxySetupState,
    host: String,
    port: Int,
    egressIp: String?,
    onReprobe: () -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var diagnoseText by remember { mutableStateOf("") }

    val (label, color) = when {
        type == ProxyType.NONE -> "直连" to Color(0xFF9AA4B2)
        state is ProxySetupState.Failed -> "代理失败·已阻止加载" to Color(0xFFF87171)
        state is ProxySetupState.Applied ->
            (if (type == ProxyType.SOCKS5) "SOCKS5 已配置" else "HTTP 已配置") to Color(0xFF64B5F6)
        else -> "代理配置中…" to Color(0xFFFBBF24)
    }
    // §4.1：徽章上直接显示 WebView 实测出口 IP
    val displayLabel = if (type != ProxyType.NONE && egressIp != null) {
        "$label · $egressIp"
    } else {
        label
    }
    Text(
        displayLabel,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.25f))
            .clickable {
                diagnoseText = WebViewProxy.diagnose(host, port)
                showDialog = true
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("代理诊断") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(diagnoseText, fontSize = 12.sp)
                    if (type != ProxyType.NONE) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "—— WebView 真实出口 ——",
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (egressIp != null) "出口 IP：$egressIp"
                            else "尚未测得（页面加载时自动探测）",
                            fontSize = 12.sp
                        )
                        Text(
                            "这是 WebView 实际发请求的出口，不是 Java 层测试。",
                            fontSize = 11.sp,
                            color = Color(0xFF9AA4B2)
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(onClick = {
                            showDialog = false
                            onReprobe()
                        }) {
                            Text("重新检测出口")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * BUG-12：自定义错误页 HTML（深色风格，带重试按钮和错误信息）。
 */
private fun errorHtml(description: String, code: Int, url: String): String {
    val esc = { s: String ->
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{background:#0A0E14;color:#fff;font-family:sans-serif;
               display:flex;flex-direction:column;align-items:center;
               justify-content:center;min-height:90vh;margin:0;padding:24px;
               text-align:center}
          h2{color:#F87171;font-size:20px}
          p{color:#9AA4B2;font-size:14px;word-break:break-all}
          button{background:#2E9E5B;color:#fff;border:none;border-radius:8px;
                 padding:12px 32px;font-size:16px;margin-top:16px}
        </style></head><body>
        <h2>页面加载失败</h2>
        <p>${esc(description)}${if (code != 0) "（$code）" else ""}</p>
        <p>${esc(url)}</p>
        <button onclick="location.reload()">重试</button>
        </body></html>
    """.trimIndent()
}

/**
 * §4.5：经 per-profile 代理下载文件（不用 DownloadManager）。
 *
 * - http/https：按 profile 的代理设置走 java.net.Proxy，带上 UA 和 Cookie
 * - data: URL：直接解 base64
 * - blob: URL：注入 JS 把 blob 读成 data URL 回传再解码
 * 写入 MediaStore.Downloads（无需存储权限），返回提示文本。
 */
private suspend fun downloadFile(
    context: Context,
    webView: WebView,
    url: String,
    userAgent: String,
    contentDisposition: String?,
    mimeType: String?,
    profile: Profile
): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val bytes: ByteArray = when {
            url.startsWith("data:") -> {
                // data:[<mime>][;base64],<data>
                val comma = url.indexOf(',')
                if (comma < 0) return@withContext "下载失败：data URL 格式错误"
                val meta = url.substring(5, comma)
                val data = url.substring(comma + 1)
                if (meta.contains("base64")) android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                else java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
            }
            url.startsWith("blob:") -> {
                // 注入 JS 把 blob 转成 data URL，通过 evaluateJavascript 回传
                val dataUrl = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val js = """
                        (function(){
                          fetch("$url").then(r=>r.blob()).then(b=>{
                            var fr=new FileReader();
                            fr.onload=()=>{window.__blobResult=fr.result;};
                            fr.onerror=()=>{window.__blobResult="ERROR";};
                            fr.readAsDataURL(b);
                          }).catch(()=>{window.__blobResult="ERROR";});
                        })()
                    """.trimIndent()
                    webView.post {
                        webView.evaluateJavascript(js) {
                            // 轮询取结果（最多 15 秒）
                            Thread {
                                var result: String? = null
                                repeat(150) {
                                    Thread.sleep(100)
                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    var v: String? = null
                                    webView.post {
                                        webView.evaluateJavascript("window.__blobResult||''") {
                                            v = it
                                            latch.countDown()
                                        }
                                    }
                                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                                    if (!v.isNullOrEmpty() && v != "\"\"") {
                                        result = v
                                        return@repeat
                                    }
                                }
                                if (cont.isActive) {
                                    val r = result
                                    if (r == null || r == "ERROR" || r == "\"ERROR\"") {
                                        cont.resume(null, null)
                                    } else {
                                        cont.resume(r.trim('"'), null)
                                    }
                                }
                            }.start()
                        }
                    }
                }
                if (dataUrl == null) return@withContext "下载失败：无法读取 blob 数据"
                val comma = dataUrl.indexOf(',')
                if (comma < 0) return@withContext "下载失败：blob 转换失败"
                android.util.Base64.decode(
                    dataUrl.substring(comma + 1), android.util.Base64.DEFAULT
                )
            }
            else -> {
                // http/https：经代理下载
                val proxy = buildDownloadProxy(profile)
                val conn = java.net.URL(url).openConnection(proxy)
                        as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", cookie)
                }
                // HTTP 代理认证（Proxy-Authorization 头）
                if (profile.proxyType == ProxyType.HTTP && profile.proxyHost.isNotBlank()) {
                    val (u, p) = ProxyCredentialStore.load(context, profile.id)
                    if (u.isNotBlank()) {
                        val cred = android.util.Base64.encodeToString(
                            "$u:$p".toByteArray(),
                            android.util.Base64.NO_WRAP
                        )
                        conn.setRequestProperty("Proxy-Authorization", "Basic $cred")
                    }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext "下载失败：HTTP $code"
                }
                conn.inputStream.use { it.readBytes() }
            }
        }

        // 写入 MediaStore.Downloads
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Downloads.DISPLAY_NAME,
                fileName.ifBlank { "download_${System.currentTimeMillis()}" }
            )
            put(
                android.provider.MediaStore.Downloads.MIME_TYPE,
                mimeType ?: "application/octet-stream"
            )
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return@withContext "下载失败：无法创建文件"
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return@withContext "下载失败：无法写入文件"
        "已下载：$fileName（${bytes.size / 1024} KB）"
    } catch (e: Exception) {
        android.util.Log.w("BrowserScreen", "下载失败", e)
        "下载失败：${e.message}"
    }
}

/** §4.5：按 profile 构造下载用的 java.net.Proxy */
private fun buildDownloadProxy(profile: Profile): java.net.Proxy {
    if (profile.proxyType == ProxyType.NONE || profile.proxyHost.isBlank()) {
        return java.net.Proxy.NO_PROXY
    }
    val addr = java.net.InetSocketAddress(profile.proxyHost, profile.proxyPort)
    return when (profile.proxyType) {
        ProxyType.HTTP -> java.net.Proxy(java.net.Proxy.Type.HTTP, addr)
        // 注意：java.net 的 SOCKS 代理不支持认证；带认证的 SOCKS5 走中继时，
        // WebView 用的是 127.0.0.1 中继，下载同样走中继（HTTP CONNECT）。
        // 这里简化：无认证 SOCKS5 用 SOCKS，有认证的由调用方处理。
        ProxyType.SOCKS5 -> java.net.Proxy(java.net.Proxy.Type.SOCKS, addr)
        ProxyType.NONE -> java.net.Proxy.NO_PROXY
    }
}

/**
 * §4.7：导入 Cookie。
 * 支持三种格式：
 * 1. EditThisCookie JSON：[{"name":"...","value":"...","domain":"...","path":"...","secure":true,"expirationDate":...}]
 * 2. Netscape 格式：domain\tTRUE\tpath\tsecure\texpires\tname\tvalue
 * 3. 简单格式：name=value; name2=value2
 * @return 成功导入的条数
 */
private fun importCookies(context: Context, url: String, text: String): Int {
    if (text.isBlank() || url.isBlank()) return 0
    val cm = CookieManager.getInstance()
    var count = 0
    val t = text.trim()
    try {
        when {
            // 1. EditThisCookie JSON 数组
            t.startsWith("[") -> {
                val arr = com.google.gson.JsonParser.parseString(t).asJsonArray
                for (el in arr) {
                    try {
                        val o = el.asJsonObject
                        val name = o.get("name")?.asString ?: continue
                        val value = o.get("value")?.asString ?: ""
                        val domain = o.get("domain")?.asString ?: ""
                        val path = o.get("path")?.asString ?: "/"
                        val secure = o.get("secure")?.asBoolean ?: false
                        val exp = o.get("expirationDate")?.asLong
                        val sb = StringBuilder("$name=$value; Path=$path")
                        if (domain.isNotBlank()) sb.append("; Domain=$domain")
                        if (secure) sb.append("; Secure")
                        if (exp != null) {
                            val date = java.text.SimpleDateFormat(
                                "EEE, dd MMM yyyy HH:mm:ss z",
                                java.util.Locale.US
                            ).apply {
                                timeZone = java.util.TimeZone.getTimeZone("GMT")
                            }.format(java.util.Date(exp * 1000))
                            sb.append("; Expires=$date")
                        }
                        // 用 domain 或当前 url 设置
                        val setUrl = if (domain.isNotBlank()) {
                            "https://${domain.trimStart('.')}$path"
                        } else url
                        cm.setCookie(setUrl, sb.toString())
                        count++
                    } catch (_: Exception) { }
                }
            }
            // 2. Netscape 格式（包含制表符）
            t.contains("\t") -> {
                for (line in t.lines()) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val parts = line.split("\t")
                    if (parts.size < 7) continue
                    try {
                        val domain = parts[0]
                        val path = parts[2]
                        val secure = parts[3].equals("TRUE", ignoreCase = true)
                        val expires = parts[4].toLongOrNull() ?: 0
                        val name = parts[5]
                        val value = parts[6]
                        val sb = StringBuilder("$name=$value; Path=$path; Domain=$domain")
                        if (secure) sb.append("; Secure")
                        if (expires > 0) {
                            val date = java.text.SimpleDateFormat(
                                "EEE, dd MMM yyyy HH:mm:ss z",
                                java.util.Locale.US
                            ).apply {
                                timeZone = java.util.TimeZone.getTimeZone("GMT")
                            }.format(java.util.Date(expires * 1000))
                            sb.append("; Expires=$date")
                        }
                        cm.setCookie("https://${domain.trimStart('.')}$path", sb.toString())
                        count++
                    } catch (_: Exception) { }
                }
            }
            // 3. 简单 name=value; 格式
            else -> {
                for (part in t.split(";")) {
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) {
                        cm.setCookie(url, "${kv[0].trim()}=${kv[1].trim()}")
                        count++
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("BrowserScreen", "Cookie 导入失败", e)
    }
    cm.flush()
    return count
}

/**
 * §4.3：解析 UA 模板。{WV} 占位符替换为 WebView 实际主版本
 * （如 Chrome/137.0.0.0），避免写死的旧版本号暴露。
 */
private fun resolveUa(uaTemplate: String): String {
    if (!uaTemplate.contains(com.limao.fingerprint.data.DeviceTemplates.WV_PLACEHOLDER)) {
        return uaTemplate
    }
    val version = try {
        android.webkit.WebView.getCurrentWebViewPackage()
            ?.versionName
            ?.split(".")
            ?.firstOrNull()
            ?.toIntOrNull()
    } catch (e: Exception) {
        null
    } ?: 120 // 取不到版本时回退到 120
    return uaTemplate.replace(
        com.limao.fingerprint.data.DeviceTemplates.WV_PLACEHOLDER,
        "$version.0.0.0"
    )
}
