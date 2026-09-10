package com.hcgy2018.site

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

@OptIn(UnstableApi::class)
class PreprocessActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: Button
    private val mainHandler = Handler(Looper.getMainLooper())

    private var transformer: Transformer? = null
    private var pendingOutput: File? = null
    private var pendingOriginalBytes = 0L

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) compressPhoto(uri)
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) compressVideo(uri)
    }

    private val officePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) convertOfficeDocument(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preprocess)

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        cancelButton = findViewById(R.id.cancel_button)

        resetUi(getString(R.string.processing_idle_with_version, BuildConfig.VERSION_NAME))

        findViewById<View>(R.id.file_pool_button).setOnClickListener {
            startActivity(Intent(this, FilePoolActivity::class.java))
        }
        findViewById<View>(R.id.photo_card).setOnClickListener { photoPicker.launch("image/*") }
        findViewById<View>(R.id.video_card).setOnClickListener { videoPicker.launch("video/*") }
        findViewById<View>(R.id.pdf_card).setOnClickListener { officePicker.launch("*/*") }
        cancelButton.setOnClickListener {
            transformer?.cancel()
            transformer = null
            clearPendingOutput()
            resetUi(getString(R.string.processing_cancelled))
        }
    }

    override fun onDestroy() {
        transformer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun compressPhoto(uri: Uri) {
        setBusy(getString(R.string.processing_photo), indeterminate = true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pendingOriginalBytes = querySize(uri)
                    val rotation = contentResolver.openInputStream(uri)!!.use {
                        ExifInterface(it).rotationDegrees.toFloat()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸" }

                    var sample = 1
                    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > PHOTO_MAX_EDGE * 2) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val decoded = contentResolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("无法解码图片")

                    val ratio = minOf(1f, PHOTO_MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
                    var result = decoded
                    if (ratio < 1f) {
                        result = Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true)
                        decoded.recycle()
                    }
                    if (rotation != 0f) {
                        val oriented = Bitmap.createBitmap(result, 0, 0, result.width, result.height, Matrix().apply { postRotate(rotation) }, true)
                        result.recycle()
                        result = oriented
                    }
                    val output = File(cacheDir, "preprocess-${System.currentTimeMillis()}.jpg")
                    FileOutputStream(output).use { stream ->
                        check(result.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, stream))
                    }
                    result.recycle()
                    output
                }
            }.onSuccess { saveToFilePool(it, suggestedName(uri, "-compressed.jpg"), "image/jpeg") }
                .onFailure { showFailure(it) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun compressVideo(uri: Uri) {
        pendingOriginalBytes = querySize(uri)
        val output = File(cacheDir, "preprocess-${System.currentTimeMillis()}.mp4")
        pendingOutput = output
        setBusy(getString(R.string.processing_video_unknown), indeterminate = false)

        val effects = Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(VIDEO_HEIGHT)))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                    transformer = null
                    saveToFilePool(output, suggestedName(uri, "-compressed.mp4"), "video/mp4")
                }

                override fun onError(composition: androidx.media3.transformer.Composition, result: ExportResult, exception: ExportException) {
                    transformer = null
                    clearPendingOutput()
                    showFailure(exception)
                }
            }).build()
        transformer!!.start(edited, output.absolutePath)
        pollVideoProgress()
    }

    @OptIn(UnstableApi::class)
    private fun pollVideoProgress() {
        val current = transformer ?: return
        val holder = ProgressHolder()
        if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            progressBar.isIndeterminate = false
            progressBar.progress = holder.progress
            statusText.text = getString(R.string.processing_video, holder.progress)
        } else {
            progressBar.isIndeterminate = true
            statusText.setText(R.string.processing_video_unknown)
        }
        mainHandler.postDelayed({ pollVideoProgress() }, 500)
    }

    private fun saveToFilePool(file: File, name: String, mimeType: String) {
        pendingOutput = file
        statusText.setText(R.string.processing_saving)
        progressBar.visibility = View.GONE
        cancelButton.visibility = View.GONE
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FilePoolStore.save(this@PreprocessActivity, file, name, mimeType) }
            }.onSuccess {
                resetUi(getString(R.string.processing_done, readableSize(pendingOriginalBytes), readableSize(file.length())))
                clearPendingOutput()
            }.onFailure { showFailure(it) }
        }
    }

    private fun convertOfficeDocument(uri: Uri) {
        if (!DocumentPdfConverter.isAvailable) {
            AlertDialog.Builder(this).setTitle(R.string.office_convert)
                .setMessage(R.string.office_engine_missing).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val name = suggestedOriginalName(uri)
        val suffix = name.substringAfterLast('.', "").lowercase()
        if (suffix !in OFFICE_SUFFIXES) {
            resetUi(getString(R.string.office_unsupported, suffix))
            return
        }
        setBusy(getString(R.string.processing_office), indeterminate = true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pendingOriginalBytes = querySize(uri)
                    require(pendingOriginalBytes in 1..MAX_OFFICE_BYTES) { "文件必须小于 64 MiB" }
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val pdf = DocumentPdfConverter.convert(bytes, suffix)
                    require(pdf.size >= 4 && pdf.copyOfRange(0, 4).contentEquals("%PDF".toByteArray())) { "转换引擎未返回有效 PDF" }
                    File(cacheDir, "preprocess-${System.currentTimeMillis()}.pdf").apply { writeBytes(pdf) }
                }
            }.onSuccess { saveToFilePool(it, name.substringBeforeLast('.', name) + ".pdf", "application/pdf") }
                .onFailure { showFailure(it.cause ?: it) }
        }
    }

    private fun setBusy(message: String, indeterminate: Boolean) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = indeterminate
        cancelButton.visibility = View.VISIBLE
    }

    private fun resetUi(message: String) {
        statusText.text = message
        progressBar.visibility = View.GONE
        cancelButton.visibility = View.GONE
    }

    private fun showFailure(error: Throwable) {
        clearPendingOutput()
        resetUi(getString(R.string.processing_failed, error.message ?: error.javaClass.simpleName))
    }

    private fun clearPendingOutput() {
        pendingOutput?.delete()
        pendingOutput = null
    }

    private fun querySize(uri: Uri): Long = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    } ?: 0L

    private fun suggestedName(uri: Uri, suffix: String): String {
        val original = suggestedOriginalName(uri)
        return original.substringBeforeLast('.', original) + suffix
    }

    private fun suggestedOriginalName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "resource"

    private fun readableSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    private companion object {
        const val PHOTO_MAX_EDGE = 1920
        const val PHOTO_QUALITY = 82
        const val VIDEO_HEIGHT = 720
        val OFFICE_SUFFIXES = setOf("docx", "pptx", "xlsx")
        const val MAX_OFFICE_BYTES = 64L * 1024L * 1024L
    }
}
