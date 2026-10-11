package com.limao.fingerprint.ui

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 本地中继代理（报告 §4.6）：让 Chromium ProxyController 也能用带账号密码的 SOCKS5。
 *
 * 原理：Chromium 的 ProxyController 不支持 SOCKS 认证，所以在 :wvN 进程内
 * 起一个只监听 127.0.0.1 随机端口的 HTTP CONNECT 中继；WebView 的代理指向
 * 它（"http://127.0.0.1:PORT"），中继再用 SOCKS5（RFC1929 用户名密码认证，
 * method 0x02；无认证时用 0x00）连到真正的上游。
 *
 * - 只实现 HTTP CONNECT：应用已禁明文流量（cleartext），WebView 只会发
 *   CONNECT 建 HTTPS 隧道，非 CONNECT 方法回 405。
 * - 域名一律用 ATYP=0x03 发给上游解析，本地不做 DNS（不泄露 DNS）；
 *   IPv6 字面地址（CONNECT 行里方括号包裹，如 [2001:db8::1]:443）用
 *   ATYP=0x04，IPv4 字面地址用 ATYP=0x01。
 * - 防蹭用：每个 CONNECT 必须带 Proxy-Authorization: Basic base64("fp:"+token)，
 *   不对回 407 + Proxy-Authenticate: Basic realm="fp"，WebView 侧在
 *   onReceivedHttpAuthRequest 里 host=="127.0.0.1" 时 proceed("fp", token)。
 * - 所有线程都是 daemon，异常全部 catch，绝不崩进程。
 * - 生命周期：随 BrowserActivity 创建（start()）/ onDestroy 关闭（stop()）。
 *
 * 典型用法：
 *     val relay = LocalRelay(upHost, upPort, user, pass, token).also { it.start() }
 *     WebViewProxy.setProxyAsync(ProxyType.HTTP, "127.0.0.1", relay.port)
 *     // Activity 销毁时 relay.stop()
 */
class LocalRelay(
    private val upHost: String,
    private val upPort: Int,
    private val user: String,
    private val pass: String,
    /** 随机口令（公开）：WebView 侧 onReceivedHttpAuthRequest 用它 proceed("fp", token) */
    val token: String
) {
    companion object {
        private const val TAG = "LocalRelay"
        /** 请求头上限 64KB，防恶意/异常连接占内存 */
        private const val MAX_HEAD_BYTES = 64 * 1024
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKS_HANDSHAKE_TIMEOUT_MS = 15_000
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** 实际监听的随机端口（127.0.0.1） */
    val port: Int get() = server.localPort

    @Volatile
    private var running = false

    /**
     * 启动后台 accept 线程（daemon）。幂等：重复调用无效果；
     * stop() 之后再次 start() 不再接受新连接（server 已关闭）。
     */
    @Synchronized
    fun start() {
        if (running || server.isClosed) return
        running = true
        thread(isDaemon = true, name = "LocalRelay-accept") {
            while (running && !server.isClosed) {
                val c = try {
                    server.accept()
                } catch (e: Exception) {
                    break // stop() 关闭了 server，或异常：退出线程，不崩进程
                }
                thread(isDaemon = true, name = "LocalRelay-conn") {
                    try {
                        handle(c)
                    } catch (e: Exception) {
                        Log.w(TAG, "连接处理异常", e)
                        try {
                            c.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    /** 关闭监听 socket。已建立的连接随各自 handle 结束自动关闭。 */
    @Synchronized
    fun stop() {
        running = false
        try {
            server.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭监听 socket 失败", e)
        }
    }

    // ==================== 连接处理 ====================

    private fun handle(c: Socket) {
        c.use { sock ->
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()
            try {
                val head = readHead(inp) ?: return
                val first = head.lineSequence().firstOrNull()?.trim() ?: return
                if (!first.startsWith("CONNECT ")) {
                    writeSimple(out, "HTTP/1.1 405 Method Not Allowed")
                    return
                }
                // 校验 Proxy-Authorization: Basic base64("fp:" + token)
                if (!proxyAuthOk(head)) {
                    out.write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"fp\"\r\n" +
                            "Content-Length: 0\r\n\r\n").toByteArray()
                    )
                    out.flush()
                    return
                }
                val target = first.split(" ").getOrNull(1)
                val (host, port) = target?.let { parseTarget(it) } ?: run {
                    writeSimple(out, "HTTP/1.1 400 Bad Request")
                    return
                }
                val up = Socket()
                try {
                    up.connect(InetSocketAddress(upHost, upPort), UPSTREAM_CONNECT_TIMEOUT_MS)
                    socks5(up, host, port)
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    out.flush()
                    // 双向管道：任一方向结束就 shutdown 对端的输出，让另一方向读到 EOF
                    val t = thread(isDaemon = true, name = "LocalRelay-pipe") {
                        pipe(up.getInputStream(), out) { safeShutdownOutput(sock) }
                    }
                    pipe(inp, up.getOutputStream()) { safeShutdownOutput(up) }
                    t.join()
                } catch (e: Exception) {
                    Log.w(TAG, "中继失败 $host:$port", e)
                    try {
                        writeSimple(out, "HTTP/1.1 502 Bad Gateway")
                    } catch (_: Exception) {
                    }
                } finally {
                    try {
                        up.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "处理连接异常", e)
            }
        }
    }

    private fun writeSimple(out: OutputStream, statusLine: String) {
        out.write("$statusLine\r\nContent-Length: 0\r\n\r\n".toByteArray())
        out.flush()
    }

    /** 读到 \r\n\r\n 为止；读不到 / 超长返回 null */
    private fun readHead(inp: InputStream): String? {
        val out = ByteArrayOutputStream(4096)
        val one = ByteArray(1)
        val marker = byteArrayOf(13, 10, 13, 10) // \r\n\r\n
        var matched = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val n = try {
                inp.read(one)
            } catch (_: Exception) {
                return null
            }
            if (n < 0) return null
            out.write(one[0].toInt())
            if (one[0] == marker[matched]) {
                matched++
                if (matched == marker.size) {
                    // 请求头是纯 ASCII，用 ISO-8859-1 逐字节还原，避免多字节字符集破坏 base64
                    return out.toString("ISO-8859-1")
                }
            } else {
                matched = if (one[0] == 13.toByte()) 1 else 0
            }
        }
        Log.w(TAG, "请求头超长(>${MAX_HEAD_BYTES}B)，拒绝")
        return null
    }

    /** 校验 Proxy-Authorization: Basic base64("fp:"+token) */
    private fun proxyAuthOk(head: String): Boolean {
        val expected = "Basic " + Base64.encodeToString(
            "fp:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        for (line in head.lineSequence().drop(1)) {
            if (line.isEmpty()) break // 空行 = 头结束
            val idx = line.indexOf(':')
            if (idx < 0) continue
            if (line.substring(0, idx).trim().equals("Proxy-Authorization", ignoreCase = true)) {
                return line.substring(idx + 1).trim() == expected
            }
        }
        return false
    }

    /**
     * 解析 CONNECT 目标 "host:port"。
     * - 方括号 IPv6：[2001:db8::1]:443
     * - 普通：example.com:443 / 1.2.3.4:443
     */
    private fun parseTarget(target: String): Pair<String, Int>? {
        return try {
            if (target.startsWith("[")) {
                val end = target.indexOf(']')
                if (end < 0) return null
                val host = target.substring(1, end)
                val rest = target.substring(end + 1)
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1).toIntOrNull() ?: return null
                if (port !in 1..65535 || host.isEmpty()) return null
                host to port
            } else {
                val host = target.substringBeforeLast(":")
                val port = target.substringAfterLast(":").toIntOrNull() ?: return null
                if (port !in 1..65535 || host.isEmpty() || host.contains(" ")) return null
                host to port
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== SOCKS5 ====================

    /**
     * SOCKS5 握手：有用户名时用 method 0x02（RFC1929 用户名密码认证），
     * 无用户名时用 0x00。域名用 ATYP=0x03 由上游解析（本地不做 DNS）；
     * IPv6 字面地址用 ATYP=0x04，IPv4 字面地址用 ATYP=0x01。
     */
    private fun socks5(s: Socket, host: String, port: Int) {
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        s.soTimeout = SOCKS_HANDSHAKE_TIMEOUT_MS // 握手阶段加超时，防卡死
        try {
            // 1) 协商认证方式
            o.write(if (user.isNotEmpty()) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
            o.flush()
            require(i.readUnsignedByte() == 5) { "SOCKS5 版本错误" }
            when (i.readUnsignedByte()) {
                0 -> { /* 无需认证 */ }
                2 -> {
                    val u = user.toByteArray(Charsets.UTF_8)
                    val p = pass.toByteArray(Charsets.UTF_8)
                    require(u.size in 1..255 && p.size <= 255) { "用户名/密码长度非法" }
                    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                    o.flush()
                    require(i.readUnsignedByte() == 1) { "RFC1929 子协商版本错误" }
                    if (i.readUnsignedByte() != 0) throw IOException("SOCKS5 认证失败")
                }
                else -> throw IOException("SOCKS5 不支持的认证方式")
            }
            // 2) CONNECT 请求
            val portBytes = byteArrayOf((port shr 8).toByte(), port.toByte())
            val req = when {
                isIpv6Literal(host) -> {
                    // getByName 对字面 IP 只做解析、不做 DNS 查询
                    val raw = InetAddress.getByName(host).address
                    require(raw.size == 16) { "IPv6 地址解析异常" }
                    byteArrayOf(5, 1, 0, 4) + raw + portBytes
                }
                isIpv4Literal(host) -> {
                    val parts = host.split(".").map { it.toInt() }
                    require(parts.size == 4 && parts.all { it in 0..255 }) { "IPv4 地址非法" }
                    byteArrayOf(5, 1, 0, 1) + parts.map { it.toByte() }.toByteArray() + portBytes
                }
                else -> {
                    val h = host.toByteArray(Charsets.UTF_8)
                    require(h.size in 1..255) { "域名长度非法" }
                    byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + portBytes
                }
            }
            o.write(req)
            o.flush()
            // 3) 响应
            require(i.readUnsignedByte() == 5) { "SOCKS5 响应版本错误" }
            val rep = i.readUnsignedByte()
            i.readUnsignedByte() // RSV
            if (rep != 0) throw IOException("SOCKS5 连接失败 rep=$rep")
            when (i.readUnsignedByte()) { // 跳过绑定地址
                1 -> i.readFully(ByteArray(4))
                3 -> i.readFully(ByteArray(i.readUnsignedByte()))
                4 -> i.readFully(ByteArray(16))
                else -> throw IOException("SOCKS5 响应地址类型未知")
            }
            i.readFully(ByteArray(2)) // 端口
        } finally {
            s.soTimeout = 0 // 管道阶段恢复阻塞模式
        }
    }

    private fun isIpv6Literal(host: String): Boolean =
        host.contains(":") &&
            host.all { it.isDigit() || it == ':' || it in 'a'..'f' || it in 'A'..'F' || it == '.' }

    private fun isIpv4Literal(host: String): Boolean =
        host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))

    // ==================== 管道 ====================

    /** 循环 read/write 8KB；结束时回调 shutdown 对端输出，让另一方向读到 EOF */
    private fun pipe(input: InputStream, output: OutputStream, onEnd: () -> Unit) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                try {
                    output.write(buf, 0, n)
                    output.flush()
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            try {
                onEnd()
            } catch (_: Exception) {
            }
        }
    }

    private fun safeShutdownOutput(s: Socket) {
        try {
            s.shutdownOutput()
        } catch (_: Exception) {
        }
    }
}
