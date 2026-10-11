package com.limao.fingerprint.ui

import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.os.Parcelable
import android.util.Log
import com.limao.fingerprint.data.ProxyType

/**
 * WebView 进程级代理设置（反射方案）。
 *
 * 背景：Android WebView 没有公开的 per-WebView 代理 API。
 * 社区常用方案是反射找到 WebView 内部注册的 ProxyChangeListener
 * （藏在 ActivityThread.mBoundApplication.info(LoadedApk).mReceivers 里），
 * 手动派发一次 PROXY_CHANGE 事件，把 ProxyInfo 灌进去，Chromium 内核会应用它。
 *
 * 在本应用里每个 profile 独占一个进程（:wvN），所以"进程级代理"天然就是
 * per-profile 代理——这正是多进程架构要解决的核心问题之一。
 *
 * 局限（v1 如实说明）：
 * - HTTP 代理：反射方案在多数设备上有效，但必须在 WebView 创建后调用。
 * - SOCKS5：公开 API 没有构造 SOCKS ProxyInfo 的方法，这里反射隐藏构造；
 *   Chromium 对 SOCKS 的实际支持因设备/系统版本而异，v1 不保证所有设备生效。
 * - 若设置失败返回 false，调用方在 UI 状态条显示"代理设置失败，已降级直连"，
 *   不静默失败、不谎报成功。
 */
object WebViewProxy {

    private const val TAG = "WebViewProxy"

    /**
     * 设置当前进程的 WebView 代理，必须在创建 WebView **之后**、loadUrl 之前调用
     *（ProxyChangeListener 是 WebView 初始化时才注册的）。
     * @return true 成功（或无需设置）；false 失败，调用方应降级直连并提示用户。
     */
    fun setProxy(context: Context, type: ProxyType, host: String, port: Int): Boolean {
        if (type == ProxyType.NONE) return true
        if (host.isBlank() || port <= 0 || port > 65535) {
            Log.w(TAG, "代理地址/端口无效，跳过设置")
            return false
        }
        return try {
            if (dispatchProxyChange(context, type, host, port)) {
                Log.i(TAG, "代理已通过 ProxyChangeListener 生效: $type $host:$port")
                true
            } else {
                // 注意：JVM 系统属性（http.proxyHost 等）Chromium 不读，
                // 对 WebView 无效。这里不再 fallback 谎报成功，直接返回 false，
                // 让 UI 如实显示"代理设置失败"。
                Log.w(TAG, "ProxyChangeListener 反射失败，WebView 代理未生效")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "代理设置失败", e)
            false
        }
    }

    /**
     * 反射派发 PROXY_CHANGE：
     * ActivityThread.currentActivityThread()
     *   -> mBoundApplication.info (LoadedApk)
     *   -> mReceivers: Map<Context, Map<BroadcastReceiver, ReceiverDispatcher>>
     * 找到类名含 ProxyChangeListener 的 receiver，调其 onReceive 灌入 ProxyInfo。
     */
    private fun dispatchProxyChange(
        context: Context, type: ProxyType, host: String, port: Int
    ): Boolean {
        return try {
            val proxyInfo = buildProxyInfo(type, host, port) ?: return false

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null) ?: return false

            val boundAppField = activityThreadClass.getDeclaredField("mBoundApplication")
            boundAppField.isAccessible = true
            val boundApp = boundAppField.get(currentThread) ?: return false

            val infoField = boundApp.javaClass.getDeclaredField("info")
            infoField.isAccessible = true
            val loadedApk = infoField.get(boundApp) ?: return false

            val receiversField = loadedApk.javaClass.getDeclaredField("mReceivers")
            receiversField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val receivers = receiversField.get(loadedApk) as? Map<*, *> ?: return false

            for ((_, receiverMap) in receivers) {
                val map = receiverMap as? Map<*, *> ?: continue
                for (receiver in map.keys) {
                    val clazz = receiver?.javaClass ?: continue
                    if (!clazz.name.contains("ProxyChangeListener")) continue
                    val onReceive = clazz.getDeclaredMethod(
                        "onReceive", Context::class.java, Intent::class.java
                    )
                    onReceive.isAccessible = true
                    val intent = Intent(Proxy.PROXY_CHANGE_ACTION).apply {
                        putExtra("proxy", proxyInfo as Parcelable)
                    }
                    onReceive.invoke(receiver, context.applicationContext, intent)
                    return true
                }
            }
            Log.w(TAG, "未找到 ProxyChangeListener，走降级方案")
            false
        } catch (e: Exception) {
            Log.w(TAG, "ProxyChangeListener 反射失败", e)
            false
        }
    }

    /**
     * 构造 ProxyInfo。
     * HTTP：ProxyInfo.buildDirectProxy(host, port)（公开 API）。
     * SOCKS5：公开 API 没有对应构造，反射隐藏构造
     * ProxyInfo(String host, int port, String pacFileUrl)，pac 传 null 表示直接代理。
     */
    private fun buildProxyInfo(type: ProxyType, host: String, port: Int): Any? {
        return try {
            val proxyInfoClass = Class.forName("android.net.ProxyInfo")
            when (type) {
                ProxyType.HTTP -> {
                    val m = proxyInfoClass.getDeclaredMethod(
                        "buildDirectProxy",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(null, host, port)
                }
                ProxyType.SOCKS5 -> {
                    val ctor = proxyInfoClass.getDeclaredConstructor(
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    ctor.isAccessible = true
                    ctor.newInstance(host, port, null)
                }
                ProxyType.NONE -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "构造 ProxyInfo 失败", e)
            null
        }
    }

    /**
     * 代理诊断：逐步检查反射链路，返回每一步的结果。
     * 用于定位"显示已连接但实际不生效"的问题。
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== 代理诊断 ===")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("进程: ${getProcessName()}")
        sb.appendLine()

        val activityThreadClass: Class<*>
        try {
            activityThreadClass = Class.forName("android.app.ActivityThread")
            sb.appendLine("① ActivityThread: OK")
        } catch (e: Exception) {
            sb.appendLine("① ActivityThread: 失败 - ${e.message}")
            return sb.toString()
        }

        val boundApp: Any?
        try {
            val currentThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread").invoke(null)
            val boundAppField = activityThreadClass.getDeclaredField("mBoundApplication")
            boundAppField.isAccessible = true
            boundApp = boundAppField.get(currentThread)
            sb.appendLine("② mBoundApplication: ${if (boundApp != null) "OK" else "null!"}")
            if (boundApp == null) return sb.toString()
        } catch (e: Exception) {
            sb.appendLine("② mBoundApplication: 失败 - ${e.message}")
            return sb.toString()
        }

        val receivers: Map<*, *>
        try {
            val infoField = boundApp.javaClass.getDeclaredField("info")
            infoField.isAccessible = true
            val loadedApk = infoField.get(boundApp)
            val receiversField = loadedApk.javaClass.getDeclaredField("mReceivers")
            receiversField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            receivers = receiversField.get(loadedApk) as? Map<*, *> ?: emptyMap<Any, Any>()
            var total = 0
            for ((_, v) in receivers) {
                total += (v as? Map<*, *>)?.size ?: 0
            }
            sb.appendLine("③ mReceivers: OK，共 $total 个 receiver")
        } catch (e: Exception) {
            sb.appendLine("③ mReceivers: 失败 - ${e.message}")
            return sb.toString()
        }

        var found: String? = null
        val proxyRelated = mutableListOf<String>()
        for ((_, receiverMap) in receivers) {
            val map = receiverMap as? Map<*, *> ?: continue
            for (receiver in map.keys) {
                val name = receiver?.javaClass?.name ?: continue
                if (name.contains("proxy", ignoreCase = true)) {
                    proxyRelated.add(name)
                }
                if (name.contains("ProxyChangeListener")) {
                    found = name
                }
            }
        }
        sb.appendLine("④ 含 proxy 的 receiver: ${if (proxyRelated.isEmpty()) "无" else proxyRelated.joinToString()}")
        sb.appendLine("⑤ ProxyChangeListener: ${found ?: "未找到!"}")
        sb.appendLine()

        try {
            val proxyInfoClass = Class.forName("android.net.ProxyInfo")
            val m = proxyInfoClass.getDeclaredMethod(
                "buildDirectProxy", String::class.java, Int::class.javaPrimitiveType
            )
            val info = m.invoke(null, "127.0.0.1", 8080)
            sb.appendLine("⑥ ProxyInfo 构造: OK ($info)")
        } catch (e: Exception) {
            sb.appendLine("⑥ ProxyInfo 构造: 失败 - ${e.message}")
        }

        return sb.toString()
    }

    private fun getProcessName(): String {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getDeclaredMethod("currentProcessName")
            method.invoke(null) as? String ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

}
