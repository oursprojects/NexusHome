package com.capstone.nexushome.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.capstone.nexushome.MainActivity
import com.capstone.nexushome.R
import com.capstone.nexushome.bluetooth.BluetoothService
import com.capstone.nexushome.bluetooth.ConnectionState
import com.capstone.nexushome.bluetooth.CurtainMotionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NexusCurtainTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            val bluetoothService = BluetoothService.getInstance(applicationContext)
            launch {
                bluetoothService.connectionState.collectLatest {
                    updateTileState()
                }
            }
            launch {
                bluetoothService.deviceStatus.collectLatest {
                    updateTileState()
                }
            }
            launch {
                bluetoothService.curtainMotionState.collectLatest {
                    updateTileState()
                }
            }
            launch {
                bluetoothService.curtainProgress.collectLatest {
                    updateTileState()
                }
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val bluetoothService = BluetoothService.getInstance(applicationContext)
        val isConnected = bluetoothService.connectionState.value is ConnectionState.Connected

        if (!isConnected) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        val motion = bluetoothService.curtainMotionState.value
        if (motion == CurtainMotionState.OPENING || motion == CurtainMotionState.CLOSING) {
            return
        }

        val isOpen = (motion == CurtainMotionState.IDLE_OPEN) || bluetoothService.deviceStatus.value.curtainOpen
        bluetoothService.triggerCurtain(applicationContext, !isOpen)
        updateTileState()
    }

    private fun getCurtainTileFrame(progress: Float, isOpening: Boolean): Int {
        val p = if (isOpening) progress.coerceIn(0f, 1f) else (1f - progress.coerceIn(0f, 1f))
        return when {
            p < 0.18f -> R.drawable.ic_tile_curtain_closed
            p < 0.42f -> R.drawable.ic_tile_curtain_transit_1
            p < 0.65f -> R.drawable.ic_tile_curtain_transit_2
            p < 0.88f -> R.drawable.ic_tile_curtain_transit_3
            else -> R.drawable.ic_tile_curtain_open
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val bluetoothService = BluetoothService.getInstance(applicationContext)
        val isConnected = bluetoothService.connectionState.value is ConnectionState.Connected
        val motion = bluetoothService.curtainMotionState.value
        val progress = bluetoothService.curtainProgress.value

        tile.label = getString(R.string.tile_curtain_label)

        if (!isConnected) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_curtain_closed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_state_disconnected)
            }
        } else {
            when (motion) {
                CurtainMotionState.OPENING -> {
                    tile.state = Tile.STATE_ACTIVE
                    val dotCount = ((progress * 40).toInt() % 3) + 1
                    val dots = ".".repeat(dotCount)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_curtain_opening) + dots
                    }
                    tile.icon = Icon.createWithResource(this, getCurtainTileFrame(progress, isOpening = true))
                }
                CurtainMotionState.CLOSING -> {
                    tile.state = Tile.STATE_ACTIVE
                    val dotCount = ((progress * 40).toInt() % 3) + 1
                    val dots = ".".repeat(dotCount)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_curtain_closing) + dots
                    }
                    tile.icon = Icon.createWithResource(this, getCurtainTileFrame(progress, isOpening = false))
                }
                CurtainMotionState.IDLE_OPEN -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_curtain_open)
                    }
                    tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_curtain_open)
                }
                CurtainMotionState.IDLE_CLOSED -> {
                    tile.state = Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = getString(R.string.tile_curtain_closed)
                    }
                    tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_curtain_closed)
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
