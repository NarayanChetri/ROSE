package dev.narayan.rose.ftpserver

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class FtpServerTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = scope.launch {
            FtpServerService.state.collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        job?.cancel()
        job = null
    }

    private fun updateTileState(state: FtpServerState) {
        val tile = qsTile ?: return
        when (state) {
            FtpServerState.STARTING,
            FtpServerState.RUNNING -> tile.state = Tile.STATE_ACTIVE
            FtpServerState.STOPPING -> tile.state = Tile.STATE_UNAVAILABLE
            FtpServerState.STOPPED -> tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun {
                FtpServerService.toggle(this)
            }
        } else {
            FtpServerService.toggle(this)
        }
    }
}

