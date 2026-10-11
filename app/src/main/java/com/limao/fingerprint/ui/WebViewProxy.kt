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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebView 进程级代理设置（AndroidX WebKit ProxyController 公开接口）。
 *
 * 每个 profile 独占一个 :wvN 进程，ProxyController 是进程级的，
 * 在对应进程内调用即天然实现 per-profile 代理，无需反射。
 *
 * - HTTP  → ProxyConfig "http://host:port"
 * - SOCKS5 → ProxyConfig "socks://host:port"（官方文档确认 scheme 支持 SOCKS）
 * - 直连   → clearProxyOverride() 明确清除，不残留旧代理
 *
 * 必须在主线程、WebView 创建后、loadUrl 前调用。
 */
object WebViewProxy {

    private const val TAG = "WebViewProxy"

    /** 是否支持代理覆盖（取决于设备 WebView 版本） */
    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * 设置当前进程的 WebView 代理。
     * @return true 配置已应用；false 失败（不支持或参数无效或异常）
     */
    fun setProxy(type: ProxyType, host: String, port: Int): Boolean {
        // 先清除旧代理，避免残留
        clearProxy()

        if (type == ProxyType.NONE) return true

        if (!isSupported()) {
            Log.w(TAG, "WebView 不支持 PROXY_OVERRIDE，代理无法设置")
            return false
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            Log.w(TAG, "代理地址/端口无效，跳过设置")
            return false
        }

        return try {
            val scheme = when (type) {
                ProxyType.HTTP -> "http"
                ProxyType.SOCKS5 -> "socks"
                ProxyType.NONE -> return true
            }
            val config = ProxyConfig.Builder()
                .addProxyRule("$scheme://$host:$port")
                .build()
            // setProxyOverride 必须传 executor（直接在当前线程跑回调）
            val directExecutor = java.util.concurrent.Executor { it.run() }
            ProxyController.getInstance().setProxyOverride(config, directExecutor, Runnable {
                Log.i(TAG, "代理已应用: $scheme://$host:$port")
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "代理设置失败", e)
            false
        }
    }

    /** 明确清除代理覆盖，切回直连 */
    fun clearProxy(): Boolean {
        return try {
            if (isSupported()) {
                val directExecutor = java.util.concurrent.Executor { it.run() }
                ProxyController.getInstance().clearProxyOverride(directExecutor, Runnable {
                    Log.i(TAG, "代理已清除，切回直连")
                })
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "清除代理失败", e)
            false
        }
    }

    /**
     * 后台测试代理是否真实生效。
     * 直连请求一次 IP 查询接口，再经代理请求一次，对比出口 IP 是否变化。
     * @return true 代理生效（IP 变化）；false 未生效
     */
    suspend fun testProxyEffective(
        host: String,
        port: Int,
        type: ProxyType
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val directIp = fetchIp(null) ?: run {
                Log.w(TAG, "直连 IP 查询失败，无法对比")
                return@withContext false
            }
            val proxyType = if (type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
            val proxy = Proxy(proxyType, InetSocketAddress(host, port))
            val proxyIp = fetchIp(proxy) ?: run {
                Log.w(TAG, "代理 IP 查询失败")
                return@withContext false
            }
            val effective = directIp != proxyIp
            Log.i(TAG, "代理生效测试: 直连=$directIp 代理=$proxyIp 生效=$effective")
            effective
        } catch (e: Exception) {
            Log.w(TAG, "代理生效测试异常", e)
            false
        }
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
     */
    fun diagnose(host: String, port: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== 代理诊断 ===")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine()
        sb.appendLine("① PROXY_OVERRIDE 支持: ${if (isSupported()) "是" else "否"}")
        sb.appendLine()

        sb.appendLine("② 代理服务器连通性 ($host:$port):")
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
