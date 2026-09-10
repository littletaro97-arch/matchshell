package com.hcgy2018.site

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件池。这是网页上传时的唯一文件来源：壳不再提供"从其他位置选文件"的入口，
 * 所有要提交的文件都必须先经「资源预处理」进入本池。
 */
class FilePoolActivity : ComponentActivity() {
    private var selectMode = false
    private var allowMultiple = false
    private val entries = mutableListOf<FilePoolEntry>()
    private val selected = linkedSetOf<FilePoolEntry>()
    private lateinit var poolAdapter: FilePoolAdapter
    private lateinit var emptyView: TextView
    private lateinit var submitButton: Button

    /** 已存在的 PDF 不必先转换，从这里直接导入池子 */
    private val pdfImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_file_pool)
        selectMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false)
        allowMultiple = intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false)

        emptyView = findViewById(R.id.file_pool_empty)
        submitButton = findViewById(R.id.file_pool_submit)
        poolAdapter = FilePoolAdapter()
        findViewById<GridView>(R.id.file_pool_list).apply {
            adapter = poolAdapter
            setOnItemClickListener { _, _, position, _ -> onItemClick(entries[position]) }
            setOnItemLongClickListener { _, _, position, _ -> confirmDelete(entries[position]); true }
        }
        submitButton.apply {
            visibility = if (selectMode && allowMultiple) View.VISIBLE else View.GONE
            setOnClickListener { returnSelection(selected.toList()) }
        }
        findViewById<Button>(R.id.file_pool_import_pdf).setOnClickListener {
            pdfImporter.launch(arrayOf("application/pdf"))
        }
        findViewById<Button>(R.id.file_pool_preprocess).setOnClickListener {
            startActivity(Intent(this, PreprocessActivity::class.java))
        }
        applyWindowInsets()
    }

    override fun onResume() {
        super.onResume()
        // 从预处理页返回后要能看到最新产物，因此每次回到前台都重扫目录
        refreshPool()
    }

    private fun refreshPool() {
        entries.clear()
        entries += FilePoolStore.list(this)
        selected.removeAll { entry -> entries.none { it.name == entry.name } }
        poolAdapter.notifyDataSetChanged()
        updateEmptyState()
        updateSubmitButton()
    }

    private fun onItemClick(entry: FilePoolEntry) {
        when {
            selectMode && allowMultiple -> {
                if (!selected.remove(entry)) selected.add(entry)
                poolAdapter.notifyDataSetChanged()
                updateSubmitButton()
            }
            selectMode -> returnSelection(listOf(entry))
            else -> openFile(entry)
        }
    }

    private fun updateSubmitButton() {
        if (submitButton.visibility != View.VISIBLE) return
        submitButton.isEnabled = selected.isNotEmpty()
        submitButton.text = if (selected.isEmpty()) {
            getString(R.string.file_pool_submit_empty)
        } else {
            getString(R.string.file_pool_submit, selected.size)
        }
    }

    private fun importPdf(uri: Uri) {
        val rawName = queryDisplayName(uri) ?: "导入的文档"
        val name = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FilePoolStore.saveFromUri(this@FilePoolActivity, uri, name) }
            }.onSuccess {
                refreshPool()
                Toast.makeText(
                    this@FilePoolActivity,
                    getString(R.string.file_pool_imported, it.name),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@FilePoolActivity,
                    getString(R.string.file_pool_import_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** 顶部按系统栏与挖孔实际高度留白，避免标题被前摄/状态栏压住。 */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.file_pool_root)
        val base = Padding(
            root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = base.left + bars.left,
                top = base.top + bars.top,
                right = base.right + bars.right,
                bottom = base.bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun confirmDelete(entry: FilePoolEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.file_delete_title)
            .setMessage(getString(R.string.file_delete_message, entry.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (FilePoolStore.delete(this, entry)) {
                    refreshPool()
                } else Toast.makeText(this, R.string.file_delete_failed, Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun updateEmptyState() { emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE }

    private fun returnSelection(picked: List<FilePoolEntry>) {
        if (picked.isEmpty()) return
        val uris = picked.map { it.uri }
        val intent = Intent().apply {
            data = uris.first()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size > 1) {
                clipData = ClipData.newUri(contentResolver, "matchshell-pool", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun openFile(entry: FilePoolEntry) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) }
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
            view.setBackgroundResource(
                if (selected.contains(entry)) R.drawable.file_pool_item_selected_background
                else R.drawable.file_pool_item_background
            )
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
