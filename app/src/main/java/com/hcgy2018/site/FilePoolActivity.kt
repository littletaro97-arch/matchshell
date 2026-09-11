package com.hcgy2018.site

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
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
 *
 * **定位是"中转站"，不是存档处**（2026-09-11 用户明确）。
 * 文案与交互都服务这个定位：池子非空一直可见、上传完成主动引导删除，
 * 也不提供自动过期或自动清理 —— "要么上传要么删除"是用户的选择，壳只提示不代劳。
 *
 * 交互模型：长按进多选。多选态下底部操作条给「重命名 / 删除」；
 * 若本次是从网页上传拉起来的（pickerMode），操作条再多一个「提交已选」。
 */
class FilePoolActivity : ComponentActivity() {

    /** 本次是否由网页上传的 file chooser 拉起 */
    private var pickerMode = false
    private var allowMultiple = false

    private val entries = mutableListOf<FilePoolEntry>()
    private val selected = linkedSetOf<FilePoolEntry>()

    /** 是否处于多选态。长按、或在选择器态点选任一文件都会进入。 */
    private var selecting = false

    private lateinit var poolAdapter: FilePoolAdapter
    private lateinit var grid: GridView
    private lateinit var titleLabel: TextView
    private lateinit var badgeLabel: TextView
    private lateinit var selectedCountLabel: TextView
    private lateinit var cancelLabel: TextView
    private lateinit var pendingLabel: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var noteLabel: TextView
    private lateinit var renameButton: Button
    private lateinit var deleteButton: Button
    private lateinit var submitButton: Button

    private lateinit var renameOverlay: LinearLayout
    private lateinit var renamePane: LinearLayout
    private lateinit var renameStepLabel: TextView
    private lateinit var renameThumb: ImageView
    private lateinit var renameOldLabel: TextView
    private lateinit var renameNewInput: EditText
    private lateinit var renameHintLabel: TextView
    private lateinit var renameMetaLabel: TextView
    private lateinit var renameConfirm: Button

    private val renameQueue = mutableListOf<FilePoolEntry>()
    private var renameIndex = 0
    private var renamedCount = 0
    private var lastRenamedName: String? = null

    /** 过渡动画播放中：用来屏蔽连点，避免"确认"被按两次把两步并成一步 */
    private var animating = false

    /** 已存在的 PDF 不必先转换，从这里直接导入池子 */
    private val pdfImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_file_pool)
        pickerMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false)
        allowMultiple = intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false)

        bindViews()
        poolAdapter = FilePoolAdapter()
        grid.apply {
            adapter = poolAdapter
            setOnItemClickListener { _, _, position, _ -> onItemClick(entries[position]) }
            setOnItemLongClickListener { _, _, position, _ -> onItemLongClick(entries[position]); true }
        }

        renameButton.setOnClickListener { startRename() }
        deleteButton.setOnClickListener { confirmDeleteSelected() }
        submitButton.setOnClickListener { submitSelection() }
        cancelLabel.setOnClickListener { exitSelecting() }
        findViewById<Button>(R.id.file_pool_import_pdf).setOnClickListener {
            pdfImporter.launch(arrayOf("application/pdf"))
        }
        findViewById<Button>(R.id.file_pool_preprocess).setOnClickListener {
            startActivity(Intent(this, PreprocessActivity::class.java))
        }

        findViewById<Button>(R.id.rename_exit).setOnClickListener { closeRenameFlow() }
        renameConfirm.setOnClickListener { applyRename() }

        applyWindowInsets()
    }

    private fun bindViews() {
        grid = findViewById(R.id.file_pool_list)
        titleLabel = findViewById(R.id.file_pool_title)
        badgeLabel = findViewById(R.id.file_pool_badge)
        selectedCountLabel = findViewById(R.id.file_pool_selected_count)
        cancelLabel = findViewById(R.id.file_pool_cancel)
        pendingLabel = findViewById(R.id.file_pool_pending)
        toolbar = findViewById(R.id.file_pool_toolbar)
        emptyView = findViewById(R.id.file_pool_empty)
        noteLabel = findViewById(R.id.file_pool_note)
        renameButton = findViewById(R.id.file_pool_rename)
        deleteButton = findViewById(R.id.file_pool_delete)
        submitButton = findViewById(R.id.file_pool_submit)

        renameOverlay = findViewById(R.id.rename_overlay)
        renamePane = findViewById(R.id.rename_pane)
        renameStepLabel = findViewById(R.id.rename_step)
        renameThumb = findViewById(R.id.rename_thumb)
        renameOldLabel = findViewById(R.id.rename_old)
        renameNewInput = findViewById(R.id.rename_new)
        renameHintLabel = findViewById(R.id.rename_hint)
        renameMetaLabel = findViewById(R.id.rename_meta)
        renameConfirm = findViewById(R.id.rename_confirm)
    }

    override fun onResume() {
        super.onResume()
        // 从预处理页返回后要能看到最新产物，因此每次回到前台都重扫目录
        refreshPool()
    }

    // ---------------- 数据与状态 ----------------

    private fun refreshPool() {
        entries.clear()
        entries += FilePoolStore.list(this)
        selected.removeAll { entry -> entries.none { it.name == entry.name } }
        if (selected.isEmpty()) selecting = false
        updateUi()
    }

    private fun updateUi() {
        val count = entries.size
        poolAdapter.notifyDataSetChanged()

        emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
        noteLabel.visibility = if (count == 0) View.GONE else View.VISIBLE
        pendingLabel.text = getString(R.string.file_pool_pending, count)
        pendingLabel.visibility = if (count == 0) View.GONE else View.VISIBLE

        // 多选态：标题与「中转站」标签换成「已选 N 项」，工具栏让位给列表
        titleLabel.visibility = if (selecting) View.GONE else View.VISIBLE
        badgeLabel.visibility = if (selecting) View.GONE else View.VISIBLE
        toolbar.visibility = if (selecting) View.GONE else View.VISIBLE
        cancelLabel.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            selectedCountLabel.text = getString(R.string.file_pool_selected_count, selected.size)
            selectedCountLabel.visibility = View.VISIBLE
        } else {
            selectedCountLabel.visibility = View.GONE
        }

        val hasSelection = selected.isNotEmpty()
        setBarButtonEnabled(renameButton, hasSelection)
        setBarButtonEnabled(deleteButton, hasSelection)

        if (pickerMode && allowMultiple) {
            submitButton.visibility = View.VISIBLE
            submitButton.text = if (hasSelection) {
                getString(R.string.file_pool_submit, selected.size)
            } else {
                getString(R.string.file_pool_submit_empty)
            }
            setBarButtonEnabled(submitButton, hasSelection)
        } else {
            submitButton.visibility = View.GONE
        }
    }

    /**
     * 操作条的禁用态。
     * 按钮背景是 state-list，没有 disabled 分支，所以额外压一层透明度，
     * 否则"不可点"和"可点"看起来一样。
     */
    private fun setBarButtonEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun exitSelecting() {
        selecting = false
        selected.clear()
        updateUi()
    }

    private fun toggleSelection(entry: FilePoolEntry) {
        if (!selected.remove(entry)) selected.add(entry)
        // 取消掉最后一个选中项就自动退出多选，避免停在一个没有意义的状态
        if (selected.isEmpty()) selecting = false
        updateUi()
    }

    // ---------------- 列表交互 ----------------

    private fun onItemClick(entry: FilePoolEntry) {
        when {
            selecting -> toggleSelection(entry)
            pickerMode && allowMultiple -> {
                selecting = true
                toggleSelection(entry)
            }
            pickerMode -> returnSelection(listOf(entry))
            else -> openFile(entry)
        }
    }

    /** 长按：进入多选并选中。已经是多选态时只做累加，不反向取消（那会和单击混淆）。 */
    private fun onItemLongClick(entry: FilePoolEntry) {
        selecting = true
        selected.add(entry)
        updateUi()
    }

    private fun importPdf(uri: Uri) {
        val rawName = queryDisplayName(uri) ?: "导入的文档"
        val name = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FilePoolStore.saveFromUri(this@FilePoolActivity, uri, name) }
            }.onSuccess {
                refreshPool()
                toast(getString(R.string.file_pool_imported, it.name))
            }.onFailure {
                toastLong(getString(R.string.file_pool_import_failed, it.message ?: ""))
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun openFile(entry: FilePoolEntry) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(entry.uri, entry.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    /** 顶部按系统栏与挖孔实际高度留白，避免标题被前摄/状态栏压住。 */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.file_pool_root)
        val base = Padding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
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

    // ---------------- 删除 ----------------

    private fun confirmDeleteSelected() {
        val targets = entries.filter { selected.contains(it) }
        if (targets.isEmpty()) return

        val message = if (targets.size == 1) {
            getString(R.string.file_delete_message, targets[0].name)
        } else {
            getString(R.string.file_delete_message_multi, targets.joinToString("\n") { "• ${it.name}" })
        }
        val title = if (targets.size == 1) {
            getString(R.string.file_delete_title)
        } else {
            getString(R.string.file_delete_title_multi, targets.size)
        }

        dialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                var failed = 0
                targets.forEach {
                    ThumbnailStore.invalidate(FilePoolStore.cacheKey(it))
                    if (!FilePoolStore.delete(this, it)) failed++
                }
                exitSelecting()
                refreshPool()
                if (failed > 0) toastLong(getString(R.string.file_delete_failed))
            }
            .show()
            .roundCorners()
    }

    // ---------------- 重命名 ----------------

    private fun startRename() {
        val targets = entries.filter { selected.contains(it) }
        if (targets.isEmpty()) return
        renameQueue.clear()
        renameQueue += targets
        renameIndex = 0
        renamedCount = 0
        lastRenamedName = null
        animating = false
        renamePane.alpha = 1f
        renamePane.translationX = 0f
        showRenameStep()
        renameOverlay.visibility = View.VISIBLE
    }

    private fun showRenameStep() {
        val entry = renameQueue.getOrNull(renameIndex) ?: return closeRenameFlow()
        renameStepLabel.text = getString(R.string.rename_step, renameIndex + 1, renameQueue.size)
        renameOldLabel.text = entry.name
        renameNewInput.setText(entry.name)
        renameNewInput.setSelection(renameNewInput.text.length)
        clearRenameError()
        renameHintLabel.text = lastRenamedName
            ?.let { getString(R.string.rename_hint_prev, it) }
            ?: getString(R.string.rename_hint_editable)
        renameMetaLabel.text = getString(R.string.rename_meta, renamedCount)
        loadRenameThumb(entry)
    }

    private fun loadRenameThumb(entry: FilePoolEntry) {
        val key = FilePoolStore.cacheKey(entry)
        renameThumb.tag = key
        val cached = ThumbnailStore.peek(key)
        if (cached != null) {
            renameThumb.setImageBitmap(cached)
            return
        }
        renameThumb.setImageDrawable(null)
        lifecycleScope.launch {
            val bitmap = ThumbnailStore.load(this@FilePoolActivity, entry)
            if (renameThumb.tag == key) renameThumb.setImageBitmap(bitmap)
        }
    }

    private fun clearRenameError() {
        renameNewInput.background =
            ContextCompat.getDrawable(this, R.drawable.rename_input_background)
        renameHintLabel.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
    }

    private fun showRenameError(message: String) {
        renameNewInput.background =
            ContextCompat.getDrawable(this, R.drawable.rename_input_error_background)
        renameHintLabel.setTextColor(ContextCompat.getColor(this, R.color.danger))
        renameHintLabel.text = message
    }

    private fun applyRename() {
        if (animating) return
        val entry = renameQueue.getOrNull(renameIndex) ?: return closeRenameFlow()
        val newName = renameNewInput.text.toString().trim()

        if (newName.isEmpty()) {
            showRenameError(getString(R.string.rename_name_empty))
            return
        }
        // 先本地判一次重名，大部分冲突不必碰磁盘
        if (FilePoolStore.nameExists(this, newName, entry.name)) {
            showRenameError(getString(R.string.rename_hint_conflict))
            return
        }

        // 先落盘、成功才推进动画。反过来的话会出现"动画走了但其实没改成"。
        val result = FilePoolStore.rename(this, entry, newName)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            showRenameError(
                when (failure.message) {
                    FilePoolStore.NAME_TAKEN -> getString(R.string.rename_hint_conflict)
                    FilePoolStore.NO_NAME -> getString(R.string.rename_name_empty)
                    else -> getString(R.string.rename_failed, failure.message ?: "")
                }
            )
            return
        }

        val updated = result.getOrThrow()
        ThumbnailStore.invalidate(FilePoolStore.cacheKey(entry))
        renamedCount++
        lastRenamedName = updated.name
        renameQueue[renameIndex] = updated

        if (renameIndex + 1 >= renameQueue.size) {
            completeRenameFlow()
        } else {
            advanceRenameStep()
        }
    }

    /**
     * 步骤过渡：旧内容向左滑出并淡出 → 更新内容 → 新内容自右滑入并淡入。
     *
     * 220ms / cubic-bezier(.23,1,.32,1)，位移 28dp（不用整屏宽，轻快不拖沓）。
     * 顶部步骤标签和底部操作条不参与移动，避免整屏都在晃。
     * 系统开启「减少动画」时退化为直接切换。
     */
    private fun advanceRenameStep() {
        if (animatorScale == 0f) {
            renameIndex++
            showRenameStep()
            return
        }
        val offset = RENAME_SLIDE_DP * resources.displayMetrics.density
        animating = true
        renamePane.animate()
            .translationX(-offset).alpha(0f)
            .setDuration(RENAME_STEP_MS).setInterpolator(RENAME_EASE)
            .withEndAction {
                renameIndex++
                showRenameStep()
                renamePane.translationX = offset
                renamePane.alpha = 0f
                renamePane.animate()
                    .translationX(0f).alpha(1f)
                    .setDuration(RENAME_STEP_MS).setInterpolator(RENAME_EASE)
                    .withEndAction { animating = false }
                    .start()
            }
            .start()
    }

    /** 最后一个改完：不滑动，淡出后关闭面板并刷新列表。 */
    private fun completeRenameFlow() {
        val done = renamedCount
        val finish = Runnable {
            closeRenameFlow()
            if (done > 0) toast(getString(R.string.rename_hint_done, done))
        }
        if (animatorScale == 0f) {
            finish.run()
            return
        }
        animating = true
        renamePane.animate()
            .alpha(0f)
            .setDuration(RENAME_STEP_MS).setInterpolator(RENAME_EASE)
            .withEndAction {
                finish.run()
                animating = false
            }
            .start()
    }

    /** 中途退出。已改名的文件在确认时就落盘了，所以这里什么都不用回滚。 */
    private fun closeRenameFlow() {
        renameOverlay.visibility = View.GONE
        renamePane.alpha = 1f
        renamePane.translationX = 0f
        animating = false
        renameQueue.clear()
        exitSelecting()
        refreshPool()
    }

    /** 系统设置里的动画时长缩放；为 0 表示用户要求减少动画。 */
    private val animatorScale: Float
        get() = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)

    // ---------------- 提交（选择器态） ----------------

    private fun submitSelection() {
        returnSelection(entries.filter { selected.contains(it) })
    }

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
            // 让 MainActivity 记住这批文件：上传完成后要据此询问是否从池里清掉
            putStringArrayListExtra(EXTRA_SUBMITTED_NAMES, ArrayList(picked.map { it.name }))
            putStringArrayListExtra(EXTRA_SUBMITTED_KEYS, ArrayList(picked.map { FilePoolStore.cacheKey(it) }))
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    // ---------------- 适配器 ----------------

    private inner class FilePoolAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_file_pool, parent, false)
            val entry = getItem(position)
            val image = view.findViewById<ImageView>(R.id.file_thumbnail)
            view.findViewById<TextView>(R.id.file_name).text = entry.name
            view.setBackgroundResource(
                if (selected.contains(entry)) R.drawable.file_pool_item_selected_background
                else R.drawable.file_pool_item_background
            )

            val key = FilePoolStore.cacheKey(entry)
            image.tag = key
            val cached = ThumbnailStore.peek(key)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                // 先清空，避免复用视图时闪出上一个文件的缩略图
                image.setImageDrawable(null)
                lifecycleScope.launch {
                    val bitmap = ThumbnailStore.load(this@FilePoolActivity, entry)
                    if (image.tag == key) image.setImageBitmap(bitmap)
                }
            }
            return view
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toastLong(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_SELECT_MODE = "select_mode"
        const val EXTRA_ALLOW_MULTIPLE = "allow_multiple"
        const val EXTRA_ACCEPT_TYPES = "accept_types"

        /** 本次提交给网页的文件名与追踪键，供上传完成后询问清理用 */
        const val EXTRA_SUBMITTED_NAMES = "submitted_names"
        const val EXTRA_SUBMITTED_KEYS = "submitted_keys"

        private const val RENAME_STEP_MS = 220L
        private const val RENAME_SLIDE_DP = 28

        /** cubic-bezier(.23, 1, .32, 1)：快进慢出，无回弹 */
        private val RENAME_EASE = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    }
}
