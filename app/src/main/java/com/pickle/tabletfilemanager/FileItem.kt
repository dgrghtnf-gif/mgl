package com.pickle.tabletfilemanager

import java.io.File
import java.util.Locale

/**
 * One row/card in the file grid.
 */
data class FileItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val childCount: Int
) {
    val path: String get() = file.absolutePath

    val extension: String =
        if (isDir) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

enum class SortMode(val label: String) {
    NAME("이름"),
    DATE("최신"),
    SIZE("크기")
}

/**
 * Type badge label + colour used by the large card badges.
 */
object FileKinds {

    private val videoExts =
        setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv")
    private val imageExts =
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
    private val audioExts =
        setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "opus")
    private val docExts =
        setOf("pdf", "doc", "docx", "hwp", "hwpx", "txt", "rtf", "odt", "csv", "xls", "xlsx", "ppt", "pptx")
    private val archiveExts =
        setOf("zip", "7z", "rar", "tar", "gz", "bz2")
    private val codeExts =
        setOf("html", "htm", "json", "xml", "js", "kt", "java", "py", "css", "md", "sh")
    private val webExts =
        setOf("html", "htm", "css", "js", "json", "xml", "md")

    fun labelOf(item: FileItem): String {
        if (item.isDir) return "DIR"
        val ext = item.extension
        if (ext.isEmpty()) return "FILE"
        return when (ext) {
            "jpeg", "jpg", "png", "webp", "bmp", "heic", "heif" -> "IMG"
            "doc", "docx", "hwp", "hwpx" -> "DOC"
            "xls", "xlsx" -> "XLS"
            "ppt", "pptx" -> "PPT"
            "htm", "html" -> "HTML"
            else -> ext.uppercase(Locale.ROOT).take(4)
        }
    }

    fun colorOf(item: FileItem): Int {
        if (item.isDir) return 0xFFF59E0B.toInt()
        val ext = item.extension
        return when {
            videoExts.contains(ext) -> 0xFF8B5CF6.toInt()
            imageExts.contains(ext) -> 0xFFEC4899.toInt()
            audioExts.contains(ext) -> 0xFF14B8A6.toInt()
            ext == "apk" || ext == "apks" || ext == "xapk" -> 0xFF06B6D4.toInt()
            ext == "pdf" -> 0xFFEF4444.toInt()
            archiveExts.contains(ext) -> 0xFF6366F1.toInt()
            codeExts.contains(ext) -> 0xFF10B981.toInt()
            docExts.contains(ext) -> 0xFF3B82F6.toInt()
            else -> 0xFF64748B.toInt()
        }
    }

    /** Windows Explorer style vector icon for a card / sidebar entry. */
    fun iconResOf(item: FileItem): Int {
        if (item.isDir) return R.drawable.ic_win_folder
        val ext = item.extension
        return when {
            ext == "pdf" -> R.drawable.ic_win_pdf
            videoExts.contains(ext) -> R.drawable.ic_win_video
            imageExts.contains(ext) -> R.drawable.ic_win_image
            audioExts.contains(ext) -> R.drawable.ic_win_audio
            ext == "apk" || ext == "apks" || ext == "xapk" -> R.drawable.ic_win_apk
            archiveExts.contains(ext) -> R.drawable.ic_win_zip
            webExts.contains(ext) -> R.drawable.ic_win_web
            docExts.contains(ext) || codeExts.contains(ext) -> R.drawable.ic_win_doc
            else -> R.drawable.ic_win_file
        }
    }

    fun kindNameOf(item: FileItem): String {
        if (item.isDir) return "폴더"
        val ext = item.extension
        return when {
            videoExts.contains(ext) -> "영상"
            imageExts.contains(ext) -> "이미지"
            audioExts.contains(ext) -> "오디오"
            ext == "apk" || ext == "apks" || ext == "xapk" -> "설치 파일"
            ext == "pdf" -> "PDF"
            archiveExts.contains(ext) -> "압축 파일"
            docExts.contains(ext) -> "문서"
            codeExts.contains(ext) -> "웹/코드"
            else -> "파일"
        }
    }

    val videoExtensions: Set<String> get() = videoExts
    val imageExtensions: Set<String> get() = imageExts
    val documentExtensions: Set<String> get() = docExts + setOf("pdf")
    val apkExtensions: Set<String> get() = setOf("apk", "apks", "xapk")
}
