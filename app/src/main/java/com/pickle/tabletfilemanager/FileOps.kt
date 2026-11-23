package com.pickle.tabletfilemanager

import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * File system helpers. Everything here is safe to call from a background thread.
 */
object FileOps {

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")

    @Suppress("DEPRECATION")
    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    @Suppress("DEPRECATION")
    fun trashDir(): File = File(Environment.getExternalStorageDirectory(), ".pickle_trash")

    /** 1.24 GB / 695 KB style, with a sensible number of decimals. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < sizeUnits.size - 1) {
            value /= 1024.0
            unit++
        }
        return when {
            unit == 0 -> "$bytes B"
            value >= 100 -> String.format(Locale.KOREA, "%.0f %s", value, sizeUnits[unit])
            value >= 10 -> String.format(Locale.KOREA, "%.1f %s", value, sizeUnits[unit])
            else -> String.format(Locale.KOREA, "%.2f %s", value, sizeUnits[unit])
        }
    }

    /** Short form for the sidebar (128 GB). */
    fun formatSizeShort(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < sizeUnits.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B"
        else String.format(Locale.KOREA, "%.0f %s", value, sizeUnits[unit])
    }

    /** LongArray(used, total) of the shared storage volume. */
    @Suppress("DEPRECATION")
    fun storageStats(): LongArray {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            longArrayOf(total - available, total)
        } catch (e: Exception) {
            longArrayOf(0L, 0L)
        }
    }

    fun toItem(file: File): FileItem {
        val isDir = file.isDirectory
        val count = if (isDir) (file.list()?.size ?: 0) else 0
        val size = if (isDir) 0L else file.length()
        return FileItem(
            file = file,
            name = file.name,
            isDir = isDir,
            size = size,
            lastModified = file.lastModified(),
            childCount = count
        )
    }

    fun countChildren(dir: File): Int {
        val children = dir.listFiles() ?: return 0
        return children.count { !it.name.startsWith(".") }
    }

    /** Direct children of one folder (hidden entries skipped). */
    fun listItems(dir: File): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        val result = ArrayList<FileItem>(children.size)
        for (child in children) {
            if (child.name.startsWith(".")) continue
            result.add(toItem(child))
        }
        return result
    }

    /**
     * Flat, recursive index of every file below [root] whose extension is in [exts].
     */
    fun scanFlat(
        root: File,
        exts: Set<String>,
        maxDepth: Int = 8,
        limit: Int = 4000
    ): List<FileItem> {
        val result = ArrayList<FileItem>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && result.size < limit) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".") || child.name == "Android") continue
                if (child.isDirectory) {
                    if (depth < maxDepth) queue.add(child to depth + 1)
                } else {
                    val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (exts.contains(ext)) result.add(toItem(child))
                }
            }
        }
        return result
    }

    /** Recursive name search below [root]. */
    fun searchItems(
        root: File,
        query: String,
        maxDepth: Int = 8,
        limit: Int = 600
    ): List<FileItem> {
        if (query.isBlank()) return emptyList()
        val result = ArrayList<FileItem>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && result.size < limit) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.name.contains(query, ignoreCase = true)) result.add(toItem(child))
                if (child.isDirectory && depth < maxDepth) queue.add(child to depth + 1)
            }
        }
        return result
    }

    /**
     * One walk of the whole storage that counts and sizes several extension groups at once:
     * key -> LongArray(count, bytes).
     */
    fun scanGroups(
        root: File,
        groups: Map<String, Set<String>>,
        maxDepth: Int = 8,
        limit: Int = 80000
    ): HashMap<String, LongArray> {
        val result = HashMap<String, LongArray>()
        for (key in groups.keys) result[key] = longArrayOf(0L, 0L)
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".") || child.name == "Android") continue
                if (child.isDirectory) {
                    if (depth < maxDepth) queue.add(child to depth + 1)
                    continue
                }
                visited++
                val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                for ((key, exts) in groups) {
                    if (exts.contains(ext)) {
                        val bucket = result.getValue(key)
                        bucket[0] = bucket[0] + 1L
                        bucket[1] = bucket[1] + child.length()
                        break
                    }
                }
            }
        }
        return result
    }

    fun sorted(items: List<FileItem>, mode: SortMode): List<FileItem> {
        val comparator: Comparator<FileItem> = when (mode) {
            SortMode.NAME ->
                compareBy<FileItem> { !it.isDir }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.DATE ->
                compareBy<FileItem> { !it.isDir }
                    .thenByDescending { it.lastModified }
            SortMode.SIZE ->
                compareBy<FileItem> { !it.isDir }
                    .thenByDescending { it.size }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return items.sortedWith(comparator)
    }

    /** "name (1).ext" if the name is already taken in [dir]. */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val dotIndex = name.lastIndexOf('.')
        val extension = if (dotIndex > 0) name.substring(dotIndex) else ""
        var index = 1
        while (target.exists() && index < 1000) {
            target = File(dir, "$base ($index)$extension")
            index++
        }
        return target
    }

    fun copyRecursive(src: File, dst: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dst.exists() && !dst.mkdirs()) return false
                val children = src.listFiles() ?: return true
                var ok = true
                for (child in children) {
                    if (!copyRecursive(child, File(dst, child.name))) ok = false
                }
                ok
            } else {
                val parent = dst.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                src.inputStream().use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
                dst.setLastModified(src.lastModified())
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun moveTo(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        val copied = copyRecursive(src, dst)
        if (!copied) return false
        return src.deleteRecursively()
    }

    fun totalSize(items: List<FileItem>): Long {
        var sum = 0L
        for (item in items) {
            if (!item.isDir) sum += item.size
        }
        return sum
    }

    fun mimeOf(item: FileItem): String {
        val ext = item.extension
        knownMime[ext]?.let { return it }
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromMap ?: "*/*"
    }

    private val knownMime = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "m4v" to "video/mp4",
        "ts" to "video/mp2t",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "pdf" to "application/pdf",
        "apk" to "application/vnd.android.package-archive",
        "xapk" to "application/vnd.android.package-archive",
        "apks" to "application/vnd.android.package-archive",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/x-rar-compressed",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "json" to "application/json",
        "xml" to "text/xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus"
    )
}
