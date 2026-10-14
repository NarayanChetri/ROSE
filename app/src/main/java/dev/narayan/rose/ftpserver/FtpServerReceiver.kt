package dev.narayan.rose.ftpserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FtpServerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            FtpServerService.stop(context)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.narayan.rose.ftpserver.ACTION_STOP"

        fun createStopIntent(context: Context): Intent =
            Intent(context, FtpServerReceiver::class.java).apply {
                action = ACTION_STOP
            }
    }
}

