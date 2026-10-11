package com.limao.fingerprint.ui

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.limao.fingerprint.data.ProxyType
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * WebView 进程级代理设置（AndroidX WebKit ProxyController 公开接口）。
 *
 * 每个 profile 独占一个 :wvN 进程，ProxyController 是进程级的，
 * 在对应进程内调用即天然实现 per-profile 代理。各 :wvN 进程的代理
 * 设置互不影响（进程隔离，见问题8）。
 *
 * - HTTP   → ProxyConfig "http://host:port"
 * - SOCKS5 → ProxyConfig "socks5://host:port"（注意不是 socks://，
 *   Chromium 里 socks:// 按 SOCKS4 解析，会 DNS 泄露；见 BUG-2）
 * - 直连   → clearProxyOverride() 明确清除，不残留旧代理
 *
 * 注意 ProxyController.setProxyOverride()/clearProxyOverride() 是异步的：
 * 必须等待完成回调确认配置已应用，才能 loadUrl（见问题2）。
 * 本文件的 setProxyAsync()/clearProxyAsync() 封装为 suspend 函数，
 * 区分"设置已提交"和"配置已应用"两种状态。
 */
object WebViewProxy {

    private const val TAG = "WebViewProxy"

    /** 直接在调用线程跑回调的 Executor（调用方保证在主线程调用） */
    private val directExecutor = Executor { it.run() }

    /** 代理设置结果：区分"已应用"与各种失败原因（见问题2、问题3） */
    sealed interface ProxySetupResult {
        /** 配置已应用（或直连已清除旧代理） */
        data object Applied : ProxySetupResult
        /** WebView 内核不支持 PROXY_OVERRIDE */
        data object NotSupported : ProxySetupResult
        /** 参数无效 */
        data class InvalidArgs(val reason: String) : ProxySetupResult
        /** 设置失败（回调明确失败或异常） */
        data class Failed(val reason: String) : ProxySetupResult
    }

    /** 是否支持代理覆盖（取决于设备 WebView 版本） */
    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * 设置当前进程的 WebView 代理（suspend，等待配置真正应用）。
     *
     * 必须在主线程调用（ProxyController 要求），在 WebView 创建后、loadUrl 前调用。
     * @return Applied=配置已应用；其他=失败，调用方应 fail-closed 阻止加载
     */
    suspend fun setProxyAsync(
        type: ProxyType,
        host: String,
        port: Int
    ): ProxySetupResult = withContext(Dispatchers.Main) {
        // 先清除旧代理，避免残留（见问题4：切换残留）
        // 注意：清除也是异步的，这里等待它完成再继续
        when (val clearResult = clearProxyAsync()) {
            is ProxySetupResult.Failed -> return@withContext clearResult
            is ProxySetupResult.NotSupported -> return@withContext clearResult
            else -> Unit // Applied / 直连无需清除
        }

        if (type == ProxyType.NONE) return@withContext ProxySetupResult.Applied

        if (!isSupported()) {
            Log.w(TAG, "WebView 不支持 PROXY_OVERRIDE，代理无法设置")
            return@withContext ProxySetupResult.NotSupported
        }
        if (host.isBlank()) {
            return@withContext ProxySetupResult.InvalidArgs("代理地址为空")
        }
        if (port <= 0 || port > 65535) {
            return@withContext ProxySetupResult.InvalidArgs("代理端口无效: $port")
        }
        // 主机格式校验（见问题9）：域名 / IPv4 / 规范 IPv6
        if (!isValidHost(host)) {
            return@withContext ProxySetupResult.InvalidArgs("代理地址格式无效: $host")
        }

        return@withContext try {
            val scheme = when (type) {
                ProxyType.HTTP -> "http"
                // 注意：Chromium 里 "socks://" 按 SOCKS4 解析，"socks5://" 才是 SOCKS5
                //（见 BUG-2）。SOCKS4 不支持域名由代理端解析，会 DNS 泄露。
                ProxyType.SOCKS5 -> "socks5"
                ProxyType.NONE -> return@withContext ProxySetupResult.Applied
            }
            val config = ProxyConfig.Builder()
                .addProxyRule("$scheme://$host:$port")
                .build()
            // 等待异步回调确认配置已应用（见问题2）
            val ok = suspendCancellableCoroutine { cont ->
                try {
                    ProxyController.getInstance().setProxyOverride(
                        config,
                        directExecutor,
                        Runnable {
                            Log.i(TAG, "代理配置已应用: $scheme://$host:$port")
                            if (cont.isActive) cont.resume(true)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "setProxyOverride 调用异常", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (ok) ProxySetupResult.Applied
            else ProxySetupResult.Failed("代理配置回调未确认")
        } catch (e: Exception) {
            Log.w(TAG, "代理设置失败", e)
            ProxySetupResult.Failed(e.message ?: "未知错误")
        }
    }

    /**
     * §4.6 本地中继：SOCKS5 带用户名密码时走这条路（Chromium ProxyController
     * 不支持 SOCKS 认证，见 LocalRelay）。
     *
     * 逻辑：随机 token 创建 LocalRelay → start() → WebView 代理指向
     * HTTP "127.0.0.1:relay.port"（走现有 setProxyAsync）→ 中继用 SOCKS5+
     * RFC1929 认证连上游。WebView 侧在 onReceivedHttpAuthRequest 里
     * host=="127.0.0.1" 时 proceed("fp", relay.token)。
     *
     * 只在 SOCKS5 带用户名时用；无认证 SOCKS5 和 HTTP 请走原 setProxyAsync。
     *
     * @return Pair(设置结果, 中继实例)。成功时调用方负责在 Activity 销毁时
     *         relay.stop()；失败时中继已由本方法自行 stop()，返回 null。
     */
    suspend fun setProxyViaRelayAsync(
        type: ProxyType,
        host: String,
        port: Int,
        username: String,
        password: String
    ): Pair<ProxySetupResult, LocalRelay?> {
        if (type != ProxyType.SOCKS5) {
            return ProxySetupResult.InvalidArgs("本地中继只用于 SOCKS5（type=$type）") to null
        }
        if (username.isBlank()) {
            return ProxySetupResult.InvalidArgs("无用户名时请走普通 SOCKS5 逻辑") to null
        }
        if (host.isBlank() || port !in 1..65535) {
            return ProxySetupResult.InvalidArgs("上游代理地址无效") to null
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val relay = try {
            LocalRelay(host, port, username, password, token)
        } catch (e: Exception) {
            Log.w(TAG, "本地中继创建失败", e)
            return ProxySetupResult.Failed("本地中继创建失败: ${e.message}") to null
        }
        relay.start()
        val result = setProxyAsync(ProxyType.HTTP, "127.0.0.1", relay.port)
        if (result is ProxySetupResult.Applied) {
            Log.i(TAG, "SOCKS5 认证已走本地中继: 127.0.0.1:${relay.port} -> socks5://$host:$port (user=$username)")
            return result to relay
        }
        // 代理配置失败：自己关掉中继，不留监听端口
        relay.stop()
        return result to null
    }

    /**
     * 明确清除代理覆盖，切回直连（suspend，等待完成）。
     * 直连 Profile 也要等清除完成再 loadUrl，确保旧代理不残留（见问题3）。
     */
    suspend fun clearProxyAsync(): ProxySetupResult = withContext(Dispatchers.Main) {
        if (!isSupported()) {
            // 不支持代理覆盖的设备上没有旧代理可清，视为成功
            return@withContext ProxySetupResult.Applied
        }
        return@withContext try {
            val ok = suspendCancellableCoroutine { cont ->
                try {
                    ProxyController.getInstance().clearProxyOverride(
                        directExecutor,
                        Runnable {
                            Log.i(TAG, "代理已清除，切回直连")
                            if (cont.isActive) cont.resume(true)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "clearProxyOverride 调用异常", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (ok) ProxySetupResult.Applied
            else ProxySetupResult.Failed("清除代理回调未确认")
        } catch (e: Exception) {
            Log.w(TAG, "清除代理失败", e)
            ProxySetupResult.Failed(e.message ?: "未知错误")
        }
    }

    // ============ 以下为诊断与连通性测试（手动触发，见问题1、问题7） ============

    /** 主机格式校验：域名 / IPv4 / [...] 包裹的 IPv6（见问题9） */
    fun isValidHost(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty() || h.length > 253) return false
        if (h.contains(" ") || h.contains("/")) return false
        // IPv6 必须用方括号包裹
        if (h.startsWith("[")) {
            return h.endsWith("]") && h.length > 2 &&
                h.substring(1, h.length - 1).all { it.isDigit() || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        }
        if (h.contains(":")) return false // 裸 IPv6 不接受
        // 域名或 IPv4：字母数字、点、横杠
        return h.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
            !h.startsWith(".") && !h.startsWith("-") && !h.endsWith(".")
    }

    /**
     * 手动触发的代理连通性测试（见问题1、问题7）。
     *
     * 必须由用户主动触发，触发前 UI 明确提示：测试会先向 api.ipify.org
     * 发起一次直连请求获取真实 IP，再经代理请求一次。结果命名为
     * "Java 代理连通性测试"，不声称 WebView 已验证。
     *
     * 返回三档独立结果（见问题7）：
     * A.configApplied - 配置是否成功应用（调用方传入）
     * B.proxyReachable - 代理连接/认证是否成功
     * C.webViewVerified - WebView 实际出口（本方法无法验证，恒为 null）
     */
    data class ProxyTestResult(
        val configApplied: Boolean,
        /** null=无法验证（如直连 IP 查不到），true=IP 变化，false=IP 未变 */
        val proxyReachable: Boolean?,
        /** WebView 实际出口：Java 层无法验证，恒为 null，UI 显示"无法验证" */
        val webViewVerified: Boolean? = null,
        val directIp: String? = null,
        val proxyIp: String? = null,
        val detail: String = ""
    )

    suspend fun testProxyConnectivity(
        host: String,
        port: Int,
        type: ProxyType,
        configApplied: Boolean
    ): ProxyTestResult = withContext(Dispatchers.IO) {
        // 直连查一次（用户已确认，见问题1）
        val directIp = fetchIp(null)
        if (directIp == null) {
            return@withContext ProxyTestResult(
                configApplied = configApplied,
                proxyReachable = null,
                directIp = null,
                detail = "直连 IP 查询失败，无法对比"
            )
        }
        val proxyType = if (type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
        val proxy = Proxy(proxyType, InetSocketAddress(host, port))
        val proxyIp = fetchIp(proxy)
        if (proxyIp == null) {
            return@withContext ProxyTestResult(
                configApplied = configApplied,
                proxyReachable = false,
                directIp = directIp,
                detail = "经代理的 IP 查询失败（代理不可达或认证失败）"
            )
        }
        val changed = directIp != proxyIp
        ProxyTestResult(
            configApplied = configApplied,
            proxyReachable = changed,
            directIp = directIp,
            proxyIp = proxyIp,
            detail = if (changed) "出口 IP 已变化" else "出口 IP 未变化（可能代理未生效）"
        )
    }

    /** 轻量 IP 查询（api.ipify.org 返回纯文本 IP） */
    private fun fetchIp(proxy: Proxy?): String? {
        return try {
            val conn = (if (proxy != null) URL("https://api.ipify.org").openConnection(proxy)
                        else URL("https://api.ipify.org").openConnection()) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.bufferedReader().use { it.readText().trim() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "IP 查询失败", e)
            null
        }
    }

    /**
     * 代理诊断：检查 ProxyController 可用性与代理服务器连通性。
     * 不发起任何直连公网请求（见问题1）。
     */
    fun diagnose(host: String, port: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== 代理诊断 ===")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine()
        sb.appendLine("① PROXY_OVERRIDE 支持: ${if (isSupported()) "是" else "否"}")
        sb.appendLine("② 代理地址格式: ${if (isValidHost(host)) "有效" else "无效"}")
        sb.appendLine()

        sb.appendLine("③ 代理服务器连通性 ($host:$port):")
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.close()
            sb.appendLine("   有服务在监听 ✓")
        } catch (e: Exception) {
            sb.appendLine("   无响应 (${e.message})")
        }
        return sb.toString()
    }
}
