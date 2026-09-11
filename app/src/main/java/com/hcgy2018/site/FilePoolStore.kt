package com.hcgy2018.site

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

data class FilePoolEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long
)

/**
 * 文件池存放在 APP 私有目录，不放公共 Downloads。
 *
 * 原因：公共目录里的照片/视频会被系统相册收录，用户设备上会出现两份看起来一样的文件；
 * 私有目录既不进相册、也不进文件管理器的最近列表。
 * 代价是卸载 APP 会一并删除，因此文件池界面有常驻提示，不得省略。
 *
 * **定位：中转站，不是存档处。**（2026-09-11 用户明确）
 * 这里只负责"文件从本机去到网页"这一段；上传完成即引导用户删除，
 * 界面文案不得出现暗示长期保存的说法。
 */
object FilePoolStore {

    private const val DIR_NAME = "火柴公益文件池"

    /** 列出池内文件。直接扫目录，不依赖持久化索引，文件被外部删除后也能自愈。 */
    fun list(context: Context): List<FilePoolEntry> =
        poolDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { entryOf(context, it) }
            .orEmpty()

    /** 把预处理产物写进池子；重名时自动加序号后缀，不覆盖已有文件。 */
    fun save(context: Context, source: File, name: String, mimeType: String): FilePoolEntry {
        val target = uniqueTarget(context, name)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return entryOf(context, target)
    }

    /**
     * 从系统选择器选的 URI 直接导入池子。
     * 这是"网页上传只能从文件池取件"之后必须留的入口：用户手里已有的 PDF 不必先转换。
     */
    fun saveFromUri(context: Context, uri: Uri, name: String): FilePoolEntry {
        val target = uniqueTarget(context, name.ifBlank { "导入的文件" })
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return entryOf(context, target)
    }

    fun delete(context: Context, entry: FilePoolEntry): Boolean =
        runCatching { File(poolDir(context), entry.name).delete() }.getOrDefault(false)

    /**
     * 池内是否已有同名文件。**不看扩展名** —— 同一目录本来也不允许重名。
     *
     * @param except 排除这个名字本身（改名前后的原名要与新名比较时用）
     */
    fun nameExists(context: Context, name: String, except: String? = null): Boolean =
        list(context).any {
            it.name.equals(name, ignoreCase = true) && !it.name.equals(except, ignoreCase = true)
        }

    /**
     * 重命名池内文件。
     *
     * 与 [uniqueTarget] 的导入行为**刻意相反**：命中重名直接失败，不自动加序号。
     * 导入是系统代劳（加序号合理）；改名是用户明确表达意图，静默改成别的名字比报错更糟。
     */
    fun rename(context: Context, entry: FilePoolEntry, newName: String): Result<FilePoolEntry> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return Result.failure(IOException(NO_NAME))
        if (trimmed.contains('/') || trimmed.contains('\\')) return Result.failure(IOException(BAD_NAME))
        // 名字没变就当成功直接返回，避免无谓的磁盘操作
        if (trimmed == entry.name) return Result.success(entry)
        if (nameExists(context, trimmed, entry.name)) return Result.failure(IOException(NAME_TAKEN))

        val source = File(poolDir(context), entry.name)
        val target = File(poolDir(context), trimmed)
        if (!source.renameTo(target)) return Result.failure(IOException(RENAME_FAILED))
        return Result.success(entryOf(context, target))
    }

    /**
     * 缩略图缓存键与"已提交给网页"的追踪键，都用它。
     * 带上大小与修改时间，改名或换文件后自动失效，不需要额外的失效逻辑。
     */
    fun cacheKey(entry: FilePoolEntry): String = "${entry.name}|${entry.size}|${entry.lastModified}"

    private fun entryOf(context: Context, file: File): FilePoolEntry =
        FilePoolEntry(
            uri = uriOf(context, file),
            name = file.name,
            mimeType = mimeTypeOf(file),
            size = file.length(),
            lastModified = file.lastModified()
        )

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

    /** 重命名失败的原因码，调用侧据此换成提示文案。 */
    const val NO_NAME = "empty_name"
    const val BAD_NAME = "bad_name"
    const val NAME_TAKEN = "name_taken"
    const val RENAME_FAILED = "rename_failed"
}
