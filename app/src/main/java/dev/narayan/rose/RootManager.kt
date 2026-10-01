package dev.narayan.rose

import android.os.Environment
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * High-performance, isolated Root management engine.
 *
 * Designed to mirror Material Files' root safety:
 * 1. Zero root commands or SU checks run when root access is disabled in settings.
 * 2. Uses libsu with Mount Master for global namespace operations.
 * 3. Safely handles symlinks, read-only partitions, and file operations.
 */
object RootManager {

    private const val TAG = "RootManager"

    init {
        try {
            // Configure libsu with Mount Master and redirect stderr so root shells have global mount visibility
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize Shell builder", e)
        }
    }

    /**
     * Checks whether root access is available and granted.
     * Safe to call from background threads.
     */
    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true || (Shell.getShell().isRoot)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Shell escapes arguments safely using single quote wrapping:
     * replaces ' with '\''
     */
    fun shellEscape(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Normalizes a root path.
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        var normalized = path.replace(Regex("/+"), "/")
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }

    /**
     * Determines whether [path] is outside standard user storage volumes.
     */
    fun isRootPath(path: String): Boolean {
        if (SafManager.isSafUri(path)) return false
        val norm = ShizukuManager.normalize(path)
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (norm == primary || norm.startsWith("$primary/")) return false
        if (norm.startsWith("/storage/")) return false
        if (norm.startsWith("/sdcard")) return false
        return norm.startsWith("/")
    }

    /**
     * High performance listing of files via Root shell using batch stat.
     */
    fun listFilesSync(path: String, showHiddenFiles: Boolean = true): List<FileItem> {
        val results = mutableListOf<FileItem>()
        val cleanPath = normalize(path)
        val searchPrefix = if (cleanPath == "/") "" else cleanPath

        try {
            val escapedPrefix = shellEscape(searchPrefix)

            // Step 1: Pre-calculate immediate child counts for subdirectories in a single fast find
            val childCounts = mutableMapOf<String, Int>()
            try {
                val countCmd = "find $escapedPrefix -mindepth 2 -maxdepth 2 2>/dev/null"
                val countResult = Shell.cmd(countCmd).exec()
                if (countResult.isSuccess) {
                    countResult.out.forEach { line ->
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
            } catch (e: Throwable) {
                Log.w(TAG, "Error counting subfolder items for $searchPrefix", e)
            }

            // Step 2: High-performance batch stat
            val cmd = "stat -c '%F|%s|%Y|%n' $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?* 2>/dev/null"
            val statResult = Shell.cmd(cmd).exec()

            fun parseLines(lines: List<String>) {
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

                    val isSymlink = typeStr.contains("symbolic link", ignoreCase = true)
                    var isDir = typeStr.contains("directory", ignoreCase = true)

                    // If it's a symlink, verify if its destination target is a directory
                    if (isSymlink) {
                        val testDir = Shell.cmd("[ -d ${shellEscape(fullPath)} ]").exec().isSuccess
                        if (testDir) isDir = true
                    }

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

            if (statResult.isSuccess && statResult.out.isNotEmpty()) {
                parseLines(statResult.out)
            }

            // Fallback: If wildcard batch produced no items, try iterating directory directly
            if (results.isEmpty()) {
                val fallbackCmd = "for f in $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?*; do [ -e \"\$f\" ] || [ -L \"\$f\" ] && stat -c '%F|%s|%Y|%n' \"\$f\" 2>/dev/null; done"
                val fallbackResult = Shell.cmd(fallbackCmd).exec()
                if (fallbackResult.isSuccess) {
                    parseLines(fallbackResult.out)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error listing root files for $searchPrefix", e)
        }

        return results.distinctBy { it.file.absolutePath }
    }

    suspend fun listFiles(path: String, showHiddenFiles: Boolean = true): List<FileItem> = withContext(Dispatchers.IO) {
        listFilesSync(path, showHiddenFiles)
    }
    fun exists(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "[ -e ${shellEscape(clean)} ] || [ -L ${shellEscape(clean)} ]"
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * Checks if a path is a directory via Root shell.
     */
    fun isDirectory(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "[ -d ${shellEscape(clean)} ]"
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * Gets accurate file size via Root stat.
     */
    fun getFileSize(path: String): Long {
        val clean = normalize(path)
        val cmd = "stat -c '%s' ${shellEscape(clean)} 2>/dev/null"
        val res = Shell.cmd(cmd).exec()
        return if (res.isSuccess) {
            res.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        } else 0L
    }

    /**
     * Calculates folder size recursively via Root du.
     */
    fun getFolderSize(path: String): Long {
        val clean = normalize(path)
        val cmd = "du -sk ${shellEscape(clean)} 2>/dev/null"
        val res = Shell.cmd(cmd).exec()
        return if (res.isSuccess) {
            val kb = res.out.firstOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toLongOrNull() ?: 0L
            kb * 1024L
        } else 0L
    }

    /**
     * Creates a directory with root permissions.
     */
    fun createFolder(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "mkdir -p ${shellEscape(clean)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Creates a directory with root permissions.
     */
    fun createFolder(parentPath: String, name: String): Boolean {
        val parent = normalize(parentPath)
        val target = if (parent == "/") "/$name" else "$parent/$name"
        val cmd = "mkdir -p ${shellEscape(target)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Deletes a file or directory recursively with root permissions.
     */
    fun delete(path: String): Boolean {
        val clean = normalize(path)
        if (clean == "/" || clean.isEmpty()) return false // Extreme safety check: never rm -rf /
        val cmd = "rm -rf ${shellEscape(clean)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Renames a file or directory with root permissions.
     */
    fun rename(path: String, newName: String): Boolean {
        val clean = normalize(path)
        if (clean == "/" || clean.isEmpty()) return false
        val parent = File(clean).parent ?: "/"
        val target = if (parent == "/") "/$newName" else "$parent/$newName"
        val cmd = "mv ${shellEscape(clean)} ${shellEscape(target)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Copies a file or directory using native root cp (-af preserves attributes).
     */
    fun copy(sourcePath: String, targetPath: String): Boolean {
        val cleanSrc = normalize(sourcePath)
        val cleanDst = normalize(targetPath)
        val parent = File(cleanDst).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val cmd = "cp -af ${shellEscape(cleanSrc)} ${shellEscape(cleanDst)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Moves a file or directory using native root mv.
     */
    fun move(sourcePath: String, targetPath: String): Boolean {
        val cleanSrc = normalize(sourcePath)
        val cleanDst = normalize(targetPath)
        val parent = File(cleanDst).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val cmd = "mv -f ${shellEscape(cleanSrc)} ${shellEscape(cleanDst)}"
        val res = Shell.cmd(cmd).exec()
        return res.isSuccess
    }

    /**
     * Remounts a partition as Read-Write if possible.
     */
    fun remountRw(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "mount -o remount,rw ${shellEscape(clean)} 2>/dev/null || mount -o remount,rw / 2>/dev/null"
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * Restores SELinux security context for [path].
     * Critical when creating or moving files into /data/data/<package>.
     */
    fun restorecon(path: String): Boolean {
        val clean = normalize(path)
        val cmd = "restorecon -R ${shellEscape(clean)} 2>/dev/null"
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * Runs a sync shell command and returns the exit code.
     */
    fun runCommandSync(command: String): Int {
        return try {
            Shell.cmd(command).exec().code
        } catch (e: Throwable) {
            -1
        }
    }

    /**
     * Opens an InputStream to read a file with root permissions using cat.
     */
    fun openInputStream(path: String): InputStream {
        val clean = normalize(path)
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat ${shellEscape(clean)}"))
        return process.inputStream
    }

    /**
     * Opens an OutputStream to write to a file with root permissions using cat > path.
     */
    fun openOutputStream(path: String): OutputStream {
        val clean = normalize(path)
        val parent = File(clean).parent
        if (parent != null) {
            Shell.cmd("mkdir -p ${shellEscape(normalize(parent))}").exec()
        }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > ${shellEscape(clean)}"))
        return process.outputStream
    }
}
