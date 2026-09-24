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
// 2. Opening archive files for preview (view_archives).
// 3. Temporary zip extracts (temp_extract).
// 4. Temporary opened files (temp_open_).
// 5. Temporary video thumbnail frames (thumb_).
// 6. Downloaded update APKs (rose-update.apk).
// 7. Unbounded Coil image cache.
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object CacheCleaner {

    private const val TAG = "CacheCleaner"
    private const val MAX_COIL_DISK_CACHE_BYTES = 50L * 1024L * 1024L // 50MB cap

    suspend fun cleanAllCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val cacheDirs = mutableListOf<File>()
            context.cacheDir?.let { cacheDirs.add(it) }
            context.externalCacheDir?.let { cacheDirs.add(it) }
            context.externalCacheDirs?.filterNotNull()?.forEach {
                if (!cacheDirs.contains(it)) cacheDirs.add(it)
            }

            for (dir in cacheDirs) {
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

    private fun cleanDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            val name = file.name
            val shouldDelete = when {
                name == "view_archives" -> true
                name.startsWith("open_restricted_") -> true
                name.startsWith("temp_open_") -> true
                name.startsWith("temp_extract") -> true
                name.startsWith("thumb_") -> true
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
