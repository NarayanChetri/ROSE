package dev.narayan.rose

import android.content.Context
import android.util.Log
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// CacheCleaner performs a deep clean of all temporary and cached files across
// both internal (cacheDir) and external (externalCacheDir) storage.
//
// This addresses cache bloating caused by:
// 1. Viewing/opening files from restricted storage (Android/data, Android/obb) which were
//    streamed or copied to cache (open_restricted_).
// 2. Sharing files from restricted storage (shared_restricted_).
// 3. Sharing extracted files from archives (share_).
// 4. Opening archive files for preview (view_archives).
// 5. Temporary zip extracts (temp_extract).
// 6. Temporary opened files (temp_open_).
// 7. Temporary video thumbnail frames (thumb_).
// 8. Atomic metadata temp files (rose_meta_tmp_).
// 9. Downloaded update APKs (rose-update.apk).
// 10. Unbounded Coil image cache.
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object CacheCleaner {

    private const val TAG = "CacheCleaner"
    private const val MAX_COIL_DISK_CACHE_BYTES = 25L * 1024L * 1024L // 25MB cap

    fun getCacheDirs(context: Context): List<File> {
        val cacheDirs = mutableListOf<File>()
        context.cacheDir?.let { cacheDirs.add(it) }
        context.externalCacheDir?.let { cacheDirs.add(it) }
        context.externalCacheDirs?.filterNotNull()?.forEach {
            if (!cacheDirs.contains(it)) cacheDirs.add(it)
        }
        return cacheDirs
    }

    suspend fun cleanAllCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            for (dir in getCacheDirs(context)) {
                cleanDirectory(dir)
            }

            // Also check Coil disk cache
            try {
                val diskCache = context.imageLoader.diskCache
                if (diskCache != null && diskCache.size > MAX_COIL_DISK_CACHE_BYTES) {
                    diskCache.clear()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error trimming Coil disk cache: ${e.message}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error running cleanAllCache", e)
        }
    }

    fun cleanAllShareFiles(context: Context) {
        runCatching {
            for (dir in getCacheDirs(context)) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith("shared_restricted_") || name.startsWith("share_")) {
                        runCatching {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                    }
                }
            }
        }
    }

    fun cleanStaleShareFiles(context: Context, maxAgeMs: Long = 60_000L) {
        runCatching {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            for (dir in getCacheDirs(context)) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith("shared_restricted_") || name.startsWith("share_")) {
                        if (file.lastModified() < cutoff) {
                            runCatching {
                                if (file.isDirectory) file.deleteRecursively() else file.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    fun cleanTemporaryOpenFiles(context: Context) {
        runCatching {
            for (dir in getCacheDirs(context)) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith("open_restricted_") || name.startsWith("temp_open_")) {
                        runCatching {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                    }
                }
            }
        }
    }

    fun cleanViewArchives(context: Context, excludePath: String? = null) {
        runCatching {
            val root = File(context.cacheDir, "view_archives")
            if (root.exists() && root.isDirectory) {
                root.listFiles()?.forEach { sessionDir ->
                    if (excludePath == null || !excludePath.startsWith(sessionDir.absolutePath)) {
                        runCatching { sessionDir.deleteRecursively() }
                    }
                }
            }
        }
    }

    private fun cleanDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            val name = file.name
            val shouldDelete = when {
                name == "view_archives" -> true
                name.startsWith("shared_restricted_") -> true
                name.startsWith("share_") -> true
                name.startsWith("open_restricted_") -> true
                name.startsWith("temp_open_") -> true
                name.startsWith("temp_extract") -> true
                name.startsWith("thumb_") -> true
                name.startsWith("rose_meta_tmp_") -> true
                name.endsWith(".tmp") -> true
                name == "rose-update.apk" -> true
                else -> false
            }

            if (shouldDelete) {
                runCatching {
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                }
            }
        }
    }
}
