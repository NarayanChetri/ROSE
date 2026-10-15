package dev.narayan.rose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VaultThumbnailLoader {
    private const val TARGET_SIZE = 200

    private val memoryCache = object : LruCache<String, Bitmap>(100) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }

    suspend fun loadThumbnail(file: File, fileType: FileType): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${file.absolutePath}_${file.lastModified()}"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        if (!file.exists()) return@withContext null

        val bitmap = when (fileType) {
            FileType.IMAGE -> decodeImageThumbnail(file)
            FileType.VIDEO -> decodeVideoThumbnail(file)
            else -> null
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    suspend fun loadVideoFrame(file: File, maxDim: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "frame_${file.absolutePath}_${file.lastModified()}"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }
        if (!file.exists()) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (frame != null) {
                val scaled = if (frame.width > maxDim || frame.height > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(frame.width, frame.height)
                    Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                } else frame
                memoryCache.put(cacheKey, scaled)
                scaled
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    suspend fun decodeFullImage(file: File, maxDim: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            var inSampleSize = 1
            val maxOriginal = maxOf(origW, origH)
            while ((maxOriginal / inSampleSize) > maxDim) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@withContext null
            rotateBitmapIfRequired(file, decoded)
        } catch (oom: OutOfMemoryError) {
            try {
                val fallbackOpts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, fallbackOpts) ?: return@withContext null
                rotateBitmapIfRequired(file, decoded)
            } catch (_: Throwable) {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeImageThumbnail(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(options, TARGET_SIZE, TARGET_SIZE)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val rotated = rotateBitmapIfRequired(file, decoded)
            scaleBitmap(rotated, TARGET_SIZE, TARGET_SIZE)
        } catch (e: Throwable) {
            null
        }
    }

    private fun rotateBitmapIfRequired(file: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    return rotated
                }
            }
            bitmap
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun decodeVideoThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (frame != null) {
                scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
            } else null
        } catch (e: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (ignored: Throwable) {}
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
