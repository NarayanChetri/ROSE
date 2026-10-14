package dev.narayan.rose.ftpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.narayan.rose.MainActivity
import dev.narayan.rose.R
import dev.narayan.rose.SettingsManager

class FtpServerNotification(private val service: Service) {
    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settings = SettingsManager(service)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.ftp_server_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = service.getString(R.string.ftp_server_notification_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startForeground() {
        val notification = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val url = FtpServerUrl.getUrl(service, settings)
        val contentText = url ?: service.getString(R.string.ftp_server_url_summary_no_local_inet_address)

        val contentIntent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FTP, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            service,
            1001,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = FtpServerReceiver.createStopIntent(service)
        val stopPendingIntent = PendingIntent.getBroadcast(
            service,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ftp_server)
            .setContentTitle(service.getString(R.string.ftp_server_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_stop,
                service.getString(R.string.ftp_server_notification_stop),
                stopPendingIntent
            )
            .build()
    }

    fun stopForeground() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val CHANNEL_ID = "rose_ftp_server"
        const val NOTIFICATION_ID = 2121
        const val EXTRA_OPEN_FTP = "open_ftp_server"
    }
}

