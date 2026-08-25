package dev.narayan.rose.filejob

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Rich, detailed progress dialog for in-flight copy/move/delete/extract/etc
 * jobs. Shows source, destination, live progress, speed, ETA and elapsed
 * time, with a Cancel button that actually stops the underlying job
 * (see JobManager.cancelJob - the old version silently no-op'd).
 *
 * Shown from FileExplorerScreen whenever JobManager.activeJobs is non-empty.
 */
@Composable
fun FileJobProgressDialog(activeJobs: List<FileJob>, onDismissRequest: () -> Unit) {
    if (activeJobs.isEmpty()) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeJobs.size > 1) "${activeJobs.size} active tasks" else "Working…",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Hide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Runs in the background - safe to close this dialog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(activeJobs, key = { it.id }) { job ->
                        JobDetailCard(job = job, onCancel = { JobManager.cancelJob(job.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun JobDetailCard(job: FileJob, onCancel: () -> Unit) {
    // The composable re-runs on every JobManager update (as often as every
    // 150ms during a fast transfer), but speed/ETA are only recalculated on
    // a fixed 500ms sample tick below - reading job.processedBytes straight
    // off a per-recomposition value made the speed number visibly jitter
    // several times a second instead of settling into a readable figure.
    // rememberUpdatedState lets the long-lived sampling loop always see the
    // latest job without being restarted or driven by every recomposition.
    val latestJob by rememberUpdatedState(job)

    var now by remember(job.id) { mutableLongStateOf(System.currentTimeMillis()) }
    var smoothedSpeedBps by remember(job.id) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(job.id) {
        var lastSampleTime = latestJob.startTime
        var lastSampleBytes = 0L
        while (true) {
            delay(500)
            val nowMs = System.currentTimeMillis()
            val sampleBytes = latestJob.processedBytes
            val dtSec = (nowMs - lastSampleTime) / 1000.0
            if (dtSec > 0.05) {
                val instantSpeed = (sampleBytes - lastSampleBytes).coerceAtLeast(0L) / dtSec
                // Exponential moving average over the 500ms samples - smooths
                // out the burst/pause pattern of real disk & network I/O into
                // one steadily-updating number instead of a jumpy raw reading.
                smoothedSpeedBps = if (smoothedSpeedBps <= 0.0) instantSpeed
                else (smoothedSpeedBps * 0.7 + instantSpeed * 0.3)
            }
            lastSampleTime = nowMs
            lastSampleBytes = sampleBytes
            now = nowMs
        }
    }

    val paths = remember(job.type) { jobPaths(job) }
    val (icon, title) = remember(job.type) { jobIconAndTitle(job) }

    val elapsedSec = ((now - job.startTime).coerceAtLeast(0L)) / 1000.0
    val remainingBytes = (job.totalBytes - job.processedBytes).coerceAtLeast(0L)
    val etaSec = if (smoothedSpeedBps >= 1.0 && job.totalBytes > 0) remainingBytes / smoothedSpeedBps else null

    // Progress bar reflects job.progress directly, with no extra tween on top
    // of it - the underlying byte-driven updates already arrive smoothly
    // (every 150ms/512KB), so an added animation only introduced visible lag
    // and a wobble whenever a fast update landed mid-animation.
    val currentProgress = job.progress.coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            // -- Header: icon badge + title + item count -----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = Color.White)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (job.totalItems > 1) {
                        Text(
                            text = "Item ${(job.processedItems + 1).coerceAtMost(job.totalItems)} of ${job.totalItems}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!job.isIndeterminate) {
                    Text(
                        text = "${(currentProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // -- From / To --------------------------------------------------
            if (paths.from != null) {
                PathRow(label = "FROM", value = paths.from)
            }
            if (paths.to != null) {
                Spacer(modifier = Modifier.height(6.dp))
                PathRow(label = "TO", value = paths.to)
            }

            if (job.currentFileName.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = job.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Progress bar -------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (job.isIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(currentProgress)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (job.totalBytes > 0) {
                    "${formatBytes(job.processedBytes)} of ${formatBytes(job.totalBytes)}"
                } else if (job.processedBytes > 0) {
                    formatBytes(job.processedBytes)
                } else {
                    "Calculating…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // -- Stats: speed / time left / elapsed ----------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    value = if (smoothedSpeedBps >= 1.0) formatSpeed(smoothedSpeedBps) else "—"
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Timer,
                    label = "Left",
                    value = etaSec?.let { formatDuration(it) } ?: "—"
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Schedule,
                    label = "Elapsed",
                    value = formatDuration(elapsedSec)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Cancel ----------------------------------------------------
            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatChip(modifier: Modifier = Modifier, icon: ImageVector, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// -- helpers ---------------------------------------------------------------

private data class JobPaths(val from: String?, val to: String?)

/**
 * Real source folder path shared by a batch of items - not a filename and not
 * a bare "N items" label. When a multi-select comes from one folder (the
 * normal case), this is that folder's full path. If sources happen to come
 * from different folders, falls back to naming how many distinct locations
 * are involved rather than guessing one.
 */
private fun sourceFolderPath(sourcePaths: List<String>): String? {
    val parents = sourcePaths.mapNotNull { java.io.File(it).parent }.distinct()
    return when {
        parents.size == 1 -> parents[0]
        parents.size > 1 -> "${parents.size} locations"
        else -> null
    }
}

private fun jobPaths(job: FileJob): JobPaths = when (val type = job.type) {
    is FileJobType.Copy -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }),
        to = type.targetDir.toString()
    )
    is FileJobType.Move -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }),
        to = type.targetDir.toString()
    )
    is FileJobType.Delete -> JobPaths(
        from = sourceFolderPath(type.targets.map { it.toString() }),
        to = null
    )
    is FileJobType.Download -> JobPaths(
        from = java.io.File(type.source.path).parent ?: type.source.displayName,
        to = type.targetFile.toString()
    )
    is FileJobType.Recycle -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }),
        to = "Recycle Bin"
    )
    is FileJobType.Restore -> JobPaths(
        from = "Recycle Bin",
        to = job.currentFileName.ifBlank { null }
    )
    is FileJobType.Extract -> JobPaths(
        from = type.source.toString(),
        to = type.targetDir.toString()
    )
}

private fun jobIconAndTitle(job: FileJob): Pair<ImageVector, String> = when (job.type) {
    is FileJobType.Copy -> Icons.Filled.ContentCopy to "Copying files"
    is FileJobType.Move -> Icons.Filled.DriveFileMove to "Moving files"
    is FileJobType.Delete -> Icons.Filled.Delete to "Deleting files"
    is FileJobType.Download -> Icons.Filled.Download to "Saving offline"
    is FileJobType.Recycle -> Icons.Filled.DeleteSweep to "Moving to Recycle Bin"
    is FileJobType.Restore -> Icons.Filled.RestoreFromTrash to "Restoring files"
    is FileJobType.Extract -> Icons.Filled.FolderZip to "Extracting archive"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private fun formatSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond.toLong())}/s"

private fun formatDuration(totalSeconds: Double): String {
    val seconds = totalSeconds.toLong().coerceAtLeast(0L)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}