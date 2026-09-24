package dev.narayan.rose

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RoseApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Automatically clean all leaked/bloated cache asynchronously on startup.
        // This ensures that when existing users update, all accumulated gigabytes of
        // stale cache are immediately purged!
        CoroutineScope(Dispatchers.IO).launch {
            CacheCleaner.cleanAllCache(this@RoseApplication)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(25L * 1024L * 1024L) // 25MB maximum disk cache
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
