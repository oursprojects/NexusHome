package com.capstone.nexushome.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import com.capstone.nexushome.R
import android.util.Log

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Reconnecting : ConnectionState()
    object Connected : ConnectionState()
    object BluetoothDisabled : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

data class DeviceStatus(
    val temperature: String = "0.0",
    val lightOn: Boolean = false,
    val fanOn: Boolean = false,
    val autoMode: Boolean = false
)

/**
 * Singleton Bluetooth service using Coroutines and StateFlow.
 * Cleaned up: Discovery/Scanning logic removed.
 */
class BluetoothService private constructor(private val context: Context) {

    private val TAG = "BluetoothService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus = _deviceStatus.asStateFlow()

    private var autoReconnectEnabled = true
    private var lastDeviceAddress: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttemptCount = 0
    private val prefs = context.getSharedPreferences("nexus_bt_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val DEVICE_NAME = "NexusHome"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val CMD_STATUS_REQUEST = "S"
        private const val STATUS_PREFIX = "STATUS"
        private const val BUFFER_MAX_SIZE = 4096

        @Volatile
        private var INSTANCE: BluetoothService? = null

        fun getInstance(context: Context): BluetoothService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var listeningJob: Job? = null
    private var connectJob: Job? = null

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isDeviceConnected(): Boolean = connectionState.value is ConnectionState.Connected

    @SuppressLint("MissingPermission")
    fun connect() {
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Failed(context.getString(R.string.bt_not_supported))
            return
        }

        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.BluetoothDisabled
            return
        }

        if (isDeviceConnected() || connectionState.value is ConnectionState.Connecting) return

        connectJob?.cancel()
        connectJob = serviceScope.launch {
            autoReconnectEnabled = true
            performConnectionFlow()
        }
    }

    private suspend fun performConnectionFlow() {
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Starting connection flow...")
        
        try {
            val device = getPairedDevice()
            if (device == null) {
                _connectionState.value = ConnectionState.Failed(context.getString(R.string.device_not_found))
                return
            }

            attemptConnection(device)
            
            _connectionState.value = ConnectionState.Connected
            reconnectAttemptCount = 0 // Reset on success
            
            Log.i(TAG, "Connected to ${device.name} (${device.address})")
            
            startListening()
            delay(500)
            sendCode(CMD_STATUS_REQUEST)

        } catch (e: CancellationException) {
            Log.i(TAG, "Connection flow cancelled by user")
            cleanup()
            _connectionState.value = ConnectionState.Disconnected
        } catch (e: Exception) {
            Log.e(TAG, "Connection flow failed: ${e.message}")
            cleanup()
            val errorMsg = when (e) {
                is TimeoutCancellationException -> "Hardware ignored connection request"
                is IOException -> "NexusHome is offline or busy"
                else -> e.message ?: "Connection error"
            }
            _connectionState.value = ConnectionState.Failed(errorMsg)
            startAutoReconnect()
        } finally {
            connectJob = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptConnection(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        lastDeviceAddress = device.address
        prefs.edit().putString("last_device_mac", lastDeviceAddress).apply()

        bluetoothAdapter?.cancelDiscovery()
        
        withTimeout(12000L) {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            
            bluetoothSocket = socket
            inputStream = socket.inputStream
            outputStream = socket.outputStream
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevice(): BluetoothDevice? {
        return try {
            val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter?.bondedDevices ?: emptySet()
            val lastMac = prefs.getString("last_device_mac", null)
            if (lastMac != null) {
                val device = pairedDevices.find { it.address == lastMac }
                if (device != null) return device
            }
            pairedDevices.find { it.name == DEVICE_NAME }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission while querying paired devices", e)
            null
        }
    }

    fun disconnect() {
        autoReconnectEnabled = false 
        connectJob?.cancel()
        reconnectJob?.cancel()
        listeningJob?.cancel()
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "Service disconnected manually")
    }

    fun shutdown() {
        disconnect()
        serviceScope.cancel("Service destroyed")
    }

    suspend fun sendCode(code: String): Result<Unit> {
        if (!isDeviceConnected()) {
            Log.w(TAG, "Command dropped while disconnected: $code")
            return Result.failure(IllegalStateException(context.getString(R.string.msg_not_connected)))
        }

        return withContext(Dispatchers.IO) {
            try {
                val out = outputStream ?: throw IOException("Output stream unavailable")
                out.write(code.toByteArray(Charsets.UTF_8))
                out.flush()
                Result.success(Unit)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send command: $code", e)
                cleanup()
                _connectionState.value = ConnectionState.Failed("Connection lost while sending command")
                if (autoReconnectEnabled) startAutoReconnect()
                Result.failure(IOException(context.getString(R.string.connection_failed), e))
            }
        }
    }

    private fun startAutoReconnect() {
        if (!autoReconnectEnabled || lastDeviceAddress == null) return
        if (reconnectJob?.isActive == true) return 

        reconnectJob = serviceScope.launch {
            Log.d(TAG, "Starting auto-reconnect loop...")
            
            while (isActive && autoReconnectEnabled && !isDeviceConnected()) {
                if (reconnectAttemptCount >= MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "Max reconnect attempts reached. Waiting for user...")
                    _connectionState.value = ConnectionState.Failed("Connection lost. Tap to retry.")
                    break
                }

                if (!isBluetoothEnabled()) {
                    Log.w(TAG, "BT disabled during reconnect")
                    _connectionState.value = ConnectionState.BluetoothDisabled
                    break
                }

                try {
                    val device = getPairedDevice()
                    if (device == null) {
                        Log.w(TAG, "Paired device not found during reconnect. Returning to idle state.")
                        autoReconnectEnabled = false
                        reconnectAttemptCount = 0
                        _connectionState.value = ConnectionState.Failed(context.getString(R.string.device_not_found))
                        break
                    }

                    _connectionState.value = ConnectionState.Reconnecting
                    reconnectAttemptCount++

                    val delayMs = (3000L * reconnectAttemptCount).coerceAtMost(20000L)
                    Log.d(TAG, "Reconnect attempt $reconnectAttemptCount in ${delayMs}ms")
                    delay(delayMs)

                    attemptConnection(device)
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttemptCount = 0
                    Log.i(TAG, "Auto-reconnect successful")

                    startListening()
                    delay(500)
                    sendCode(CMD_STATUS_REQUEST)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt $reconnectAttemptCount failed: ${e.message}")
                    cleanup()
                }
            }
        }
    }

    private fun startListening() {
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            val buffer = ByteArray(1024)
            val dataBuilder = StringBuilder()
            
            Log.d(TAG, "Starting socket listener...")
            
            while (isActive) {
                try {
                    val inputStreamSafe = inputStream ?: throw IOException("Stream closed")
                    val bytesRead = inputStreamSafe.read(buffer)
                    
                    if (bytesRead <= 0) throw IOException("End of stream")

                    val received = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    
                    if (dataBuilder.length > BUFFER_MAX_SIZE) {
                        Log.w(TAG, "Buffer overflow, clearing...")
                        dataBuilder.setLength(0)
                    }
                    dataBuilder.append(received)

                    while (dataBuilder.contains("\n") || dataBuilder.contains("\r")) {
                        val newlineIndex = dataBuilder.indexOfFirst { it == '\n' || it == '\r' }
                        val line = dataBuilder.substring(0, newlineIndex).trim()
                        dataBuilder.delete(0, newlineIndex + 1)

                        if (line.isNotEmpty() && line.startsWith(STATUS_PREFIX)) {
                            parseStatus(line)
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Socket listener cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error: ${e.message}")
                    if (isDeviceConnected()) {
                        cleanup()
                        _connectionState.value = ConnectionState.Disconnected
                        if (autoReconnectEnabled) startAutoReconnect()
                    }
                    break
                }
            }
        }
    }

    private fun parseStatus(data: String) {
        try {
            val parts = data.split('|')
            if (parts.size < 5 || parts[0] != STATUS_PREFIX) {
                Log.w(TAG, "Ignored malformed status frame: $data")
                return
            }

            val temperature = parts[1].trim()
            if (temperature.toFloatOrNull() == null) {
                Log.w(TAG, "Ignored invalid temperature in status frame: $data")
                return
            }

            val lightOn = toBinaryFlagOrNull(parts[2])
            val fanOn = toBinaryFlagOrNull(parts[3])
            val autoMode = toBinaryFlagOrNull(parts[4])
            if (lightOn == null || fanOn == null || autoMode == null) {
                Log.w(TAG, "Ignored invalid control flags in status frame: $data")
                return
            }

            _deviceStatus.value = DeviceStatus(
                temperature = temperature,
                lightOn = lightOn,
                fanOn = fanOn,
                autoMode = autoMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Status parse error: ${e.message} for data: $data")
        }
    }

    private fun toBinaryFlagOrNull(value: String): Boolean? {
        return when (value.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private fun resetStatus() {
        _deviceStatus.value = DeviceStatus()
    }

    private fun cleanup() {
        resetStatus()
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }
}
