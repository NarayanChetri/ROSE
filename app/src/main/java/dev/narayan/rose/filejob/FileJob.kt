package dev.narayan.rose.filejob

import android.content.Context
import android.net.Uri
import dev.narayan.rose.SafManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed class FileJobType {
    data class Copy(val sources: List<SourcePath>, val targetDir: Path) : FileJobType()
    data class Move(val sources: List<SourcePath>, val targetDir: Path) : FileJobType()
    data class Delete(val targets: List<Path>) : FileJobType()
    data class Download(val source: SourcePath, val targetFile: Path) : FileJobType()
    data class Recycle(val sources: List<SourcePath>) : FileJobType()
    data class Restore(val sources: List<SourcePath>) : FileJobType()
    data class Extract(val source: Path, val targetDir: Path, val entries: List<String>? = null, val passphrase: String? = null) : FileJobType()
    data class Compress(val sources: List<SourcePath>, val targetFile: Path, val passphrase: String? = null) : FileJobType()
}

data class SourcePath(
    val path: String,
    val displayName: String
)

data class FileJob(
    val id: String = UUID.randomUUID().toString(),
    val type: FileJobType,
    var totalItems: Int,
    var processedItems: Int = 0,
    var currentFileName: String = "",
    val completedPaths: MutableSet<String> = mutableSetOf(),
    var progress: Float = 0f, // 0.0 to 1.0
    var totalBytes: Long = 0L,
    var processedBytes: Long = 0L,
    var isPaused: Boolean = false,
    // True while we don't yet know totalBytes (e.g. a cloud provider that
    // hasn't reported a size) - UI should show a spinner, not a stuck 0%.
    var isIndeterminate: Boolean = false,
    // Wall-clock time the job was created. Only ever set once, on the
    // original construction - job.copy() (used by JobManager on every
    // update) carries the existing value forward instead of re-stamping
    // "now" each time, so elapsed-time/speed math in the UI stays correct
    // across the job's whole lifetime instead of resetting on every tick.
    val startTime: Long = System.currentTimeMillis()
)

/**
 * Runs copy/move/delete jobs. Paths under Android/data or Android/obb cannot
 * be touched with java.nio.file.Files (the OS blocks it even with
 * MANAGE_EXTERNAL_STORAGE) so those are routed through SafManager's
 * DocumentFile/ContentResolver based operations instead. Everything else
 * uses the fast, direct NIO path as before.
 */
@Suppress("NewApi")
object FileOperationRunner {

    fun execute(context: Context, job: FileJob, onProgress: (FileJob) -> Unit, onFinished: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            // MediaStore never learns about files this app writes/moves/deletes
            // directly on disk unless we tell it to rescan - without this,
            // copied/moved files silently don't show up in Recent, no matter
            // how many times the folder itself is refreshed.
            val touchedPaths = mutableListOf<String>()
            try {
                JobManager.updateJob(job)
                when (val type = job.type) {
                    is FileJobType.Copy -> {
                        val directBytes = calculateDirectFilesTotalSize(appContext, type.sources)
                        if (directBytes != null) {
                            job.totalBytes = directBytes
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = false
                        } else {
                            job.totalBytes = 0L
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = true
                            startAsyncBatchStatsCalculation(appContext, type.sources, job)
                        }

                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")

                            if (isDirectory(appContext, source.path) && SafManager.isSubdirectoryOrSame(type.targetDir.toString(), source.path)) {
                                throw IllegalArgumentException("Cannot copy a directory into itself or its subdirectories: ${source.displayName}")
                            }

                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName, sourcePath = source.path, isCopy = true)
                            val success = copyRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success) {
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                throw Exception("Failed to copy ${source.displayName}. Check if storage is full or access is denied.")
                            }
                            touchedPaths.add(target.toString())
                        }
                    }
                    is FileJobType.Move -> {
                        val directBytes = calculateDirectFilesTotalSize(appContext, type.sources)
                        if (directBytes != null) {
                            job.totalBytes = directBytes
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = false
                        } else {
                            job.totalBytes = 0L
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = true
                            startAsyncBatchStatsCalculation(appContext, type.sources, job)
                        }

                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")

                            if (isDirectory(appContext, source.path) && SafManager.isSubdirectoryOrSame(type.targetDir.toString(), source.path)) {
                                throw IllegalArgumentException("Cannot move a directory into itself or its subdirectories: ${source.displayName}")
                            }

                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName, sourcePath = source.path, isCopy = false)

                            // If same file, skip
                            if (source.path == target.toString() || SafManager.isSamePath(source.path, target.toString())) {
                                job.processedItems++
                                return@forEach
                            }

                            val success = moveRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success) {
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                throw Exception("Failed to move ${source.displayName}. Make sure Shizuku is authorized and target is writable.")
                            }
                            touchedPaths.add(source.path)
                            touchedPaths.add(target.toString())
                        }
                    }
                    is FileJobType.Delete -> {
                        // Priority 1: Shizuku bulk delete (Super fast, bypasses recursive walk)
                        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                            // Track failures instead of assuming every `rm -rf` succeeded -
                            // a discarded non-zero exit code here used to mean the job was
                            // always reported as a full success even when a target survived
                            // (read-only mount, a locked file, a permission edge case).
                            val failedTargets = mutableListOf<String>()
                            type.targets.forEach { target ->
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                job.currentFileName = target.fileName?.toString() ?: target.toString()
                                val clean = dev.narayan.rose.ShizukuManager.normalize(target.toString())
                                val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                                    "rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}"
                                )
                                if (exitCode == 0) {
                                    job.completedPaths.add(target.toString())
                                    touchedPaths.add(target.toString())
                                } else {
                                    failedTargets.add(target.toString())
                                }
                                job.processedItems++
                                job.progress = if (job.totalItems > 0) {
                                    (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                                } else 1f
                                onProgress(job)
                                JobManager.updateJob(job)
                            }

                            if (touchedPaths.isNotEmpty()) {
                                try {
                                    android.media.MediaScannerConnection.scanFile(appContext, touchedPaths.toTypedArray(), null, null)
                                } catch (e: Exception) { /* best-effort */ }
                            }

                            if (failedTargets.isNotEmpty()) {
                                // Surface the failure instead of silently reporting success -
                                // caught below and turned into a proper Error state.
                                throw Exception(
                                    "Failed to delete ${failedTargets.size} item(s): " +
                                            failedTargets.joinToString(", ") { java.io.File(it).name }
                                )
                            }

                            job.progress = 1f
                            onProgress(job)
                            JobManager.completeJob(job, success = true)
                            onFinished(true)
                            return@Thread
                        }

                        // Fast pre-calculation using java.io.File
                        var totalBytes = 0L
                        var totalItems = 0

                        // For very large deletions, skip the pre-scan to make it feel "instant" start
                        val firstTarget = type.targets.firstOrNull()?.toFile()
                        val shouldPreScan = type.targets.size < 5 && (firstTarget?.listFiles()?.size ?: 0) < 100

                        if (shouldPreScan) {
                            fun fastScan(file: java.io.File) {
                                totalItems++
                                if (file.isFile) {
                                    totalBytes += file.length()
                                } else {
                                    file.listFiles()?.forEach { fastScan(it) }
                                }
                            }

                            type.targets.forEach { target ->
                                fastScan(target.toFile())
                            }
                        }

                        job.totalItems = totalItems
                        job.totalBytes = totalBytes
                        job.processedItems = 0
                        job.processedBytes = 0L
                        job.isIndeterminate = !shouldPreScan

                        type.targets.forEach { target ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            deleteRecursive(appContext, target, job, onProgress)
                            job.completedPaths.add(target.toString())
                            touchedPaths.add(target.toString())
                        }
                        job.progress = 1f
                        onProgress(job)
                        JobManager.updateJob(job)
                    }
                    is FileJobType.Download -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        // Same pre-scan as Copy/Move (batch of one here) - copyFileWithProgress
                        // no longer sets job.totalBytes itself, so this is what gives Download
                        // its size and turns on the spinner if the size can't be known upfront.
                        val stats = calculateBatchStats(appContext, listOf(type.source.path))
                        job.totalBytes = stats?.first ?: 0L
                        job.processedBytes = 0L
                        job.isIndeterminate = stats == null
                        job.currentFileName = type.source.displayName
                        onProgress(job)
                        JobManager.updateJob(job)
                        copyRecursive(appContext, type.source.path, type.targetFile, job) {
                            onProgress(it)
                            JobManager.updateJob(it)
                        }
                        job.completedPaths.add(type.source.path)
                        touchedPaths.add(type.targetFile.toString())
                        job.processedItems++
                    }
                    is FileJobType.Recycle -> {
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            job.currentFileName = source.displayName
                            job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                            onProgress(job)
                            JobManager.updateJob(job)

                            val file = java.io.File(source.path)
                            dev.narayan.rose.RecycleBinManager.recycle(appContext, file) { processed, total ->
                                if (total > 0) {
                                    val itemWeight = 1f / job.totalItems
                                    val itemProgress = processed.toFloat() / total
                                    job.progress = (job.processedItems.toFloat() / job.totalItems + itemProgress * itemWeight).coerceIn(0f, 1f)
                                    onProgress(job)
                                    JobManager.updateJob(job)
                                }
                            }
                            job.completedPaths.add(source.path)
                            touchedPaths.add(source.path)
                            job.processedItems++
                        }
                    }
                    is FileJobType.Restore -> {
                        val metadata = dev.narayan.rose.RecycleBinManager.listItems(appContext)
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            job.currentFileName = source.displayName
                            job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                            onProgress(job)
                            JobManager.updateJob(job)
                            val item = metadata.find { it.id == source.path } // In Restore, path is the ID in bin
                            if (item != null) {
                                val success = dev.narayan.rose.RecycleBinManager.restore(appContext, item) { processed, total ->
                                    if (total > 0) {
                                        val itemWeight = 1f / job.totalItems
                                        val itemProgress = processed.toFloat() / total
                                        job.progress = (job.processedItems.toFloat() / job.totalItems + itemProgress * itemWeight).coerceIn(0f, 1f)
                                        onProgress(job)
                                        JobManager.updateJob(job)
                                    }
                                }
                                if (success) {
                                    touchedPaths.add(item.originalPath)
                                    job.completedPaths.add(source.path)
                                }
                            }
                            job.processedItems++
                            onProgress(job)
                            JobManager.updateJob(job)
                        }
                    }
                    is FileJobType.Extract -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        job.currentFileName = type.source.fileName.toString()
                        onProgress(job)
                        JobManager.updateJob(job)

                        val success = extractRecursive(type.source, type.targetDir, job) {
                            onProgress(it)
                            JobManager.updateJob(it)
                        }
                        if (!success) {
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            throw Exception("Failed to extract archive. Check if storage is full or archive is corrupted.")
                        }
                        touchedPaths.add(type.targetDir.toString())
                        job.processedItems++
                    }
                    is FileJobType.Compress -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        job.currentFileName = type.targetFile.fileName.toString()
                        onProgress(job)
                        JobManager.updateJob(job)

                        var lastUpdate = 0L
                        val success = compressRecursive(appContext, type.sources, type.targetFile, job) {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150 || it.processedBytes == it.totalBytes) {
                                onProgress(it)
                                JobManager.updateJob(it)
                                lastUpdate = now
                            }
                        }
                        if (!success) {
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            throw Exception("Failed to create archive.")
                        }
                        touchedPaths.add(type.targetFile.toString())
                        job.processedItems++
                    }
                }
                if (touchedPaths.isNotEmpty()) {
                    try {
                        android.media.MediaScannerConnection.scanFile(appContext, touchedPaths.toTypedArray(), null, null)
                    } catch (e: Exception) { /* best-effort */ }
                }
                JobManager.completeJob(job, success = true)
                onFinished(true)
            } catch (e: Exception) {
                e.printStackTrace()
                JobManager.completeJob(job, success = false, error = e.message ?: e.javaClass.simpleName)
                onFinished(false)
            }
        }.start()
    }

    // -- Helpers ----------------------------------------------------------

    private fun calculateDirectFilesTotalSize(context: Context, sources: List<SourcePath>): Long? {
        var sum = 0L
        for (source in sources) {
            val path = source.path
            if (SafManager.isSafUri(path)) return null
            val isRestricted = SafManager.isRestrictedPath(path)
            if (!isRestricted) {
                val f = java.io.File(path)
                if (f.isDirectory) return null
                if (f.isFile) {
                    sum += f.length()
                } else {
                    return null
                }
            } else if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                val clean = dev.narayan.rose.ShizukuManager.normalize(path)
                val isDir = dev.narayan.rose.ShizukuManager.runCommandSync(
                    "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]"
                ) == 0
                if (isDir) return null
                val size = dev.narayan.rose.ShizukuManager.getFileSize(clean)
                sum += size
            } else {
                if (SafManager.isDirectory(context, path)) return null
                val size = SafManager.getReliableSize(context, path)
                sum += size
            }
        }
        return sum
    }

    private fun startAsyncBatchStatsCalculation(
        context: Context,
        sources: List<SourcePath>,
        job: FileJob
    ) {
        Thread {
            try {
                var totalBytes = 0L
                var totalCount = 0
                for (source in sources) {
                    if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return@Thread
                    val path = source.path
                    val isRestricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
                    if (!isRestricted) {
                        val f = java.io.File(path)
                        if (f.isDirectory) {
                            fun fastDirScan(dir: java.io.File) {
                                if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return
                                dir.listFiles()?.forEach { child ->
                                    if (child.isDirectory) {
                                        fastDirScan(child)
                                    } else {
                                        totalCount++
                                        totalBytes += child.length()
                                    }
                                }
                            }
                            fastDirScan(f)
                        } else {
                            totalCount++
                            totalBytes += f.length()
                        }
                    } else if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                        val clean = dev.narayan.rose.ShizukuManager.normalize(path)
                        val isDir = dev.narayan.rose.ShizukuManager.runCommandSync(
                            "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]"
                        ) == 0
                        if (isDir) {
                            val folderSize = dev.narayan.rose.ShizukuManager.getFolderSize(clean)
                            totalBytes += folderSize
                            totalCount += 1
                        } else {
                            totalCount += 1
                            totalBytes += dev.narayan.rose.ShizukuManager.getFileSize(clean)
                        }
                    } else {
                        fun scanSaf(p: String) {
                            if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return
                            val children = SafManager.listFiles(context, p)
                            children.forEach { child ->
                                if (child.isDirectory) {
                                    scanSaf(child.file.path)
                                } else {
                                    totalCount++
                                    totalBytes += child.size
                                }
                            }
                        }
                        if (SafManager.isDirectory(context, path)) {
                            scanSaf(path)
                        } else {
                            totalCount++
                            totalBytes += SafManager.getReliableSize(context, path)
                        }
                    }
                }
                if (!JobManager.isCancelled(job.id) && !JobManager.isCompleted(job.id) && totalBytes > 0) {
                    job.totalBytes = totalBytes
                    if (totalCount > 0) job.totalItems = totalCount
                    job.isIndeterminate = false
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    }
                    JobManager.updateJob(job)
                }
            } catch (ignored: Exception) {}
        }.start()
    }

    private fun isDirectory(context: Context, path: String): Boolean {
        val restricted = SafManager.isRestrictedPath(path)
        return if (restricted || SafManager.isSafUri(path)) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                val cleanSource = dev.narayan.rose.ShizukuManager.normalize(path)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSource)} ]"
                ) == 0
            } else {
                SafManager.isDirectory(context, path)
            }
        } else {
            try {
                Files.isDirectory(Paths.get(path))
            } catch (e: Exception) {
                java.io.File(path).isDirectory
            }
        }
    }

    private fun isSubdirectoryOrSame(childPath: String, parentPath: String): Boolean {
        return SafManager.isSubdirectoryOrSame(childPath, parentPath)
    }

    private fun checkExists(context: Context, path: String): Boolean {
        return if (SafManager.isRestrictedPath(path)) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                dev.narayan.rose.ShizukuManager.exists(path)
            } else if (SafManager.hasPermission(context, path) && SafManager.exists(context, path)) {
                true
            } else {
                SafManager.exists(context, path)
            }
        } else {
            try {
                val f = java.io.File(path)
                f.exists() || Files.exists(Paths.get(path))
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getNonConflictingTarget(context: Context, targetDir: Path, displayName: String, sourcePath: String? = null, isCopy: Boolean = false): Path {
        val lastDot = displayName.lastIndexOf('.')
        val (name, ext) = if (lastDot > 0 && !displayName.startsWith(".")) {
            displayName.substring(0, lastDot) to displayName.substring(lastDot)
        } else {
            displayName to ""
        }

        var target = targetDir.resolve(displayName)

        // If move and target is same as source, it's a no-op
        if (!isCopy && sourcePath != null && (sourcePath == target.toString() || SafManager.isSamePath(sourcePath, target.toString()))) {
            return target
        }

        var count = 1
        val isSameAsSource = isCopy && sourcePath != null && (
            sourcePath == target.toString() || SafManager.isSamePath(sourcePath, target.toString())
        )

        if (isSameAsSource || checkExists(context, target.toString())) {
            target = targetDir.resolve("$name ($count)$ext")
            count++
            while (checkExists(context, target.toString())) {
                target = targetDir.resolve("$name ($count)$ext")
                count++
            }
        }
        return target
    }

    // -- Copy -----------------------------------------------------------

    internal data class ChildItem(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long
    )

    private fun listChildren(context: Context, path: String): List<ChildItem> {
        val restricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
        if (!restricted) {
            val f = java.io.File(path)
            val list = f.listFiles() ?: return emptyList()
            return list.map {
                ChildItem(
                    path = it.path,
                    name = it.name,
                    isDirectory = it.isDirectory,
                    size = if (it.isDirectory) 0L else it.length()
                )
            }
        }

        // If Shizuku is available and has permission, list via single Shizuku stat command (~15ms)
        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
            val results = mutableListOf<ChildItem>()
            val cleanSource = dev.narayan.rose.ShizukuManager.normalize(path)
            val searchPrefix = if (cleanSource == "/") "" else cleanSource.trimEnd('/')
            val escapedPrefix = dev.narayan.rose.ShizukuManager.shellEscape(searchPrefix)
            val cmd = "stat -c '%F|%s|%n' $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?* 2>/dev/null"
            try {
                val process = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", cmd), null, null)
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        val parts = trimmed.split('|', limit = 3)
                        if (parts.size == 3) {
                            val typeStr = parts[0]
                            val sizeStr = parts[1]
                            val fullPath = parts[2]
                            val name = fullPath.substringAfterLast('/')
                            if (name.isNotEmpty() && name != "." && name != "..") {
                                val isDir = typeStr.contains("directory", ignoreCase = true)
                                results.add(
                                    ChildItem(
                                        path = fullPath,
                                        name = name,
                                        isDirectory = isDir,
                                        size = if (isDir) 0L else (sizeStr.toLongOrNull() ?: 0L)
                                    )
                                )
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                /* fallback below */
            }
            if (results.isNotEmpty()) return results

            // Fallback with find if stat glob was empty
            try {
                val findCmd = "find $escapedPrefix -mindepth 1 -maxdepth 1 2>/dev/null"
                val findProc = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", findCmd), null, null)
                val paths = mutableListOf<String>()
                findProc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { l ->
                        val t = l.trim()
                        if (t.isNotEmpty()) paths.add(t)
                    }
                }
                findProc.waitFor()
                if (paths.isNotEmpty()) {
                    return paths.map { p ->
                        val name = p.substringAfterLast('/')
                        val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(p)} ]") == 0
                        val sz = if (isDir) 0L else dev.narayan.rose.ShizukuManager.getFileSize(p)
                        ChildItem(path = p, name = name, isDirectory = isDir, size = sz)
                    }
                }
            } catch (e: Exception) {}
        }

        // SAF fallback
        return try {
            SafManager.listFiles(context, path).map {
                ChildItem(
                    path = it.file.path,
                    name = it.name,
                    isDirectory = it.isDirectory,
                    size = it.size
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun calculateBatchStats(context: Context, paths: List<String>, job: FileJob? = null): Pair<Long, Int>? {
        var totalBytes = 0L
        var totalItems = 0
        val scanLimit = 20_000

        fun scan(path: String): Boolean {
            if (job != null) {
                if (JobManager.isCancelled(job.id)) return false
                try {
                    JobManager.checkWaitIfPaused(job.id)
                } catch (e: Exception) {
                    return false
                }
            }
            totalItems++
            if (totalItems > scanLimit) return false

            val restricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
            return if (restricted) {
                if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(path)
                    val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]") == 0
                    if (isDir) {
                        val folderSize = dev.narayan.rose.ShizukuManager.getFolderSize(clean)
                        totalBytes += folderSize
                        try {
                            val countProc = dev.narayan.rose.ShizukuManager.newProcess(
                                arrayOf("sh", "-c", "find ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} 2>/dev/null | wc -l"),
                                null, null
                            )
                            val countStr = countProc.inputStream.bufferedReader().readText().trim()
                            countProc.waitFor()
                            val c = countStr.toIntOrNull() ?: 1
                            totalItems += (c - 1).coerceAtLeast(0)
                        } catch (e: Exception) {}
                    } else {
                        totalBytes += dev.narayan.rose.ShizukuManager.getFileSize(path)
                    }
                    true
                } else if (isDirectory(context, path)) {
                    val children = listChildren(context, path)
                    children.all { child ->
                        if (child.isDirectory) {
                            scan(child.path)
                        } else {
                            totalItems++
                            totalBytes += child.size
                            true
                        }
                    }
                } else {
                    val size = SafManager.getReliableSize(context, path)
                    totalBytes += size
                    true
                }
            } else {
                val file = java.io.File(path)
                if (file.isDirectory) {
                    file.listFiles()?.all { scan(it.path) } ?: true
                } else {
                    totalBytes += file.length()
                    true
                }
            }
        }

        for (path in paths) {
            if (!scan(path)) return null
        }
        return totalBytes to totalItems
    }

    private fun copyChannelWithProgress(
        srcChannel: java.nio.channels.FileChannel,
        destChannel: java.nio.channels.FileChannel,
        fileSize: Long,
        job: FileJob? = null,
        onProgress: ((FileJob) -> Unit)? = null
    ) {
        val chunkSize = 4L * 1024L * 1024L // 4 MB chunks for optimal sendfile throughput
        var position = 0L
        var lastReportedPos = 0L
        var lastUpdate = 0L
        var useDirectBuffer = false
        var directBuffer: java.nio.ByteBuffer? = null

        fun reportDelta() {
            if (job == null || onProgress == null) return
            val delta = position - lastReportedPos
            if (delta <= 0) return
            job.processedBytes += delta
            lastReportedPos = position
            if (job.totalBytes > 0) {
                job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
            }
            onProgress(job)
        }

        val actualSize = if (fileSize > 0) fileSize else try { srcChannel.size() } catch (e: Exception) { 0L }

        while (actualSize <= 0L || position < actualSize) {
            if (job != null) {
                if (JobManager.isCancelled(job.id)) {
                    throw java.io.InterruptedIOException("Copy cancelled")
                }
                JobManager.checkWaitIfPaused(job.id)
            }

            val count = if (actualSize > 0) Math.min(chunkSize, actualSize - position) else chunkSize
            var bytesThisLoop = 0L

            if (!useDirectBuffer) {
                try {
                    val transferred = srcChannel.transferTo(position, count, destChannel)
                    if (transferred > 0) {
                        bytesThisLoop = transferred
                    } else {
                        // transferTo returned 0, fallback to direct buffer
                        useDirectBuffer = true
                    }
                } catch (e: Exception) {
                    useDirectBuffer = true
                }
            }

            if (useDirectBuffer) {
                if (directBuffer == null) {
                    directBuffer = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024)
                }
                directBuffer.clear()
                if (actualSize > 0 && directBuffer.capacity() > (actualSize - position)) {
                    directBuffer.limit((actualSize - position).toInt())
                }
                val read = srcChannel.read(directBuffer, position)
                if (read <= 0) break
                directBuffer.flip()
                while (directBuffer.hasRemaining()) {
                    destChannel.write(directBuffer)
                }
                bytesThisLoop = read.toLong()
            }

            if (bytesThisLoop <= 0) break
            position += bytesThisLoop

            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 100 || (actualSize > 0 && position >= actualSize)) {
                reportDelta()
                lastUpdate = now
            }
        }
        reportDelta()
    }

    private fun copyFileWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalSize: Long,
        job: FileJob? = null,
        onProgress: ((FileJob) -> Unit)? = null,
        activeProcesses: List<Process> = emptyList()
    ) {
        try {
            input.use { inp ->
                output.use { out ->
                    val buffer = ByteArray(8 * 1024 * 1024) // 8 MB buffer — 4x larger for better SAF fallback throughput
                    var bytesRead: Int
                    var fileBytesRead = 0L
                    var lastReportedFileBytes = 0L
                    var lastUpdate = 0L

                    fun reportDelta() {
                        if (job == null || onProgress == null) return
                        val delta = fileBytesRead - lastReportedFileBytes
                        if (delta <= 0) return
                        job.processedBytes += delta
                        lastReportedFileBytes = fileBytesRead
                        if (job.totalBytes > 0) {
                            job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                        }
                        onProgress(job)
                    }

                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        if (job != null) {
                            if (JobManager.isCancelled(job.id)) {
                                activeProcesses.forEach { it.destroy() }
                                throw java.io.InterruptedIOException("Copy cancelled")
                            }
                            JobManager.checkWaitIfPaused(job.id)
                        }
                        out.write(buffer, 0, bytesRead)
                        fileBytesRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 100) {
                            reportDelta()
                            lastUpdate = now
                        }
                    }
                    out.flush()
                    reportDelta()
                }
            }
        } catch (e: Exception) {
            activeProcesses.forEach { it.destroy() }
            throw e
        }
    }

    private fun copyRecursive(
        context: Context,
        sourcePath: String,
        target: Path,
        job: FileJob? = null,
        knownIsDir: Boolean? = null,
        knownSize: Long? = null,
        onProgress: ((FileJob) -> Unit)? = null
    ): Boolean {
        if (job != null) {
            if (JobManager.isCancelled(job.id)) return false
            try {
                JobManager.checkWaitIfPaused(job.id)
            } catch (e: Exception) {
                return false
            }
        }

        val targetPath = target.toString()
        val sourceIsDir = knownIsDir ?: isDirectory(context, sourcePath)

        if (sourceIsDir && SafManager.isSubdirectoryOrSame(targetPath, sourcePath)) {
            return false
        }

        val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
        val targetRestricted = SafManager.isRestrictedPath(targetPath)

        // Resolve fileSize upfront for all files before branching
        val fileSize = if (sourceIsDir) 0L else {
            knownSize ?: if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                if (sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(sourcePath)) {
                    dev.narayan.rose.ShizukuManager.getFileSize(sourcePath)
                } else {
                    SafManager.getReliableSize(context, sourcePath)
                }
            } else {
                try { Files.size(Paths.get(sourcePath)) } catch (e: Exception) { 0L }
            }
        }

        // If job totalBytes was unknown (e.g. single file where pre-scan was skipped
        // or async calculation is still running), immediately set totalBytes and switch to determinate mode.
        if (job != null && job.totalBytes == 0L && fileSize > 0L) {
            job.totalBytes = fileSize
            job.isIndeterminate = false
            JobManager.updateJob(job)
        }

        // -- Fast path #1: Shizuku native cp (~300 MB/s, matches CX File Explorer) --------
        // Applied for individual FILES only, and ONLY when at least one end is a restricted
        // path (Android/data, Android/obb). For unrestricted paths (e.g. Camera -> Downloads)
        // the NIO FileChannel path below gives smooth per-100ms progress updates at the same
        // speed, so there is no benefit to routing through Shizuku and losing granular progress.
        if (!sourceIsDir
            && (sourceRestricted || targetRestricted)
            && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
            && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
            val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
            val cleanDest = dev.narayan.rose.ShizukuManager.normalize(targetPath)
            val cpDestParent = java.io.File(cleanDest).parent
            if (cpDestParent != null) {
                val cleanParent = dev.narayan.rose.ShizukuManager.normalize(cpDestParent)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}"
                )
            }

            fun cleanTarget() {
                try {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(cleanDest)
                    dev.narayan.rose.ShizukuManager.runCommandSync("rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}")
                } catch (ignored: Exception) {}
            }

            try {
                // Launch cp -f asynchronously so we can poll dest size while it runs
                val cpProcess = dev.narayan.rose.ShizukuManager.newProcess(
                    arrayOf("sh", "-c", "cp -f ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanDest)}"),
                    null, null
                )

                val isFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                val exitCodeHolder = java.util.concurrent.atomic.AtomicInteger(-1)
                val errorMsgHolder = java.lang.StringBuilder()

                val waitThread = Thread {
                    try {
                        try { cpProcess.outputStream.close() } catch (ignored: Exception) {}
                        val errThread = Thread {
                            try {
                                cpProcess.errorStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        if (errorMsgHolder.length < 1000) {
                                            errorMsgHolder.appendLine(line)
                                        }
                                    }
                                }
                            } catch (ignored: Exception) {}
                        }.apply { isDaemon = true; start() }

                        val code = cpProcess.waitFor()
                        exitCodeHolder.set(code)
                        try { errThread.join(500) } catch (ignored: Exception) {}
                    } catch (e: Exception) {
                        android.util.Log.e("FileJob", "cpProcess waiter thread error", e)
                        exitCodeHolder.set(-1)
                    } finally {
                        isFinished.set(true)
                    }
                }.apply {
                    name = "ShizukuCpWaiter-${System.currentTimeMillis()}"
                    isDaemon = true
                    start()
                }

                val bytesAtFileStart = job?.processedBytes ?: 0L
                val destFile = java.io.File(cleanDest)

                // Poll dest size while cp is running -- gives real-time byte progress & speed
                var lastPolledBytes = 0L
                while (!isFinished.get()) {
                    Thread.sleep(120)
                    if (job != null && onProgress != null) {
                        // Cancellation check: destroy the cp process, clean target, and bail
                        if (JobManager.isCancelled(job.id)) {
                            try { cpProcess.destroy() } catch (ignored: Exception) {}
                            try { waitThread.join(500) } catch (ignored: Exception) {}
                            cleanTarget()
                            return false
                        }
                        try {
                            JobManager.checkWaitIfPaused(job.id)
                        } catch (e: Exception) {
                            try { cpProcess.destroy() } catch (ignored: Exception) {}
                            try { waitThread.join(500) } catch (ignored: Exception) {}
                            cleanTarget()
                            return false
                        }

                        // Poll current destination size:
                        // 1. If not restricted, destFile.length() works directly and instantly in Java.
                        // 2. If target is restricted (Android/data or Android/obb), java.io.File.length() returns 0
                        //    due to Android 11+ UID isolation, so query Shizuku stat directly (~3ms).
                        val rawDestBytes = if (!targetRestricted) {
                            val len = destFile.length()
                            if (len > 0L) len else {
                                if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                                    dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                                } else len
                            }
                        } else {
                            val len = destFile.length()
                            if (len > 0L) len else {
                                dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                            }
                        }

                        val destBytes = if (fileSize > 0L) rawDestBytes.coerceIn(0L, fileSize) else rawDestBytes
                        if (destBytes >= lastPolledBytes) {
                            lastPolledBytes = destBytes
                            job.processedBytes = bytesAtFileStart + destBytes
                            if (job.totalBytes > 0L) {
                                job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                            }
                            JobManager.updateJob(job)
                            onProgress(job)
                        }
                    }
                }

                try { waitThread.join(2000) } catch (ignored: Exception) {}
                val cpExitCode = exitCodeHolder.get()
                if (cpExitCode == 0) {
                    job?.let { j ->
                        j.processedItems++
                        // Set final bytes to exact file size
                        val finalFileBytes = if (fileSize > 0L) fileSize else {
                            if (targetRestricted) dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                            else destFile.length()
                        }
                        j.processedBytes = bytesAtFileStart + finalFileBytes
                        if (j.totalBytes > 0L) {
                            j.progress = (j.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                        }
                        JobManager.updateJob(j)
                        onProgress?.invoke(j)
                    }
                    return true
                } else {
                    android.util.Log.e("FileJob", "cp failed with exit code $cpExitCode: ${errorMsgHolder.toString().trim()}")
                    cleanTarget()
                }
            } catch (e: Exception) {
                if (job != null && JobManager.isCancelled(job.id)) {
                    cleanTarget()
                    return false
                }
                android.util.Log.e("FileJob", "Fast path cp failed with exception, falling through", e)
                cleanTarget()
            }
            // Non-zero exit or exception -- fall through to SAF / channel path below
        }

        // -- Fast path #2: Same SAF provider copyDocument (instant, no JVM data crossing) ---
        // Mirrors moveDocument() in moveRecursive(). Available on API 24+.
        // Only run when Shizuku is NOT available/permitted so Fast Path #1 handles Shizuku with full speed & animation.
        if (sourceRestricted && targetRestricted
            && (!dev.narayan.rose.ShizukuManager.isAvailable() || !dev.narayan.rose.ShizukuManager.hasPermission())
            && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
            val targetParentForCopy = target.parent?.toString()
            if (targetParentForCopy != null) {
                val copied = SafManager.copyDocument(context, sourcePath, targetParentForCopy)
                if (copied) {
                    val expectedName = target.fileName.toString()
                    val actualSrcName = sourcePath.substringAfterLast('/')
                    if (expectedName != actualSrcName) {
                        SafManager.rename(context, "$targetParentForCopy/$actualSrcName", expectedName)
                    }
                    job?.let { j ->
                        j.processedItems++
                        if (fileSize > 0L) {
                            j.processedBytes += fileSize
                            if (j.totalBytes > 0L) {
                                j.progress = (j.processedBytes.toFloat() / j.totalBytes).coerceIn(0f, 1f)
                            }
                            JobManager.updateJob(j)
                            onProgress?.invoke(j)
                        }
                    }
                    return true
                }
            }
        }

        if (sourceIsDir) {
            if (targetRestricted) {
                if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(targetPath)) {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                    dev.narayan.rose.ShizukuManager.runCommandSync("mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}")
                } else {
                    SafManager.createDirectory(context, targetPath)
                }
            } else if (!Files.exists(target)) {
                Files.createDirectories(target)
            }

            val children = listChildren(context, sourcePath)
            var allChildrenSuccess = true
            children.forEach { child ->
                if (job != null && JobManager.isCancelled(job.id)) return false
                job?.currentFileName = child.name
                val childTarget = target.resolve(child.name)
                if (!copyRecursive(
                        context = context,
                        sourcePath = child.path,
                        target = childTarget,
                        job = job,
                        knownIsDir = child.isDirectory,
                        knownSize = child.size,
                        onProgress = onProgress
                    )
                ) {
                    allChildrenSuccess = false
                }
            }
            return allChildrenSuccess
        } else {
            fun cleanTarget() {
                if (targetRestricted) {
                    if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(targetPath)) {
                        val clean = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                        dev.narayan.rose.ShizukuManager.runCommandSync("rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}")
                    } else {
                        SafManager.delete(context, targetPath)
                    }
                } else {
                    try { Files.deleteIfExists(target) } catch (ignored: Exception) {}
                }
            }

            // High Performance Path: FileChannel (zero-copy kernel sendfile via ParcelFileDescriptor / NIO)
            var srcChannel: java.nio.channels.FileChannel? = null
            var srcCloseable: java.io.Closeable? = null
            var destChannel: java.nio.channels.FileChannel? = null
            var destCloseable: java.io.Closeable? = null

            try {
                // Open source channel
                if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                    val pfd = SafManager.openFileDescriptor(context, sourcePath, "r")
                    if (pfd != null) {
                        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        srcCloseable = stream
                        srcChannel = stream.channel
                    }
                } else {
                    val fis = java.io.FileInputStream(java.io.File(sourcePath))
                    srcCloseable = fis
                    srcChannel = fis.channel
                }

                // Open dest channel
                if (targetRestricted) {
                    val pfd = SafManager.openFileDescriptorForNewFile(context, targetPath)
                    if (pfd != null) {
                        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                        destCloseable = stream
                        destChannel = stream.channel
                    }
                } else {
                    target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                    val fos = java.io.FileOutputStream(target.toFile())
                    destCloseable = fos
                    destChannel = fos.channel
                }

                if (srcChannel != null && destChannel != null) {
                    copyChannelWithProgress(srcChannel, destChannel, fileSize, job, onProgress)
                    job?.let { it.processedItems++ }
                    return true
                }
            } catch (e: Exception) {
                cleanTarget()
                if (JobManager.isCancelled(job?.id ?: "")) {
                    throw java.io.InterruptedIOException("Cancelled")
                }
                // If channel copy failed, fall through to streaming fallback below
            } finally {
                try { srcCloseable?.close() } catch (ignored: Exception) {}
                try { destCloseable?.close() } catch (ignored: Exception) {}
            }

            // -- SAF InputStream fallback (Shizuku unavailable or cross-provider SAF URIs) --
            // The old double-cat relay (cat src | Java | cat > dest) spawned two Shizuku
            // processes making all data cross the JVM 3 times. Direct SAF streams are simpler.
            val input = if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                SafManager.openInputStream(context, sourcePath)
            } else {
                Files.newInputStream(Paths.get(sourcePath))
            }

            val output = if (targetRestricted) {
                SafManager.openOutputStreamForNewFile(context, targetPath)
            } else {
                target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                Files.newOutputStream(target)
            }

            if (input != null && output != null) {
                return try {
                    copyFileWithProgress(
                        input,
                        output,
                        fileSize,
                        job,
                        onProgress,
                        emptyList()
                    )
                    job?.let { it.processedItems++ }
                    true
                } catch (e: Exception) {
                    cleanTarget()
                    false
                }
            }
            return false
        }
    }

    // -- Move -------------------------------------------------------------

    private fun getPackageDir(path: String): String? {
        val clean = dev.narayan.rose.ShizukuManager.normalize(path)
        val markerData = "/Android/data/"
        val markerObb = "/Android/obb/"
        val marker = when {
            clean.contains(markerData) -> markerData
            clean.contains(markerObb) -> markerObb
            else -> null
        } ?: return null
        val after = clean.substringAfter(marker)
        val pkg = after.substringBefore('/')
        return if (pkg.isNotBlank()) pkg else null
    }

    private fun moveRecursive(context: Context, sourcePath: String, target: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null): Boolean {
        if (job != null) {
            if (JobManager.isCancelled(job.id)) return false
            try {
                JobManager.checkWaitIfPaused(job.id)
            } catch (e: Exception) {
                return false
            }
        }

        val targetPath = target.toString()
        if (isDirectory(context, sourcePath) && SafManager.isSubdirectoryOrSame(targetPath, sourcePath)) {
            return false
        }
        val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
        val targetRestricted = SafManager.isRestrictedPath(targetPath)

        // 1. Direct local file to direct local file -> Fast atomic filesystem move
        if (!sourceRestricted && !targetRestricted && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
            val srcFile = java.io.File(sourcePath)
            val movedSize = if (srcFile.isFile) srcFile.length() else 0L
            return try {
                Files.move(Paths.get(sourcePath), target, StandardCopyOption.REPLACE_EXISTING)
                job?.let {
                    it.processedItems++
                    if (movedSize > 0L) it.processedBytes += movedSize
                    if (it.totalBytes > 0L) {
                        it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                    }
                    JobManager.updateJob(it)
                    onProgress?.invoke(it)
                }
                true
            } catch (e: Exception) {
                // If atomic move fails (e.g. cross filesystem boundary), fall through to copy + delete
                if (copyRecursive(context, sourcePath, target, job, onProgress = onProgress)) {
                    deleteRecursive(context, Paths.get(sourcePath), null, null)
                    true
                } else false
            }
        }

        // 2. Intra-package atomic move inside Android/data or Android/obb via Shizuku
        // On Android 11+ FUSE, atomic rename ONLY succeeds if source and destination
        // reside within the EXACT same package folder. Across different packages or
        // between Downloads and Android/data, rename() returns EXDEV.
        // For intra-package, atomic `mv` finishes in ~1ms!
        if (sourceRestricted && targetRestricted
            && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
            && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
            val srcPkg = getPackageDir(sourcePath)
            val dstPkg = getPackageDir(targetPath)
            if (srcPkg != null && dstPkg != null && srcPkg == dstPkg) {
                val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
                val cleanTarget = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                val parent = java.io.File(cleanTarget).parent
                if (parent != null) {
                    val cleanParent = dev.narayan.rose.ShizukuManager.normalize(parent)
                    dev.narayan.rose.ShizukuManager.runCommandSync("mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}")
                }
                val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ]") == 0
                val movedFileSize = if (isDir) {
                    dev.narayan.rose.ShizukuManager.getFolderSize(cleanSrc)
                } else {
                    dev.narayan.rose.ShizukuManager.getFileSize(cleanSrc)
                }
                val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                    "mv -f ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanTarget)}"
                )
                if (exitCode == 0) {
                    job?.let {
                        it.processedItems++
                        if (movedFileSize > 0L) it.processedBytes += movedFileSize
                        if (it.totalBytes > 0L) {
                            it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                        }
                        JobManager.updateJob(it)
                        onProgress?.invoke(it)
                    }
                    return true
                }
            }
        }

        // 3. Same SAF DocumentProvider atomic move (e.g. Android/data to Android/data via DocumentsContract.moveDocument)
        // Used when Shizuku is not available.
        if (sourceRestricted && targetRestricted
            && (!dev.narayan.rose.ShizukuManager.isAvailable() || !dev.narayan.rose.ShizukuManager.hasPermission())) {
            val targetParent = target.parent?.toString()
            if (targetParent != null) {
                val moved = SafManager.moveDocument(context, sourcePath, targetParent)
                if (moved) {
                    val expectedName = target.fileName.toString()
                    val actualSrcName = sourcePath.substringAfterLast('/')
                    if (expectedName != actualSrcName) {
                        SafManager.rename(context, "$targetParent/$actualSrcName", expectedName)
                    }
                    job?.let {
                        it.processedItems++
                        val sz = SafManager.getReliableSize(context, sourcePath)
                        if (sz > 0L) it.processedBytes += sz
                        if (it.totalBytes > 0L) {
                            it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                        }
                        JobManager.updateJob(it)
                        onProgress?.invoke(it)
                    }
                    return true
                }
            }
        }

        // 4. Cross-boundary move (e.g. Downloads to Android/data, Android/data to Downloads,
        // or cross-package Android/data moves):
        // Shizuku native copy first with real-time 300 MB/s speed, smooth progress animation,
        // and full cancellation protection, then delete source without double-counting bytes.
        if (copyRecursive(context, sourcePath, target, job, onProgress = onProgress)) {
            deleteRecursive(context, Paths.get(sourcePath), null, null)
            return true
        }
        return false
    }

    // -- Delete -----------------------------------------------------------

    private fun deleteRecursive(context: Context, path: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null) {
        if (job != null) {
            if (JobManager.isCancelled(job.id)) return
            try {
                JobManager.checkWaitIfPaused(job.id)
            } catch (e: Exception) {
                return
            }
        }

        val pathStr = path.toString()
        val file = path.toFile()
        val size = if (file.isFile) file.length() else 0L

        if (SafManager.isRestrictedPath(pathStr)) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(pathStr)) {
                val clean = dev.narayan.rose.ShizukuManager.normalize(pathStr)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}"
                )
            } else if (SafManager.hasPermission(context, pathStr)) {
                SafManager.delete(context, pathStr)
            }
            if (job != null && onProgress != null) {
                job.processedBytes += size
                job.processedItems++
                if (!job.isIndeterminate) {
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    } else if (job.totalItems > 0) {
                        job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                    }
                }
                onProgress(job)
                JobManager.updateJob(job)
            }
            return
        }

        if (file.isDirectory) {
            file.listFiles()?.forEach {
                deleteRecursive(context, it.toPath(), job, onProgress)
            }
        }

        try {
            Files.delete(path)
        } catch (e: Exception) {
            file.delete()
        }

        if (job != null && onProgress != null) {
            job.processedBytes += size
            job.processedItems++

            // Only update UI occasionally for large deletions to keep it super fast.
            // Pushing StateFlow updates for every file in a 10,000 file folder
            // is what makes the app feel laggy during deletion.
            val shouldUpdate = if (job.processedItems < 10) true
            else if (job.processedItems < 100) job.processedItems % 10 == 0
            else job.processedItems % 50 == 0 || job.processedItems == job.totalItems

            if (shouldUpdate) {
                job.currentFileName = path.fileName.toString()
                if (!job.isIndeterminate) {
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    } else if (job.totalItems > 0) {
                        job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                    }
                }
                onProgress(job)
                JobManager.updateJob(job)
            }
        }
    }

    private fun extractRecursive(sourceZip: Path, targetDir: Path, job: FileJob, onProgress: (FileJob) -> Unit): Boolean {
        try {
            val file = sourceZip.toFile()
            if (!Files.exists(targetDir)) Files.createDirectories(targetDir)

            val extractType = job.type as? FileJobType.Extract
            val entriesToExtract = extractType?.entries
            val passphrase = extractType?.passphrase
            val entries = dev.narayan.rose.ArchiveManager.readEntries(file)
            val filteredEntries = if (entriesToExtract != null) {
                entries.filter { entry ->
                    entriesToExtract.any { target ->
                        entry.name == target || entry.name.startsWith("$target/")
                    }
                }
            } else {
                entries
            }

            job.totalItems = filteredEntries.size
            job.totalBytes = filteredEntries.filter { !it.isDirectory }.sumOf { it.size }
            job.processedItems = 0
            job.processedBytes = 0L

            val targetSet = if (entriesToExtract != null) filteredEntries.map { it.name }.toSet() else null

            dev.narayan.rose.ArchiveManager.extractAll(file, passphrase) { name, isDirectory, copyTask ->
                if (JobManager.isCancelled(job.id)) return@extractAll
                if (targetSet != null && name !in targetSet) return@extractAll

                val entryFile = targetDir.resolve(name).normalize()
                if (!entryFile.startsWith(targetDir)) return@extractAll

                if (isDirectory) {
                    Files.createDirectories(entryFile)
                } else {
                    if (entryFile.parent != null) Files.createDirectories(entryFile.parent)
                    Files.newOutputStream(entryFile).use { output ->
                        val progressOutput = object : java.io.OutputStream() {
                            override fun write(b: Int) {
                                output.write(b)
                                job.processedBytes += 1
                            }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                output.write(b, off, len)
                                job.processedBytes += len
                                if (job.totalBytes > 0 && System.currentTimeMillis() % 100 == 0L) {
                                    job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                                    onProgress(job)
                                }
                            }
                        }
                        copyTask(progressOutput)
                    }
                }
                job.processedItems++
                job.currentFileName = name.substringAfterLast('/')
                onProgress(job)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            if (JobManager.isCancelled(job.id)) return false
            throw e
        }
    }

    private fun compressRecursive(context: android.content.Context, sources: List<SourcePath>, targetFile: Path, job: FileJob, onProgress: (FileJob) -> Unit): Boolean {
        try {
            val stats = calculateBatchStats(context, sources.map { it.path })
            job.totalBytes = stats?.first ?: 0L
            job.processedBytes = 0L
            job.isIndeterminate = stats == null

            val compressType = job.type as? FileJobType.Compress
            val passphrase = compressType?.passphrase

            dev.narayan.rose.ArchiveManager.compress(
                sources = sources.map { java.io.File(it.path) },
                targetFile = targetFile.toFile(),
                passphrase = passphrase
            ) { name, processed ->
                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                job.currentFileName = name
                job.processedBytes += processed
                if (job.totalBytes > 0) {
                    job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    onProgress(job)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
