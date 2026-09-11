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
- **无 appcompat**；资源预处理新增 ExifInterface 与 Media3 Transformer/Effect/Common

### 构建版本
- AGP 8.9.2 + Kotlin 2.1.21 + JDK 21 + Gradle 8.11.1（取自本机缓存，无 wrapper 脚本）
- compileSdk 36 / minSdk 26 / targetSdk 35

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
- 修改入口：长按右上角 reload 按钮

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

- 沉浸式全屏（状态栏/导航栏隐藏，内容延伸至刘海/挖孔区域；在华为/荣耀等 OEM 上额外加 `FLAG_FULLSCREEN` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` 兜底）
- 壳标识：默认 UA 末尾追加 `MatchShell/<版本名>`；JS 桥接提供 `getAppVersion()` / `isMatchShell()`
- 安全区注入：每个页面在 `<html>` 上写入 `--ms-safe-top/bottom/left/right`（CSS px，取"忽略系统栏可见性"的 insets，含挖孔）
- 底部避让：页面加载后注入 CSS，把网站已存在的贴底固定元素（预览翻页底栏、资源浏览底栏、通知浮层）
  用 `max(网站变量, var(--ms-safe-bottom))` 抬到手势条之上。
  **不给 WebView 留白** —— 1.1.3 试过 `layout_marginBottom = 底部安全区`，但那个值取自
  `getInsetsIgnoringVisibility()`：导航条隐藏时它照样返回导航条高度，底部被永久占掉约 48dp，
  边到边全屏失效，1.1.4 已回滚。选择器清单见 `UPSTREAM_CONTRACT.md`
- 上传完成检测：注入脚本钩住 `window.fetch`，命中网站分片上传的收尾请求 `POST …/complete/` 且成功
  → 去抖 1.5s → 询问是否把已提交的文件从文件池删掉。JS 桥接同时暴露 `onUploadComplete()`
  供网站将来主动调用（网站侧目前未实现任何契约）
- 弹窗统一圆角：`dialogBuilder()` + `AlertDialog.roundCorners()`（`Dialogs.kt`），
  22dp 圆角白面，见 `Theme.MatchShell.Dialog`
- 长按改 URL，支持最近 5 条历史地址（SharedPreferences 持久化）
- 系统返回键：优先网站 JS 处理器，否则 `webView.goBack()` / `finish()`
- 同 host 留 WebView，跳 host 用外部浏览器
- 本地调试时自动把页面内 `hcgy2018.site` 硬编码链接重定向到当前调试地址
- `onShowFileChooser` 文件多选上传
- `DownloadManager` 下载（Android 13+ 通知权限 + cookie + RFC 5987 中文文件名）
- **应用内更新改为后台下载**：走系统 `DownloadManager`（`setDestinationInExternalFilesDir` 落到 APP 私有
  `files/Download/`，不需要存储权限，也不污染公共目录），通知栏可见进度；APP 切后台/被杀都不影响下载。
  任务账目存 SharedPreferences（`app_update/pending_download`），进程重启靠 `resumePendingDownload()` 恢复
  ——广播只在进程活着时收得到，不能只依赖 `ACTION_DOWNLOAD_COMPLETE`。菜单「检查更新」有下载在进行时
  改为展示进度面板（可「后台下载」关闭，可「取消下载」），面板关闭不中断下载。
  校验链保持原样：清单 RSA 签名 → APK SHA-256 → 包名/versionCode/签名证书，通过后才问是否安装。
- **启动图标深浅色适配**：底板色用 `@color/launcher_plate`（`values-night` 覆盖为 `#2D2A26`），
  foreground 保持原图不变色；**刻意不提供 `<monochrome>`**，否则 Android 13+ 主题图标会把 logo 单色化。
  注意 `ic_launcher_background` 已拆成两个名字：`page_background`（APP 页面底色，不做深色适配）
  与 `launcher_plate`（仅图标底板），别再混用——APP 内部文字还是浅色一套，改深色背景会变成深底深字。
- 加载超时 / 错误分类；**重连策略为「C+D」**：不做定时重试，只在 `ConnectivityManager` 报告网络可用/切换时自动重试一次（间隔 ≥30 秒冷却）；`onReceivedHttpError`（服务器已应答）不自动重试；手动重试在加载期间禁用按钮防叠加。原有的定时重试（1.5 秒 × 3 次）已移除
- 文件池「导入 PDF」入口：已有 PDF 直接进池，不经转换引擎；`PreprocessActivity` 的「文件转 PDF」选到 PDF 时也走同一条导入路径
- PDF 无损压缩**未接入**：实测用户样本（112 页课件，图片占体积 82.5%）无损结构优化仅 −2.4%，判断为不值得引入 qpdf native 库；若后续要压缩，唯一稳妥路径是 qpdf arm64 交叉编译 + JNI
- `BuildConfig.DEBUG` 下开启 WebView 远程调试（`chrome://inspect`）
- 旋转不重建 Activity（manifest `configChanges`）
- 触摸全链路诊断日志（`ACT` / `WV` / `WV LOAD` / `WV PGSTART` / `WV PGFIN` / `WV ERR`）
- 独立原生「资源预处理」板块：照片 EXIF 校正与 JPEG 压缩；视频经 Media3 转为 720p H.264/AAC MP4，并支持进度、取消和系统文件保存器输出
- 资源预处理结果写入 **APP 私有目录**（`getExternalFilesDir(DIRECTORY_DOWNLOADS)/火柴公益文件池`，回退 `filesDir`），
  经 `FileProvider`（authority `${applicationId}.fileprovider`）以 content URI 提供给 WebView；路径映射见 `res/xml/file_paths.xml`
- 网页上传**只能从文件池选**：已删除「浏览其他文件」入口与 `ACTION_OPEN_DOCUMENT` 回调；多选模式改成池内勾选 + 「提交已选」按钮
- 文件池私有化的原因：公共 Downloads 里的照片/视频会被相册收录，用户设备上会出现两份同款文件；私有目录既不进相册也不进最近列表，代价是卸载即删（页面有常驻提示，不可删）
- `PreprocessActivity` 与 `FilePoolActivity` 均按 `systemBars ∪ displayCutout` 的 insets 动态加内边距（`setDecorFitsSystemWindows(false)`），避免标题被前摄/状态栏压住
- v0.2.0 只完成 Office 容器探测；该历史边界已由 v0.3.0 的可选开源转换引擎推进，Windows LibreOffice `soffice` 仍未直接集成到 Android APK
- v0.3.0 使用 `lite` / `pdf` product flavor：Lite 为 `0.3.0-lite`/30，不含转换 `.so`；PDF 为 `0.3.0-pdf`/31，arm64 下支持 DOCX/PPTX/XLSX→PDF。两版均使用文件池网格缩略图与长按确认删除。
# 当前产品线

- 从 `0.4.0-pdf` 起只维护 PDF 转换能力，不再继续开发或交付 Lite 变体。
- 首个正式稳定版使用 `com.hcgy2018.site`；不得重新添加 `.pdf` 后缀。
- 正式版本从 `1.0.0` / versionCode `100` 起步，后续 versionCode 必须严格递增并保持同一发布证书。
- versionCode 映射：major×100 + minor×10 + patch（`1.0.0`→100、`1.1.4`→114）。
  已发布：`1.0.0`/100、`1.1.1`/111、`1.1.2`/112、`1.1.3`/113；当前开发版 `1.1.4`/114。
- ⚠️ **发版时版本号必须严格递增，不要复用已有 tag。** 两个独立原因：
  1. 更新判定要求清单 versionCode `> BuildConfig.VERSION_CODE`（见 `AppUpdateManager.shouldOffer`），
     与已装版本同号 → 那台设备收不到任何更新提示。
  2. `release.yml` 遇到已存在的 Release 会**复用旧资产**（`gh release download` 后原样 `cp`，
     再据此算 sha256）。复用 tag 会让清单指向**没有本次改动的旧 APK**，且不会报错——
     属于静默失败，必须靠递增版本号规避。
- 稳定发布通过 `.github/workflows/release.yml` 手动触发；真机更新链未验收前不得创建稳定 Release。

# UI 与交互规范（长期，2026-09-11 用户确立）

改 UI 时按这套来，不要另造风格。

## 弹窗

- **一律圆角 22dp**：白面、内边距 20dp、按钮右对齐间距 22dp。
  平台 `AlertDialog` 默认画的是直角，必须走 `Dialogs.kt` 的 `dialogBuilder()` +
  `show().roundCorners()`，不要再直接 `AlertDialog.Builder(context)`。
- 危险动作（删除）主按钮用 `@color/danger`（`#A92F24`，与网站侧同色）；确认类动作用品牌色。
- ⚠️ 圆角是否覆盖干净（四角会不会残留平台自己的边距）**必须真机看一眼**，模拟里验不了。

## 文件池

- **定位是"中转站"，不是存档处。** 不允许出现暗示长期保存的文案
  （历史上有过"需要长期保留请另行备份"，已删）。可保留的事实：私有目录、不进相册、卸载即清。
  **不做自动过期 / 自动清理** —— "要么上传要么删除"是用户的选择，壳只提示不代劳。
- **网格卡片必须等高**：缩略图固定 104dp + 文件名固定 32dp（两行）→ 卡片固定 160dp。
  改回 `wrap_content` 会让行高随文件名长短抖动，文件数为奇数时末行会贴住上一行（用户报过这个 bug）。
- **贴底操作条高度固定 68dp**（44 按钮 + 上下各 12），且**内容按状态整组替换**：
  - 常态：`[导入 PDF] [去资源预处理]`
  - 多选态：`[重命名] [删除]`（从网页上传拉起时再加 `[提交已选 N]`）
  - 高度恒定、切语境不跳动。位置贴设备底部，靠 `file_pool_root` 的 insets 内边距避开手势条。
- ⚠️ **操作条本身不加背景**。曾经铺过一层 `card_background`，结果是一条通栏直角色块，
  和全站圆角语言打架（用户报"外部还有直角框"）。只让里面的圆角按钮自己成形即可。
  内边距配 `paddingStart=4dp` + `paddingEnd=14dp`，按钮各带 10dp `marginStart`，
  这样首尾对齐（14dp）与按钮间距（10dp）不随换哪一组按钮而变。
- 交互模型：**长按进多选**（不再是"长按直接删"）。**重命名 / 删除 只允许在多选态出现**，
  多选之前不得暴露这两个选项。删除需二次确认。
- 多选态必然是"已有选中项"才进得去（`toggleSelection` 清空即退出多选），
  所以操作条的按钮**不需要禁用态**。
  ⚠️ 别再加 alpha 压暗：`button_secondary_background` 的颜色与页面底几乎同色，
  压到 0.45 后按钮会"消失"（用户报过"没有圆角按钮包裹"）。
- 重命名：逐个文件走，**确认即落盘**（所以中途退出不会白干）；重名**不自动加序号**，
  直接拦住让用户改（与导入时的 `uniqueTarget()` 行为刻意相反）。

## 动效

- 短、有目的、ease-out、无回弹。参考值 **220ms + `cubic-bezier(.23,1,.32,1)`**，位移 28dp。
- 重命名步骤切换：整体面板滑动 + 淡入淡出；顶部标签与底部操作条不动（避免整屏都在晃）。
- **先落盘成功再播动画** —— 反过来的话会出现"动画走了但其实没改成"。
- 尊重系统「减少动画」：`Settings.Global.ANIMATOR_DURATION_SCALE == 0` 时退化为直接切换。

## 视觉 token（沿用，不要另造数字）

- 色：页面底 `#F5EFE6` / 卡片 `#E8E4DA` / 按下 `#DDD9CF` / 品牌 `#A65E44` / 危险 `#A92F24`
  / 正文 `#2D2A26` / 次要 `#6B655D` / 三级 `#8A837A`
- 圆角：卡片 20 · 缩略图 12 · 按钮 16 · 弹窗 22 · pill 999
- 触摸目标下限 44dp

## 缩略图

- 统一走 `ThumbnailStore`（内存 LRU + 磁盘缓存，键 = 文件名|大小|修改时间），
  不要在适配器里直接解码；`cacheKey` 同时被缩略图缓存和"已提交文件"追踪复用。
- 视频抽帧用 `MediaMetadataRetriever`，并受 `Semaphore(1)` 串行保护
  —— 它很吃内存，并发解多个视频在低端机上容易 OOM。
- 图片解码：API 29+ 用 `ContentResolver.loadThumbnail`，低版本自己按 `inSampleSize` 解 + 按 EXIF 转正
  （`loadThumbnail` 是 API 29 才有的，minSdk 26 上直接叫会抛）。
