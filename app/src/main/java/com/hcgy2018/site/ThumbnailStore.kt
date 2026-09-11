package com.hcgy2018.site

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 文件池缩略图。内存 LRU + 磁盘缓存，键用 [FilePoolStore.cacheKey]
 * （文件名 + 大小 + 修改时间），所以改名或换文件后自动失效，不需要额外的失效逻辑。
 *
 * 为什么要缓存：改之前适配器每绑定一次就新起协程解码一次，且没有任何缓存。
 * 图片勉强能忍，视频抽首帧在低端机上单次 100–300ms，滚动会明显卡。
 *
 * 视频解码额外加了信号量串行化：MediaMetadataRetriever 很吃内存，
 * 一次并发解 5 个视频在低端机上容易触发 GC 风暴甚至 OOM。
 */
object ThumbnailStore {

    private const val THUMB_PX = 320
    private const val PLACEHOLDER_PX = 240
    private const val JPEG_QUALITY = 85
    private const val MAX_DISK_FILES = 200
    private const val MEMORY_BYTES = 24 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 视频抽帧串行闸门，见类注释。 */
    private val videoGate = Semaphore(1)

    /** 同步取内存里的缩略图，用于绑定瞬间直接出图、避免闪一下旧图。 */
    fun peek(key: String): Bitmap? = memory.get(key)

    /**
     * 取缩略图：内存 → 磁盘 → 现场解码（结果落盘）。
     * 永不抛异常；解不出来就退化成扩展名占位图。
     */
    suspend fun load(context: Context, entry: FilePoolEntry): Bitmap {
        val key = FilePoolStore.cacheKey(entry)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            memory.get(key)?.let { return@withContext it }

            readDisk(context, key)?.let {
                memory.put(key, it)
                return@withContext it
            }

            val bitmap = runCatching { decode(context, entry) }.getOrNull()
                ?: placeholder(context, entry.name)
            writeDisk(context, key, bitmap)
            memory.put(key, bitmap)
            bitmap
        }
    }

    /** 换文件后让缓存立即失效（改名/删除时调用，避免同名占着旧图）。 */
    fun invalidate(key: String) {
        memory.remove(key)
    }

    private suspend fun decode(context: Context, entry: FilePoolEntry): Bitmap? = when {
        entry.mimeType == "application/pdf" -> decodePdf(context, entry.uri)
        entry.mimeType.startsWith("video/") -> decodeVideo(context, entry.uri)
        entry.mimeType.startsWith("image/") -> decodeImage(context, entry.uri)
        else -> null
    }

    private fun decodePdf(context: Context, uri: Uri): Bitmap? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val height = (THUMB_PX * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(THUMB_PX, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /**
     * 抽视频首帧。上一版 MP4 只能显示"MP4"文字占位，就是因为没走这条路。
     * 优先取同步帧（快），取不到再退回最近帧。
     */
    private suspend fun decodeVideo(context: Context, uri: Uri): Bitmap? = videoGate.withPermit {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX, THUMB_PX
                )
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.getFrameAtTime(0)
            frame
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // API 29+ 走系统缩略图：省内存，且自带了 EXIF 方向处理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null) }
                .getOrNull()?.let { return it }
        }

        // 低版本回退：按 inSampleSize 自己解码，再按 EXIF 方向转正。
        // （原来无条件下调 loadThumbnail，而它是 API 29 才有的，minSdk 26 的设备上会直接抛。）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        return applyExifRotation(context, uri, bitmap)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** 解不出来时的兜底：卡底色 + 品牌色扩展名，与改动前的观感一致。 */
    private fun placeholder(context: Context, name: String): Bitmap {
        val size = PLACEHOLDER_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.card_background))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.brand)
            textSize = 42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = name.substringAfterLast('.', "FILE").uppercase().take(5)
        canvas.drawText(label, size / 2f, size / 2f + paint.textSize / 3f, paint)
        return bitmap
    }

    private fun thumbsDir(context: Context): File =
        File(context.cacheDir, "thumbs").apply { if (!exists()) mkdirs() }

    private fun diskFile(context: Context, key: String): File =
        File(thumbsDir(context), sha1(key) + ".jpg")

    private fun readDisk(context: Context, key: String): Bitmap? = runCatching {
        val file = diskFile(context, key)
        if (!file.exists()) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun writeDisk(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(diskFile(context, key)).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }
            pruneDisk(context)
        }
    }

    /** 磁盘缓存按数量裁剪，避免长期使用无限增长。 */
    private fun pruneDisk(context: Context) {
        val files = thumbsDir(context).listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_FILES)
            .forEach { it.delete() }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
