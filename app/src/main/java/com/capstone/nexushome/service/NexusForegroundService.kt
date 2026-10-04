package com.capstone.nexushome.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.capstone.nexushome.MainActivity
import com.capstone.nexushome.R
import com.capstone.nexushome.bluetooth.BluetoothService
import com.capstone.nexushome.bluetooth.ConnectionState
import com.capstone.nexushome.bluetooth.DeviceStatus
import com.capstone.nexushome.widget.NexusHomeWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NexusForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForegroundActive = false

    companion object {
        const val CHANNEL_ID = "nexus_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.capstone.nexushome.action.START_FOREGROUND"
        const val ACTION_STOP = "com.capstone.nexushome.action.STOP_FOREGROUND"
        const val ACTION_DISCONNECT = "com.capstone.nexushome.action.DISCONNECT"

        fun startService(context: Context) {
            val intent = Intent(context, NexusForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NexusForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeBluetooth()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                BluetoothService.getInstance(applicationContext).disconnect()
                stopForegroundGracefully()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundGracefully()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val bluetoothService = BluetoothService.getInstance(applicationContext)
                val notification = buildNotification(bluetoothService.deviceStatus.value)
                promoteToForeground(notification)
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(notification: Notification) {
        if (!isForegroundActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun observeBluetooth() {
        val bluetoothService = BluetoothService.getInstance(applicationContext)

        serviceScope.launch {
            bluetoothService.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val notification = buildNotification(bluetoothService.deviceStatus.value)
                        promoteToForeground(notification)
                    }
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        stopForegroundGracefully()
                    }
                    else -> {}
                }
                NexusHomeWidgetProvider.updateAllWidgets(applicationContext)
            }
        }

        serviceScope.launch {
            bluetoothService.deviceStatus.collectLatest { status ->
                if (bluetoothService.connectionState.value is ConnectionState.Connected) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, buildNotification(status))
                }
                NexusHomeWidgetProvider.updateAllWidgets(applicationContext)
            }
        }
    }

    private fun buildNotification(status: DeviceStatus): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, NexusForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tempText = if (status.temperature.isNotEmpty() && status.temperature != "0.0") {
            "${status.temperature} °C"
        } else {
            "-- °C"
        }
        val fanText = if (status.fanOn) getString(R.string.fan_state_on) else getString(R.string.fan_state_off)
        val lightText = if (status.lightOn) getString(R.string.light_state_on) else getString(R.string.light_state_off)

        val contentText = getString(
            R.string.notification_connected_format,
            tempText,
            fanText,
            lightText
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_bluetooth)
            .setContentTitle(getString(R.string.notification_connected_title))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_lucide_bluetooth_off,
                getString(R.string.notification_action_disconnect),
                disconnectPendingIntent
            )
            .build()
    }

    private fun stopForegroundGracefully() {
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        isForegroundActive = false
        super.onDestroy()
    }
}
