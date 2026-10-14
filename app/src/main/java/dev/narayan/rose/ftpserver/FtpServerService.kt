package dev.narayan.rose.ftpserver

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.narayan.rose.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

enum class FtpServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}

class FtpServerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wakeWifiLock: WakeWifiLock
    private lateinit var notification: FtpServerNotification
    private lateinit var settings: SettingsManager

    private var ftpServer: FtpServer? = null

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (_state.value == FtpServerState.RUNNING) {
                notification.updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeWifiLock = WakeWifiLock(this, "ROSE:FtpServer")
        notification = FtpServerNotification(this)
        settings = SettingsManager(this)

        @Suppress("DEPRECATION")
        registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        executeStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (e: Exception) {
            // ignore
        }
        executeStop()
    }

    private fun executeStart() {
        if (_state.value == FtpServerState.STARTING || _state.value == FtpServerState.RUNNING) {
            return
        }
        _state.value = FtpServerState.STARTING
        wakeWifiLock.isAcquired = true
        notification.startForeground()

        thread(name = "FtpServer-Start", isDaemon = true) {
            try {
                val username = if (settings.ftpServerAnonymousLogin) {
                    USERNAME_ANONYMOUS
                } else {
                    settings.ftpServerUsername
                }
                val password = if (settings.ftpServerAnonymousLogin) {
                    null
                } else {
                    settings.ftpServerPassword.ifEmpty { null }
                }
                val port = settings.ftpServerPort
                val homeDir = File(settings.ftpServerHomeDirectory)
                val writable = settings.ftpServerWritable

                val server = FtpServer(
                    username = username,
                    password = password,
                    port = port,
                    homeDirectory = homeDir,
                    writable = writable
                )
                server.start()
                ftpServer = server
                mainHandler.post {
                    _state.value = FtpServerState.RUNNING
                    notification.updateNotification()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    _state.value = FtpServerState.STOPPED
                    wakeWifiLock.isAcquired = false
                    notification.stopForeground()
                    Toast.makeText(this@FtpServerService, "FTP Server error: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }
    }

    private fun executeStop() {
        _state.value = FtpServerState.STOPPING
        wakeWifiLock.isAcquired = false
        try {
            notification.stopForeground()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val server = ftpServer
        ftpServer = null

        if (server == null) {
            _state.value = FtpServerState.STOPPED
            return
        }

        thread(name = "FtpServer-Stop", isDaemon = true) {
            try {
                server.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mainHandler.post {
                    _state.value = FtpServerState.STOPPED
                }
            }
        }
    }

    companion object {
        const val USERNAME_ANONYMOUS = "anonymous"

        private val _state = MutableStateFlow(FtpServerState.STOPPED)
        val state: StateFlow<FtpServerState> = _state.asStateFlow()

        val isRunning: Boolean
            get() = _state.value == FtpServerState.RUNNING || _state.value == FtpServerState.STARTING

        fun start(context: Context) {
            val intent = Intent(context, FtpServerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            _state.value = FtpServerState.STOPPING
            val intent = Intent(context, FtpServerService::class.java)
            context.stopService(intent)
        }

        fun toggle(context: Context) {
            when (_state.value) {
                FtpServerState.STARTING, FtpServerState.STOPPING -> {}
                FtpServerState.RUNNING -> stop(context)
                FtpServerState.STOPPED -> start(context)
            }
        }
    }
}
