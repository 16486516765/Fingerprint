package com.limao.fingerprint.data

import java.util.UUID
import kotlin.random.Random

/**
 * 生成指纹随机种子（永不为 0，0 表示"未生成"）。
 * 同一种子 => 同一套 Canvas/Audio 噪声，保证同一 profile 指纹稳定。
 */
fun generateFpSeed(): Long {
    var s: Long
    do {
        s = Random.nextLong()
    } while (s == 0L)
    return s
}

/**
 * 指纹伪装配置（§4.3）。
 *
 * 每个 profile 一套，创建时从 [DeviceTemplates] 抽一套真实设备组合，
 * 型号 / 分辨率 / GPU / 内存相互匹配，避免用户随意拼出不合理组合。
 *
 * @param webrtcMode 0=禁用 WebRTC（默认，最防泄漏），1=允许
 */

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

data class FpConfig(
    val screenW: Int = 1080,
    val screenH: Int = 2400,
    val dpr: Float = 2.625f,
    val cores: Int = 9,
    val memGb: Int = 8,
    val platform: String = "Linux armv8l",
    val languages: List<String> = listOf("en-US", "en"),
    val timezone: String = "America/New_York",
    val webglVendor: String = "Google Inc.",
    val webglRenderer: String = "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)",
    val canvasNoise: Boolean = true,
    val audioNoise: Boolean = true,
    /** 0=禁用 WebRTC（默认），1=允许 */
    val webrtcMode: Int = 0
)

/**
 * 浏览器 Profile：代表一个完全隔离的浏览环境。
 *
 * 隔离说明（多进程方案）：
 * - 每个 profile 分配一个固定 slot（0-5），运行在独立的 Android 进程 `:wvN` 里。
 * - 进程启动时（Application.onCreate）在创建任何 WebView 之前调用
 *   WebView.setDataDirectorySuffix()，因此每个 profile 拥有独立的
 *   Cookie jar / localStorage / IndexedDB / 缓存目录，进程级硬隔离。
 * - 代理也是进程级设置（AndroidX WebKit ProxyController 公开接口），
 *   独立进程 = 天然 per-profile 代理。
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
    /**
     * 代理用户名（已废弃，见问题4）。
     * 不再持久化到 JSON（ProfileRepository.mutate 会剥离），只走 ProxyCredentialStore。
     * 保留字段仅为兼容旧数据反序列化，迁移后恒为空。
     */
    val proxyUsername: String = "",
    /**
     * 代理密码（已废弃，见问题4）。
     * 不再持久化到 JSON，只走 ProxyCredentialStore。保留字段仅为兼容旧数据。
     */
    val proxyPassword: String = "",
    /** 分组名，用于列表筛选，默认"未分组" */
    val group: String = "未分组",
    /** 最后访问的 URL，列表页显示用 */
    val lastVisitedUrl: String = "",
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 指纹随机种子（§4.3）。
     * 0 表示未生成（旧数据），ProfileRepository.migrateFpConfigIfNeeded() 会补生成。
     * 一旦生成永不改变，保证同一 profile 的 Canvas/Audio 噪声稳定。
     */
    val fpSeed: Long = 0L,
    /**
     * 指纹伪装配置（§4.3）。
     * Gson 反序列化旧 JSON（缺字段）时由 ProfileRepository.readList 归一化为默认实例。
     */
    val fpConfig: FpConfig = FpConfig()
)

/**
 * 真实设备模板库（§4.3）。
 *
 * 每个模板 = 一套相互匹配的 FpConfig（型号/分辨率/GPU/内存/时区/语言一致）
 * + 对应的 UA 模板。UA 里 Chrome 版本用 `{WV}` 占位，调用方在注入前替换为
 * WebView 实际版本（WebView.getCurrentWebViewPackage()?.versionName）。
 */
object DeviceTemplates {

    data class DeviceTemplate(
        /** 显示名，如 "Pixel 8" */
        val displayName: String,
        val config: FpConfig,
        /** UA 模板，含 {WV} 占位符 */
        val uaTemplate: String
    )

    /** 模板下拉里的"自定义"选项显示名 */
    const val CUSTOM_LABEL = "自定义"

    /** UA 模板里的 WebView 版本占位符 */
    const val WV_PLACEHOLDER = "{WV}"

    val all: List<DeviceTemplate> = listOf(
        DeviceTemplate(
            "Pixel 8",
            FpConfig(
                screenW = 1080, screenH = 2400, dpr = 2.625f,
                cores = 9, memGb = 8,
                platform = "Linux armv8l",
                languages = listOf("en-US", "en"),
                timezone = "America/New_York",
                webglVendor = "Google Inc.",
                webglRenderer = "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)"
            ),
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/{WV} Mobile Safari/537.36"
        ),
        DeviceTemplate(
            "Galaxy S24",
            FpConfig(
                screenW = 1080, screenH = 2340, dpr = 3.0f,
                cores = 8, memGb = 8,
                platform = "Linux armv8l",
                languages = listOf("en-US", "en"),
                timezone = "America/Chicago",
                webglVendor = "Google Inc.",
                webglRenderer = "ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)"
            ),
            "Mozilla/5.0 (Linux; Android 14; SM-S921U) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/{WV} Mobile Safari/537.36"
        ),
        DeviceTemplate(
            "Pixel Tablet",
            FpConfig(
                screenW = 1600, screenH = 2560, dpr = 2.0f,
                cores = 8, memGb = 8,
                platform = "Linux armv8l",
                languages = listOf("en-US", "en"),
                timezone = "America/Los_Angeles",
                webglVendor = "Google Inc.",
                webglRenderer = "ANGLE (ARM, Mali-G710, OpenGL ES 3.2)"
            ),
            "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/{WV} Safari/537.36"
        ),
        DeviceTemplate(
            "一加 12",
            FpConfig(
                screenW = 1440, screenH = 3168, dpr = 3.5f,
                cores = 8,
                // 注意：Chrome 的 deviceMemory 上限就是 8，填更大反而可疑
                memGb = 8,
                platform = "Linux armv8l",
                languages = listOf("zh-CN", "zh", "en-US", "en"),
                timezone = "Asia/Shanghai",
                webglVendor = "Google Inc.",
                webglRenderer = "ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)"
            ),
            "Mozilla/5.0 (Linux; Android 14; CPH2581) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/{WV} Mobile Safari/537.36"
        )
    )

    /** 随机抽一套模板（"随机生成一套"按钮 / 旧数据迁移用） */
    fun randomTemplate(): DeviceTemplate = all.random()

    /** 按 FpConfig 反查模板，自定义组合返回 null */
    fun findByConfig(config: FpConfig): DeviceTemplate? =
        all.find { it.config == config }

    /**
     * 渲染模板 UA：把 {WV} 替换为 WebView 实际版本。
     * @param webViewVersion 如 "138.0.0.0"，为空时回退 120.0.0.0
     */
    fun uaFor(template: DeviceTemplate, webViewVersion: String): String =
        template.uaTemplate.replace(
            WV_PLACEHOLDER,
            webViewVersion.ifBlank { "120.0.0.0" }
        )
}

/**
 * 主流手机 UA 预设，供新建/编辑 profile 时快速选择。
 *
 * 注意（§4.3）：Safari / Firefox 预设已删除——UA 写 iPhone Safari / Firefox，
 * 但实际内核是 Chromium（navigator.vendor="Google Inc."、有 window.chrome 对象），
 * 内核信息不一致本身就是强特征，反而更可疑。
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
