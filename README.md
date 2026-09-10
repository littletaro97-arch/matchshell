# MatchShell — 火柴公益全屏调试壳

一个只有 WebView 的 Android 壳，用于在手机上全屏查看火柴公益网站（去掉浏览器地址栏/工具栏），
方便移动端 debug。不是给外部用户用的产品，是开发者的查看器。

- 正式包名 `com.hcgy2018.site`
- minSdk 26（Android 8.0）／targetSdk 35
- 当前稳定版基线：`1.0.0` / versionCode `100`，只维护 PDF 转换变体
- UI 仍保持无 appcompat；资源预处理使用 AndroidX ExifInterface 与 Media3 Transformer
- 默认打开 `https://hcgy2018.site/`（在 `res/values/strings.xml` 里改）

## 上游网站关系

MatchShell 是火柴公益网站的独立 Android 承载端，不是网站源码或运行数据副本。两项目的版本、接口和联调边界见 [UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md)；不要通过目录链接或复制网站工作树进行“同步”。

## 构建

本工程目录**没有 `gradlew` 脚本**（wrapper jar 在，但脚本没补）。直接调本机缓存里的 Gradle：

```bash
cd E:/课外项目/matchshell
export JAVA_HOME="D:/Program Files/Java/jdk-21"
export ANDROID_HOME="C:/Users/LittleTaro/AppData/Local/Android/Sdk"
GRADLE_BIN="C:/Users/LittleTaro/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle"
"$GRADLE_BIN" assembleDebug        # 第一次联网拉几个缺失的小 jar，之后可加 --offline
"$GRADLE_BIN" assembleRelease      # release 包已签名（临时密钥），发版前需替换
"$GRADLE_BIN" lintDebug            # 静态检查
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境：JDK 21、Android SDK（build-tools 37 / platform 36）已在 `C:\Users\LittleTaro\AppData\Local\Android\Sdk`。

每次完成一个功能都会提交到 Git；回滚用 `git log` + `git checkout`。

## 用法

| 操作 | 效果 |
|---|---|
| 点右上角菜单 | 刷新 / 资源预处理 / 文件池 / 改调试地址 / 检查更新 |
| 文件池「导入 PDF」 | 把手头已有的 PDF 直接放进文件池，不经转换（网页上传只认文件池，这是必要入口） |
| 菜单 →「改调试地址」 | 弹出最近 5 条历史地址，点击直接加载；每条右侧「×」可单条删除；选「手动输入…」可新增 |
| 返回键 | 网页内后退；若网站通过 `MatchShell.setBackHandler` 注册处理器，优先由网站决定 |
| 下载文件 | Android 13+ 会先申请通知权限；中文文件名使用 `filename*` 解码 |

## 资源预处理（首版）

- 照片：校正 EXIF 旋转，最长边限制为 1920 px，导出 JPEG（质量 82）。
- 视频：使用 Android 官方 Media3 Transformer 转为 720p H.264/AAC MP4，支持进度与取消。
- PPT 转 PDF：入口仅说明能力边界，尚未实现。网站依赖 Windows LibreOffice `soffice`，不能直接打入 Android APK；需独立验证可信的 Android 文档渲染方案。
- 输出自动写入 **APP 私有目录**（`Android/data/com.hcgy2018.site/files/Download/火柴公益文件池/`）；
  中间文件位于 APP 缓存，完成或取消后清理。
- **网页上传只认文件池**：文件选择器直接进入文件池，不再提供"浏览其他文件"入口；
  想上传的文件必须先经资源预处理进池。多选上传时点选多个文件后按「提交已选」。
- 私有目录的取舍：文件不进系统相册、也不进文件管理器的最近列表，用户设备上不会出现第二份同款文件；
  代价是**卸载 APP 会一并删除**（文件池页面有常驻提示）。文件经 `FileProvider` 以 content URI 交给 WebView 上传。
- 从预处理页返回文件池会自动重扫目录，新产物无需手动刷新。

### v1.0.0 PDF 转换版

- 后续只维护 PDF 转换版：包含照片/视频压缩、文件池网格缩略图、长按删除及 arm64 端侧 DOCX/PPTX/XLSX→PDF。
- 资源预处理页采用 WorkBuddy HTML 原型的暖米色卡片布局，卡片和“前往文件池”均已连接现有原生功能。
- PDF 转换引擎来自 Apache-2.0 `office2pdf` 的 Android JNI 构建；输入上限 64 MiB，复杂排版属于待实机验证范围。

改网址是核心用法：局域网调试时填 `http://192.168.x.x:8880/`，正式站填 `https://hcgy2018.site/`。

## APP 内更新

- 启动 3 秒后后台检查，每 24 小时最多自动检查一次；右上角菜单可手动“检查更新”。
- 更新清单：`https://littletaro97-arch.github.io/matchshell/updates/stable.json`。
- APK 来自 GitHub Releases；下载后校验清单 RSA 签名、APK SHA-256、包名、versionCode 和签名证书。
- Android 8+ 首次更新需要允许本 APP 安装未知来源，安装动作仍由系统确认。
- 发布步骤和 GitHub Secrets 见 [docs/github-update-release.md](docs/github-update-release.md)。

### JS 桥接（网站侧）

```javascript
// 注册一个返回键处理函数
window.MatchShell.setBackHandler('onMatchShellBack');

window.onMatchShellBack = function() {
    if (当前弹窗打开) {
        关闭弹窗();
        return true;   // 消费返回键，APP 不退出
    }
    return false;      // APP 继续走 canGoBack / 退出
};
```

也可调用 `window.MatchShell.finishApp()` 主动退出、`window.MatchShell.reload()` 刷新。
`window.MatchShell.getAppVersion()` 返回壳版本（如 `1.0.0`），`isMatchShell()` 恒为 true。

## 重连策略

服务器不稳定时不允许反复请求，所以**没有定时重试**：

| 场景 | 行为 |
|---|---|
| 加载失败 / 15 秒超时 | 显示错误页 + 重试按钮，**不自动重试** |
| 服务器已应答（HTTP 4xx / 5xx） | 同样只提示，**绝不自动重试**——后端有问题，重试没有意义 |
| 系统报告网络恢复 / 切换 | 自动重试一次；两次自动重试之间强制间隔 **30 秒**（防网络抖动） |
| 用户点「重试」 | 立即重载；加载期间按钮禁用，不会叠出第二次请求 |

判断依据：`onReceivedError`（网络类，可自动重试）与 `onReceivedHttpError`（服务器已应答，不可自动重试）分开处理。

## 网站识别壳（APP 模式的基础）

壳向暴露两层信息，网站据此渲染 APP 模式（隐藏顶部导航、底部固定入口、放大触摸目标）：

1. **User-Agent**：系统默认 UA 末尾追加 `MatchShell/<版本名>`，例如
   `Mozilla/5.0 (Linux; Android 16; PKT110 ...) ... Chrome/151.0.7922.199 ... MatchShell/1.0.0`。
   服务端可据此直接在模板上输出 `data-app-mode`，**不需要等 JS 执行**，是首选判定方式。
2. **JS 桥接**：页面内 `typeof window.MatchShell !== "undefined"` 即表示在壳内运行。

另外，壳会在每个页面的 `<html>` 上写入安全区变量（单位 CSS px，随旋转/挖孔变化自动更新）：

```css
.bottom-nav {
    padding-bottom: calc(12px + var(--ms-safe-bottom, 0px));
}
```

可用变量：`--ms-safe-top` / `--ms-safe-bottom` / `--ms-safe-left` / `--ms-safe-right`。
取值为"忽略系统栏可见性"的 insets，所以用户临时滑出系统栏时底部条不会跟着跳动。
壳是全屏沉浸式，手势条区域常驻，底部固定元素必须避让，否则会被手势条压住。

## 为什么不用 PWA

PWA 要求安全上下文（HTTPS 或 localhost）。局域网 `http://192.168.x.x:8765` 不是安全上下文，
manifest 不生效，装不上，也就没有 standalone 全屏。调试主要在本地跑，所以走原生壳。

## 已处理的坑

这几条不处理，APP 就是残废的：

1. **返回键** — 没有地址栏就没有后退按钮。已接到 `web.goBack()`，否则用户在子页面按返回会直接退出。
2. **文件上传** — `resources/upload.html`、`home_editor.html` 有 `<input type="file" multiple>`。
   WebView 默认不提供文件选择器，点上传毫无反应。已实现 `onShowFileChooser` + 多选。
3. **下载带登录态** — Django 的资源下载需要会话。`DownloadManager` 跑在独立进程、不带 WebView 的
   cookie，直接下载会拿到登录页。已把 `CookieManager` 的 cookie 注入请求头。
4. **中文文件名** — 从 `Content-Disposition` 解析 `filename*`（RFC 5987）并 URL 解码，
   否则中文文件名会乱码。
5. **旋转重建** — manifest 里声明了 `configChanges`，旋转屏幕不会重建 Activity，
   WebView 的滚动位置和页面状态不丢。
6. **远程调试** — `BuildConfig.DEBUG` 下开 `WebView.setWebContentsDebuggingEnabled(true)`，
   电脑上打开 `chrome://inspect` 就能审查手机里的页面元素。
7. **系统栏遮挡** — 默认隐藏状态栏和导航栏，内容延伸至刘海/挖孔/手势区域；
   只调整右上角刷新按钮的边距，避免被状态栏/刘海压住。
8. **加载白屏** — 主文档 15 秒未完成视为超时，按错误码给出具体提示。
   重试策略见下节「重连策略」：不做定时重试，只在网络恢复时最多自动重试一次。
9. **本地调试硬编码链接** — 页面内写死的 `https://hcgy2018.site/...` 链接，
   在调试地址下会被自动重定向到当前调试服务器。
10. **下载通知权限** — Android 13+ 先申请 `POST_NOTIFICATIONS`；被拒绝仍继续下载并提示用户。

## 服务端这边要注意

`matchsite/settings.py` 里：

```python
if MATCH_SITE_HTTPS:
    SECURE_SSL_REDIRECT = True
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_HSTS_SECONDS = 31536000
```

- **局域网 HTTP 调试**：必须用 `MATCH_SITE_HTTPS=0` 启动，并把该 IP 加进 `ALLOWED_HOSTS`。
  开着 HTTPS 模式时，`SECURE_SSL_REDIRECT` 会把 `http://192.168.x.x` 重定向到 `https://192.168.x.x`，
  证书不匹配直接失败。
- **HSTS 记 1 年**：只要这台手机的 WebView 访问过 `hcgy2018.site`，一年内对该域名强制 HTTPS，
  手输 http 无效。要清就得在 Chrome 里访问 `chrome://net-internals/#hsts` 删除域，或者直接清 APP 数据。
  用 IP 调试不受影响（HSTS 按域名生效）。
- **网站维护入口**：当前为 `E:\火柴公益官网建设-全新架构`；双击其中 `启动手机联调.bat`，使用窗口显示的网址。旧 `.worktrees/v0.8.13.2.1` 仅作为迁移来源保留只读。
  本工程不依赖任何版本目录，只依赖一个 URL，所以不污染项目的只读版本线和 worktree。

## 发布前必须改

`AndroidManifest.xml` 里的 `android:usesCleartextTraffic="true"` 是为局域网 HTTP 调试开的。
要公开发布就删掉它，或改成 `network_security_config.xml` 限定域名。
