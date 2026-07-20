package dev.narayan.rose.filejob

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import dev.narayan.rose.MainActivity
import java.nio.file.Paths

@Suppress("NewApi")
class FileJobService : Service() {

    companion object {
        const val CHANNEL_ID = "file_job_channel"
        const val SILENT_CHANNEL_ID = "file_job_silent_channel"
        const val COMPLETION_CHANNEL_ID = "file_job_completion_channel"
        // Base id for the live foreground/progress notification of a given job.
        // Kept per-job (not a single shared constant) so a download in progress
        // doesn't get its notification clobbered/replaced by an unrelated copy/move.
        private const val NOTIFICATION_ID_BASE = 1001
        private const val COMPLETION_NOTIFICATION_ID_BASE = 2001

        const val ACTION_START_COPY = "action_start_copy"
        const val ACTION_START_MOVE = "action_start_move"
        const val ACTION_START_DELETE = "action_start_delete"
        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_START_RECYCLE = "action_start_recycle"
        const val ACTION_START_RESTORE = "action_start_restore"
        const val EXTRA_SOURCES = "extra_sources"
        const val EXTRA_DISPLAY_NAMES = "extra_display_names"
        const val EXTRA_TARGET_DIR = "extra_target_dir"
        const val EXTRA_TARGET_FILE = "extra_target_file"
        const val EXTRA_JOB_ID = "extra_job_id"

        fun startCopy(context: Context, sources: List<String>, displayNames: List<String>, targetDir: String) {
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_COPY
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
                putExtra(EXTRA_TARGET_DIR, targetDir)
            }
            startService(context, intent)
        }

        fun startMove(context: Context, sources: List<String>, displayNames: List<String>, targetDir: String) {
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_MOVE
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
                putExtra(EXTRA_TARGET_DIR, targetDir)
            }
            startService(context, intent)
        }

        fun startDelete(context: Context, sources: List<String>, displayNames: List<String>) {
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_DELETE
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
            }
            startService(context, intent)
        }

        fun startRecycle(context: Context, sources: List<String>, displayNames: List<String>) {
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_RECYCLE
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
            }
            startService(context, intent)
        }

        fun startRestore(context: Context, ids: List<String>, displayNames: List<String>): String {
            val jobId = java.util.UUID.randomUUID().toString()
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_RESTORE
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(ids))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
                putExtra(EXTRA_JOB_ID, jobId)
            }
            startService(context, intent)
            return jobId
        }

        fun startDownload(context: Context, sourcePath: String, displayName: String, targetFile: String) {
            val intent = Intent(context, FileJobService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putStringArrayListExtra(EXTRA_SOURCES, arrayListOf(sourcePath))
                putStringArrayListExtra(EXTRA_DISPLAY_NAMES, arrayListOf(displayName))
                putExtra(EXTRA_TARGET_FILE, targetFile)
            }
            startService(context, intent)
        }

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // job.id -> stable small int, so each concurrent job keeps its own notification slot.
    private val notificationIds = mutableMapOf<String, Int>()
    private var nextSlot = 0

    private fun notificationIdFor(jobId: String): Int =
        notificationIds.getOrPut(jobId) { NOTIFICATION_ID_BASE + (nextSlot++) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES) ?: return START_NOT_STICKY
        val displayNames = intent.getStringArrayListExtra(EXTRA_DISPLAY_NAMES) ?: return START_NOT_STICKY

        val sourcePaths = sources.indices.map { i ->
            val path = sources[i]
            val name = displayNames.getOrNull(i) ?: path.substringAfterLast('/').ifEmpty { "file" }
            SourcePath(path, name)
        }

        val jobType = when (action) {
            ACTION_START_COPY -> {
                val targetDir = intent.getStringExtra(EXTRA_TARGET_DIR) ?: return START_NOT_STICKY
                FileJobType.Copy(sourcePaths, java.io.File(targetDir).toPath())
            }
            ACTION_START_MOVE -> {
                val targetDir = intent.getStringExtra(EXTRA_TARGET_DIR) ?: return START_NOT_STICKY
                FileJobType.Move(sourcePaths, java.io.File(targetDir).toPath())
            }
            ACTION_START_DELETE -> {
                FileJobType.Delete(sourcePaths.map { java.io.File(it.path).toPath() })
            }
            ACTION_START_RECYCLE -> {
                FileJobType.Recycle(sourcePaths)
            }
            ACTION_START_RESTORE -> {
                FileJobType.Restore(sourcePaths)
            }
            ACTION_START_DOWNLOAD -> {
                val targetFile = intent.getStringExtra(EXTRA_TARGET_FILE) ?: return START_NOT_STICKY
                FileJobType.Download(sourcePaths[0], java.io.File(targetFile).toPath())
            }
            else -> return START_NOT_STICKY
        }

        val job = FileJob(
            id = intent.getStringExtra(EXTRA_JOB_ID) ?: java.util.UUID.randomUUID().toString(),
            type = jobType,
            totalItems = sourcePaths.size
        )
        val notificationId = notificationIdFor(job.id)

        // Only show status bar notifications for downloads (Save offline)
        val isDownload = jobType is FileJobType.Download
        val channelToUse = if (isDownload) CHANNEL_ID else SILENT_CHANNEL_ID

        // Foreground notification must be posted synchronously here
        startForeground(notificationId, createNotification(job, channelToUse))

        FileOperationRunner.execute(applicationContext, job, { updatedJob ->
            // Only update live progress in notification shade for downloads
            if (isDownload) {
                updateNotification(notificationId, updatedJob, channelToUse)
            }
        }, { success ->
            notificationIds.remove(job.id)
            // Only show completion notifications for downloads
            if (isDownload) {
                showCompletionNotification(job, success)
            }

            // Only drop foreground state / stop the service once nothing else
            // is running - a second job may have started while this one finished.
            if (JobManager.activeJobs.value.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        })

        return START_NOT_STICKY
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotification(job: FileJob, channelId: String = CHANNEL_ID): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val title = when (job.type) {
            is FileJobType.Copy -> "Copying Files"
            is FileJobType.Move -> "Moving Files"
            is FileJobType.Delete -> "Deleting Files"
            is FileJobType.Download -> "Saving Offline"
            is FileJobType.Recycle -> "Moving to Recycle Bin"
            is FileJobType.Restore -> "Restoring Files"
        }

        val progressPercent = (job.progress * 100).toInt()
        val sizeSuffix = if (job.isIndeterminate && job.processedBytes > 0) {
            " (${formatBytes(job.processedBytes)})"
        } else ""

        val contentText = if (job.isIndeterminate) {
            if (job.totalItems > 1) {
                "Processing ${job.processedItems}/${job.totalItems}: ${job.currentFileName}$sizeSuffix"
            } else {
                "Downloading ${job.currentFileName}$sizeSuffix"
            }
        } else if (job.totalItems > 1) {
            "Processing ${job.processedItems}/${job.totalItems}: ${job.currentFileName} ($progressPercent%)"
        } else {
            "Processing ${job.currentFileName}: $progressPercent%"
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            // Unknown size -> indeterminate spinner instead of a bar frozen at 0%.
            // Known size -> a real, moving progress bar.
            .setProgress(100, progressPercent, job.isIndeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(notificationId: Int, job: FileJob, channelId: String = CHANNEL_ID) {
        if (!hasNotificationPermission()) return
        val notification = createNotification(job, channelId)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    private fun showCompletionNotification(job: FileJob, success: Boolean) {
        if (!hasNotificationPermission()) return

        val label = when (job.type) {
            is FileJobType.Copy -> "Copy"
            is FileJobType.Move -> "Move"
            is FileJobType.Delete -> "Delete"
            is FileJobType.Download -> "Save offline"
            is FileJobType.Recycle -> "Moving to bin"
            is FileJobType.Restore -> "Restore"
        }
        val title = if (success) {
            when (job.type) {
                is FileJobType.Recycle -> "Moved to Recycle Bin"
                is FileJobType.Restore -> "Files restored"
                else -> "$label complete"
            }
        } else "$label failed"
        val text = if (success) {
            job.currentFileName.ifBlank { "Done" }
        } else {
            "Couldn't finish ${job.currentFileName}"
        }

        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(COMPLETION_NOTIFICATION_ID_BASE + (job.id.hashCode() and 0xFFFF), notification)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "File Operations", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(SILENT_CHANNEL_ID, "Background Operations", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(COMPLETION_CHANNEL_ID, "File Operation Results", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}