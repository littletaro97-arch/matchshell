package com.hcgy2018.site

import android.content.ContentValues
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FilePoolEntry(val uri: Uri, val name: String, val mimeType: String, val size: Long)

object FilePoolStore {
    private const val PREFS = "file_pool"
    private const val KEY_ENTRIES = "entries"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/火柴公益文件池"

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @SuppressLint("NewApi")
    fun save(context: Context, source: File, name: String, mimeType: String): FilePoolEntry {
        check(isSupported()) { context.getString(R.string.file_pool_android_too_old) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建文件池文件")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val entry = FilePoolEntry(uri, name, mimeType, source.length())
            remember(context, entry)
            return entry
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    fun list(context: Context): List<FilePoolEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
        val valid = mutableListOf<FilePoolEntry>()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val entry = FilePoolEntry(
                    Uri.parse(item.getString("uri")),
                    item.getString("name"),
                    item.optString("mime", "application/octet-stream"),
                    item.optLong("size", 0L)
                )
                val exists = runCatching {
                    context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (exists) valid += entry
            }
        }
        write(context, valid)
        return valid
    }

    fun delete(context: Context, entry: FilePoolEntry): Boolean {
        val deleted = runCatching { context.contentResolver.delete(entry.uri, null, null) > 0 }.getOrDefault(false)
        if (deleted) write(context, list(context).filterNot { it.uri == entry.uri })
        return deleted
    }

    private fun remember(context: Context, entry: FilePoolEntry) {
        val entries = list(context).toMutableList()
        entries.removeAll { it.uri == entry.uri }
        entries.add(0, entry)
        write(context, entries.take(100))
    }

    private fun write(context: Context, entries: List<FilePoolEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("uri", entry.uri.toString())
                put("name", entry.name)
                put("mime", entry.mimeType)
                put("size", entry.size)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
