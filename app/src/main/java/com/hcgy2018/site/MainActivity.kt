package com.hcgy2018.site

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import java.net.URLDecoder

class MainActivity : ComponentActivity() {

    private val TAG = "matchshell-touch"

    private lateinit var web: WebView
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private lateinit var productionHost: String

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var retryCount = 0
    private var autoRetryPending = false
    private var lastFailedUrl: String? = null

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { maybeAutoRetry() }
        }
    }

    private val fileChooser =
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
        val fab = findViewById<Button>(R.id.reload_fab)

        productionHost = getString(R.string.production_host)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        configureWebView()
        applyInsets(fab)

        findViewById<Button>(R.id.retry_button).setOnClickListener {
            retryCount = 0
            cancelAutoRetry()
            web.stopLoading()
            web.loadUrl(currentUrl())
        }
        fab.setOnClickListener { web.reload() }
        fab.setOnLongClickListener { showUrlDialog(); true }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(currentUrl())
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
        cancelAutoRetry()
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

            if (BuildConfig.DEBUG) {
                // debug 保留缩放与自动播放，方便真机调试
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = true
                allowContentAccess = true
            } else {
                // release 收紧权限与行为
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
            }
            setGeolocationEnabled(false)
        }
        web.webViewClient = ShellWebViewClient()
        web.webChromeClient = ShellChromeClient()
        web.setDownloadListener { url, _, disposition, mimeType, _ ->
            download(url, disposition, mimeType)
        }

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

    private fun applyInsets(fab: View) {
        // 默认隐藏系统栏，实现真正的全屏；用户从顶部/底部滑入可临时显示
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 只拿 insets 来调整 reload_fab 的边距，避免被手势导航条/状态栏压住。
        // 不再给根容器加 padding：全屏模式下网站内容应延伸至刘海/挖孔/手势区域。
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            fab.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = (16 * resources.displayMetrics.density).toInt() + bars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun download(url: String, disposition: String?, mimeType: String?) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
            addRequestHeader("User-Agent", web.settings.userAgentString)
            setMimeType(mimeType)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                guessFileName(url, disposition)
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show() }
            .onFailure { openExternal(Uri.parse(url)) }
    }

    private fun guessFileName(url: String, disposition: String?): String {
        val fromHeader = disposition
            ?.let { Regex("filename\\*?=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.startsWith("UTF-8''", ignoreCase = true)) it.substring(7) else it }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrElse { _ -> it } }
        return fromHeader?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, null, null)
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
        lastFailedUrl = url
        showError(getString(R.string.error_timeout), url)
        if (retryCount < MAX_AUTO_RETRY) maybeAutoRetry()
    }

    private fun showError(message: String, url: String? = null) {
        val detail = url?.let { "\n\n$it" } ?: ""
        errorText.text = "$message$detail"
        errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        retryCount = 0
        lastFailedUrl = null
    }

    private fun maybeAutoRetry() {
        if (isDestroyed || isFinishing) return
        if (errorView.visibility != View.VISIBLE) return
        if (retryCount >= MAX_AUTO_RETRY) return
        if (autoRetryPending) return
        autoRetryPending = true
        errorText.text = getString(R.string.error_retrying, retryCount + 1, MAX_AUTO_RETRY)
        mainHandler.postDelayed({
            autoRetryPending = false
            if (isDestroyed || isFinishing) return@postDelayed
            if (errorView.visibility == View.VISIBLE && retryCount < MAX_AUTO_RETRY) {
                doAutoRetry()
            }
        }, AUTO_RETRY_DELAY_MS)
    }

    private fun cancelAutoRetry() {
        autoRetryPending = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun doAutoRetry() {
        retryCount++
        web.stopLoading()
        web.loadUrl(currentUrl())
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
            showUrlInputDialog(currentUrl())
            return
        }

        val items = history.toMutableList()
        items.add(getString(R.string.url_history_manual))

        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_url_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == history.size) {
                    showUrlInputDialog(currentUrl())
                } else {
                    val url = history[which]
                    saveUrl(url)
                    web.loadUrl(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUrlInputDialog(defaultUrl: String) {
        val input = EditText(this).apply {
            setText(defaultUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
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
        if (history.size > 5) history.removeAt(history.size - 1)
        return org.json.JSONArray(history).toString()
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
            startPageTimeout(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WV PGFIN   $url")
            cancelPageTimeout()
            hideError()
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
            lastFailedUrl = request.url.toString()
            showError(getString(R.string.error_template, describeError(error)))
            if (retryCount < MAX_AUTO_RETRY) maybeAutoRetry()
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

            val intent = try {
                params.createIntent().apply { putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
            } catch (e: Exception) {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            return try {
                fileChooser.launch(intent)
                true
            } catch (e: Exception) {
                fileCallback = null
                callback.onReceiveValue(null)
                false
            }
        }
    }

    private companion object {
        const val PREFS = "shell"
        const val KEY_URL = "url"
        const val KEY_URL_HISTORY = "url_history"
        const val MAX_HISTORY_SIZE = 5

        const val PAGE_TIMEOUT_MS = 15_000L
        const val MAX_AUTO_RETRY = 3
        const val AUTO_RETRY_DELAY_MS = 1_500L
    }
}
