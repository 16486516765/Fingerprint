package com.limao.fingerprint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
private val AccentColor = Color(0xFF2E9E5B)

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

    // WebView 持有：factory 只跑一次，跨 recomposition 复用
    var webView by remember { mutableStateOf<WebView?>(null) }
    // WebView 是否已创建（factory 跑完后触发代理设置流程）
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

    val initialUrl = remember {
        profile.lastVisitedUrl.ifBlank { profile.homepageUrl }.ifBlank { "about:blank" }
    }

    /**
     * 代理设置流程（见问题2、问题3）：
     * WebView 创建后，等待 ProxyController 配置真正应用，再 loadUrl。
     * 代理失败时 fail-closed：不加载，弹窗让用户选择"仍然直连"或返回。
     */
    LaunchedEffect(webViewCreated) {
        if (!webViewCreated) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        val result = WebViewProxy.setProxyAsync(
            profile.proxyType, profile.proxyHost, profile.proxyPort
        )
        when (result) {
            is WebViewProxy.ProxySetupResult.Applied -> {
                proxyState = ProxySetupState.Applied
                wv.loadUrl(initialUrl)
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
        webView?.loadUrl(url)
    }

    /** 页面加载完成：同步地址栏 + 回调 URL（由主进程持久化，见问题10） */
    // 记录上次已回调的 URL，避免重复回调（见问题14）
    var lastReportedUrl by remember { mutableStateOf(profile.lastVisitedUrl) }
    fun onPageDone(url: String?) {
        val wv = webView ?: return
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
        if (!url.isNullOrBlank()) {
            if (!fieldFocused) urlText = url
            if (url != lastReportedUrl && !url.startsWith("about:")) {
                lastReportedUrl = url
                onUrlChanged(url)
            }
        }
    }

    // 返回键：WebView 能后退则后退，否则回列表
    BackHandler {
        val wv = webView
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
                        onClick = { webView?.goBack() },
                        enabled = canGoBack
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack, "后退",
                            tint = if (canGoBack) Color.White else Color.Gray
                        )
                    }
                    IconButton(
                        onClick = { webView?.goForward() },
                        enabled = canGoForward
                    ) {
                        Icon(
                            Icons.Filled.ArrowForward, "前进",
                            tint = if (canGoForward) Color.White else Color.Gray
                        )
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = Color.White)
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
                            .background(Color(0xFF4ADE80))
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
                        port = profile.proxyPort
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
                                    ClipData.newPlainText("url", webView?.url ?: urlText)
                                )
                                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清除浏览数据（仅本 profile）", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                val wv = webView
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
                    }
                    // 自定义 UA（非空才覆盖）
                    if (profile.userAgent.isNotBlank()) {
                        settings.userAgentString = profile.userAgent
                    }
                    // 注意：代理设置不在这里做。ProxyController 是异步的，
                    // 由 LaunchedEffect(webViewCreated) 等待配置应用后再 loadUrl
                    //（见问题2、问题3）。这里只创建 WebView。
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: Bitmap?
                        ) {
                            progress = 0.05f
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 1f
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

                        // 代理认证：只在认证 host 与配置的代理 host 精确一致时
                        // 才自动提交凭据（见问题5）。删除了宽松的 endsWith 子域名
                        // 匹配，防止钓鱼网站伪造认证框套取代理账号。
                        // 注意：HTTP 代理认证走这里；SOCKS5 的用户名密码是协议内
                        // 协商，ProxyController 不支持，界面已明确标注（见问题6）。
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            val isProxyAuth = host != null &&
                                profile.proxyHost.isNotBlank() &&
                                profile.proxyType == ProxyType.HTTP &&
                                host.equals(profile.proxyHost, ignoreCase = true)
                            if (isProxyAuth) {
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
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                    }
                    // 注意：不在这里 loadUrl。等 LaunchedEffect 确认代理配置
                    // 应用后（或用户选择"仍然直连"）再加载（见问题2、问题3）。
                    webView = this
                    webViewCreated = true
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BgColor),
            onRelease = { it.destroy() }
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
                    webView?.loadUrl(initialUrl)
                }) { Text("仍然直连") }
            },
            dismissButton = {
                TextButton(onClick = { onNavigateBack() }) { Text("返回") }
            }
        )
    }
}

/** 状态条右端的代理徽章，点击出诊断 + 手动连通性测试（见问题1、问题7） */
@Composable
private fun ProxyStatusBadge(
    type: ProxyType,
    state: ProxySetupState,
    host: String,
    port: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var diagnoseText by remember { mutableStateOf("") }
    // 手动测试相关
    var showTestConfirm by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<WebViewProxy.ProxyTestResult?>(null) }

    val (label, color) = when {
        type == ProxyType.NONE -> "直连" to Color(0xFF9AA4B2)
        state is ProxySetupState.Failed -> "代理失败·已阻止加载" to Color(0xFFF87171)
        state is ProxySetupState.Applied ->
            (if (type == ProxyType.SOCKS5) "SOCKS5 已配置" else "HTTP 已配置") to Color(0xFF4ADE80)
        else -> "代理配置中…" to Color(0xFFFBBF24)
    }
    Text(
        label,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.25f))
            .clickable {
                diagnoseText = WebViewProxy.diagnose(host, port)
                testResult = null
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
                            "连通性测试为手动触发，不会自动运行。",
                            fontSize = 12.sp,
                            color = Color(0xFF9AA4B2)
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(
                            onClick = { showTestConfirm = true },
                            enabled = !testing
                        ) {
                            Text(if (testing) "测试中…" else "测试代理连通性")
                        }
                        testResult?.let { r ->
                            Spacer(Modifier.height(8.dp))
                            Text("—— Java 代理连通性测试 ——", fontSize = 12.sp)
                            Text(
                                "A. 配置已应用：${if (r.configApplied) "是" else "否"}",
                                fontSize = 12.sp
                            )
                            Text(
                                "B. 代理连接：" + when (r.proxyReachable) {
                                    true -> "成功（出口 IP 已变化）"
                                    false -> "失败（${r.detail}）"
                                    null -> "无法验证（${r.detail}）"
                                },
                                fontSize = 12.sp
                            )
                            Text(
                                "C. WebView 实际出口：无法验证（Java 层测不到 WebView 流量）",
                                fontSize = 12.sp,
                                color = Color(0xFF9AA4B2)
                            )
                            if (r.directIp != null) {
                                Text("直连 IP: ${r.directIp}", fontSize = 11.sp)
                            }
                            if (r.proxyIp != null) {
                                Text("代理 IP: ${r.proxyIp}", fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("关闭") }
            }
        )
    }
    // 测试前二次确认（见问题1）：明确告知会发起直连请求
    if (showTestConfirm) {
        AlertDialog(
            onDismissRequest = { showTestConfirm = false },
            title = { Text("确认测试") },
            text = {
                Text(
                    "测试会先向 api.ipify.org 发起一次直连请求获取真实出口 IP，" +
                        "再经代理请求一次做对比。直连请求会暴露你的真实 IP 给该服务。" +
                        "继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTestConfirm = false
                    testing = true
                    scope.launch {
                        val applied = state is ProxySetupState.Applied
                        testResult = WebViewProxy.testProxyConnectivity(host, port, type, applied)
                        testing = false
                    }
                }) { Text("继续测试") }
            },
            dismissButton = {
                TextButton(onClick = { showTestConfirm = false }) { Text("取消") }
            }
        )
    }
}
