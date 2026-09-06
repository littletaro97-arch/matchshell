# MatchShell 架构审查与改进建议

> 审查范围：当前 `E:\课外项目\matchshell` 工程
> 审查目的：从"临时调试壳"转向"可发布、可长期维护的移动端 APP"
> 审查立场：严苛找漏洞、不默认肯定、不堆过度设计

---

## 一、当前架构一句话

MatchShell 是一个**极简 Android WebView 壳**：用原生 Kotlin 搭一个全屏窗口，里面塞一个 WebView，加载火柴公益网站。网站负责所有业务逻辑，壳只负责"显示网页 + 系统级桥接"。

这个方向**本身没错**，尤其适合小团队/单人维护。鸿蒙 Next 目前对 Android APK 兼容性好，也得益于这种"壳足够薄"的设计。但薄不等于能直接发布，下面按优先级列出问题。

---

## 二、高优先级：不做就称不上"真正 APP"的事

### 1. 身份问题：包名、应用名、版本号都在说"我是调试工具"

| 项目 | 当前 | 问题 |
|------|------|------|
| 包名 | `com.matchcharity.debugshell` | 含 `debugshell`，无法作为生产包 |
| 版本号 | `0.1.0` / `versionCode 1` | 没有版本演进机制 |
| 应用名 | `火柴公益` | 应用市场里和无数个同名慈善项目撞车 |
| Activity 标签 | 无 | 桌面上只显示应用名，没有独立 Activity 标题 |

**建议**：
- 包名改为 `com.matchcharity.android.app` 或 `org.matchcharity.app`，把 `debug` 字样彻底清掉。
- 建立版本号规则：`versionName` 用 `MAJOR.MINOR.PATCH`，`versionCode` 每次发版递增。建议当前阶段从 `1.0.0` / `versionCode 1000000` 起（用 `versionCode = major*10000 + minor*100 + patch`，避免后续混乱）。
- 应用名考虑差异化，例如"火柴公益 - 让爱心延续"，既保留品牌又降低市场混淆风险。

### 2. 网络安全策略：现在是一个"裸奔的 WebView"

**当前风险**：
- `AndroidManifest.xml` 开全局明文流量：`android:usesCleartextTraffic="true"`。
- `WebSettings` 开了 `allowFileAccess = true`、`allowContentAccess = true`。
- `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE`，不是最严格的 `MIXED_CONTENT_NEVER_ALLOW`。
- 没有 `network_security_config.xml`。

**建议**：
- 立刻为调试和生产做 flavor 区分。
  - `debug` flavor 才允许 `usesCleartextTraffic` + HTTP URL。
  - `release` flavor 默认只允许 HTTPS，且通过 `network_security_config.xml` 只放行 `hcgy2018.site`。
- `release` 关闭 `allowFileAccess` / `allowContentAccess`。
- 考虑把 `mixedContentMode` 在 release 下设为 `MIXED_CONTENT_NEVER_ALLOW`。

### 3. 签名与构建：只有 debug，没有 release

当前 `build.gradle.kts` 里：
- 没有 `signingConfig`。
- `release` buildType 只配置了 `isMinifyEnabled = false`，连 shrinkResources 都没提。
- 没有 `proguard-rules.pro` 文件。

**建议**：
- 生成一个 release 签名密钥（jks/keystore），写入 `~/.gradle/gradle.properties` 或本地安全文件，通过环境变量注入。
- 配置 release signing：
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(System.getenv("MATCHSHELL_KEYSTORE") ?: "release.keystore")
          storePassword = System.getenv("MATCHSHELL_KEYSTORE_PASSWORD")
          keyAlias = System.getenv("MATCHSHELL_KEY_ALIAS")
          keyPassword = System.getenv("MATCHSHELL_KEY_PASSWORD")
      }
  }
  ```
- 至少做一次 `assembleRelease` 并验证 APK 能被系统安装，不要等到要发版那天才发现问题。

### 4. 图标资源：只在 API 26+ 上"看起来正常"

当前只有 `mipmap-anydpi-v26/ic_launcher.xml` 和 `ic_launcher_round.xml`，依赖自适应图标。

**问题**：
- Android 8.0 以下设备会拿不到图标，系统回退行为不可控。
- `ic_launcher_foreground.png` 是一个带透明底 PNG，直接当单色层（monochrome）用。**颜色会全部变成单一颜色 + 透明度**，现在这个 Logo 有红色、黄色、白色，单色化后可能成一团灰雾。
- 缺少按密度分发的 PNG：`mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi`。

**建议**：
- 补上 `mipmap-mdpi` 到 `mipmap-xxxhdpi` 的 PNG（纯色背景 + Logo 居中）。
- 如果要做单色图标，单独做一份只有轮廓线的矢量/PNG，不要把彩色 Logo 直接当 monochrome。
- 启动页 splash 也建议补一个（哪怕只是 Logo + 背景色的静态 Activity），避免 WebView 白屏前用户以为卡死。

---

## 三、中优先级：体验、稳定、可维护

### 5. WebView 配置还有残留调试配置（已修复）

```kotlin
setSupportZoom(false)
builtInZoomControls = false
mediaPlaybackRequiresUserGesture = true  // release
```

**状态**：
- 缩放已彻底关闭，双指/双击缩放都不会触发。
- release 下 `mediaPlaybackRequiresUserGesture` 恢复为 `true`，避免后台音频/视频自动播放被应用商店拒审。
- debug 下仍保留自动播放与文件访问，方便局域网调试。

### 6. 错误页/白屏处理太粗糙

当前逻辑：
- `onReceivedError` 只在主框架错误时显示 `error_view`。
- 资源页（如 `/resources/`）如果 CSS/JS 加载失败、或者网站响应慢，不会触发 `onReceivedError`，用户只看到白屏 + 滚动条。
- 没有超时机制。
- 没有网络变化监听（切 WiFi/关流量后不会自动重试）。

**建议**：
- 增加加载超时：在 `onPageStarted` 时启动 Handler，若 `onPageFinished` 超 X 秒未到，显示"加载慢，重试"提示。
- 监听 `CONNECTIVITY_ACTION` 或 `NetworkCallback`，网络恢复时自动重试（仅限用户之前失败过）。
- 增加一个"强制刷新/清缓存重开"的入口（比如长按刷新按钮之外再加一个"清除缓存"选项，或者双击 FAB）。

### 7. 返回键与网页导航边界

当前：
```kotlin
if (web.canGoBack()) web.goBack() else finish()
```

**问题**：
- 如果当前页面通过 `history.replaceState` 把栈搞乱了，`canGoBack()` 可能不准。
- 某些 SPA 路由变化不会触发 WebView 历史栈，按返回会误以为在首页而退出。
- 用户编辑表单时误触返回，直接退出。

**建议**：
- 增加"再按一次返回才退出"的防抖，或根据页面可见的 UI 元素判断。
- 在 JS 层暴露一个桥接，让网站主动告知"当前页面是否允许返回退出"。例如：
  ```javascript
  // 网页调用
  window.MatchShell?.setBackExitEnabled?.(true)
  ```
  这样网站自己决定哪些页面可以返回退出。

### 8. 文件上传/下载的边界情况

文件上传：
- 调起文件选择器后，如果用户点击取消，`fileCallback?.onReceiveValue(null)` 已经处理，OK。
- 但**取消时没有清空 `fileCallback`**，如果用户再次点上传，旧 callback 还在，可能导致内存泄漏或异常。

下载：
- `guessFileName` 的正则 `filename\*?="?([^";]+)"?` 对 `filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf` 这种 RFC 5987 格式**覆盖不完整**。例如 `filename*=UTF-8''name.pdf` 会被当普通 `filename` 解析，取到 `UTF-8''name.pdf`。
- Android 10+ 用 `setDestinationInExternalPublicDir` 需要 `WRITE_EXTERNAL_STORAGE` 吗？实际上 DownloadManager 自己会用 `MediaStore`，但**通知权限**在 Android 13+ 需要 `POST_NOTIFICATIONS`，否则用户看不到下载完成通知。
- 下载失败没有重试或提示具体原因。

**建议**：
- 严格区分 `filename` 和 `filename*` 解析逻辑。
- Android 13+ 动态申请 `POST_NOTIFICATIONS`。
- 下载失败时尝试降级为浏览器打开。

---

## 四、低优先级 / 未来可扩展

### 9. 鸿蒙兼容性不是"不用管"，而是"主动保持薄"

既然现在能在鸿蒙上跑，说明 Android 兼容层够用了。但下面几点会让未来更稳：
- 不要用被华为/鸿蒙列为"不推荐"的 API（如 `DownloadManager` 在部分鸿蒙设备上行为有差异）。
- 避免读取 Android 系统私有目录、剪切板敏感权限。
- 目标 SDK 不要一下子跳到最新，先观察鸿蒙兼容层对 `targetSdk 35` 的适配情况。

### 10. 缺少 APP 级能力

| 能力 | 现状 | 是否需要 |
|------|------|---------|
| 推送通知 | 无 | 公益类 APP 常用，但目前网站就能满足，不急 |
| 离线缓存 | 无 | WebView 默认有 HTTP 缓存，但缺少 Service Worker 或本地缓存策略 |
| 分享 | 无 | 网页内实现即可 |
| Deep Link / App Link | 无 | 想从短信/微信跳转打开 APP 时需要 |
| 崩溃收集 | 无 | 现阶段用 Google Play 的 crash reporting 或自己写日志上传 |
| 应用内更新 | 无 | 非应用商店分发时需要 |

**建议**：
- 这些能力**不要现在做**，但要预留接口位置（例如一个 `BridgeInterface` 或 `JsBridge` 类），避免以后在 `MainActivity` 里堆代码。

### 11. 代码组织：所有逻辑都在 MainActivity

当前 `MainActivity.kt` 315 行，已经同时承担：
- Activity 生命周期
- WebView 配置
- 系统栏 insets
- 下载、上传、错误处理、URL 弹窗、返回键

**建议**：
- 拆出 `WebViewConfigurator`、`DownloadHelper`、`FileChooserHelper`、`InsetsHelper` 等独立类。
- 不是为了炫技，而是为了 3 个月后改某一块时不会打翻全局。

---

## 五、立刻可以动手的最小改动清单

如果想从"调试壳"到"能见人"的第一步，按这个顺序做：

1. **改包名、应用名、版本号**（`build.gradle.kts` + `AndroidManifest.xml` + `strings.xml`）。
2. **分 debug/release 网络安全策略**：
   - release 删掉 `usesCleartextTraffic`。
   - 新增 `network_security_config.xml`。
3. **补上各密度启动图标**，并修正 monochrome 图标。
4. **配置 release 签名**。
5. **跑一次 `assembleRelease`**，确认能装能跑。
6. **清理 WebView 调试残留**：缩放控制、自动播放、文件访问。
7. **处理下载通知权限**和 `filename*` 解析。
8. **增加加载超时与网络恢复自动重试**。
9. **把 MainActivity 里的功能按职责拆小类**。

---

## 六、不需要做的事（避免过度设计）

- ❌ 现在上 Flutter / React Native / 原生鸿蒙 ArkTS：当前网站能在 WebView 里稳定跑，换框架收益极低。
- ❌ 复杂的状态管理、依赖注入：一个 WebView 壳不值得。
- ❌ 自己的离线包更新系统：先把 WebView + 网站缓存做好再说。
- ❌  push 推送 / 埋点 / 用户系统：等业务明确需要再做。

---

## 七、最大风险总结

1. **发布风险**：包名含 `debug`，没有 release 签名，全局明文流量，应用商店初审就可能被拒。
2. **安全风险**：`allowFileAccess` + `allowContentAccess` + 全局 cleartext，如果网站被注入恶意脚本，壳会放大危害。
3. **体验风险**：白屏/卡顿没有兜底，用户一旦遇到就是卸载。
4. **维护风险**：所有逻辑挤在一个 Activity，三个月后加入新功能时会越来越难改。

当前方向是对的，但**从"能跑"到"能发布"还差一层安全、打包、图标、错误处理的工作**。先做最小清单，不要一次堆太多。
