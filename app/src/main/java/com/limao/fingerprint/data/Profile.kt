package com.limao.fingerprint.data

import java.util.UUID

/**
 * 代理类型
 * NONE   - 直连
 * HTTP   - HTTP/HTTPS 代理
 * SOCKS5 - SOCKS5 代理
 */
enum class ProxyType {
    NONE,
    HTTP,
    SOCKS5
}

/**
 * 浏览器 Profile：代表一个完全隔离的浏览环境。
 *
 * 隔离说明（多进程方案）：
 * - 每个 profile 分配一个固定 slot（0-5），运行在独立的 Android 进程 `:wvN` 里。
 * - 进程启动时（Application.onCreate）在创建任何 WebView 之前调用
 *   WebView.setDataDirectorySuffix()，因此每个 profile 拥有独立的
 *   Cookie jar / localStorage / IndexedDB / 缓存目录，进程级硬隔离。
 * - 代理也是进程级设置（反射），独立进程 = 天然 per-profile 代理。
 * - 多个 profile 可同时在线，互不干扰。MAX_PROFILES = 6，受手机内存限制。
 */
data class Profile(
    /** 唯一 ID，创建时自动生成 */
    val id: String = UUID.randomUUID().toString(),
    /**
     * 进程 slot（0-5），决定进程名 `:wvN` 与 WebView 数据目录后缀。
     * -1 表示尚未分配，Repository.addProfile 时自动分配最小空闲 slot。
     */
    val slot: Int = -1,
    /** 显示名称，如"甲骨文主号" */
    val name: String = "",
    /** 主页 URL，也是 favicon 图标的来源 */
    val homepageUrl: String = "",
    /** 自定义 User-Agent，空字符串表示使用 WebView 系统默认 */
    val userAgent: String = "",
    /** 代理类型 */
    val proxyType: ProxyType = ProxyType.NONE,
    /** 代理地址 */
    val proxyHost: String = "",
    /** 代理端口 */
    val proxyPort: Int = 1080,
    /** 代理用户名（可选，部分代理需要认证） */
    val proxyUsername: String = "",
    /** 代理密码（可选） */
    val proxyPassword: String = "",
    /** 分组名，用于列表筛选，默认"未分组" */
    val group: String = "未分组",
    /** 最后访问的 URL，列表页显示用 */
    val lastVisitedUrl: String = "",
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 主流手机 UA 预设，供新建/编辑 profile 时快速选择。
 */
object UaPresets {

    data class UaPreset(
        /** 显示名 */
        val displayName: String,
        /** UA 字符串，空字符串表示"系统默认" */
        val ua: String
    )

    /** 自定义选项的显示名（UA 为空时由调用方弹文本框填写） */
    const val CUSTOM_LABEL = "自定义…"

    val presets: List<UaPreset> = listOf(
        UaPreset(
            "Chrome 120 / Android 14",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        ),
        UaPreset(
            "Chrome 120 / Android 平板",
            "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        ),
        UaPreset(
            "Safari 17 / iOS 17",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
        ),
        UaPreset(
            "Firefox 121 / Android",
            "Mozilla/5.0 (Android 14; Mobile; rv:121.0) Gecko/121.0 Firefox/121.0"
        ),
        UaPreset(
            "Edge 120 / Android",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EdgA/120.0.0.0"
        ),
        UaPreset(
            "系统默认",
            ""
        )
    )

    /** 根据 UA 字符串反查显示名，找不到返回"自定义…" */
    fun labelFor(ua: String): String =
        presets.find { it.ua == ua }?.displayName ?: CUSTOM_LABEL
}
