package dev.narayan.rose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Loads thumbnails for images and videos located in restricted directories (Android/data, Android/obb)
 * where normal Java File / FileInputStream is blocked by Android Scoped Storage.
 *
 * It uses:
 * 1. SAF DocumentsProvider stream if SAF access is granted.
 * 2. Shizuku shell IPC streaming if Shizuku is authorized.
 *
 * All decoded bitmaps are cached in-memory with an LRU cache (RGB_565 for minimal memory footprint).
 * Any transient video extraction files are deleted immediately in a finally block, leaving zero
 * cache bloat on disk.
 */
object RestrictedThumbnailLoader {

    private const val TAG = "RestrictedThumbLoader"
    private const val TARGET_SIZE = 160

    // In-memory LRU cache: 80 items ~ 4-6 MB max in RGB_565
    private val memoryCache = object : LruCache<String, Bitmap>(80) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024 // in KB
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    suspend fun loadThumbnail(context: Context, path: String, fileType: FileType): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${path}_$fileType"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        val bitmap = when (fileType) {
            FileType.IMAGE -> loadImageThumbnail(context, path)
            FileType.VIDEO -> loadVideoThumbnail(context, path)
            else -> null
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    private fun loadImageThumbnail(context: Context, path: String): Bitmap? {
        // Priority 1: SAF input stream if permission is present
        if (SafManager.hasPermission(context, path)) {
            val uri = SafManager.getContentUri(context, path)
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        val sampled = decodeSampledBitmapFromByteArray(bytes, TARGET_SIZE, TARGET_SIZE)
                        if (sampled != null) return sampled
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "SAF image thumbnail failed for $path", e)
                }
            }
        }

        // Priority 2: Shizuku cat stream
        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            try {
                val clean = ShizukuManager.normalize(path)
                val escaped = ShizukuManager.shellEscape(clean)
                val proc = ShizukuManager.newProcess(arrayOf("sh", "-c", "cat $escaped"), null, null)
                val bytes = proc.inputStream.use { it.readBytes() }
                proc.waitFor()
                val sampled = decodeSampledBitmapFromByteArray(bytes, TARGET_SIZE, TARGET_SIZE)
                if (sampled != null) return sampled
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku image thumbnail failed for $path", e)
            }
        }

        return null
    }

    private fun loadVideoThumbnail(context: Context, path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var tempFile: File? = null
        try {
            // Priority 1: SAF FileDescriptor
            if (SafManager.hasPermission(context, path)) {
                val uri = SafManager.getContentUri(context, path)
                if (uri != null) {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                            if (frame != null) {
                                return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "SAF video thumbnail failed for $path", e)
                    }
                }
            }

            // Priority 2: Shizuku stream header (up to 8MB) to a temporary file
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val clean = ShizukuManager.normalize(path)
                val escaped = ShizukuManager.shellEscape(clean)
                tempFile = File.createTempFile("thumb_vid_", ".tmp", context.cacheDir)
                val escapedTemp = ShizukuManager.shellEscape(tempFile.absolutePath)
                val proc = ShizukuManager.newProcess(arrayOf("sh", "-c", "head -c 8388608 $escaped > $escapedTemp"), null, null)
                proc.waitFor()

                if (tempFile.exists() && tempFile.length() > 0) {
                    retriever.setDataSource(tempFile.absolutePath)
                    val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    if (frame != null) {
                        return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Video thumbnail failed for $path", e)
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) {}
            tempFile?.let {
                runCatching { it.delete() }
            }
        }
        return null
    }

    private fun decodeSampledBitmapFromByteArray(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            scaleBitmap(decoded, reqWidth, reqHeight)
        } catch (e: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (bitmap.width <= maxW && bitmap.height <= maxH) return bitmap
        val ratio = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}
