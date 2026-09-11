package com.hcgy2018.site

import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.math.absoluteValue

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minimumSupportedVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val mandatory: Boolean,
    val rolloutPercent: Int,
    val releaseNotes: String
)

class AppUpdateManager(
    private val activity: ComponentActivity,
    private val requestInstallPermission: () -> Unit
) {
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pendingApk: File? = null

    // 交给系统 DownloadManager 在后台下载：APP 退到后台或被杀都不影响。
    // 下载项状态存 prefs，进程重启后靠它恢复（广播只在进程活着时收得到）。
    private var download: DownloadTask? = null
    private var receiverRegistered = false
    private var progressDialog: AlertDialog? = null
    private val progressHandler = Handler(Looper.getMainLooper())

    private data class DownloadTask(
        val id: Long,
        val versionName: String,
        val versionCode: Int,
        val sha256: String,
        val size: Long
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("sha256", sha256)
            put("size", size)
        }

        companion object {
            fun from(raw: String?): DownloadTask? = runCatching {
                val json = JSONObject(raw ?: return null)
                DownloadTask(
                    json.getLong("id"),
                    json.getString("versionName"),
                    json.getInt("versionCode"),
                    json.getString("sha256"),
                    json.optLong("size", 0L)
                )
            }.getOrNull()
        }
    }

    private data class Snapshot(
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val localUri: String?,
        val reason: Int
    )

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val finishedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val task = download ?: loadTask() ?: return
            if (finishedId != task.id) return
            val snapshot = query(task.id) ?: return
            if (snapshot.status == DownloadManager.STATUS_SUCCESSFUL) {
                verifyAndOfferInstall(task, snapshot)
            } else {
                clearTask()
                Toast.makeText(activity, activity.getString(R.string.update_download_failed, reasonText(snapshot.reason)), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkAutomatically() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { fetchUpdateInfo() } }
                .onSuccess { info ->
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    if (shouldOffer(info, manual)) showUpdateDialog(info)
                    else if (manual) Toast.makeText(activity, R.string.update_is_latest, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    if (manual) Toast.makeText(activity, activity.getString(R.string.update_check_failed, it.message), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun fetchUpdateInfo(): AppUpdateInfo {
        val manifestBytes = getBytes(BuildConfig.UPDATE_MANIFEST_URL, MAX_MANIFEST_BYTES)
        verifyManifestSignature(manifestBytes)
        val json = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        return AppUpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            minimumSupportedVersionCode = json.optInt("minimumSupportedVersionCode", 1),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.optLong("size", 0L),
            mandatory = json.optBoolean("mandatory", false),
            rolloutPercent = json.optInt("rolloutPercent", 100).coerceIn(0, 100),
            releaseNotes = json.optJSONArray("releaseNotes")?.let { array ->
                (0 until array.length()).joinToString("\n") { "• ${array.getString(it)}" }
            }.orEmpty()
        )
    }

    private fun verifyManifestSignature(manifestBytes: ByteArray) {
        val publicKeyText = BuildConfig.UPDATE_PUBLIC_KEY_BASE64
        if (publicKeyText.isBlank()) {
            check(BuildConfig.DEBUG) { "更新清单公钥未配置" }
            return
        }
        val signatureBytes = Base64.decode(
            getBytes("${BuildConfig.UPDATE_MANIFEST_URL}.sig", MAX_SIGNATURE_BYTES).toString(Charsets.US_ASCII).trim(),
            Base64.DEFAULT
        )
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(publicKeyText, Base64.DEFAULT))
        )
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(manifestBytes)
            verify(signatureBytes)
        }) { "更新清单签名无效" }
    }

    private fun shouldOffer(info: AppUpdateInfo, manual: Boolean): Boolean {
        if (info.versionCode <= BuildConfig.VERSION_CODE) return false
        if (manual || info.mandatory || BuildConfig.VERSION_CODE < info.minimumSupportedVersionCode) return true
        val deviceId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val bucket = ("$deviceId:${info.versionCode}".hashCode().absoluteValue % 100) + 1
        return bucket <= info.rolloutPercent
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        val forced = info.mandatory || BuildConfig.VERSION_CODE < info.minimumSupportedVersionCode
        val message = buildString {
            append(activity.getString(R.string.update_available_message, info.versionName, readableSize(info.size)))
            if (info.releaseNotes.isNotBlank()) append("\n\n").append(info.releaseNotes)
        }
        dialogBuilder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> startBackgroundDownload(info) }
            .apply { if (!forced) setNegativeButton(R.string.update_later, null) }
            .setCancelable(!forced)
            .show()
            .roundCorners()
    }

    /** 交给系统下载器在后台下载；这里只负责入队和记账，不阻塞界面。 */
    private fun startBackgroundDownload(info: AppUpdateInfo) {
        val fileName = "matchshell-${info.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle(activity.getString(R.string.update_download_title, info.versionName))
            setDescription(activity.getString(R.string.update_downloading))
            setMimeType("application/vnd.android.package-archive")
            // 通知栏可见，用户可以切走继续做别的事
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            addRequestHeader("User-Agent", "MatchShell/${BuildConfig.VERSION_NAME}")
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_download_failed, it.message),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val task = DownloadTask(id, info.versionName, info.versionCode, info.sha256, info.size)
        download = task
        saveTask(task)
        registerReceiverIfNeeded()
        Toast.makeText(activity, R.string.update_download_started, Toast.LENGTH_LONG).show()
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            completionReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    /** APP 启动时调用：把上次没走完的下载接上。 */
    fun resumePendingDownload() {
        val task = loadTask() ?: return
        download = task
        registerReceiverIfNeeded()
        when (query(task.id)?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> query(task.id)?.let { verifyAndOfferInstall(task, it) }
            DownloadManager.STATUS_FAILED -> clearTask()
            else -> Unit // 还在跑，安静等着，不打扰用户
        }
    }

    /**
     * 菜单里点「检查更新」时调用。
     * 有进行中的下载就展示进度面板并返回 true，否则返回 false 交回检查流程。
     */
    fun showDownloadProgressIfAny(): Boolean {
        val task = download ?: loadTask() ?: return false
        val snapshot = query(task.id)
        if (snapshot == null) {
            clearTask()
            return false
        }
        when (snapshot.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                verifyAndOfferInstall(task, snapshot)
                return true
            }
            DownloadManager.STATUS_FAILED -> {
                clearTask()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_download_failed, reasonText(snapshot.reason)),
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
        }
        download = task
        registerReceiverIfNeeded()
        showProgressDialog(task)
        return true
    }

    private fun showProgressDialog(task: DownloadTask) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(48, 24, 48, 24)
        }
        progressDialog = dialogBuilder(activity)
            .setTitle(R.string.update_downloading)
            .setView(bar)
            .setPositiveButton(R.string.update_download_hide, null)
            .setNegativeButton(R.string.update_download_cancel) { _, _ -> cancelDownload(task) }
            .setOnDismissListener { stopProgressUpdates() }
            .show()
            .roundCorners()
        // 面板关掉不中断下载，只是不再刷新进度
        val tick = object : Runnable {
            override fun run() {
                val snapshot = query(task.id) ?: return
                if (snapshot.status != DownloadManager.STATUS_RUNNING &&
                    snapshot.status != DownloadManager.STATUS_PENDING &&
                    snapshot.status != DownloadManager.STATUS_PAUSED
                ) {
                    progressDialog?.dismiss()
                    return
                }
                val total = snapshot.total.takeIf { it > 0 } ?: task.size
                if (total > 0) {
                    bar.isIndeterminate = false
                    bar.progress = ((snapshot.downloaded * 100) / total).toInt().coerceIn(0, 100)
                } else {
                    bar.isIndeterminate = true
                }
                progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        progressHandler.post(tick)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacksAndMessages(null)
        progressDialog = null
    }

    private fun cancelDownload(task: DownloadTask) {
        stopProgressUpdates()
        runCatching {
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(task.id)
        }
        clearTask()
        Toast.makeText(activity, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun query(id: Long): Snapshot? = runCatching {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Snapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        }
    }.getOrNull()

    private fun localFile(snapshot: Snapshot): File? {
        val raw = snapshot.localUri ?: return null
        return runCatching { File(Uri.parse(raw).path ?: return null) }.getOrNull()?.takeIf { it.exists() }
    }

    /** 下载结束后：先校验完整性与身份，再问用户装不装。 */
    private fun verifyAndOfferInstall(task: DownloadTask, snapshot: Snapshot) {
        if (activity.isFinishing || activity.isDestroyed) return // 保留记录，下次启动再处理
        val apk = localFile(snapshot)
        if (apk == null) {
            clearTask()
            Toast.makeText(
                activity,
                activity.getString(R.string.update_download_failed, activity.getString(R.string.update_file_missing)),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    check(sha256(apk).equals(task.sha256, ignoreCase = true)) { "APK 校验值不匹配" }
                    checkApkIdentity(apk, task.versionCode)
                }
            }
            result.onSuccess {
                clearTask()
                dialogBuilder(activity)
                    .setTitle(R.string.update_ready_title)
                    .setMessage(activity.getString(R.string.update_ready_message, task.versionName))
                    .setPositiveButton(R.string.update_install_now) { _, _ -> beginInstall(apk) }
                    .setNegativeButton(R.string.update_install_later) { _, _ -> apk.delete() }
                    .setCancelable(false)
                    .show()
                    .roundCorners()
            }.onFailure {
                apk.delete()
                clearTask()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_download_failed, it.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveTask(task: DownloadTask) {
        prefs.edit().putString(KEY_DOWNLOAD, task.toJson().toString()).apply()
    }

    private fun loadTask(): DownloadTask? = DownloadTask.from(prefs.getString(KEY_DOWNLOAD, null))

    private fun clearTask() {
        download = null
        prefs.edit().remove(KEY_DOWNLOAD).apply()
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> activity.getString(R.string.update_error_space)
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            activity.getString(R.string.update_error_http, reason)
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_FILE_ERROR ->
            activity.getString(R.string.update_error_io)
        else -> activity.getString(R.string.update_error_unknown, reason)
    }

    /** Activity 销毁时调用，别把接收器和轮询泄漏出去。 */
    fun release() {
        stopProgressUpdates()
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(completionReceiver) }
            receiverRegistered = false
        }
    }

    fun installPendingAfterPermission() {
        pendingApk?.takeIf { it.exists() }?.let { beginInstall(it) }
    }

    private fun beginInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            requestInstallPermission()
            return
        }
        pendingApk = null
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("matchshell.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) }
            }
            val callback = PendingIntent.getBroadcast(
                activity,
                sessionId,
                Intent(activity, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
    }

    private fun checkApkIdentity(apk: File, expectedVersionCode: Int) {
        val archive = packageInfo(apk.absolutePath) ?: error("无法读取下载的 APK")
        check(archive.packageName == activity.packageName) { "APK 包名不匹配" }
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        check(archiveCode == expectedVersionCode.toLong() && archiveCode > BuildConfig.VERSION_CODE) { "APK 版本号无效" }
        val installed = packageInfo(activity.packageName) ?: error("无法读取当前应用签名")
        check(certDigests(archive) == certDigests(installed)) { "APK 签名证书不匹配" }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pathOrPackage: String): PackageInfo? = if (pathOrPackage.endsWith(".apk")) {
        activity.packageManager.getPackageArchiveInfo(pathOrPackage, signingFlag())
    } else {
        activity.packageManager.getPackageInfo(pathOrPackage, signingFlag())
    }

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    private fun getBytes(url: String, maxBytes: Int): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MatchShell/${BuildConfig.VERSION_NAME}")
        }
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            check(bytes.size <= maxBytes) { "响应内容过大" }
            bytes
        }.also { connection.disconnect() }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun readableSize(bytes: Long): String = when {
        bytes <= 0 -> activity.getString(R.string.update_size_unknown)
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    private companion object {
        const val PREFS = "app_update"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_DOWNLOAD = "pending_download"
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val PROGRESS_INTERVAL_MS = 800L
        const val MAX_MANIFEST_BYTES = 128 * 1024
        const val MAX_SIGNATURE_BYTES = 8 * 1024
    }
}
