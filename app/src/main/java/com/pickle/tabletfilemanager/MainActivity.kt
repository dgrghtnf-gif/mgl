package com.pickle.tabletfilemanager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---------- views ----------
    private lateinit var sidebarPanel: LinearLayout
    private lateinit var sidebarList: LinearLayout
    private lateinit var storageText: TextView
    private lateinit var storageProgress: ProgressBar
    private lateinit var breadcrumb: TextView
    private lateinit var searchInput: EditText
    private lateinit var btnNewFolder: TextView
    private lateinit var btnSort: TextView
    private lateinit var fileGrid: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var permissionText: TextView
    private lateinit var btnGrant: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var btnPaste: TextView
    private lateinit var btnPasteCancel: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionText: TextView
    private lateinit var btnCopy: TextView
    private lateinit var btnMove: TextView
    private lateinit var btnRename: TextView
    private lateinit var btnShare: TextView
    private lateinit var btnDelete: TextView

    // ---------- state ----------
    private enum class PendingType { COPY, MOVE }

    private class PendingOp(val type: PendingType, val paths: List<String>, val label: String)

    private class Category(
        val id: String,
        val title: String,
        val color: Int,
        val exts: Set<String>?,
        val flat: Boolean,
        val isTrash: Boolean,
        val baseDir: File
    ) {
        var subtitle: String = "—"
    }

    private val categories = ArrayList<Category>()
    private val sidebarRows = HashMap<String, View>()
    private lateinit var gridAdapter: FileGridAdapter

    private lateinit var currentCategory: Category
    private lateinit var currentDir: File
    private var sortMode = SortMode.DATE
    private var pendingOp: PendingOp? = null
    private var suppressSearch = false
    private var loadToken = 0

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { loadCurrent() }

    companion object {
        private const val REQ_STORAGE = 1001
        private const val STATE_CAT = "state_cat"
        private const val STATE_DIR = "state_dir"
        private const val STATE_SORT = "state_sort"
    }

    // =====================================================================
    // lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        buildCategories()
        buildSidebar()
        setupToolbar()
        setupGrid()
        setupSelectionActions()

        val savedCat = savedInstanceState?.getString(STATE_CAT)
        val savedDir = savedInstanceState?.getString(STATE_DIR)
        val savedSort = savedInstanceState?.getString(STATE_SORT)
        sortMode = SortMode.values().firstOrNull { it.name == savedSort } ?: SortMode.DATE
        btnSort.text = "정렬: ${sortMode.label}"

        currentCategory = categories.firstOrNull { it.id == savedCat } ?: categories.first()
        val restored = savedDir?.let { File(it) }
        currentDir = if (restored != null && restored.isDirectory) restored else currentCategory.baseDir

        applyResponsiveLayout()
        highlightCategory()
        updateStorageBar()
        refreshSidebarStats()

        if (hasStoragePermission()) {
            permissionPanel.visibility = View.GONE
            loadCurrent()
        } else {
            showPermissionPanel()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            if (permissionPanel.visibility == View.VISIBLE) {
                permissionPanel.visibility = View.GONE
                loadCurrent()
            }
            updateStorageBar()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAT, currentCategory.id)
        outState.putString(STATE_DIR, currentDir.absolutePath)
        outState.putString(STATE_SORT, sortMode.name)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(searchRunnable)
        executor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pendingOp != null) {
            cancelPaste()
            return
        }
        if (!currentCategory.flat && currentDir.absolutePath != currentCategory.baseDir.absolutePath) {
            val parent = currentDir.parentFile
            currentDir = if (parent != null && parent.absolutePath.length >= currentCategory.baseDir.absolutePath.length) {
                parent
            } else {
                currentCategory.baseDir
            }
            gridAdapter.clearSelection()
            loadCurrent()
            return
        }
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (hasStoragePermission()) {
                permissionPanel.visibility = View.GONE
                loadCurrent()
            } else {
                toast("저장소 권한을 허용해야 파일을 볼 수 있습니다")
            }
        }
    }

    // =====================================================================
    // setup
    // =====================================================================

    private fun bindViews() {
        sidebarPanel = findViewById(R.id.sidebarPanel)
        sidebarList = findViewById(R.id.sidebarList)
        storageText = findViewById(R.id.storageText)
        storageProgress = findViewById(R.id.storageProgress)
        breadcrumb = findViewById(R.id.breadcrumb)
        searchInput = findViewById(R.id.searchInput)
        btnNewFolder = findViewById(R.id.btnNewFolder)
        btnSort = findViewById(R.id.btnSort)
        fileGrid = findViewById(R.id.fileGrid)
        emptyView = findViewById(R.id.emptyView)
        permissionPanel = findViewById(R.id.permissionPanel)
        permissionText = findViewById(R.id.permissionText)
        btnGrant = findViewById(R.id.btnGrant)
        pasteBar = findViewById(R.id.pasteBar)
        pasteText = findViewById(R.id.pasteText)
        btnPaste = findViewById(R.id.btnPaste)
        btnPasteCancel = findViewById(R.id.btnPasteCancel)
        selectionBar = findViewById(R.id.selectionBar)
        selectionText = findViewById(R.id.selectionText)
        btnCopy = findViewById(R.id.btnCopy)
        btnMove = findViewById(R.id.btnMove)
        btnRename = findViewById(R.id.btnRename)
        btnShare = findViewById(R.id.btnShare)
        btnDelete = findViewById(R.id.btnDelete)
    }

    private fun buildCategories() {
        val root = FileOps.externalRoot()
        categories.clear()
        categories.add(
            Category(
                id = "internal",
                title = "내부 저장소",
                color = 0xFF3B82F6.toInt(),
                exts = null,
                flat = false,
                isTrash = false,
                baseDir = root
            )
        )
        categories.add(
            Category(
                id = "downloads",
                title = "다운로드",
                color = 0xFF10B981.toInt(),
                exts = null,
                flat = false,
                isTrash = false,
                baseDir = File(root, Environment.DIRECTORY_DOWNLOADS)
            )
        )
        categories.add(
            Category(
                id = "videos",
                title = "동영상",
                color = 0xFF8B5CF6.toInt(),
                exts = FileKinds.videoExtensions,
                flat = true,
                isTrash = false,
                baseDir = root
            )
        )
        categories.add(
            Category(
                id = "images",
                title = "사진 / 이미지",
                color = 0xFFEC4899.toInt(),
                exts = FileKinds.imageExtensions,
                flat = true,
                isTrash = false,
                baseDir = root
            )
        )
        categories.add(
            Category(
                id = "documents",
                title = "문서 / PDF",
                color = 0xFFF59E0B.toInt(),
                exts = FileKinds.documentExtensions,
                flat = true,
                isTrash = false,
                baseDir = root
            )
        )
        categories.add(
            Category(
                id = "apk",
                title = "설치 파일 (APK)",
                color = 0xFF06B6D4.toInt(),
                exts = FileKinds.apkExtensions,
                flat = true,
                isTrash = false,
                baseDir = root
            )
        )
        categories.add(
            Category(
                id = "trash",
                title = "휴지통",
                color = 0xFF64748B.toInt(),
                exts = null,
                flat = false,
                isTrash = true,
                baseDir = FileOps.trashDir()
            )
        )
    }

    private fun buildSidebar() {
        sidebarList.removeAllViews()
        sidebarRows.clear()
        for (cat in categories) {
            val row = layoutInflater.inflate(R.layout.layout_sidebar_item, sidebarList, false)
            val name = row.findViewById<TextView>(R.id.catName)
            val meta = row.findViewById<TextView>(R.id.catMeta)
            val dot = row.findViewById<View>(R.id.catDot)
            name.text = cat.title
            meta.text = cat.subtitle
            dot.background = dotDrawable(cat.color)
            row.setOnClickListener { selectCategory(cat) }
            sidebarList.addView(row)
            sidebarRows[cat.id] = row
        }
    }

    private fun setupToolbar() {
        btnNewFolder.setOnClickListener { createFolder() }
        btnSort.setOnClickListener { cycleSort() }
        breadcrumb.setOnClickListener { goUp() }
        btnGrant.setOnClickListener { requestStoragePermission() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearch) return
                mainHandler.removeCallbacks(searchRunnable)
                mainHandler.postDelayed(searchRunnable, 350)
            }
        })
        searchInput.setOnEditorActionListener { _, _, _ ->
            loadCurrent()
            true
        }
    }

    private fun setupGrid() {
        gridAdapter = FileGridAdapter(
            onClick = { item -> openItem(item) },
            onLongClick = { item -> toggleSelection(item) },
            onToggle = { item -> toggleSelection(item) }
        )
        fileGrid.layoutManager = GridLayoutManager(this, 3)
        fileGrid.adapter = gridAdapter
    }

    private fun setupSelectionActions() {
        btnCopy.setOnClickListener { startPending(PendingType.COPY) }
        btnMove.setOnClickListener { startPending(PendingType.MOVE) }
        btnRename.setOnClickListener { renameSelected() }
        btnShare.setOnClickListener { shareSelected() }
        btnDelete.setOnClickListener { deleteSelected() }
        btnPaste.setOnClickListener { doPaste() }
        btnPasteCancel.setOnClickListener { cancelPaste() }
    }

    private fun applyResponsiveLayout() {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val sidebarDp = when {
            widthDp < 720f -> 220
            widthDp < 900f -> 260
            else -> 300
        }
        sidebarPanel.layoutParams = (sidebarPanel.layoutParams as LinearLayout.LayoutParams).apply {
            width = (sidebarDp * metrics.density).toInt()
        }
        val available = widthDp - sidebarDp - 40f
        val columns = maxOf(2, (available / 215f).toInt())
        (fileGrid.layoutManager as? GridLayoutManager)?.spanCount = columns
    }

    private fun dotDrawable(color: Int): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(color)
        }
    }

    // =====================================================================
    // permissions
    // =====================================================================

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionPanel() {
        permissionPanel.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissionText.text =
                "태블릿의 모든 폴더를 보려면 「모든 파일 액세스」를 허용해 주세요.\n설정 → 권한에서 언제든 바꿀 수 있습니다."
            btnGrant.text = "설정에서 권한 허용"
        } else {
            permissionText.text = "파일을 읽고 쓰려면 저장소 권한을 허용해 주세요."
            btnGrant.text = "권한 허용하기"
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    // =====================================================================
    // listing
    // =====================================================================

    private fun selectCategory(cat: Category) {
        suppressSearch = true
        searchInput.setText("")
        suppressSearch = false
        currentCategory = cat
        currentDir = cat.baseDir
        pendingOp = null
        pasteBar.visibility = View.GONE
        gridAdapter.clearSelection()
        highlightCategory()
        loadCurrent()
    }

    private fun highlightCategory() {
        for (cat in categories) {
            val row = sidebarRows[cat.id] ?: continue
            row.isSelected = cat.id == currentCategory.id
        }
    }

    private fun loadCurrent() {
        if (!hasStoragePermission()) {
            showPermissionPanel()
            return
        }
        val cat = currentCategory
        val dir = currentDir
        val query = searchInput.text?.toString()?.trim().orEmpty()
        showBusy(true)
        val token = ++loadToken
        executor.execute {
            val items: List<FileItem> = try {
                when {
                    cat.flat -> {
                        val all = if (cat.isTrash) {
                            FileOps.listItems(dir)
                        } else {
                            FileOps.scanFlat(dir, cat.exts ?: emptySet())
                        }
                        if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    query.isNotEmpty() -> FileOps.searchItems(dir, query)
                    else -> FileOps.listItems(dir)
                }
            } catch (e: Exception) {
                emptyList()
            }
            val sortedItems = FileOps.sorted(items, sortMode)
            mainHandler.post {
                if (token != loadToken) return@post
                showBusy(false)
                gridAdapter.submit(sortedItems)
                updateBreadcrumb()
                updateSelectionBar()
                if (sortedItems.isEmpty()) {
                    emptyView.text = when {
                        query.isNotEmpty() -> "검색 결과가 없습니다"
                        cat.flat -> "표시할 파일이 없습니다"
                        cat.isTrash -> "휴지통이 비어 있습니다"
                        else -> "폴더가 비어 있습니다"
                    }
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }
    }

    private fun showBusy(busy: Boolean) {
        if (busy) {
            emptyView.text = "불러오는 중…"
            emptyView.visibility = View.VISIBLE
        } else if (emptyView.visibility == View.VISIBLE) {
            emptyView.visibility = View.GONE
        }
    }

    private fun updateBreadcrumb() {
        val basePath = currentCategory.baseDir.absolutePath.trimEnd('/')
        val currentPath = currentDir.absolutePath.trimEnd('/')
        breadcrumb.text = when {
            currentCategory.flat -> "${currentCategory.title} · 전체"
            currentPath == basePath -> currentCategory.title
            else -> {
                val relative = currentPath.removePrefix(basePath).trim('/')
                val parts = relative.split('/').filter { it.isNotEmpty() }
                (listOf(currentCategory.title) + parts).joinToString(" > ")
            }
        }
    }

    private fun updateStorageBar() {
        val stats = FileOps.storageStats()
        val used = stats[0]
        val total = stats[1]
        if (total <= 0L) {
            storageText.text = "저장 공간 정보를 읽을 수 없습니다"
            storageProgress.progress = 0
            return
        }
        val percent = ((used * 100) / total).toInt()
        storageProgress.progress = percent
        storageText.text = "사용 중 $percent% (여유 ${FileOps.formatSize(total - used)})"
    }

    private fun refreshSidebarStats() {
        executor.execute {
            val root = FileOps.externalRoot()
            val groups = HashMap<String, Set<String>>()
            for (cat in categories) {
                val exts = cat.exts ?: continue
                if (cat.isTrash) continue
                groups[cat.id] = exts
            }
            val scanned = try {
                FileOps.scanGroups(root, groups)
            } catch (e: Exception) {
                HashMap<String, LongArray>()
            }
            val stats = FileOps.storageStats()
            val values = HashMap<String, String>()
            values["internal"] =
                "${FileOps.formatSizeShort(stats[0])} / ${FileOps.formatSizeShort(stats[1])}"
            values["downloads"] =
                "${FileOps.countChildren(File(root, Environment.DIRECTORY_DOWNLOADS))}개 항목"
            for ((id, bucket) in scanned) {
                values[id] = "${bucket[0]}개 · ${FileOps.formatSize(bucket[1])}"
            }
            val trash = FileOps.trashDir()
            val trashCount = trash.listFiles()?.size ?: 0
            values["trash"] = if (trashCount == 0) "비어 있음" else "${trashCount}개"
            mainHandler.post {
                for (cat in categories) {
                    values[cat.id]?.let { cat.subtitle = it }
                    val row = sidebarRows[cat.id] ?: continue
                    val meta = row.findViewById<TextView>(R.id.catMeta)
                    meta.text = cat.subtitle
                }
            }
        }
    }

    private fun goUp() {
        if (pendingOp != null) {
            cancelPaste()
            return
        }
        if (currentCategory.flat) return
        val base = currentCategory.baseDir
        if (currentDir.absolutePath == base.absolutePath) {
            toast("최상위 폴더입니다")
            return
        }
        val parent = currentDir.parentFile
        currentDir = if (parent != null && parent.absolutePath.length >= base.absolutePath.length) {
            parent
        } else {
            base
        }
        gridAdapter.clearSelection()
        loadCurrent()
    }

    private fun cycleSort() {
        sortMode = when (sortMode) {
            SortMode.NAME -> SortMode.DATE
            SortMode.DATE -> SortMode.SIZE
            SortMode.SIZE -> SortMode.NAME
        }
        btnSort.text = "정렬: ${sortMode.label}"
        loadCurrent()
    }

    // =====================================================================
    // selection
    // =====================================================================

    private fun toggleSelection(item: FileItem) {
        gridAdapter.toggle(item)
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        val items = gridAdapter.selectedItems()
        if (items.isEmpty()) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.visibility = View.VISIBLE
        val bytes = FileOps.totalSize(items)
        val hasFolder = items.any { it.isDir }
        val sizeText = when {
            bytes <= 0L -> ""
            hasFolder -> " (${FileOps.formatSize(bytes)} + 폴더)"
            else -> " (${FileOps.formatSize(bytes)})"
        }
        selectionText.text = "${items.size}개 선택됨$sizeText"
        val single = items.size == 1
        btnRename.isEnabled = single
        btnRename.alpha = if (single) 1f else 0.4f
    }

    // =====================================================================
    // file actions
    // =====================================================================

    private fun openItem(item: FileItem) {
        if (item.isDir) {
            if (currentCategory.flat) {
                toast("전체 목록 화면입니다. 내부 저장소에서 폴더를 열어 주세요")
                return
            }
            currentDir = item.file
            gridAdapter.clearSelection()
            loadCurrent()
            return
        }
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", item.file)
        } catch (e: Exception) {
            toast("이 파일을 열 수 없습니다")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileOps.mimeOf(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("이 파일을 열 수 있는 앱이 없습니다")
        } catch (e: Exception) {
            toast("이 파일을 열 수 없습니다")
        }
    }

    private fun createFolder() {
        val target = if (currentCategory.flat) FileOps.externalRoot() else currentDir
        showInputDialog("새 폴더", "폴더 이름", "새 폴더") { name ->
            val dir = File(target, name)
            if (dir.exists()) {
                toast("같은 이름의 항목이 이미 있습니다")
                return@showInputDialog
            }
            if (dir.mkdirs()) {
                toast("폴더를 만들었습니다")
                loadCurrent()
                refreshSidebarStats()
            } else {
                toast("폴더를 만들지 못했습니다")
            }
        }
    }

    private fun renameSelected() {
        val items = gridAdapter.selectedItems()
        if (items.size != 1) {
            toast("이름을 바꿀 항목 하나만 선택하세요")
            return
        }
        val item = items.first()
        showInputDialog("이름 변경", "새 이름", item.name) { name ->
            if (name == item.name) return@showInputDialog
            val target = File(item.file.parentFile, name)
            if (target.exists()) {
                toast("같은 이름이 이미 있습니다")
                return@showInputDialog
            }
            if (item.file.renameTo(target)) {
                toast("이름을 바꿨습니다")
                gridAdapter.clearSelection()
                loadCurrent()
            } else {
                toast("이름을 바꾸지 못했습니다")
            }
        }
    }

    private fun startPending(type: PendingType) {
        val items = gridAdapter.selectedItems()
        if (items.isEmpty()) return
        val label = if (type == PendingType.COPY) "복사" else "이동"
        pendingOp = PendingOp(type, items.map { it.path }, label)
        val destinationHint = if (currentCategory.flat) {
            "전체 목록에서는 내부 저장소 최상위로 저장됩니다"
        } else {
            "대상 폴더로 이동한 뒤 「붙여넣기」"
        }
        pasteText.text = "${items.size}개 $label 준비됨 · $destinationHint"
        pasteBar.visibility = View.VISIBLE
        gridAdapter.clearSelection()
        updateSelectionBar()
    }

    private fun pasteDestination(): File {
        return if (currentCategory.flat) FileOps.externalRoot() else currentDir
    }

    private fun doPaste() {
        val op = pendingOp ?: return
        val destination = pasteDestination()
        if (!destination.exists()) destination.mkdirs()
        pasteBar.visibility = View.GONE
        val token = ++loadToken
        toast("${op.paths.size}개 ${op.label} 중…")
        executor.execute {
            var done = 0
            var failed = 0
            for (path in op.paths) {
                val source = File(path)
                if (!source.exists()) {
                    failed++
                    continue
                }
                val sameParent = source.parentFile?.absolutePath == destination.absolutePath
                if (op.type == PendingType.MOVE && sameParent) {
                    done++
                    continue
                }
                val target = FileOps.uniqueTarget(destination, source.name)
                val success = if (op.type == PendingType.COPY) {
                    FileOps.copyRecursive(source, target)
                } else {
                    FileOps.moveTo(source, target)
                }
                if (success) done++ else failed++
            }
            mainHandler.post {
                pendingOp = null
                if (token <= loadToken) {
                    toast(
                        if (failed == 0) "${done}개 ${op.label} 완료"
                        else "${done}개 완료, ${failed}개 실패"
                    )
                }
                loadCurrent()
                refreshSidebarStats()
            }
        }
    }

    private fun cancelPaste() {
        pendingOp = null
        pasteBar.visibility = View.GONE
        toast("취소했습니다")
    }

    private fun deleteSelected() {
        val items = gridAdapter.selectedItems()
        if (items.isEmpty()) return
        val inTrash = currentCategory.isTrash
        val message = if (inTrash) {
            "${items.size}개 항목을 완전히 삭제할까요?\n되돌릴 수 없습니다."
        } else {
            "${items.size}개 항목을 휴지통으로 보낼까요?\n휴지통에서 직접 삭제할 수 있습니다."
        }
        AlertDialog.Builder(this)
            .setTitle(if (inTrash) "완전 삭제" else "휴지통으로 이동")
            .setMessage(message)
            .setNegativeButton("취소", null)
            .setPositiveButton(if (inTrash) "삭제" else "휴지통으로") { _, _ ->
                performDelete(items, inTrash)
            }
            .show()
    }

    private fun performDelete(items: List<FileItem>, permanent: Boolean) {
        val trash = FileOps.trashDir()
        executor.execute {
            var done = 0
            var failed = 0
            for (item in items) {
                val success = if (permanent) {
                    item.file.deleteRecursively()
                } else {
                    if (!trash.exists()) trash.mkdirs()
                    val target = File(trash, "${System.currentTimeMillis()}_${item.name}")
                    FileOps.moveTo(item.file, target)
                }
                if (success) done++ else failed++
            }
            mainHandler.post {
                gridAdapter.clearSelection()
                updateSelectionBar()
                toast(
                    if (failed == 0) "${done}개 처리 완료"
                    else "${done}개 완료, ${failed}개 실패"
                )
                loadCurrent()
                refreshSidebarStats()
            }
        }
    }

    private fun shareSelected() {
        val items = gridAdapter.selectedItems()
        if (items.isEmpty()) return
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) {
            toast("폴더는 공유할 수 없습니다")
            return
        }
        val uris = ArrayList<Uri>()
        for (item in files) {
            try {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", item.file))
            } catch (e: Exception) {
                // skip files FileProvider cannot expose
            }
        }
        if (uris.isEmpty()) {
            toast("공유할 수 없습니다")
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = FileOps.mimeOf(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri("files", uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "공유"))
        } catch (e: ActivityNotFoundException) {
            toast("공유할 앱이 없습니다")
        }
    }

    // =====================================================================
    // dialogs / helpers
    // =====================================================================

    private fun showInputDialog(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.dialogInput)
        input.hint = hint
        input.setText(initial)
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("확인", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "이름을 입력하세요"
                    return@setOnClickListener
                }
                if (name.contains('/')) {
                    input.error = "/ 는 쓸 수 없습니다"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onOk(name)
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
