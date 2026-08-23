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
    data class Extract(val source: Path, val targetDir: Path, val entries: List<String>? = null) : FileJobType()
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
    // True while we don't yet know totalBytes (e.g. a cloud provider that
    // hasn't reported a size) - UI should show a spinner, not a stuck 0%.
    var isIndeterminate: Boolean = false
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
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) return@Thread
                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName)
                            val success = copyRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success) {
                                if (JobManager.isCancelled(job.id)) return@Thread
                                throw Exception("Failed to copy ${source.displayName}. Check if storage is full or access is denied.")
                            }
                            touchedPaths.add(target.toString())
                            job.processedItems++
                        }
                    }
                    is FileJobType.Move -> {
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) return@Thread
                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName, source.path)

                            // If same file, skip
                            if (source.path == target.toString()) {
                                job.processedItems++
                                return@forEach
                            }

                            val success = moveRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success) {
                                if (JobManager.isCancelled(job.id)) return@Thread
                                throw Exception("Failed to move ${source.displayName}. Make sure Shizuku is authorized and target is writable.")
                            }
                            touchedPaths.add(source.path)
                            touchedPaths.add(target.toString())
                            job.processedItems++
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
                                if (JobManager.isCancelled(job.id)) return@Thread
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
                            if (JobManager.isCancelled(job.id)) return@Thread
                            deleteRecursive(appContext, target, job, onProgress)
                            job.completedPaths.add(target.toString())
                            touchedPaths.add(target.toString())
                        }
                        job.progress = 1f
                        onProgress(job)
                        JobManager.updateJob(job)
                    }
                    is FileJobType.Download -> {
                        if (JobManager.isCancelled(job.id)) return@Thread
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
                            if (JobManager.isCancelled(job.id)) return@Thread
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
                            if (JobManager.isCancelled(job.id)) return@Thread
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
                        if (JobManager.isCancelled(job.id)) return@Thread
                        job.currentFileName = type.source.fileName.toString()
                        onProgress(job)
                        JobManager.updateJob(job)

                        val success = extractRecursive(type.source, type.targetDir, job) {
                            onProgress(it)
                            JobManager.updateJob(it)
                        }
                        if (!success) {
                            if (JobManager.isCancelled(job.id)) return@Thread
                            throw Exception("Failed to extract archive. Check if storage is full or archive is corrupted.")
                        }
                        touchedPaths.add(type.targetDir.toString())
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

    private fun checkExists(context: Context, path: String): Boolean {
        return if (SafManager.isRestrictedPath(path)) {
            SafManager.exists(context, path)
        } else {
            try {
                Files.exists(Paths.get(path))
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getNonConflictingTarget(context: Context, targetDir: Path, displayName: String, sourcePath: String? = null): Path {
        val lastDot = displayName.lastIndexOf('.')
        val (name, ext) = if (lastDot > 0 && !displayName.startsWith(".")) {
            displayName.substring(0, lastDot) to displayName.substring(lastDot)
        } else {
            displayName to ""
        }

        var target = targetDir.resolve(displayName)

        // If move and target is same as source, it's a no-op
        if (sourcePath != null && sourcePath == target.toString()) {
            return target
        }

        var count = 1
        while (checkExists(context, target.toString())) {
            target = targetDir.resolve("$name ($count)$ext")
            count++
        }
        return target
    }

    // -- Copy -----------------------------------------------------------

    private fun copyRecursive(context: Context, sourcePath: String, target: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null): Boolean {
        if (job != null && JobManager.isCancelled(job.id)) return false

        val targetPath = target.toString()
        val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
        val targetRestricted = SafManager.isRestrictedPath(targetPath)

        // Priority 1: Shizuku direct copy (Fast and reliable for restricted paths)
        if ((sourceRestricted || targetRestricted) &&
            dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
        ) {
            val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
            val cleanDest = dev.narayan.rose.ShizukuManager.normalize(targetPath)

            // Ensure target directory exists for shell cp
            val destParent = target.parent.toString()
            if (SafManager.isRestrictedPath(destParent)) {
                val cleanParent = dev.narayan.rose.ShizukuManager.normalize(destParent)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}"
                )
            } else {
                java.io.File(destParent).mkdirs()
            }

            val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                "cp -r ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanDest)}"
            )
            if (exitCode == 0) return true
            // If shell copy failed, fall through to SAF
        }

        val sourceIsPath = !SafManager.isSafUri(sourcePath) && !sourceRestricted

        if (sourceIsPath && !targetRestricted) {
            val source = Paths.get(sourcePath)
            return try {
                if (Files.isDirectory(source)) {
                    if (!Files.exists(target)) Files.createDirectories(target)
                    var allSubSuccess = true
                    Files.list(source).use { stream ->
                        stream.forEach {
                            if (!copyRecursive(context, it.toString(), target.resolve(it.fileName.toString()), job, onProgress)) {
                                allSubSuccess = false
                            }
                        }
                    }
                    allSubSuccess
                } else {
                    copyFileWithProgress(Files.newInputStream(source), Files.newOutputStream(target), Files.size(source), job, onProgress)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

        val sourceIsDir = if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
            val fromSaf = SafManager.isDirectory(context, sourcePath)
            if (!fromSaf && sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                // If SAF fails, check via Shizuku
                val cleanSource = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSource)} ]"
                ) == 0
            } else fromSaf
        } else {
            Files.isDirectory(Paths.get(sourcePath))
        }

        if (sourceIsDir) {
            if (targetRestricted) {
                SafManager.createDirectory(context, targetPath)
            } else if (!Files.exists(target)) {
                Files.createDirectories(target)
            }

            val childPaths: List<String> = if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                val fromSaf = SafManager.listChildPaths(context, sourcePath)
                if (fromSaf.isEmpty() && sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                    // Fallback to Shizuku for listing
                    val results = mutableListOf<String>()
                    val cleanSource = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
                    val escapedSource = dev.narayan.rose.ShizukuManager.shellEscape(cleanSource)
                    val cmd = "for f in $escapedSource/* $escapedSource/.*; do [ -e \"\$f\" ] && [ \"\${f##*/}\" != \".\" ] && [ \"\${f##*/}\" != \"..\" ] && echo \"\$f\"; done"
                    val process = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", cmd), null, null)
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { results.add(it) }
                    }
                    process.waitFor()
                    results
                } else fromSaf
            } else {
                Files.list(Paths.get(sourcePath)).use { stream -> stream.map { it.toString() }.toList() }
            }

            var allChildrenSuccess = true
            childPaths.forEach { childPath ->
                val name = if (SafManager.isSafUri(childPath) || SafManager.isRestrictedPath(childPath)) {
                    val doc = SafManager.getDocumentFile(context, childPath)
                    doc?.name ?: childPath.substringAfterLast('/')
                } else {
                    Paths.get(childPath).fileName.toString()
                }
                if (!copyRecursive(context, childPath, target.resolve(name), job, onProgress)) {
                    allChildrenSuccess = false
                }
            }
            return allChildrenSuccess
        } else {
            val input = if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                val safInput = SafManager.openInputStream(context, sourcePath)
                if (safInput == null && sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                    val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
                    val escapedSrc = dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)
                    val process = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", "cat $escapedSrc"), null, null)
                    process.inputStream
                } else safInput
            } else {
                Files.newInputStream(Paths.get(sourcePath))
            }

            val output = if (targetRestricted) {
                SafManager.openOutputStreamForNewFile(context, targetPath)
            } else {
                Files.newOutputStream(target)
            }

            val size = if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                val safSize = SafManager.getReliableSize(context, sourcePath)
                if (safSize <= 0 && sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                    val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
                    val escapedSrc = dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)
                    val process = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", "stat -c %s $escapedSrc"), null, null)
                    val sizeStr = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    sizeStr.toLongOrNull() ?: 0L
                } else safSize
            } else {
                Files.size(Paths.get(sourcePath))
            }

            if (input != null && output != null) {
                // copyFileWithProgress now closes both streams itself and can throw
                // (IOException from the write side, or on cancellation) - catch here
                // so both cases cleanly return false, same as the plain-path branch
                // above, instead of propagating past the caller's `if (!success)`
                // cancellation check and surfacing as a spurious failure toast.
                return try {
                    copyFileWithProgress(input, output, size, job, onProgress)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            return false
        }
    }

    // Both streams are opened by the caller and handed in already-open, so this
    // function is responsible for closing them - previously it wasn't, which
    // leaked 2 file descriptors on every cancelled copy AND on every IOException
    // (most importantly ENOSPC when storage fills up mid-transfer). A batch copy
    // onto a full/slow SD card hitting that repeatedly could exhaust the
    // process's FD limit and start breaking unrelated I/O app-wide. Wrapping
    // both in `.use{}` guarantees they close on every exit path: normal
    // completion, thrown IOException, or cancellation.
    private fun copyFileWithProgress(input: java.io.InputStream, output: java.io.OutputStream, totalSize: Long, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null) {
        input.use { inp ->
            output.use { out ->
                val buffer = ByteArray(128 * 1024)
                var bytesRead: Int
                var totalRead = 0L
                var lastUpdate = 0L
                val knownSize = totalSize > 0

                // Report the starting state immediately (0% or spinner) instead of
                // waiting for the first 200ms tick - this is what makes the
                // notification actually appear with progress right away.
                if (job != null && onProgress != null) {
                    job.totalBytes = totalSize
                    job.processedBytes = 0L
                    job.progress = 0f
                    job.isIndeterminate = !knownSize
                    onProgress(job)
                }

                while (inp.read(buffer).also { bytesRead = it } != -1) {
                    if (job != null && JobManager.isCancelled(job.id)) {
                        // Throw (instead of returning) so both `use` blocks still
                        // run and close their streams. Callers already treat any
                        // exception from this function as "this file failed", so
                        // behavior for the caller is unchanged.
                        throw java.io.InterruptedIOException("Copy cancelled")
                    }
                    out.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    // Update at least every 150ms AND at least every 512KB, so short
                    // transfers still show intermediate steps instead of jumping 0 -> 100.
                    if (job != null && onProgress != null &&
                        (now - lastUpdate > 150 || totalRead - job.processedBytes > 512 * 1024 || bytesRead < buffer.size)
                    ) {
                        if (knownSize) {
                            job.progress = (totalRead.toFloat() / totalSize).coerceIn(0f, 1f)
                        }
                        job.processedBytes = totalRead
                        job.totalBytes = totalSize
                        job.isIndeterminate = !knownSize
                        onProgress(job)
                        lastUpdate = now
                    }
                }
                out.flush()

                if (job != null && onProgress != null) {
                    job.progress = 1f
                    job.processedBytes = totalRead
                    job.totalBytes = if (knownSize) totalSize else totalRead
                    job.isIndeterminate = false
                    onProgress(job)
                }
            }
        }
    }

    // -- Move -------------------------------------------------------------

    private fun moveRecursive(context: Context, sourcePath: String, target: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null): Boolean {
        val targetPath = target.toString()
        val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
        val targetRestricted = SafManager.isRestrictedPath(targetPath)

        // Priority 1: Shizuku direct move (Fast and atomic for restricted paths)
        if ((sourceRestricted || targetRestricted) &&
            dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
        ) {
            val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
            val cleanDest = dev.narayan.rose.ShizukuManager.normalize(targetPath)

            // Ensure target directory exists for shell mv
            val destParent = target.parent.toString()
            if (SafManager.isRestrictedPath(destParent)) {
                val cleanParent = dev.narayan.rose.ShizukuManager.normalize(destParent)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}"
                )
            } else {
                java.io.File(destParent).mkdirs()
            }

            val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                "mv ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanDest)}"
            )
            if (exitCode == 0) return true
            // If shell move failed, maybe it's across volumes, fall through to copy+delete
        }

        if (!sourceRestricted && !targetRestricted && !SafManager.isSafUri(sourcePath)) {
            return try {
                Files.move(Paths.get(sourcePath), target, StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (e: Exception) {
                false
            }
        }

        // Check if copy works before deleting source (Avoid file loss)
        if (copyRecursive(context, sourcePath, target, job, onProgress)) {
            deleteRecursive(context, Paths.get(sourcePath), job, onProgress)
            return true
        }
        return false
    }

    // -- Delete -----------------------------------------------------------

    private fun deleteRecursive(context: Context, path: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null) {
        if (job != null && JobManager.isCancelled(job.id)) return

        val pathStr = path.toString()
        val file = path.toFile()
        val size = if (file.isFile) file.length() else 0L

        if (SafManager.isRestrictedPath(pathStr)) {
            if (SafManager.hasPermission(context, pathStr)) {
                SafManager.delete(context, pathStr)
            } else if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                val clean = dev.narayan.rose.ShizukuManager.normalize(pathStr)
                dev.narayan.rose.ShizukuManager.runCommandSync(
                    "rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}"
                )
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

            val entriesToExtract = (job.type as? FileJobType.Extract)?.entries

            java.util.zip.ZipFile(file).use { zip ->
                val allEntries = zip.entries().asSequence().toList()
                val targetEntries = if (entriesToExtract != null) {
                    allEntries.filter { entry ->
                        entriesToExtract.any { target ->
                            entry.name == target || entry.name.startsWith("$target/")
                        }
                    }
                } else {
                    allEntries
                }

                val totalEntries = targetEntries.size
                job.totalItems = totalEntries
                job.totalBytes = targetEntries.filter { !it.isDirectory }.sumOf { it.size }
                job.processedItems = 0
                job.processedBytes = 0L

                targetEntries.forEach { entry ->
                    if (JobManager.isCancelled(job.id)) return false

                    // If extracting specific entries, we might want to strip the prefix
                    // to avoid creating deep folder structures if only one subfolder was picked.
                    // But standard behavior is to keep the relative path from the zip root or selection.
                    // Here we keep the entry name as is, which is safest.
                    val entryFile = targetDir.resolve(entry.name).normalize()

                    // Security check: ensure entry is within targetDir
                    if (!entryFile.startsWith(targetDir)) {
                        return@forEach
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(entryFile)
                    } else {
                        if (!Files.exists(entryFile.parent)) {
                            Files.createDirectories(entryFile.parent)
                        }
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(entryFile).use { output ->
                                val buffer = ByteArray(8192 * 4)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } >= 0) {
                                    output.write(buffer, 0, bytesRead)
                                    job.processedBytes += bytesRead
                                    if (job.totalBytes > 0 && System.currentTimeMillis() % 10 == 0L) {
                                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                                        onProgress(job)
                                    }
                                }
                            }
                        }
                    }
                    job.processedItems++
                    if (job.totalBytes <= 0) {
                        job.progress = job.processedItems.toFloat() / totalEntries
                    } else {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    }
                    job.currentFileName = entry.name.substringAfterLast('/')
                    onProgress(job)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}