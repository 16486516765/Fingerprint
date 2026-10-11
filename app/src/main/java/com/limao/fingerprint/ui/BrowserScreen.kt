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
 * 浏览器界面（运行在 :wvN 独立进程，由 BrowserActivity 承载）。
 *
 * @param profile 当前 profile（不可变快照；lastVisitedUrl 的持久化在内部处理）
 * @param onNavigateBack 返回列表（BrowserActivity 负责 finish）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(profile: Profile, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ProfileRepository.getInstance(context) }

    // WebView 持有：factory 只跑一次，跨 recomposition 复用
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 地址栏文本
    var urlText by remember { mutableStateOf(profile.lastVisitedUrl.ifBlank { profile.homepageUrl }) }
    // 地址栏是否聚焦：聚焦时不让页面加载回调覆盖用户输入
    var fieldFocused by remember { mutableStateOf(false) }
    // 加载进度 0..1
    var progress by remember { mutableFloatStateOf(0f) }
    // 导航按钮可用状态
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // 代理设置结果：null=尚未尝试，true=配置已应用，false=配置失败降级直连
    var proxyOk by remember { mutableStateOf<Boolean?>(null) }
    // 代理生效检测：null=检测中/未检测，true=已生效，false=未生效
    var proxyEffective by remember { mutableStateOf<Boolean?>(null) }

    // 代理配置成功后，后台测一次真实生效性（对比直连/代理出口 IP）
    androidx.compose.runtime.LaunchedEffect(proxyOk) {
        if (proxyOk == true && profile.proxyType != ProxyType.NONE) {
            proxyEffective = null // 检测中
            proxyEffective = WebViewProxy.testProxyEffective(
                profile.proxyHost, profile.proxyPort, profile.proxyType
            )
        } else {
            proxyEffective = null
        }
    }
    // 右下角菜单展开
    var menuOpen by remember { mutableStateOf(false) }

    val initialUrl = remember {
        profile.lastVisitedUrl.ifBlank { profile.homepageUrl }.ifBlank { "about:blank" }
    }

    /** 跳转 */
    fun navigate(raw: String) {
        val url = normalizeUrl(raw)
        if (url.isBlank()) return
        urlText = url
        webView?.loadUrl(url)
    }

    /** 页面加载完成：同步地址栏 + 持久化 lastVisitedUrl（只在变化时写库） */
    fun onPageDone(url: String?) {
        val wv = webView ?: return
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
        if (!url.isNullOrBlank()) {
            if (!fieldFocused) urlText = url
            if (url != profile.lastVisitedUrl && !url.startsWith("about:")) {
                scope.launch {
                    // 只更新 lastVisitedUrl 单个字段，带重试，不抛异常
                    repository.updateLastVisitedUrl(profile.id, url)
                }
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
                        ok = proxyOk,
                        effective = proxyEffective,
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
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                WebStorage.getInstance().deleteAllData()
                                Toast.makeText(
                                    context, "已清除本 profile 的浏览数据", Toast.LENGTH_SHORT
                                ).show()
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
                    }
                    // 自定义 UA（非空才覆盖）
                    if (profile.userAgent.isNotBlank()) {
                        settings.userAgentString = profile.userAgent
                    }
                    // 代理设置：ProxyController 公开接口，进程级生效。
                    // 必须在主线程、WebView 创建后、loadUrl 前调用。
                    proxyOk = WebViewProxy.setProxy(
                        profile.proxyType, profile.proxyHost, profile.proxyPort
                    )
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

                        // 站内跳转留在 WebView 里，不抛给外部浏览器
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean = false

                        // 代理认证：只在认证 host 与配置的代理服务器一致时
                        // 才自动提交凭据，防止钓鱼网站伪造认证框套取账号。
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            val isProxyAuth = host != null &&
                                profile.proxyHost.isNotBlank() &&
                                (host == profile.proxyHost ||
                                 host.endsWith(".${profile.proxyHost}"))
                            if (isProxyAuth && profile.proxyUsername.isNotBlank()) {
                                handler?.proceed(
                                    profile.proxyUsername, profile.proxyPassword
                                )
                            } else {
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
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BgColor),
            onRelease = { it.destroy() }
        )
    }
}

/** 状态条右端的代理徽章（三态），点击出诊断报告 */
@Composable
private fun ProxyStatusBadge(
    type: ProxyType,
    ok: Boolean?,
    effective: Boolean?,
    host: String,
    port: Int
) {
    var showDiagnose by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var diagnoseText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val (label, color) = when {
        type == ProxyType.NONE -> "直连" to Color(0xFF9AA4B2)
        ok == false -> "代理设置失败·已直连" to Color(0xFFF87171)
        ok == true && effective == null -> "代理检测中…" to Color(0xFFFBBF24)
        ok == true && effective == true ->
            (if (type == ProxyType.SOCKS5) "SOCKS5 已生效" else "HTTP 已生效") to Color(0xFF4ADE80)
        ok == true && effective == false -> "代理未生效" to Color(0xFFFB923C)
        else -> "HTTP 已连接" to Color(0xFF4ADE80)
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
                showDiagnose = true
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
    if (showDiagnose) {
        AlertDialog(
            onDismissRequest = { showDiagnose = false },
            title = { Text("代理诊断") },
            text = {
                Text(
                    diagnoseText,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiagnose = false }) { Text("关闭") }
            }
        )
    }
}
