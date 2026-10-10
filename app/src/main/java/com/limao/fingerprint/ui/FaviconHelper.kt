package com.limao.fingerprint.ui

import android.net.Uri

/**
 * favicon 相关小工具。
 *
 * 图标来源：Google 的 favicon 服务，按 domain 取 64px 图标。
 * 例：https://www.google.com/s2/favicons?domain=oracle.com&sz=64
 */
object FaviconHelper {

    /** 从任意 URL 里提取 host（domain），提取失败返回空字符串 */
    fun domainOf(url: String): String {
        if (url.isBlank()) return ""
        return try {
            // 用户可能只填了 domain 没带 scheme，补一个
            val fixed = if (url.contains("://")) url else "https://$url"
            Uri.parse(fixed).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 根据 URL 拼出 favicon 地址，拿不到 domain 返回 null（调用方显示默认图标） */
    fun faviconUrlFor(url: String): String? {
        val domain = domainOf(url)
        if (domain.isBlank()) return null
        return "https://www.google.com/s2/favicons?domain=$domain&sz=64"
    }

    /** profile 卡片优先用最后访问的 URL，其次用主页 URL */
    fun pickUrl(homepageUrl: String, lastVisitedUrl: String): String =
        lastVisitedUrl.ifBlank { homepageUrl }
}
