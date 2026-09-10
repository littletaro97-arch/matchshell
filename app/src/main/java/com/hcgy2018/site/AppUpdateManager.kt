package com.hcgy2018.site

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
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
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> download(info) }
            .apply { if (!forced) setNegativeButton(R.string.update_later, null) }
            .setCancelable(!forced)
            .show()
    }

    private fun download(info: AppUpdateInfo) {
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = info.size <= 0
            max = 100
            setPadding(48, 24, 48, 24)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setView(progress)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = File(activity.cacheDir, "matchshell-update.apk")
                    downloadFile(info.apkUrl, target, info.size) { percent ->
                        activity.runOnUiThread { progress.isIndeterminate = false; progress.progress = percent }
                    }
                    check(sha256(target).equals(info.sha256, ignoreCase = true)) { "APK 校验值不匹配" }
                    checkApkIdentity(target, info.versionCode)
                    target
                }
            }.onSuccess { dialog.dismiss(); beginInstall(it) }
                .onFailure {
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.update_download_failed, it.message), Toast.LENGTH_LONG).show()
                }
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

    private fun downloadFile(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MatchShell/${BuildConfig.VERSION_NAME}")
        }
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        connection.disconnect()
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
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val MAX_MANIFEST_BYTES = 128 * 1024
        const val MAX_SIGNATURE_BYTES = 8 * 1024
    }
}
