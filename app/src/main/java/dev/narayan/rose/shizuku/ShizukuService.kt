package dev.narayan.rose.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

@Keep
class ShizukuService : IShizukuService.Stub {

    constructor() : super()

    // Shizuku v13+ will try constructor(Context) first
    constructor(context: Context) : super()

    override fun destroy() {
        Log.d(TAG, "ShizukuService destroyed")
        exitProcess(0)
    }

    override fun openFile(path: String, mode: Int): ParcelFileDescriptor? {
        return try {
            val file = File(path)
            ParcelFileDescriptor.open(file, mode)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open file: $path with mode: $mode", e)
            null
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (e: Throwable) {
            false
        }
    }

    override fun getFileSize(path: String): Long {
        return try {
            File(path).length()
        } catch (e: Throwable) {
            0L
        }
    }

    companion object {
        private const val TAG = "ShizukuService"
    }
}
