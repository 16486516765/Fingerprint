# Fingerprint

开源免费的 Android 多 Profile 隔离浏览器。

每个 Profile 拥有独立的 WebView 数据目录（Cookie、缓存、localStorage 完全隔离）、独立的 User-Agent 和独立的代理配置。一号一环境，互不串号。

> 能力边界说明：本应用是"多 Profile 数据隔离 + 自定义 User-Agent"浏览器，
> 不是完整反指纹浏览器。修改 UA 只改变部分浏览器标识，不改变 WebView 内核、
> Canvas/WebGL 指纹等其他维度（见问题17）。

## 功能（v1）

- **Profile 管理**：创建 / 切换 / 删除 / 重命名，分组管理
- **数据隔离**：每个 Profile 独立 WebView 数据目录
- **自定义 UA**：每个 Profile 可设不同 User-Agent，内置主流手机 UA 预设
- **独立代理**：每个 Profile 独立代理配置（HTTP / SOCKS5 / 直连）
  - SOCKS5 用户名密码认证暂不支持（WebView 公开接口限制）
- **Favicon 识别**：可选，默认关闭（隐私优先），开启后自动读取绑定网站的 favicon
- **深色主题**：纯黑 Material 3 界面

## 网络策略

- 仅允许 http/https 网页浏览（about:blank 除外），file/intent/javascript 等 scheme 会被阻止
- 禁止明文 HTTP 流量（usesCleartextTraffic=false），http:// 网站无法访问
- 代理失败时默认阻止加载（fail-closed），需手动选择"仍然直连"才放行

## 免责声明

本工具仅用于合法的多账号隔离管理（如跨境电商多店铺、社交媒体矩阵号等合规场景）。请遵守各平台服务条款，开发者不对账号封禁等后果负责。
