# MatchShell — 火柴公益全屏调试壳

一个只有 WebView 的 Android 壳，用于在手机上全屏查看火柴公益网站（去掉浏览器地址栏/工具栏），
方便移动端 debug。不是给外部用户用的产品，是开发者的查看器。

- 包名 `com.hcgy2018.site`
- minSdk 26（Android 8.0）／targetSdk 35
- 版本号 `0.1.0` / versionCode `1`
- 依赖只有 `core-ktx` + `activity-ktx`（最小化，能不要 appcompat 就不要）
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
| 点右下角半透明「刷新」 | 重新加载当前页 |
| **长按**「刷新」 | 弹出最近 5 条调试地址，点击直接加载；选「手动输入…」可新增地址 |
| 返回键 | 网页内后退；若网站通过 `MatchShell.setBackHandler` 注册处理器，优先由网站决定 |
| 下载文件 | Android 13+ 会先申请通知权限；中文文件名使用 `filename*` 解码 |

改网址是核心用法：局域网调试时填 `http://192.168.x.x:8880/`，正式站填 `https://hcgy2018.site/`。

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
8. **加载白屏** — 主文档 15 秒未完成视为超时，按错误码给出具体提示；
   网络恢复时自动重试，最多 3 次；手动重试会清零计数。
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
