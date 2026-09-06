# matchshell — 接手工程事实

## 这工程做什么
一个 Android APP，全屏显示「火柴公益」网站（默认 `https://hcgy2018.site/`，可在壳内改），
用户在 OPPO 平板上以无浏览器 UI 方式查看网页。**仅供开发者本人 debug，不是面向用户的产品。**

## 位置
- 工程根：`E:\课外项目\matchshell\`
- Git 仓库：已初始化，当前分支 `main`
- APK 产物：`E:\课外项目\matchshell\app\build\outputs\apk\debug\app-debug.apk` / `...\release\app-release.apk`
- 包名：`com.hcgy2018.site`
- 主 Activity：`com.hcgy2018.site.MainActivity`
- release 签名：`C:\Users\LittleTaro\.android\matchshell-release.keystore`（临时，发版前必须替换为自己的正式密钥）

## 设备（adb target）
- OPPO PKT110（ColorOS 16 / Android 16）
- 系统 WebView：**仅** `com.google.android.webview` 151.0.7922.199（无 beta/dev/canary/其他 provider）
- 物理分辨率 1216×2640，dpr 3.5
- 序列号 `XCU47H455LJJUODQ`
- adb 全路径：`C:/Users/LittleTaro/AppData/Local/Android/Sdk/platform-tools/adb.exe`

## 当前代码状态

### `app/src/main/java/com/hcgy2018/site/MainActivity.kt`
- `ComponentActivity`，无 appcompat
- 沉浸式全屏（`WindowCompat.setDecorFitsSystemWindows(window, false)` + insets 监听）
- `applyInsets()`：默认隐藏状态栏和导航栏，内容延伸至刘海/挖孔/手势区域；用户从顶部/底部滑入可临时显示系统栏；只调整 reload_fab 边距，不再给根容器加 padding
- 长按右下角浮窗 → 弹出最近 5 条历史地址列表，可点击直接加载；选择"手动输入…"进入编辑对话框（自动识别局域网地址补 `http://`，其他补 `https://`），存 SharedPreferences，保存后 Toast 提示新地址
- `OnBackPressedCallback`：优先调用网站注册的 JS 返回键处理器（`window.MatchShell.setBackHandler`）；未注册或返回 false 时，按 `webView.canGoBack()` 决定 `goBack()` 或 `finish()`
- `WebViewClient`：
  - `shouldOverrideUrlLoading`：非 http(s) scheme 跳外部浏览器；与当前地址同 host 在 WebView 内打开；目标为生产域名 `hcgy2018.site` 且当前处于调试地址时，自动重定向到调试地址，避免本地调试时页面硬编码生产链接跳浏览器
  - `onPageStarted` / `onPageFinished` / `onReceivedError`（诊断日志）
  - 主文档加载 15 秒未完成视为超时；错误页按错误码区分：域名解析 / 连接失败 / 超时 / 未知
- 网络恢复监听（`ConnectivityManager.NetworkCallback`）：错误页状态下网络可用时自动重试，最多 3 次；手动重试会清零计数
- `WebChromeClient.onShowFileChooser` → `registerForActivityResult(StartActivityForResult)` + 多选
- `setDownloadListener` → `DownloadManager`
  - Android 13+ 先申请 `POST_NOTIFICATIONS`，被拒绝仍继续下载
  - 自动注入 `CookieManager` cookie
  - 文件名解析使用 `URLUtil.guessFileName(url, disposition, mimeType)`，框架统一处理 `filename` / `filename*` / RFC 5987 中文
- `dispatchTouchEvent` 钩子打日志（log tag `matchshell-touch`，仅 `BuildConfig.DEBUG`）
- `web.setOnTouchListener` 打日志（不消费事件）
- `BuildConfig.DEBUG` 下 `WebView.setWebContentsDebuggingEnabled(true)`
- JS 桥接 `window.MatchShell`：
  - `MatchShell.setBackHandler(name)`：网站注册全局返回键处理函数，返回 true 表示消费返回键
  - `MatchShell.finishApp()`：退出 APP
  - `MatchShell.reload()`：刷新当前页面

### `app/src/main/res/layout/activity_main.xml`
- `FrameLayout` 根（match_parent × match_parent，背景色 `ic_launcher_background`）
- `WebView`（match_parent × match_parent）
- `LinearLayout error_view`（默认 `gone`，居中，含错误 `TextView` + 重试按钮）
- `Button reload_fab`（右下角，`alpha=0.45`，长按改 URL，点击 reload）

### `app/src/main/AndroidManifest.xml`（当前）
- permission：`INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS`
- application：
  - `usesCleartextTraffic="${usesCleartextTraffic}"`：debug 为 `true`（局域网 HTTP 调试需要），release 为 `false`
  - `networkSecurityConfig`：`debug` 允许所有明文；`release` 仅 `hcgy2018.site` 且强制 HTTPS
  - `hardwareAccelerated="true"`
  - `theme="@style/Theme.MatchShell"`
- activity `.MainActivity`：
  - `launchMode="singleTop"`
  - `windowSoftInputMode="adjustResize"`
  - `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`（旋转不重建）

### `gradle.properties`
- `android.overridePathCheck=true`（工程路径含中文需要）

### 依赖（`app/build.gradle.kts`）
- `androidx.core:core-ktx`
- `androidx.activity:activity-ktx:1.10.1`
- `androidx.lifecycle:lifecycle-*`
- **无 appcompat**

### 构建版本
- AGP 8.9.2 + Kotlin 2.1.21 + JDK 21 + Gradle 8.11.1（取自本机缓存，无 wrapper 脚本）
- minSdk 26 / targetSdk 35

## 构建

```bash
cd "E:/课外项目/matchshell"
export JAVA_HOME="D:/Program Files/Java/jdk-21"
export ANDROID_HOME="C:/Users/LittleTaro/AppData/Local/Android/Sdk"
export GRADLE_USER_HOME="C:/Users/LittleTaro/.gradle"
GRADLE_BIN="C:/Users/LittleTaro/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle"
"$GRADLE_BIN" assembleDebug

# release（临时签名，不能发版）
"$GRADLE_BIN" assembleRelease
```

第一次联网会拉几个缺失的小 jar；之后可加 `--offline`。

## 部署到设备

```bash
cp "E:/课外项目/matchshell/app/build/outputs/apk/debug/app-debug.apk" \
   "C:/Users/LittleTaro/AppData/Local/Temp/matchshell.apk"
ADB="C:/Users/LittleTaro/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r "C:/Users/LittleTaro/AppData/Local/Temp/matchshell.apk"
"$ADB" shell am force-stop com.hcgy2018.site
"$ADB" shell am start -n com.hcgy2018.site/.MainActivity
```

## 调试命令

```bash
ADB="C:/Users/LittleTaro/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# 触摸/页面日志
"$ADB" logcat -s matchshell-touch:*

# 找 APP pid（每次启动会变）
"$ADB" shell pidof com.hcgy2018.site

# 截屏
"$ADB" exec-out screencap -p > /tmp/shot.png

# CDP forward
"$ADB" forward tcp:9222 localabstract:webview_devtools_remote_<pid>

# 之后用 websocket 连 ws://localhost:9222/devtools/page/<id>，
#   连接时需 suppress_origin=True（WebView 默认拒绝带 origin 的连接）
```

## 历史 SharedPreferences 记录（实际设备需现场核对）
- URL：`http://172.30.223.124:8765/`（用户改成的局域网地址）
- 默认 `https://hcgy2018.site/`（在 `res/values/strings.xml` 的 `default_url`）
- 修改入口：长按右下角 reload FAB

## 网站项目迁移后的关联入口（2026-09-05）
- 网站正式维护目录：`E:\火柴公益官网建设-全新架构`；契约见本项目 `UPSTREAM_CONTRACT.md`。
- 手机联调：双击网站根目录 `启动手机联调.bat`，将窗口显示的完整 URL 填入壳；旧 IP/8765 只是历史记录，不作为当前默认联调地址。
- 网站源码、数据、账号与壳的 Android 构建分别维护。网页版本迭代不默认重建 APK；全屏/WebView 原生行为变化才评估壳改动。
- 本文 PKT110 与网站旧计划 PTK110 存在型号差异，必须以实际设备「关于本机」确认；历史 WebView provider/版本和 APK 文件也不当作手机实时状态。

## 已遇到并处理的具体技术约束

| 现象 | 已用方案 |
|---|---|
| 工程路径 `E:\课外项目\` 含中文，AGP 拒绝构建 | `gradle.properties` 加 `android.overridePathCheck=true` |
| PATH 里的 `java.exe` 指向 Oracle shim，不是真 JDK | `JAVA_HOME="D:/Program Files/Java/jdk-21"` 显式设 |
| 编译产物路径含中文 → `adb install` 静默失败 | cp APK 到 `C:/Users/LittleTaro/AppData/Local/Temp/` 再 install |
| OPPO 平板远程 `adb install` 卡在指纹/勾选弹窗 | 当前状态：APK 已经装好；如需重装要走现场人机交互流程 |
| WebView CDP 拒绝 `http://localhost:9222` / `devtools://devtools` origin | websocket 连接时 `suppress_origin=True` |
| 系统只有 1 个 WebView provider（151.0.7922.199） | 无 beta/dev/canary 可切；想换内核需先装 |
| ADB USER `LittleTaro`，存在 `C:\Users\杨宇田\` 另一账户 | 处理文件前确认路径 |
| adb 命令 PowerShell 工具在本机返回空输出 | 走 Git Bash（已配 `BASH_DEFAULT_TIMEOUT_MS`） |

## 已实现的壳能力

- 沉浸式全屏（状态栏/导航栏隐藏，内容延伸至刘海/挖孔/手势区域）
- 长按改 URL，支持最近 5 条历史地址（SharedPreferences 持久化）
- 系统返回键：优先网站 JS 处理器，否则 `webView.goBack()` / `finish()`
- 同 host 留 WebView，跳 host 用外部浏览器
- 本地调试时自动把页面内 `hcgy2018.site` 硬编码链接重定向到当前调试地址
- `onShowFileChooser` 文件多选上传
- `DownloadManager` 下载（Android 13+ 通知权限 + cookie + RFC 5987 中文文件名）
- 加载超时 / 错误分类 / 网络恢复自动重试（最多 3 次）
- `BuildConfig.DEBUG` 下开启 WebView 远程调试（`chrome://inspect`）
- 旋转不重建 Activity（manifest `configChanges`）
- 触摸全链路诊断日志（`ACT` / `WV` / `WV LOAD` / `WV PGSTART` / `WV PGFIN` / `WV ERR`）
