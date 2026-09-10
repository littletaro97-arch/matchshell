package com.hcgy2018.site

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilePoolActivity : ComponentActivity() {
    private var selectMode = false
    private var allowMultiple = false
    private val entries = mutableListOf<FilePoolEntry>()
    private lateinit var poolAdapter: FilePoolAdapter
    private lateinit var emptyView: TextView

    private val externalPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK, result.data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_pool)
        selectMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false)
        allowMultiple = intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false)
        entries += FilePoolStore.list(this)

        emptyView = findViewById(R.id.file_pool_empty)
        poolAdapter = FilePoolAdapter()
        findViewById<GridView>(R.id.file_pool_list).apply {
            adapter = poolAdapter
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries[position]
                if (selectMode) returnSelection(entry.uri) else openFile(entry)
            }
            setOnItemLongClickListener { _, _, position, _ -> confirmDelete(entries[position]); true }
        }
        updateEmptyState()
        findViewById<Button>(R.id.file_pool_browse).apply {
            visibility = if (selectMode) View.VISIBLE else View.GONE
            setOnClickListener { browseOtherFiles() }
        }
    }

    private fun confirmDelete(entry: FilePoolEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.file_delete_title)
            .setMessage(getString(R.string.file_delete_message, entry.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (FilePoolStore.delete(this, entry)) {
                    entries.remove(entry)
                    poolAdapter.notifyDataSetChanged()
                    updateEmptyState()
                } else Toast.makeText(this, R.string.file_delete_failed, Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun updateEmptyState() { emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE }

    private fun returnSelection(uri: Uri) {
        setResult(Activity.RESULT_OK, Intent().apply { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        finish()
    }

    private fun openFile(entry: FilePoolEntry) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) }
    }

    private fun browseOtherFiles() {
        val accepted = intent.getStringArrayExtra(EXTRA_ACCEPT_TYPES)?.filter { it.isNotBlank() }.orEmpty()
        externalPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accepted.size == 1) accepted.first() else "*/*"
            if (accepted.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }

    private inner class FilePoolAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_file_pool, parent, false)
            val entry = getItem(position)
            val image = view.findViewById<ImageView>(R.id.file_thumbnail)
            view.findViewById<TextView>(R.id.file_name).text = entry.name
            image.tag = entry.uri
            image.setImageBitmap(genericThumbnail(entry.name))
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadThumbnail(entry) }
                if (bitmap != null && image.tag == entry.uri) image.setImageBitmap(bitmap)
            }
            return view
        }
    }

    @SuppressLint("NewApi")
    private fun loadThumbnail(entry: FilePoolEntry): Bitmap? = runCatching {
        if (entry.mimeType == "application/pdf") {
            contentResolver.openFileDescriptor(entry.uri, "r")!!.use { fd ->
                PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page ->
                    val width = 320
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } }
            }
        } else contentResolver.loadThumbnail(entry.uri, Size(320, 320), null)
    }.getOrNull()

    private fun genericThumbnail(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(this, R.color.card_background))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@FilePoolActivity, R.color.brand)
            textSize = 42f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(name.substringAfterLast('.', "FILE").uppercase().take(5), 120f, 134f, paint)
        return bitmap
    }

    companion object {
        const val EXTRA_SELECT_MODE = "select_mode"
        const val EXTRA_ALLOW_MULTIPLE = "allow_multiple"
        const val EXTRA_ACCEPT_TYPES = "accept_types"
    }
}
