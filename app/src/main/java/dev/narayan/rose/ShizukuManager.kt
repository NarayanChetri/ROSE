package dev.narayan.rose

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
     * Single-quotes a shell argument so it is always treated as one opaque
     * literal by `sh -c`, regardless of what characters it contains
     * (spaces, ", `, $, ;, backticks, newlines, etc). Every path/name that
     * gets interpolated into a command string built for [runShizukuCommand]
     * MUST go through this first - Android file/folder names are free-form
     * and can legally contain shell metacharacters, so building commands
     * with raw string interpolation (e.g. "rm -rf \"$path\"") is a command
     * injection vulnerability: a file named `foo"; rm -rf /; echo "` would
     * execute arbitrary shell commands with Shizuku's elevated privileges.
     *
     * Standard POSIX single-quote escaping: wrap in single quotes, and for
     * every literal single quote in the input, close the quote, emit an
     * escaped quote, and reopen the quote (' -> '\'').
     */
    fun shellEscape(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Normalizes paths to canonical /storage/emulated/0 form, matching NFile Manager logic.
     * Skips normalization for URIs to prevent breaking them.
     */
    fun normalize(path: String): String {
        if (path.startsWith("content://") || path.startsWith("file://") || path.startsWith("/content:/")) {
            return path
        }

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
            val escapedPrefix = shellEscape(searchPrefix)

            // Step 1: Pre-calculate immediate child counts for all subdirectories in a single fast command (~15-20ms).
            // -mindepth 2 -maxdepth 2 visits only the immediate children of the subdirectories and stops there.
            val childCounts = mutableMapOf<String, Int>()
            try {
                val countCmd = "find $escapedPrefix -mindepth 2 -maxdepth 2 2>/dev/null"
                val countProc = runShizukuCommand(countCmd)
                BufferedReader(InputStreamReader(countProc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val name = File(trimmed).name
                            if (showHiddenFiles || !name.startsWith(".")) {
                                val parent = File(trimmed).parent
                                if (parent != null) {
                                    childCounts[parent] = (childCounts[parent] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
                countProc.waitFor()
            } catch (e: Throwable) {
                Log.w(TAG, "Error pre-counting subfolder items for $searchPrefix", e)
            }

            // Step 2: High-performance batch stat.
            // Using safe globbing that matches non-hidden files and hidden files without ever matching "." or "..":
            // $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?*
            // stat -c (without -L) stats files and symlinks directly without failing on broken symlinks.
            val cmd = "stat -c '%F|%s|%Y|%n' $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?* 2>/dev/null"

            fun parseLines(reader: BufferedReader) {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        val parts = trimmed.split('|', limit = 4)
                        if (parts.size < 4) return@forEach

                        val typeStr = parts[0]
                        val sizeStr = parts[1]
                        val timeStr = parts[2]
                        val fullPath = parts[3]

                        val file = File(fullPath)
                        val name = file.name

                        if (name == "." || name == ".." || name.isEmpty()) return@forEach
                        if (!showHiddenFiles && name.startsWith(".")) return@forEach

                        val isDir = typeStr.contains("directory", ignoreCase = true)
                        val size = if (isDir) 0L else (sizeStr.toLongOrNull() ?: 0L)
                        val seconds = timeStr.toLongOrNull() ?: 0L
                        val timestamp = seconds * 1000
                        val itemCount = if (isDir) (childCounts[file.absolutePath] ?: 0) else null

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
            }

            val process = runShizukuCommand(cmd)
            parseLines(BufferedReader(InputStreamReader(process.inputStream)))
            process.waitFor()

            // Fallback: If wildcard batch produced no items, check if a directory has thousands of entries exceeding ARG_MAX
            if (results.isEmpty()) {
                val fallbackCmd = "for f in $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?*; do [ -e \"\$f\" ] || [ -L \"\$f\" ] && stat -c '%F|%s|%Y|%n' \"\$f\" 2>/dev/null; done"
                val fallbackProc = runShizukuCommand(fallbackCmd)
                parseLines(BufferedReader(InputStreamReader(fallbackProc.inputStream)))
                fallbackProc.waitFor()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error listing files for $searchPrefix", e)
        }
        // Crucial safety guarantee: ensure every file item has a unique absolutePath.
        // Prevents Compose LazyColumn/LazyVerticalGrid crashes (IllegalArgumentException: Key already used).
        results.distinctBy { it.file.absolutePath }
    }

    /**
     * Accurately calculates folder size recursively via Shizuku using native du command.
     */
    fun getFolderSize(path: String): Long {
        if (!isAvailable() || !hasPermission()) return 0L
        val clean = normalize(path)
        val escaped = shellEscape(clean)
        return try {
            val process = runShizukuCommand("du -sk $escaped 2>/dev/null")
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            val kb = output.split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
            kb * 1024L
        } catch (e: Throwable) {
            Log.e(TAG, "Error calculating folder size for $path", e)
            0L
        }
    }

    /**
     * Gets accurate file size via Shizuku stat command.
     */
    fun getFileSize(path: String): Long {
        if (!isAvailable() || !hasPermission()) return 0L
        val clean = normalize(path)
        val escaped = shellEscape(clean)
        return try {
            val process = runShizukuCommand("stat -c '%s' $escaped 2>/dev/null")
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting file size for $path", e)
            0L
        }
    }

    /**
     * Checks if a file or directory exists via Shizuku.
     */
    fun exists(path: String): Boolean {
        if (!isAvailable() || !hasPermission()) return false
        val clean = normalize(path)
        val escaped = shellEscape(clean)
        return try {
            val process = runShizukuCommand("[ -e $escaped ]")
            process.waitFor() == 0
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Streams file contents from a restricted path to a local destination file.
     */
    fun copyToFile(srcPath: String, destFile: File): Boolean {
        if (!isAvailable() || !hasPermission()) return false
        val clean = normalize(srcPath)
        val escaped = shellEscape(clean)
        return try {
            destFile.parentFile?.mkdirs()
            val process = runShizukuCommand("cat $escaped")
            destFile.outputStream().use { out ->
                process.inputStream.copyTo(out)
            }
            val code = process.waitFor()
            code == 0 && destFile.exists()
        } catch (e: Throwable) {
            Log.e(TAG, "Error copying $srcPath to $destFile via Shizuku", e)
            false
        }
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
        return runCommand("rm -rf ${shellEscape(clean)}")
    }

    suspend fun rename(oldPath: String, newName: String): Boolean {
        val cleanOld = normalize(oldPath)
        val parent = File(cleanOld).parent ?: return false
        val cleanNew = normalize("$parent/$newName")
        return runCommand("mv ${shellEscape(cleanOld)} ${shellEscape(cleanNew)}")
    }

    suspend fun createFolder(parentPath: String, name: String): Boolean {
        val cleanParent = normalize(parentPath)
        val cleanPath = normalize("$cleanParent/$name")
        return runCommand("mkdir -p ${shellEscape(cleanPath)}")
    }

    suspend fun createFile(parentPath: String, name: String): Boolean {
        val cleanParent = normalize(parentPath)
        val cleanPath = normalize("$cleanParent/$name")
        return runCommand("touch ${shellEscape(cleanPath)}")
    }

    suspend fun copy(srcPath: String, destPath: String): Boolean {
        val cleanSrc = normalize(srcPath)
        val cleanDest = normalize(destPath)
        return runCommand("cp -r ${shellEscape(cleanSrc)} ${shellEscape(cleanDest)}")
    }

    suspend fun move(srcPath: String, destPath: String): Boolean {
        val cleanSrc = normalize(srcPath)
        val cleanDest = normalize(destPath)
        return runCommand("mv ${shellEscape(cleanSrc)} ${shellEscape(cleanDest)}")
    }

    /**
     * High performance recursive file search within [searchRoot] using native find and stat via Shizuku.
     */
    suspend fun searchFiles(
        searchRoot: String,
        query: String,
        filterType: FileType? = null,
        showHiddenFiles: Boolean = false,
        maxResults: Int = 100
    ): List<FileItem> = withContext(Dispatchers.IO) {
        if (!isAvailable() || !hasPermission()) return@withContext emptyList()
        val normalizedRoot = normalize(searchRoot)
        val cleanRoot = if (normalizedRoot == "/" || !normalizedRoot.endsWith("/")) normalizedRoot else normalizedRoot.dropLast(1)
        val escapedRoot = shellEscape(cleanRoot)
        val sanitizedQuery = query.replace("'", "").replace("\"", "").replace("*", "").replace(";", "").replace("|", "").trim()
        if (sanitizedQuery.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<FileItem>()
        try {
            val hiddenClause = if (!showHiddenFiles) "! -path '*/.*'" else ""
            val cmd = "find $escapedRoot -maxdepth 8 $hiddenClause -iname '*$sanitizedQuery*' 2>/dev/null | head -n ${maxResults * 2}"
            val process = runShizukuCommand(cmd)
            val matchedPaths = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val path = line.trim()
                    if (path.isNotEmpty() && path != cleanRoot) {
                        matchedPaths.add(path)
                    }
                }
            }
            process.waitFor()

            if (matchedPaths.isNotEmpty()) {
                for (chunk in matchedPaths.chunked(50)) {
                    ensureActive()
                    val statTargets = chunk.joinToString(" ") { shellEscape(it) }
                    val statCmd = "stat -c '%F|%s|%Y|%n' $statTargets 2>/dev/null"
                    val statProc = runShizukuCommand(statCmd)
                    BufferedReader(InputStreamReader(statProc.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) return@forEach
                            val parts = trimmed.split('|', limit = 4)
                            if (parts.size < 4) return@forEach
                            val typeStr = parts[0]
                            val sizeStr = parts[1]
                            val timeStr = parts[2]
                            val fullPath = parts[3]

                            val file = File(fullPath)
                            val name = file.name
                            if (name.isEmpty() || name == "." || name == "..") return@forEach
                            if (!showHiddenFiles && name.startsWith(".")) return@forEach

                            val isDir = typeStr.contains("directory", ignoreCase = true)
                            val size = if (isDir) 0L else (sizeStr.toLongOrNull() ?: 0L)
                            val seconds = timeStr.toLongOrNull() ?: 0L
                            val timestamp = seconds * 1000

                            val item = FileItem(
                                file = file,
                                isDirectory = isDir,
                                name = name,
                                size = size,
                                lastModified = timestamp,
                                extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase(),
                                itemCount = null
                            )
                            if (filterType == null || item.matchesCategory(filterType)) {
                                results.add(item)
                            }
                        }
                    }
                    statProc.waitFor()
                    if (results.size >= maxResults) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error searching files via Shizuku in $cleanRoot", e)
        }
        results.take(maxResults).distinctBy { it.file.absolutePath }
    }
}