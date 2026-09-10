package com.hcgy2018.site

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

data class FilePoolEntry(val uri: Uri, val name: String, val mimeType: String, val size: Long)

/**
 * 文件池存放在 APP 私有目录，不放公共 Downloads。
 *
 * 原因：公共目录里的照片/视频会被系统相册收录，用户设备上会出现两份看起来一样的文件；
 * 私有目录既不进相册、也不进文件管理器的最近列表。
 * 代价是卸载 APP 会一并删除，因此文件池界面有常驻提示，不得省略。
 */
object FilePoolStore {

    private const val DIR_NAME = "火柴公益文件池"

    /** 列出池内文件。直接扫目录，不依赖持久化索引，文件被外部删除后也能自愈。 */
    fun list(context: Context): List<FilePoolEntry> =
        poolDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { FilePoolEntry(uriOf(context, it), it.name, mimeTypeOf(it), it.length()) }
            .orEmpty()

    /** 把预处理产物写进池子；重名时自动加序号后缀，不覆盖已有文件。 */
    fun save(context: Context, source: File, name: String, mimeType: String): FilePoolEntry {
        val target = uniqueTarget(context, name)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return FilePoolEntry(uriOf(context, target), target.name, mimeType, target.length())
    }

    /**
     * 从系统选择器选的 URI 直接导入池子。
     * 这是"网页上传只能从文件池取件"之后必须留的入口：用户手里已有的 PDF 不必先转换。
     */
    fun saveFromUri(context: Context, uri: Uri, name: String): FilePoolEntry {
        val target = uniqueTarget(context, name.ifBlank { "导入的文件" })
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return FilePoolEntry(uriOf(context, target), target.name, mimeTypeOf(target), target.length())
    }

    fun delete(context: Context, entry: FilePoolEntry): Boolean =
        runCatching { File(poolDir(context), entry.name).delete() }.getOrDefault(false)

    private fun poolDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun uniqueTarget(context: Context, name: String): File {
        val dir = poolDir(context)
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val next = File(dir, if (suffix.isEmpty()) "$base ($index)" else "$base ($index).$suffix")
            if (!next.exists()) return next
            index++
        }
    }

    private fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "mp4" -> "video/mp4"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }
    }
}
