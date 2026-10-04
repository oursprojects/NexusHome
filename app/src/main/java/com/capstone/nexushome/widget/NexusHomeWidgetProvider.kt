package com.capstone.nexushome.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.capstone.nexushome.MainActivity
import com.capstone.nexushome.R
import com.capstone.nexushome.bluetooth.BluetoothService
import com.capstone.nexushome.bluetooth.ConnectionState
import com.capstone.nexushome.data.AppDatabase
import com.capstone.nexushome.data.CommandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NexusHomeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_LIGHT = "com.capstone.nexushome.widget.ACTION_TOGGLE_LIGHT"
        const val ACTION_TOGGLE_FAN = "com.capstone.nexushome.widget.ACTION_TOGGLE_FAN"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, NexusHomeWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isNotEmpty()) {
                val views = buildRemoteViews(context)
                appWidgetManager.updateAppWidget(appWidgetIds, views)
            }
        }

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nexus_home)
            val bluetoothService = BluetoothService.getInstance(context.applicationContext)
            val isConnected = bluetoothService.connectionState.value is ConnectionState.Connected
            val status = bluetoothService.deviceStatus.value

            // 1. PendingIntents to launch MainActivity from header and temp panel
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.llWidgetHeader, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.llWidgetTemp, openAppPendingIntent)

            // 2. PendingIntents for interactive Fan & Light toggles
            val toggleFanIntent = Intent(context, NexusHomeWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_FAN
            }
            val toggleFanPendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                toggleFanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetFan, toggleFanPendingIntent)

            val toggleLightIntent = Intent(context, NexusHomeWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_LIGHT
            }
            val toggleLightPendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                toggleLightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetLight, toggleLightPendingIntent)

            // 3. Render Connection Status Badge
            if (isConnected) {
                views.setTextViewText(R.id.tvWidgetStatus, context.getString(R.string.widget_status_connected))
                views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.status_connected_text))
                views.setInt(R.id.tvWidgetStatus, "setBackgroundResource", R.drawable.bg_status_positive)
            } else {
                views.setTextViewText(R.id.tvWidgetStatus, context.getString(R.string.widget_status_disconnected))
                views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.status_disconnected_text))
                views.setInt(R.id.tvWidgetStatus, "setBackgroundResource", R.drawable.bg_status_neutral)
            }

            // 4. Render Temperature & Environmental Cues
            if (isConnected && status.temperature.isNotEmpty() && status.temperature != "0.0") {
                val tempFloat = status.temperature.toFloatOrNull() ?: 0f
                views.setTextViewText(R.id.tvWidgetTemperature, "${status.temperature} °C")
                views.setImageViewResource(
                    R.id.ivWidgetTempIcon,
                    if (tempFloat >= 30f) R.drawable.temp_hot else R.drawable.temp_cold
                )
                val hintText = when {
                    tempFloat < 20f -> context.getString(R.string.temperature_state_cool)
                    tempFloat < 25f -> context.getString(R.string.temperature_state_comfortable)
                    tempFloat < 30f -> context.getString(R.string.temperature_state_warm)
                    else -> context.getString(R.string.temperature_state_hot)
                }
                views.setTextViewText(R.id.tvWidgetTempHint, hintText)
            } else {
                views.setTextViewText(R.id.tvWidgetTemperature, context.getString(R.string.temp_default))
                views.setImageViewResource(R.id.ivWidgetTempIcon, R.drawable.temp_cold)
                views.setTextViewText(R.id.tvWidgetTempHint, context.getString(R.string.connection_hint_disconnected))
            }

            // 5. Render Fan Button
            if (isConnected && status.fanOn) {
                views.setInt(R.id.btnWidgetFan, "setBackgroundResource", R.drawable.bg_widget_control_on)
                views.setTextViewText(R.id.tvWidgetFanState, context.getString(R.string.fan_state_on))
            } else {
                views.setInt(R.id.btnWidgetFan, "setBackgroundResource", R.drawable.bg_widget_control_off)
                views.setTextViewText(R.id.tvWidgetFanState, context.getString(R.string.fan_state_off))
            }

            // 6. Render Light Button
            if (isConnected && status.lightOn) {
                views.setInt(R.id.btnWidgetLight, "setBackgroundResource", R.drawable.bg_widget_control_on)
                views.setTextViewText(R.id.tvWidgetLightState, context.getString(R.string.light_state_on))
            } else {
                views.setInt(R.id.btnWidgetLight, "setBackgroundResource", R.drawable.bg_widget_control_off)
                views.setTextViewText(R.id.tvWidgetLightState, context.getString(R.string.light_state_off))
            }

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildRemoteViews(context)
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val bluetoothService = BluetoothService.getInstance(context.applicationContext)
        val isConnected = bluetoothService.connectionState.value is ConnectionState.Connected

        when (intent.action) {
            ACTION_TOGGLE_LIGHT -> {
                if (!isConnected) {
                    launchMainActivity(context)
                } else {
                    val currentLight = bluetoothService.deviceStatus.value.lightOn
                    val targetCommand = if (currentLight) "a" else "A"
                    val targetAction = if (currentLight) "Light OFF" else "Light ON"

                    CoroutineScope(Dispatchers.IO).launch {
                        val result = bluetoothService.sendCode(targetCommand)
                        if (result.isSuccess) {
                            runCatching {
                                val ts = SimpleDateFormat("MMM d, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date())
                                val db = AppDatabase.getInstance(context.applicationContext)
                                db.commandLogDao().insert(CommandLog(timestamp = ts, action = targetAction))
                            }
                        }
                        updateAllWidgets(context)
                    }
                }
            }
            ACTION_TOGGLE_FAN -> {
                if (!isConnected) {
                    launchMainActivity(context)
                } else {
                    val currentFan = bluetoothService.deviceStatus.value.fanOn
                    val targetCommand = if (currentFan) "b" else "B"
                    val targetAction = if (currentFan) "Fan OFF" else "Fan ON"

                    CoroutineScope(Dispatchers.IO).launch {
                        val result = bluetoothService.sendCode(targetCommand)
                        if (result.isSuccess) {
                            runCatching {
                                val ts = SimpleDateFormat("MMM d, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date())
                                val db = AppDatabase.getInstance(context.applicationContext)
                                db.commandLogDao().insert(CommandLog(timestamp = ts, action = targetAction))
                            }
                        }
                        updateAllWidgets(context)
                    }
                }
            }
        }
    }

    private fun launchMainActivity(context: Context) {
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(appIntent)
    }
}
