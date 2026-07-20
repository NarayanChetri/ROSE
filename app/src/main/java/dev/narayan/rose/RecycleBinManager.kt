package dev.narayan.rose

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.*

data class RecycledItem(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val deletionTime: Long,
    val size: Long,
    val isDirectory: Boolean
)

object RecycleBinManager {
    private const val RECYCLE_BIN_FOLDER = ".rose_recycle_bin"
    private const val METADATA_FILE = "metadata.json"
    private const val RETENTION_PERIOD_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    private fun getRecycleBinDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), RECYCLE_BIN_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMetadataFile(context: Context): File {
        return File(getRecycleBinDir(context), METADATA_FILE)
    }

    private fun loadMetadata(context: Context): MutableMap<String, RecycledItem> {
        val file = getMetadataFile(context)
        if (!file.exists()) return mutableMapOf()

        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, RecycledItem>()
            json.keys().forEach { id ->
                val obj = json.getJSONObject(id)
                map[id] = RecycledItem(
                    id = id,
                    originalName = obj.getString("name"),
                    originalPath = obj.getString("path"),
                    deletionTime = obj.getLong("time"),
                    size = obj.optLong("size", 0L),
                    isDirectory = obj.optBoolean("isDir", false)
                )
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveMetadata(context: Context, metadata: Map<String, RecycledItem>) {
        val json = JSONObject()
        metadata.forEach { (id, item) ->
            val obj = JSONObject().apply {
                put("name", item.originalName)
                put("path", item.originalPath)
                put("time", item.deletionTime)
                put("size", item.size)
                put("isDir", item.isDirectory)
            }
            json.put(id, obj)
        }
        getMetadataFile(context).writeText(json.toString())
    }

    fun getRecycledFile(context: Context, item: RecycledItem): File {
        return File(getRecycleBinDir(context), item.id)
    }

    fun recycle(context: Context, file: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        if (!file.exists()) return false
        
        val extension = file.extension
        val uuid = UUID.randomUUID().toString()
        val fileName = if (extension.isNotEmpty() && !file.isDirectory) "$uuid.$extension" else uuid
        val recycleBinDir = getRecycleBinDir(context)
        val destination = File(recycleBinDir, fileName)
        
        return try {
            val metadata = loadMetadata(context)
            val size = if (file.isDirectory) 0L else file.length()
            val item = RecycledItem(
                id = fileName, // Use the actual filename as ID
                originalName = file.name,
                originalPath = file.absolutePath,
                deletionTime = System.currentTimeMillis(),
                size = size,
                isDirectory = file.isDirectory
            )
            
            if (file.renameTo(destination)) {
                metadata[fileName] = item
                saveMetadata(context, metadata)
                true
            } else {
                // Fallback for cross-volume move if renameTo fails
                val success = if (file.isDirectory) {
                    file.copyRecursively(destination) && file.deleteRecursively()
                } else {
                    try {
                        // Use a custom copy for large files to report progress
                        val result = copyWithProgress(file, destination, onProgress)
                        if (result) file.delete() else false
                    } catch (e: Exception) {
                        false
                    }
                }
                
                if (success) {
                    metadata[fileName] = item
                    saveMetadata(context, metadata)
                }
                success
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyWithProgress(source: File, target: File, onProgress: ((Long, Long) -> Unit)?): Boolean {
        return try {
            val totalBytes = source.length()
            var bytesCopied = 0L
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192 * 4)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        onProgress?.invoke(bytesCopied, totalBytes)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun listItems(context: Context): List<RecycledItem> {
        cleanupExpired(context)
        return loadMetadata(context).values.sortedByDescending { it.deletionTime }
    }

    fun restore(context: Context, item: RecycledItem, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val recycledFile = File(getRecycleBinDir(context), item.id)
        if (!recycledFile.exists()) return false

        val originalFile = File(item.originalPath)
        originalFile.parentFile?.mkdirs()

        return try {
            if (recycledFile.renameTo(originalFile)) {
                val metadata = loadMetadata(context)
                metadata.remove(item.id)
                saveMetadata(context, metadata)
                true
            } else {
                // Fallback for cross-volume move
                val success = if (recycledFile.isDirectory) {
                    recycledFile.copyRecursively(originalFile) && recycledFile.deleteRecursively()
                } else {
                    val result = copyWithProgress(recycledFile, originalFile, onProgress)
                    if (result) recycledFile.delete() else false
                }
                
                if (success) {
                    val metadata = loadMetadata(context)
                    metadata.remove(item.id)
                    saveMetadata(context, metadata)
                }
                success
            }
        } catch (e: Exception) {
            false
        }
    }

    fun deletePermanently(context: Context, item: RecycledItem): Boolean {
        val recycledFile = File(getRecycleBinDir(context), item.id)
        val deleted = if (recycledFile.isDirectory) recycledFile.deleteRecursively() else recycledFile.delete()
        
        if (deleted || !recycledFile.exists()) {
            val metadata = loadMetadata(context)
            metadata.remove(item.id)
            saveMetadata(context, metadata)
            return true
        }
        return false
    }

    fun emptyBin(context: Context) {
        val dir = getRecycleBinDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        saveMetadata(context, emptyMap())
    }

    private fun cleanupExpired(context: Context) {
        val now = System.currentTimeMillis()
        val metadata = loadMetadata(context)
        val toRemove = mutableListOf<String>()

        metadata.forEach { (id, item) ->
            if (now - item.deletionTime > RETENTION_PERIOD_MS) {
                val file = File(getRecycleBinDir(context), id)
                if (file.exists()) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                toRemove.add(id)
            }
        }

        if (toRemove.isNotEmpty()) {
            toRemove.forEach { metadata.remove(it) }
            saveMetadata(context, metadata)
        }
    }
}
