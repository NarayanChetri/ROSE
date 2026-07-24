package dev.narayan.rose

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShizukuManager {

    private const val TAG = "ShizukuManager"

    fun requestBinder(context: Context) {
        try {
            if (!Shizuku.pingBinder()) {
                rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(context)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    private fun runShizukuCommand(command: String): Process {
        return newProcess(arrayOf("sh", "-c", command), null, null)
    }

    /**
     * Normalizes paths to canonical /storage/emulated/0 form, matching NFile Manager logic.
     */
    fun normalize(path: String): String {
        var normalized = path.replace(Regex("/+"), "/")
        if (normalized.isEmpty()) normalized = "/"
        
        if (normalized.startsWith("/sdcard")) {
            normalized = normalized.replaceFirst("/sdcard", "/storage/emulated/0")
        } else if (normalized.startsWith("/mnt/sdcard")) {
            normalized = normalized.replaceFirst("/mnt/sdcard", "/storage/emulated/0")
        }
        return normalized
    }

    /**
     * Exactly NFile Manager logic for listing files via Shizuku.
     * Uses a robust shell command to gather type, size, modified time, and full path.
     */
    suspend fun listFiles(path: String, showHiddenFiles: Boolean = true): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        val normalizedPath = normalize(path)
        
        val cleanPath = if (normalizedPath == "/" || !normalizedPath.endsWith("/")) {
            normalizedPath
        } else {
            normalizedPath.substring(0, normalizedPath.length - 1)
        }

        // If cleanPath is "/", use empty string prefix to prevent search pattern from becoming //* and //.*
        val searchPrefix = if (cleanPath == "/") "" else cleanPath
        
        try {
            // NFile's listing command: efficient and handles hidden files correctly.
            // Modified to also include child count for directories.
            val cmd = "for f in \"$searchPrefix\"/* \"$searchPrefix\"/.*; do [ -e \"\$f\" ] && [ \"\${f##*/}\" != \".\" ] && [ \"\${f##*/}\" != \"..\" ] && { count=0; [ -d \"\$f\" ] && count=$(ls -1A \"\$f\" 2>/dev/null | wc -l); (stat -L -c \"%F|%s|%Y|\$count|%n\" \"\$f\" 2>/dev/null || stat -c \"%F|%s|%Y|\$count|%n\" \"\$f\"); }; done"

            val process = runShizukuCommand(cmd)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().isEmpty()) return@forEach
                    val parts = line.split('|')
                    if (parts.size < 5) return@forEach

                    val typeStr = parts[0]
                    val sizeStr = parts[1]
                    val timeStr = parts[2]
                    val countStr = parts[3]
                    // Path might contain '|'
                    val fullPath = parts.subList(4, parts.size).joinToString("|")

                    val file = File(fullPath)
                    val name = file.name
                    
                    if (!showHiddenFiles && name.startsWith(".") && name != "." && name != "..") {
                        return@forEach
                    }

                    val isDir = typeStr.contains("directory", ignoreCase = true)
                    val size = sizeStr.toLongOrNull() ?: 0L
                    val seconds = timeStr.toLongOrNull() ?: 0L
                    val timestamp = seconds * 1000
                    val itemCount = if (isDir) countStr.toIntOrNull() else null

                    results.add(
                        FileItem(
                            file = file,
                            isDirectory = isDir,
                            name = name,
                            size = size,
                            lastModified = timestamp,
                            extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase(),
                            itemCount = itemCount
                        )
                    )
                }
            }
            process.waitFor()

            if (results.isEmpty()) {
                val errText = process.errorStream.bufferedReader().readText().trim()
                if (errText.isNotEmpty()) {
                    Log.w(TAG, "listFiles(\"$searchPrefix\") returned 0 items, shell stderr: $errText")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files for $searchPrefix", e)
        }
        results
    }

    /** Matches NFile's runCommand logic. Returns true if exit code is 0. */
    suspend fun runCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        runCommandSync(command) == 0
    }

    /**
     * Synchronous version of runCommand for use in background threads where suspend
     * isn't available (like FileOperationRunner).
     */
    fun runCommandSync(command: String): Int {
        return try {
            val process = runShizukuCommand(command)
            val result = process.waitFor()
            if (result != 0) {
                val errText = process.errorStream.bufferedReader().readText().trim()
                if (errText.isNotEmpty()) Log.e(TAG, "Command failed: $command, stderr: $errText")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed for: $command", e)
            -1
        }
    }

    // --- High-level operations matching NFile logic ---

    suspend fun delete(path: String): Boolean {
        val clean = normalize(path)
        return runCommand("rm -rf \"$clean\"")
    }

    suspend fun rename(oldPath: String, newName: String): Boolean {
        val cleanOld = normalize(oldPath)
        val parent = File(cleanOld).parent ?: return false
        val cleanNew = normalize("$parent/$newName")
        return runCommand("mv \"$cleanOld\" \"$cleanNew\"")
    }

    suspend fun createFolder(parentPath: String, name: String): Boolean {
        val cleanParent = normalize(parentPath)
        val cleanPath = normalize("$cleanParent/$name")
        return runCommand("mkdir -p \"$cleanPath\"")
    }

    suspend fun createFile(parentPath: String, name: String): Boolean {
        val cleanParent = normalize(parentPath)
        val cleanPath = normalize("$cleanParent/$name")
        return runCommand("touch \"$cleanPath\"")
    }

    suspend fun copy(srcPath: String, destPath: String): Boolean {
        val cleanSrc = normalize(srcPath)
        val cleanDest = normalize(destPath)
        return runCommand("cp -r \"$cleanSrc\" \"$cleanDest\"")
    }

    suspend fun move(srcPath: String, destPath: String): Boolean {
        val cleanSrc = normalize(srcPath)
        val cleanDest = normalize(destPath)
        return runCommand("mv \"$cleanSrc\" \"$cleanDest\"")
    }
}
