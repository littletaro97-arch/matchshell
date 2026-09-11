package com.hcgy2018.site

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

class MainActivity : ComponentActivity() {

    private val TAG = "matchshell-touch"

    private lateinit var web: WebView
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private val appBridge = AppBridge(this)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingDownload?.let { info ->
                pendingDownload = null
                if (!granted) {
                    Toast.makeText(this, R.string.download_no_notification_permission, Toast.LENGTH_LONG).show()
                }
                doDownload(info.url, info.disposition, info.mimeType)
            }
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateManager.installPendingAfterPermission()
        }

    private lateinit var updateManager: AppUpdateManager

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private lateinit var productionHost: String

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var lastFailedUrl: String? = null

    // 重试策略（C+D）：不做定时重试，只在系统报告网络可用/切换时自动重试一次；
    // 服务器已经应答（HTTP 4xx/5xx）时一律不自动重试，交回用户手动决定。
    private var serverResponded = false
    private var lastAutoRetryAt = 0L
    private var pageLoading = false

    // 安全区（CSS px），供网站底部固定元素避让手势条/刘海
    private var safeAreaTop = 0
    private var safeAreaBottom = 0
    private var safeAreaLeft = 0
    private var safeAreaRight = 0

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { autoRetryOnNetworkChange() }
        }
    }

    private val filePoolChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            val picked = if (result.resultCode == Activity.RESULT_OK) collectUris(result.data) else null
            callback.onReceiveValue(picked)
        }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webview)
        errorView = findViewById(R.id.error_view)
        errorText = findViewById(R.id.error_text)
        val menuButton = findViewById<ImageButton>(R.id.menu_button)

        productionHost = getString(R.string.production_host)
        updateManager = AppUpdateManager(this) {
            installPermissionLauncher.launch(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        configureWebView()
        applyInsets(menuButton)

        retryButton = findViewById(R.id.retry_button)
        retryButton.setOnClickListener { retryNow() }
        menuButton.setOnClickListener { showMainMenu(it) }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handler = appBridge.backHandlerName
                if (!handler.isNullOrBlank()) {
                    web.evaluateJavascript("($handler)()") { result ->
                        val consumed = result?.trim { it == '"' }?.toBoolean() == true
                        if (!consumed) defaultBackAction()
                    }
                } else {
                    defaultBackAction()
                }
            }

            private fun defaultBackAction() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(currentUrl())
        // 上次的更新包可能已在后台下载完，先把这笔账接上再谈检查
        updateManager.resumePendingDownload()
        mainHandler.postDelayed({ updateManager.checkAutomatically() }, UPDATE_CHECK_DELAY_MS)
    }

    // --- DIAG (仅DEBUG): 完整事件流追踪 ---
    // 想知道: 触摸到了 Activity 之后, 到底哪一环断了?
    //  1) dispatchTouchEvent (Activity): 是否进入? -> 已确认 ✅
    //  2) WebView.onTouchListener: WebView 自己收没收到?
    //  3) WebViewClient.onPageStarted/onReceivedError: 是否触发跳转?
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (BuildConfig.DEBUG && ev != null) {
            val name = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> "ACT${ev.actionMasked}"
            }
            Log.d(TAG, "ACT $name @(${ev.rawX.toInt()},${ev.rawY.toInt()})")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        cancelPageTimeout()
        updateManager.release()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            // 网站已提供 viewport meta；概览缩放会把平板误带入宽桌面布局，
            // 造成比例压缩，并与资源页的 900px 响应式分支冲突。
            loadWithOverviewMode = false
            setSupportMultipleWindows(false)

            // 产品体验上不允许用户手动缩放；所有缩放入口统一关闭
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            if (BuildConfig.DEBUG) {
                // debug 保留自动播放与文件访问，方便真机调试
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = true
                allowContentAccess = true
            } else {
                // release 收紧权限与行为
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
            }
            setGeolocationEnabled(false)

            // 安全：不保存表单密码；登录态应由网站通过 cookie/token/session 自行管理
            @Suppress("DEPRECATION")
            savePassword = false
            @Suppress("DEPRECATION")
            saveFormData = false

            // 让网站能识别 MatchShell：保留系统默认 UA，只在末尾追加产品 token。
            // 网站据此渲染 APP 模式（隐藏顶部导航、底部固定入口、放大触摸目标）。
            val baseUa = userAgentString
            if (baseUa.contains(UA_PRODUCT).not()) {
                userAgentString = "$baseUa $UA_TOKEN"
            }
        }
        web.webViewClient = ShellWebViewClient()
        web.webChromeClient = ShellChromeClient()
        web.setDownloadListener { url, _, disposition, mimeType, _ ->
            download(url, disposition, mimeType)
        }

        // 暴露 JS 桥接，让网站控制返回键等行为
        web.addJavascriptInterface(appBridge, "MatchShell")

        // DIAG: WebView 自己有没有收到触摸事件?
        web.setOnTouchListener { _, ev ->
            if (BuildConfig.DEBUG) {
                val name = when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> "DOWN"
                    MotionEvent.ACTION_UP -> "UP"
                    MotionEvent.ACTION_MOVE -> "MOVE"
                    MotionEvent.ACTION_CANCEL -> "CANCEL"
                    else -> "EV${ev.actionMasked}"
                }
                Log.d(TAG, "WV  $name @(${ev.x.toInt()},${ev.y.toInt()})")
            }
            false  // 不消费, 让 WebView 自己处理
        }
    }

    private fun applyInsets(menuButton: View) {
        // 默认隐藏系统栏，实现真正的全屏；用户从顶部/底部滑入可临时显示
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 部分 OEM（如华为/荣耀）仅靠 WindowInsetsController 不够，需要旧版 flags 兜底
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        // 允许内容延伸到刘海/挖孔区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 只拿 insets 来调整 reload_fab 的边距，避免被状态栏/刘海压住。
        // 顶部仍不给根容器加 padding：全屏模式下网站内容延伸至刘海/挖孔是刻意的观感选择。
        // 底部例外，见 applyBottomClearance()。
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            menuButton.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = (16 * resources.displayMetrics.density).toInt() + bars.top
            }

            // 安全区用"忽略可见性"的 insets：系统栏是临时滑入的，
            // 用可见性会让它一进一出导致页面底部固定条跟着跳。
            val stable = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            updateSafeArea(stable.top, stable.bottom, stable.left, stable.right)
            applyBottomClearance(stable.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 把底部安全区从 WebView 视口里扣掉，让网站的贴底固定元素自然抬起。
     *
     * 网站侧本来用 `env(safe-area-inset-bottom)` 避让手势条，但 `base.html` 的 viewport
     * 没有声明 `viewport-fit=cover`，按 CSS 规范此时 `env()` 恒为 0
     * —— 也就是网站那些避让代码目前是空转的（预览页翻页底栏只剩 7px 底距，被手势条压住）。
     *
     * 壳不改网站，改为在视口层面兜底：缩小 WebView 自身高度，Chromium 的布局视口随之变矮，
     * 而 `position: fixed` 正是以视口为包含块，所以贴底固定元素会整体上移。
     * 用 layout_marginBottom 而不是 padding：边距在 View 层就直接把 WebView 量小，
     * 不依赖 WebView 对 padding 的内部处理，行为确定。
     * 让出的那条露出根容器底色（@color/page_background，与网站 --paper 几乎同色）。
     *
     * 只在数值变化时写入：insets 回调触发频繁，重复设置会引起无谓重排。
     */
    private fun applyBottomClearance(bottomPx: Int) {
        val params = web.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.bottomMargin == bottomPx) return
        web.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bottomPx }
    }

    /**
     * 记录安全区（CSS px）并注入给当前页面。
     * 网站底部固定元素加 `padding-bottom: var(--ms-safe-bottom, 0px)` 即可避开手势条。
     */
    private fun updateSafeArea(top: Int, bottom: Int, left: Int, right: Int) {
        val density = resources.displayMetrics.density
        fun toCssPx(px: Int) = (px / density).roundToInt()
        safeAreaTop = toCssPx(top)
        safeAreaBottom = toCssPx(bottom)
        safeAreaLeft = toCssPx(left)
        safeAreaRight = toCssPx(right)
        applySafeAreaCssVars()
    }

    private fun applySafeAreaCssVars() {
        if (!::web.isInitialized) return
        val js = "(function(){" +
            "var r=document.documentElement;" +
            "if(!r)return;" +
            "r.style.setProperty('--ms-safe-top','${safeAreaTop}px');" +
            "r.style.setProperty('--ms-safe-bottom','${safeAreaBottom}px');" +
            "r.style.setProperty('--ms-safe-left','${safeAreaLeft}px');" +
            "r.style.setProperty('--ms-safe-right','${safeAreaRight}px');" +
            "})();"
        web.evaluateJavascript(js, null)
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.reload)).setOnMenuItemClickListener { web.reload(); true }
            menu.add(getString(R.string.preprocess_entry)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, PreprocessActivity::class.java)); true
            }
            menu.add(getString(R.string.menu_file_pool)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, FilePoolActivity::class.java)); true
            }
            menu.add(getString(R.string.menu_debug_address)).setOnMenuItemClickListener {
                showUrlDialog(); true
            }
            menu.add(getString(R.string.menu_check_update)).setOnMenuItemClickListener {
                // 有后台下载在进行就展示进度，否则才去查更新
                if (!updateManager.showDownloadProgressIfAny()) updateManager.checkManually()
                true
            }
            show()
        }
    }

    private fun download(url: String, disposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = PendingDownload(url, disposition, mimeType)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        doDownload(url, disposition, mimeType)
    }

    private fun doDownload(url: String, disposition: String?, mimeType: String?) {
        // 游客预览 PDF 时不应自动下载到本地。
        // 只要网站没显式要求下载（Content-Disposition 不是 attachment），
        // 遇到 PDF 就交给系统浏览器/ PDF 查看器处理。
        val isAttachment = disposition?.contains("attachment", ignoreCase = true) == true
        if (!isAttachment && (mimeType == "application/pdf" || url.endsWith(".pdf", ignoreCase = true))) {
            openPdf(url)
            return
        }

        val fileName = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
            addRequestHeader("User-Agent", web.settings.userAgentString)
            setMimeType(mimeType)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show() }
            .onFailure { openExternal(Uri.parse(url)) }
    }

    private fun collectUris(data: Intent?): Array<Uri>? {
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) {
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        return data?.data?.let { arrayOf(it) }
    }

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.error_template, it.message ?: getString(R.string.error_unknown)),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun openPdf(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                // 没有 PDF 查看器时回退到浏览器，浏览器通常会内嵌预览而非下载
                openExternal(uri)
            }
    }

    // --- 加载超时 / 网络恢复自动重试 ---

    private fun startPageTimeout(url: String) {
        if (isDestroyed || isFinishing) return
        cancelPageTimeout()
        timeoutRunnable = Runnable { onPageTimeout(url) }
        mainHandler.postDelayed(timeoutRunnable!!, PAGE_TIMEOUT_MS)
    }

    private fun cancelPageTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it); timeoutRunnable = null }
    }

    private fun onPageTimeout(url: String) {
        if (isDestroyed || isFinishing) return
        if (errorView.visibility == View.VISIBLE) return
        pageLoading = false
        lastFailedUrl = url
        showError(getString(R.string.error_timeout), url)
        retryButton.isEnabled = true
    }

    private fun showError(message: String, url: String? = null) {
        val detail = url?.let { "\n\n$it" } ?: ""
        errorText.text = "$message$detail"
        errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        lastFailedUrl = null
    }

    /** 用户主动重试。加载期间按钮是禁用的，所以这里不会叠出第二次请求。 */
    private fun retryNow() {
        if (isDestroyed || isFinishing || pageLoading) return
        pageLoading = true
        retryButton.isEnabled = false
        cancelPageTimeout()
        web.stopLoading()
        web.loadUrl(currentUrl())
    }

    /**
     * 网络恢复/切换时才自动重试，最多一次。
     * 服务器已经应答过（HTTP 错误）就不打扰——那只说明后端有问题，重试没用；
     * 两次自动重试之间强制间隔冷却时长，避免网络抖动时反复砸服务器。
     */
    private fun autoRetryOnNetworkChange() {
        if (isDestroyed || isFinishing) return
        if (errorView.visibility != View.VISIBLE) return
        if (serverResponded) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoRetryAt < AUTO_RETRY_COOLDOWN_MS) return
        lastAutoRetryAt = now
        retryNow()
    }

    private fun describeError(error: WebResourceError): String {
        return when (error.errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> getString(R.string.error_host_lookup)
            WebViewClient.ERROR_CONNECT -> getString(R.string.error_connect)
            WebViewClient.ERROR_TIMEOUT -> getString(R.string.error_timeout)
            else -> error.description?.toString() ?: getString(R.string.error_unknown)
        }
    }

    private fun currentUrl(): String =
        prefs.getString(KEY_URL, null) ?: getString(R.string.default_url)

    private fun isProductionHost(host: String): Boolean {
        return host.equals(productionHost, ignoreCase = true)
            || host.endsWith(".$productionHost", ignoreCase = true)
    }

    /**
     * 补全用户输入的 URL。
     * - 已带 scheme 的保持原样
     * - 裸写 IP:port 或 localhost:port 默认用 http（局域网调试多为 HTTP）
     * - 其他默认 https
     */
    private fun resolveUrl(raw: String): String? {
        val input = raw.trim()
        if (input.isBlank()) return null
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            return input
        }
        // IPv4:port 或 localhost:port 等局域网常见形式默认走 http
        val looksLikeLan = input.startsWith("192.168.", ignoreCase = true) ||
            input.startsWith("10.", ignoreCase = true) ||
            input.startsWith("172.", ignoreCase = true) ||
            input.startsWith("localhost", ignoreCase = true) ||
            input.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+"))
        return if (looksLikeLan) "http://$input" else "https://$input"
    }

    private fun showUrlDialog() {
        val history = getUrlHistory()
        if (history.isEmpty()) {
            showUrlInputDialog("")
            return
        }

        val items = history.toMutableList()
        items.add(getString(R.string.url_history_manual))

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }

        var dialog: AlertDialog? = null
        val adapter = UrlHistoryAdapter(
            items = items,
            onSelect = { item ->
                dialog?.dismiss()
                if (item == getString(R.string.url_history_manual)) {
                    showUrlInputDialog("")
                } else {
                    saveUrl(item)
                    web.loadUrl(item)
                }
            },
            onDelete = { item ->
                dialog?.let { showDeleteHistoryDialog(item, it) }
            }
        )
        listView.adapter = adapter

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.prompt_url_title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteHistoryDialog(url: String, parentDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(R.string.url_history_delete_title)
            .setMessage(getString(R.string.url_history_delete_message, url))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                removeFromHistory(url)
                parentDialog.dismiss()
                if (getUrlHistory().isEmpty()) {
                    showUrlInputDialog("")
                } else {
                    showUrlDialog()
                }
            }
            .show()
    }

    private fun showUrlInputDialog(defaultUrl: String) {
        val input = EditText(this).apply {
            if (defaultUrl.isBlank()) {
                setText("")
                hint = currentUrl()
            } else {
                setText(defaultUrl)
                setSelection(text.length)
            }
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_url_title)
            .setMessage(R.string.prompt_url_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = resolveUrl(input.text.toString())
                if (url == null) {
                    Toast.makeText(this, R.string.url_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveUrl(url)
                web.loadUrl(url)
            }
            .show()
    }

    private fun saveUrl(url: String) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_URL_HISTORY, updateHistory(url))
            .apply()
        Toast.makeText(this, getString(R.string.url_changed, url), Toast.LENGTH_SHORT).show()
    }

    private fun getUrlHistory(): List<String> {
        val json = prefs.getString(KEY_URL_HISTORY, "[]") ?: "[]"
        return try {
            val array = org.json.JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateHistory(url: String): String {
        val history = getUrlHistory().toMutableList()
        history.removeAll { it.equals(url, ignoreCase = true) }
        history.add(0, url)
        if (history.size > MAX_HISTORY_SIZE) history.removeAt(history.size - 1)
        return org.json.JSONArray(history).toString()
    }

    private fun removeFromHistory(url: String) {
        val history = getUrlHistory().toMutableList()
        history.removeAll { it.equals(url, ignoreCase = true) }
        prefs.edit()
            .putString(KEY_URL_HISTORY, org.json.JSONArray(history).toString())
            .apply()
    }

    private inner class UrlHistoryAdapter(
        items: List<String>,
        private val onSelect: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : ArrayAdapter<String>(this@MainActivity, R.layout.dialog_url_history_item, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.dialog_url_history_item, parent, false)
            val item = getItem(position) ?: return view
            val urlText = view.findViewById<TextView>(R.id.url_text)
            val deleteBtn = view.findViewById<TextView>(R.id.url_delete)

            urlText.text = item
            val isManual = item == getString(R.string.url_history_manual)
            deleteBtn.visibility = if (isManual) View.GONE else View.VISIBLE

            urlText.setOnClickListener { onSelect(item) }
            deleteBtn.setOnClickListener { onDelete(item) }
            return view
        }
    }

    private inner class ShellWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (BuildConfig.DEBUG) Log.d(TAG, "WV LOAD ${request.url}")
            val url = request.url
            if (url.scheme != "http" && url.scheme != "https") {
                openExternal(url)
                return true
            }

            val base = Uri.parse(currentUrl())
            val baseHost = base.host
            val targetHost = url.host

            // 与当前地址同 host，在 WebView 内打开
            if (targetHost != null && targetHost.equals(baseHost, ignoreCase = true)) {
                return false
            }

            // 目标为生产域名，且当前处于调试地址，把链接重定向到调试地址
            // 这样本地调试时，页面内 hard-coded 的 hcgy2018.site 资源链接仍走本地服务器
            if (targetHost != null && isProductionHost(targetHost)
                && baseHost != null && !isProductionHost(baseHost)
            ) {
                val redirected = url.buildUpon()
                    .scheme(base.scheme)
                    .encodedAuthority(base.encodedAuthority)
                    .build()
                if (BuildConfig.DEBUG) Log.d(TAG, "WV REDIRECT $url -> $redirected")
                view.loadUrl(redirected.toString())
                return true
            }

            // 其他外部链接交给系统浏览器
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WV PGSTART $url")
            pageLoading = true
            serverResponded = false
            retryButton.isEnabled = false
            startPageTimeout(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WV PGFIN   $url")
            cancelPageTimeout()
            pageLoading = false
            retryButton.isEnabled = true
            hideError()
            // 移除 WebView 默认的蓝色点击高亮，让体验更接近原生 APP
            view.evaluateJavascript(DISABLE_TAP_HIGHLIGHT, null)
            // 每个新文档都要重新注入：上页设置的行内样式会随导航丢掉
            applySafeAreaCssVars()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "WV ERR ${request.url} code=${error.errorCode} desc=${error.description}")
            }
            if (!request.isForMainFrame) return
            cancelPageTimeout()
            pageLoading = false
            serverResponded = false
            retryButton.isEnabled = true
            lastFailedUrl = request.url.toString()
            showError(getString(R.string.error_template, describeError(error)))
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            cancelPageTimeout()
            pageLoading = false
            retryButton.isEnabled = true
            // 服务器已经应答，说明是后端问题：不自动重试，等用户决定
            serverResponded = true
            lastFailedUrl = request.url.toString()
            showError(getString(R.string.error_http, errorResponse.statusCode))
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback

            val intent = Intent(this@MainActivity, FilePoolActivity::class.java).apply {
                putExtra(FilePoolActivity.EXTRA_SELECT_MODE, true)
                putExtra(FilePoolActivity.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                putExtra(FilePoolActivity.EXTRA_ACCEPT_TYPES, params.acceptTypes)
            }

            return try {
                filePoolChooser.launch(intent)
                true
            } catch (e: Exception) {
                fileCallback = null
                callback.onReceiveValue(null)
                false
            }
        }
    }

    private data class PendingDownload(
        val url: String,
        val disposition: String?,
        val mimeType: String?
    )

    /**
     * JS 桥接。
     * 网站可通过 window.MatchShell 与壳交互，例如设置返回键处理器。
     */
    class AppBridge(private val activity: MainActivity) {

        @Volatile
        var backHandlerName: String? = null
            private set

        /**
         * 网站注册一个返回键处理器函数名。
         * 该函数需要在全局作用域可访问，返回 true 表示消费返回键。
         * 示例：MatchShell.setBackHandler("onMatchShellBack")
         */
        @JavascriptInterface
        fun setBackHandler(name: String?) {
            backHandlerName = name?.takeIf { it.isNotBlank() }
        }

        /** 壳版本名，形如 `1.0.0`；网站可按版本决定用哪些能力 */
        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        /** 恒为 true；配合 `typeof window.MatchShell` 判断页面是否在壳内运行 */
        @JavascriptInterface
        fun isMatchShell(): Boolean = true

        /** 退出 APP */
        @JavascriptInterface
        fun finishApp() {
            activity.runOnUiThread { activity.finish() }
        }

        /** 刷新当前页面 */
        @JavascriptInterface
        fun reload() {
            activity.runOnUiThread { activity.web.reload() }
        }
    }

    private companion object {
        /** UA 产品名；网站用 UA 里是否含它来判断是否处于壳内 */
        const val UA_PRODUCT = "MatchShell"

        /** 追加到系统默认 UA 末尾的 token，形如 `MatchShell/1.0.0` */
        val UA_TOKEN = "$UA_PRODUCT/${BuildConfig.VERSION_NAME}"

        const val PREFS = "shell"
        const val KEY_URL = "url"
        const val KEY_URL_HISTORY = "url_history"
        const val MAX_HISTORY_SIZE = 5

        const val PAGE_TIMEOUT_MS = 15_000L

        /** 两次自动重试之间的最小间隔；网络抖动时防止反复请求服务器 */
        const val AUTO_RETRY_COOLDOWN_MS = 30_000L
        const val UPDATE_CHECK_DELAY_MS = 3_000L

        // 注入 CSS 禁用 WebView 默认的蓝色点击高亮
        private const val DISABLE_TAP_HIGHLIGHT = """
            (function(){
                var s=document.createElement('style');
                s.textContent='*{-webkit-tap-highlight-color:transparent!important;}';
                document.head.appendChild(s);
            })();
        """
    }
}
