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
import com.capstone.nexushome.data.AppDatabase
import com.capstone.nexushome.data.CommandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NexusModeTileService : TileService() {

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

        val isAuto = bluetoothService.deviceStatus.value.autoMode
        val targetCommand = if (isAuto) "c" else "C"
        val targetAction = if (isAuto) "Manual Mode" else "Auto Mode"

        serviceScope.launch {
            val result = bluetoothService.sendCode(targetCommand)
            if (result.isSuccess) {
                runCatching {
                    val ts = SimpleDateFormat("MMM d, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date())
                    val db = AppDatabase.getInstance(applicationContext)
                    db.commandLogDao().insert(CommandLog(timestamp = ts, action = targetAction))
                }
            }
            updateTileState()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val bluetoothService = BluetoothService.getInstance(applicationContext)
        val isConnected = bluetoothService.connectionState.value is ConnectionState.Connected

        tile.label = getString(R.string.tile_mode_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mode)

        if (!isConnected) {
            tile.state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_state_disconnected)
            }
        } else {
            val isAuto = bluetoothService.deviceStatus.value.autoMode
            tile.state = if (isAuto) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (isAuto) getString(R.string.tile_mode_auto) else getString(R.string.tile_mode_manual)
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
