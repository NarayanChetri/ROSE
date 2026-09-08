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
            // NFile's listing command: efficient and handles hidden files correctly.
            // Modified to also include child count for directories.
            // searchPrefix is escaped since it's a real filesystem path that can
            // contain shell metacharacters - see shellEscape() for why this matters.
            //
            // The child count used to be `count=$(ls -1A "$f" | wc -l)`, which forks
            // two extra processes (ls, wc) *and* a subshell for every single directory
            // entry. On a folder like Android/data - which can easily hold 100+ app
            // subfolders - that's 300+ extra fork/execs in one listing, and was the
            // main reason browsing Android/data or Android/obb felt so slow. Counting
            // via shell's own glob expansion (`set -- "$f"/* "$f"/.*`) needs zero
            // forks, so the whole directory count is effectively free.
            val escapedPrefix = shellEscape(searchPrefix)
            val cmd = buildString {
                append("for f in $escapedPrefix/* $escapedPrefix/.*; do ")
                append("[ -e \"\$f\" ] || continue; ")
                append("n=\"\${f##*/}\"; ")
                append("[ \"\$n\" = \".\" ] && continue; ")
                append("[ \"\$n\" = \"..\" ] && continue; ")
                append("count=0; ")
                append("if [ -d \"\$f\" ]; then ")
                append("cnt=0; ")
                append("set -- \"\$f\"/* \"\$f\"/.*; ")
                append("for x in \"\$@\"; do ")
                append("[ -e \"\$x\" ] || continue; ")
                append("xn=\"\${x##*/}\"; ")
                append("[ \"\$xn\" = \".\" ] && continue; ")
                append("[ \"\$xn\" = \"..\" ] && continue; ")
                append("cnt=\$((cnt+1)); ")
                append("done; ")
                append("count=\$cnt; ")
                append("fi; ")
                append("stat -L -c \"%F|%s|%Y|\$count|%n\" \"\$f\" 2>/dev/null || stat -c \"%F|%s|%Y|\$count|%n\" \"\$f\"; ")
                append("done")
            }

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

    /**
     * True if [path] exists as seen by Shizuku's shell identity. Used before opening
     * a file inside Android/data or Android/obb: java.io.File.exists() always returns
     * false there (that's the whole reason we're going through Shizuku in the first
     * place), so checking `file.exists()` for these paths was reporting every single
     * file as "no longer exists" even though it was right there.
     */
    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val clean = normalize(path)
        runCommandSync("[ -e ${shellEscape(clean)} ]") == 0
    }

    /**
     * Streams a remote file's bytes into [destFile] via `cat`, without ever holding
     * the whole file in this process's memory - safe for large videos, etc. [destFile]
     * must be somewhere our own app process can write directly (its own cache dir is
     * the normal choice): Shizuku's shell identity only needs *read* access to the
     * restricted source, our own process handles the write with its own normal
     * permissions, so no elevated write access is ever required or attempted.
     *
     * This is what lets "Open with" / thumbnails / sharing work for files inside
     * Android/data or Android/obb when only Shizuku (and not the SAF folder grant)
     * has been authorized - previously those code paths only ever tried SAF's
     * DocumentFile, so they failed with "file no longer exists" / "couldn't access
     * file" any time SAF wasn't also separately granted.
     */
    suspend fun copyToLocalFile(path: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val clean = normalize(path)
        try {
            destFile.parentFile?.mkdirs()
            val process = runShizukuCommand("cat ${shellEscape(clean)}")
            process.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val exit = process.waitFor()
            if (exit != 0) {
                val err = process.errorStream.bufferedReader().readText().trim()
                if (err.isNotEmpty()) Log.e(TAG, "copyToLocalFile failed for $clean: $err")
                destFile.delete()
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToLocalFile exception for $clean", e)
            destFile.delete()
            false
        }
    }

    /**
     * Recursive size (bytes) of a directory via Shizuku, for the Properties dialog.
     * Plain shell (find + stat + a builtin arithmetic loop) - no `du`/`awk` dependency,
     * since their availability varies across OEM toybox/busybox builds.
     */
    suspend fun folderSize(path: String): Long = withContext(Dispatchers.IO) {
        val clean = normalize(path)
        val escaped = shellEscape(clean)
        val cmd = "find $escaped -type f -exec stat -L -c '%s' {} + 2>/dev/null | ( total=0; while read -r sz; do case \"\$sz\" in ''|*[!0-9]*) continue;; esac; total=\$((total+sz)); done; echo \$total )"
        try {
            val process = runShizukuCommand(cmd)
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "folderSize failed for $clean", e)
            0L
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
}