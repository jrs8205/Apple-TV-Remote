package com.jrs8205.appletvremote.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jrs8205.appletvremote.MainActivity
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.appContainer
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Quick Settings tile: shows the paired Apple TV and opens the remote. */
class RemoteTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updates: Job? = null

    override fun onStartListening() {
        updates?.cancel()
        updates = scope.launch {
            appContainer.remoteController.state.collect { state ->
                val tile = qsTile ?: return@collect
                val device = state.device
                tile.state = when {
                    device == null -> Tile.STATE_INACTIVE
                    state.connection == ConnectionState.Ready -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = device?.name ?: getString(R.string.tile_not_paired)
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        updates?.cancel()
        updates = null
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pending)
    }

    override fun onDestroy() {
        updates?.cancel()
        super.onDestroy()
    }
}
