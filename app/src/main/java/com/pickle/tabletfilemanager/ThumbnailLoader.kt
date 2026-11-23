package com.pickle.tabletfilemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** What kind of preview a card can show. */
enum class ThumbKind { NONE, IMAGE, VIDEO }

/**
 * Loads image / video previews off the main thread and keeps the decoded bitmaps in an
 * in-memory [LruCache] keyed by `absolutePath:lastModified`, so scrolling back and forth
 * through a folder of photos stays smooth and never re-decodes the same file.
 *
 * Images are decoded downsampled (~[TARGET_PX] px longest edge) with `inJustDecodeBounds`
 * + `inSampleSize` and rotated by their EXIF orientation. Videos use
 * `ThumbnailUtils.createVideoThumbnail` (framework [Size] variant on API 29+) with a
 * `MediaMetadataRetriever` frame as the last resort.
 */
class ThumbnailLoader(threads: Int = 4) {

    private val executor = Executors.newFixedThreadPool(threads, object : ThreadFactory {
        private val count = AtomicInteger(0)
        override fun newThread(r: Runnable) = Thread(r, "thumb-${count.incrementAndGet()}").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    })

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Bitmap cache, at most 1/8 of the heap. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    fun cacheKey(file: File): String = "${file.absolutePath}:${file.lastModified()}"

    /** Bitmap already decoded for this file (and this version of it), or null. */
    fun cached(file: File): Bitmap? = cache.get(cacheKey(file))

    /**
     * Decodes the preview for [file] in the background. [onReady] runs on the main thread
     * with the cache key of the request, so a caller can ignore results for a recycled card.
     */
    fun load(file: File, kind: ThumbKind, onReady: (key: String, bitmap: Bitmap?) -> Unit) {
        if (kind == ThumbKind.NONE) return
        val key = cacheKey(file)
        cache.get(key)?.let {
            onReady(key, it)
            return
        }
        executor.execute {
            val bitmap = try {
                if (kind == ThumbKind.VIDEO) videoThumbnail(file) else imageThumbnail(file)
            } catch (e: Throwable) {
                null
            }
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { onReady(key, bitmap) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        cache.evictAll()
    }

    // ===================== images =====================

    private fun imageThumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        } ?: return null

        return applyExifRotation(decoded, file)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= TARGET_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        if (file.extension.lowercase(Locale.ROOT) !in exifExtensions) return bitmap
        val degrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Throwable) {
            0f
        }
        if (degrees == 0f) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Throwable) {
            bitmap
        }
    }

    // ===================== video =====================

    private fun videoThumbnail(file: File): Bitmap? {
        val fromFramework = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ThumbnailUtils.createVideoThumbnail(file, Size(TARGET_PX, TARGET_PX), null)
            } catch (e: Throwable) {
                null
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            } catch (e: Throwable) {
                null
            }
        }
        return fromFramework ?: retrieverFrame(file)
    }

    private fun retrieverFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Throwable) {
                // ignored
            }
        }
    }

    companion object {
        /** Longest edge of a decoded preview. Small enough to stay cheap, sharp enough for ~200dp cards. */
        private const val TARGET_PX = 384

        private val exifExtensions = setOf("jpg", "jpeg", "heic", "heif", "webp")
    }
}
