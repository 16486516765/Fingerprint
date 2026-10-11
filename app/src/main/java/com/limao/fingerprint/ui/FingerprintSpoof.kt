package com.limao.fingerprint.ui

import androidx.webkit.WebViewFeature
import com.limao.fingerprint.data.FpConfig

/**
 * 指纹伪装注入脚本（§4.3）。
 *
 * [buildScript] 按 profile 的 fpSeed + [FpConfig] 渲染出一段 JS，
 * 供调用方通过 DOCUMENT_START_SCRIPT 在页面脚本执行前注入
 * （注入时机必须早于任何页面 JS，否则指纹库读到的是真实值）。
 *
 * 覆盖面：
 * - WebRTC：webrtcMode==0 时把 RTCPeerConnection 等置 undefined，
 *   getUserMedia 直接拒绝（防内网 IP 泄漏）
 * - Canvas：seed 稳定随机噪声（只改 2% 像素 R 通道 +1），
 *   getImageData / toDataURL / toBlob / WebGL readPixels 全覆盖
 * - WebGL（含 WebGL2）：getParameter 伪装 UNMASKED_VENDOR/RENDERER
 * - navigator：hardwareConcurrency / deviceMemory / platform /
 *   language / languages
 * - screen：宽高 / avail 宽高 / colorDepth + devicePixelRatio
 * - 时区：Intl.DateTimeFormat.resolvedOptions().timeZone、
 *   Date.getTimezoneOffset、Date.toString
 * - AudioContext：AudioBuffer.getChannelData 加微小噪声
 * - Function.prototype.toString 统一伪装：所有被 patch 的函数
 *   toString() 都返回 "function xxx() { [native code] }"
 *
 * 调用示例（由 BrowserScreen 负责，见 §4.3(2)）：
 * ```
 * if (FingerprintSpoof.isSupported()) {
 *     WebViewCompat.addDocumentStartJavaScript(
 *         webView,
 *         FingerprintSpoof.buildScript(profile.fpSeed, profile.fpConfig),
 *         setOf("*")
 *     )
 * }
 * ```
 * 注意：addDocumentStartJavaScript 需要 androidx.webkit 1.11.0+
 * 且系统 WebView 121+，先用 [isSupported] 判断。
 *
 * 坦白的局限（§4.3(5)）：JS 层伪装挡得住大多数基础指纹关联，
 * 但挡不住专业检测（原型链深挖、渲染差异、Worker 内指纹等）。
 */
object FingerprintSpoof {

    /**
     * DOCUMENT_START_SCRIPT 是否可用。
     *
     * 用字符串特性名判断（WebViewFeature.isFeatureSupported(String) 是公开 API），
     * 避免编译期依赖 androidx.webkit 1.11 才有的 DOCUMENT_START_SCRIPT 常量。
     * 运行时要求：androidx.webkit 1.11.0+ 且系统 WebView 121+。
     */
    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported("DOCUMENT_START_SCRIPT")

    /**
     * 渲染注入脚本。seed 会被截断到 31 位，保证 JS Number 精度内稳定。
     */
    fun buildScript(seed: Long, config: FpConfig): String {
        val langs = config.languages.joinToString(",", "[", "]") { jsString(it) }
        return SCRIPT_TEMPLATE
            .replace("__SEED__", (seed and 0x7fffffffL).toString())
            .replace("__VENDOR__", jsString(config.webglVendor))
            .replace("__RENDERER__", jsString(config.webglRenderer))
            .replace("__PLATFORM__", jsString(config.platform))
            .replace("__LANG0__", jsString(config.languages.firstOrNull() ?: "en-US"))
            .replace("__LANGS__", langs)
            .replace("__TZ__", jsString(config.timezone))
            .replace("__CORES__", config.cores.coerceAtLeast(1).toString())
            .replace("__MEM__", config.memGb.coerceAtLeast(1).toString())
            .replace("__W__", config.screenW.coerceAtLeast(1).toString())
            .replace("__H__", config.screenH.coerceAtLeast(1).toString())
            .replace("__DPR__", config.dpr.takeIf { it > 0 }?.toString() ?: "1.0")
            .replace("__CANVAS_NOISE__", config.canvasNoise.toString())
            .replace("__AUDIO_NOISE__", config.audioNoise.toString())
            .replace("__WEBRTC_DISABLED__", (config.webrtcMode == 0).toString())
    }

    /** 转义为 JS 单引号字符串字面量 */
    private fun jsString(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // 防御性：避免字面量里出现 <（如 </script> 逃逸）
                '<' -> sb.append("\\x3c")
                else -> sb.append(c)
            }
        }
        sb.append('\'')
        return sb.toString()
    }

    // 注意：模板里不要出现 ${（Kotlin raw string 会解析）与 """。
    // 占位符 __XXX__ 由 buildScript 统一替换，禁止字符串拼接拼 JS。
    private const val SCRIPT_TEMPLATE = """
(function () {
  'use strict';

  /* ===== 0. 种子随机数：同一 seed 结果稳定，不同 profile 不同 ===== */
  var __fpSeed = __SEED__;
  function __fpRnd(i) {
    var x = Math.sin(__fpSeed + i * 12.9898) * 43758.5453;
    return x - Math.floor(x);
  }

  /* ===== 1. Function.prototype.toString 伪装 =====
     先 patch 它，后面所有被替换的函数都登记"原生"签名，
     fn.toString() 返回 "function xxx() { [native code] }"，不暴露 patch 痕迹 */
  var __fpNativeMap = new WeakMap();
  var __fpOrigToString = Function.prototype.toString;
  Function.prototype.toString = function () {
    if (__fpNativeMap.has(this)) return __fpNativeMap.get(this);
    return __fpOrigToString.call(this);
  };
  __fpNativeMap.set(Function.prototype.toString, 'function toString() { [native code] }');
  function __fpSpoof(fn, name) {
    try {
      if (typeof fn === 'function') {
        __fpNativeMap.set(fn, 'function ' + (name || fn.name || 'anonymous') + '() { [native code] }');
      }
    } catch (e) {}
    return fn;
  }
  function __fpDefGetter(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, {
        configurable: true,
        enumerable: true,
        get: function () { return value; }
      });
    } catch (e) {}
  }

  /* ===== 2. WebRTC（禁用模式：防 STUN 内网 IP 泄漏） ===== */
  if (__WEBRTC_DISABLED__) {
    ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCSessionDescription',
     'RTCIceCandidate', 'RTCDataChannel'].forEach(function (n) {
      try {
        Object.defineProperty(window, n, {
          configurable: true,
          enumerable: true,
          get: function () { return undefined; },
          set: function () {}
        });
      } catch (e) {}
    });
    try {
      if (typeof MediaDevices !== 'undefined' && MediaDevices.prototype.getUserMedia) {
        MediaDevices.prototype.getUserMedia = __fpSpoof(function () {
          return Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
        }, 'getUserMedia');
      }
    } catch (e) {}
  }

  /* ===== 3. Canvas 噪声（2% 像素 R 通道 +1） ===== */
  if (__CANVAS_NOISE__) {
    var __fpNoiseCalls = 0;
    function __fpNoisyU8(px, salt) {
      __fpNoiseCalls++;
      var base = __fpNoiseCalls * 100003 + salt;
      for (var i = 0; i < px.length; i += 4) {
        if (__fpRnd(base + (i >> 2)) < 0.02) px[i] = (px[i] + 1) & 255;
      }
    }
    var __fpOrigGetImageData = CanvasRenderingContext2D.prototype.getImageData;
    CanvasRenderingContext2D.prototype.getImageData = __fpSpoof(function () {
      var d = __fpOrigGetImageData.apply(this, arguments);
      __fpNoisyU8(d.data, 0);
      return d;
    }, 'getImageData');

    // toDataURL：复制到离屏 canvas（走已被加噪的 getImageData），再用原始方法导出
    var __fpOrigToDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = __fpSpoof(function () {
      try {
        if (this.width > 0 && this.height > 0) {
          var c = document.createElement('canvas');
          c.width = this.width;
          c.height = this.height;
          var ctx = c.getContext('2d');
          if (ctx) {
            ctx.drawImage(this, 0, 0);
            var img = ctx.getImageData(0, 0, c.width, c.height);
            ctx.putImageData(img, 0, 0);
            return __fpOrigToDataURL.apply(c, arguments);
          }
        }
      } catch (e) {}
      return __fpOrigToDataURL.apply(this, arguments);
    }, 'toDataURL');

    // toBlob：走加噪后的 toDataURL，再转回 Blob（保持回调签名）
    var __fpOrigToBlob = HTMLCanvasElement.prototype.toBlob;
    if (typeof __fpOrigToBlob === 'function') {
      HTMLCanvasElement.prototype.toBlob = __fpSpoof(function (cb) {
        var args = Array.prototype.slice.call(arguments, 1);
        function done(blob) {
          if (typeof cb === 'function') { try { cb(blob); } catch (e2) {} }
        }
        try {
          var dataUrl = HTMLCanvasElement.prototype.toDataURL.apply(this, args);
          var comma = dataUrl.indexOf(',');
          var mime = dataUrl.substring(0, comma).split(':')[1].split(';')[0];
          var bin = atob(dataUrl.substring(comma + 1));
          var u8 = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
          done(new Blob([u8], { type: mime }));
        } catch (e) {
          __fpOrigToBlob.apply(this, arguments);
        }
      }, 'toBlob');
    }

    // WebGL readPixels 同样加噪（WebGL 指纹常用它读像素）
    function __fpPatchReadPixels(proto) {
      if (!proto || typeof proto.readPixels !== 'function') return;
      var __origRp = proto.readPixels;
      proto.readPixels = __fpSpoof(function () {
        __origRp.apply(this, arguments);
        try {
          var px = arguments[5];
          if (px && px.length) __fpNoisyU8(px, 7);
        } catch (e) {}
      }, 'readPixels');
    }
    if (typeof WebGLRenderingContext !== 'undefined') __fpPatchReadPixels(WebGLRenderingContext.prototype);
    if (typeof WebGL2RenderingContext !== 'undefined') __fpPatchReadPixels(WebGL2RenderingContext.prototype);
  }

  /* ===== 4. WebGL vendor / renderer 伪装（含 WebGL2） ===== */
  (function () {
    function patch(proto) {
      if (!proto || typeof proto.getParameter !== 'function') return;
      var __gp = proto.getParameter;
      proto.getParameter = __fpSpoof(function (p) {
        if (p === 37445) return __VENDOR__;   /* UNMASKED_VENDOR_WEBGL */
        if (p === 37446) return __RENDERER__; /* UNMASKED_RENDERER_WEBGL */
        return __gp.call(this, p);
      }, 'getParameter');
    }
    if (typeof WebGLRenderingContext !== 'undefined') patch(WebGLRenderingContext.prototype);
    if (typeof WebGL2RenderingContext !== 'undefined') patch(WebGL2RenderingContext.prototype);
  })();

  /* ===== 5. navigator / screen ===== */
  __fpDefGetter(Navigator.prototype, 'hardwareConcurrency', __CORES__);
  __fpDefGetter(Navigator.prototype, 'deviceMemory', __MEM__);
  __fpDefGetter(Navigator.prototype, 'platform', __PLATFORM__);
  __fpDefGetter(Navigator.prototype, 'language', __LANG0__);
  __fpDefGetter(Navigator.prototype, 'languages', __LANGS__);
  if (typeof Screen !== 'undefined') {
    __fpDefGetter(Screen.prototype, 'width', __W__);
    __fpDefGetter(Screen.prototype, 'height', __H__);
    __fpDefGetter(Screen.prototype, 'availWidth', __W__);
    __fpDefGetter(Screen.prototype, 'availHeight', __H__);
    __fpDefGetter(Screen.prototype, 'colorDepth', 24);
    __fpDefGetter(Screen.prototype, 'pixelDepth', 24);
  }
  __fpDefGetter(window, 'devicePixelRatio', __DPR__);

  /* ===== 6. 时区 ===== */
  var __fpTz = __TZ__;
  var __fpOrigResolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions;
  Intl.DateTimeFormat.prototype.resolvedOptions = __fpSpoof(function () {
    var o = __fpOrigResolvedOptions.call(this);
    try { o.timeZone = __fpTz; } catch (e) {}
    return o;
  }, 'resolvedOptions');

  // 用 Intl 反推目标时区相对 UTC 的偏移（分钟，目标 UTC+8 => +480）
  function __fpTzOffsetMinutes(date) {
    try {
      var dtf = new Intl.DateTimeFormat('en-US', {
        timeZone: __fpTz, hour12: false,
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      });
      var parts = dtf.formatToParts(date);
      var m = {};
      for (var i = 0; i < parts.length; i++) m[parts[i].type] = parts[i].value;
      var asUTC = Date.UTC(+m.year, +m.month - 1, +m.day, (+m.hour) % 24, +m.minute, +m.second);
      return Math.round((asUTC - date.getTime()) / 60000);
    } catch (e) {
      return 0;
    }
  }
  Date.prototype.getTimezoneOffset = __fpSpoof(function () {
    /* getTimezoneOffset 定义：本地时间 = UTC - 返回值；目标 UTC+8 => 返回 -480 */
    return -__fpTzOffsetMinutes(this);
  }, 'getTimezoneOffset');
  var __fpOrigDateToString = Date.prototype.toString;
  Date.prototype.toString = __fpSpoof(function () {
    try {
      return new Intl.DateTimeFormat('en-US', {
        timeZone: __fpTz, weekday: 'short', year: 'numeric', month: 'short',
        day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
        hour12: false, timeZoneName: 'shortOffset'
      }).format(this).replace(/,/g, '');
    } catch (e) {
      return __fpOrigDateToString.call(this);
    }
  }, 'toString');

  /* ===== 7. AudioContext 噪声（getChannelData 加微小扰动） ===== */
  if (__AUDIO_NOISE__) {
    try {
      var __fpOrigGetChannelData = AudioBuffer.prototype.getChannelData;
      AudioBuffer.prototype.getChannelData = __fpSpoof(function (ch) {
        var d = __fpOrigGetChannelData.call(this, ch);
        var out = new Float32Array(d.length);
        var salt = ((ch | 0) + 1) * 7919;
        for (var i = 0; i < d.length; i++) {
          out[i] = d[i] + (__fpRnd(i * 31 + salt) - 0.5) * 0.0002;
        }
        return out;
      }, 'getChannelData');
    } catch (e) {}
  }
})();
"""
}
