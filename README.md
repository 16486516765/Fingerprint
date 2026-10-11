# Fingerprint

开源免费的 Android 多 Profile 隔离浏览器。

每个 Profile 拥有独立的 WebView 数据目录（Cookie、缓存、localStorage 完全隔离）、独立的 User-Agent、独立的代理配置和独立的指纹伪装。一号一环境，互不串号。

> 指纹伪装说明（§4.3）：本应用通过 JS 注入伪装 Canvas、WebGL、navigator、时区等
> 指纹维度，能挡住大多数基础指纹关联检测。但挡不住专业检测（会检测 toString、
> 原型链、渲染差异、Worker 内指纹等）。要做到真正级别只能换内核（自编译 Chromium）。
> 对手机端个人/小团队用途，当前方案已是性价比最高的选择。

## 功能（v1.2）

- **Profile 管理**：创建 / 切换 / 删除 / 重命名 / 克隆，分组管理
- **数据隔离**：每个 Profile 独立 WebView 数据目录（独立进程 :wv0~:wv5）
- **指纹伪装**：每个 Profile 独立指纹种子（fpSeed），Canvas 噪声、WebGL vendor/renderer、
  navigator（核心数/内存/语言）、屏幕分辨率、时区伪装；内置 Pixel 8 / Galaxy S24 /
  Pixel Tablet / 一加 12 真实设备模板 + 自定义
- **动态 UA**：UA 按 WebView 实际版本动态生成（Chrome/{实际版本}），不再用写死的旧版本
- **独立代理**：每个 Profile 独立代理配置（HTTP / SOCKS5 / 直连）
  - SOCKS5 用户名密码认证：通过本地中继（127.0.0.1）支持
  - 代理失败时默认阻止加载（fail-closed），需手动选择"仍然直连"才放行
  - 状态栏显示 WebView 实测出口 IP（非 Java 层测试）
- **WebRTC 防泄露**：默认禁用 RTCPeerConnection，防止真实 IP 经 STUN 泄露
- **多标签**：每个 Profile 内多标签页（共享 Cookie），标签切换器
- **文件上传/下载**：`<input type=file>` 系统选择器；下载经代理（不用 DownloadManager），支持 blob:
- **JS 弹窗**：alert/confirm/prompt 系统对话框
- **Cookie 导入/导出**：支持 EditThisCookie JSON / Netscape / name=value 格式
- **Profile 加密导出/导入**：PBKDF2 + AES-GCM，密码保护
- **应用锁**：指纹/设备凭据验证，后台超 2 分钟重新验证
- **防截屏**：FLAG_SECURE 可选，防最近任务缩略图泄露
- **运行状态**：列表页显示哪些 Profile 正在运行，一键关闭进程
- **Favicon 识别**：可选，默认关闭（隐私优先）
- **深色主题**：纯黑 Material 3 界面

## 网络策略

- 仅允许 http/https 网页浏览（about:blank 除外），file/intent/javascript 等 scheme 会被阻止
- 禁止明文 HTTP 流量（usesCleartextTraffic=false），http:// 网站无法访问
- 代理失败时默认阻止加载（fail-closed），需手动选择"仍然直连"才放行

## 免责声明

本工具仅用于合法的多账号隔离管理（如跨境电商多店铺、社交媒体矩阵号等合规场景）。请遵守各平台服务条款，开发者不对账号封禁等后果负责。
