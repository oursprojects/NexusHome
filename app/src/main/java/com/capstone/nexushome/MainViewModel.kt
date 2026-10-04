package com.capstone.nexushome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.nexushome.bluetooth.BluetoothService
import com.capstone.nexushome.bluetooth.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val bluetoothService = BluetoothService.getInstance(application)

    val connectionState = bluetoothService.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Disconnected)

    val deviceStatus = bluetoothService.deviceStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), bluetoothService.deviceStatus.value)

    val curtainMotionState = bluetoothService.curtainMotionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), bluetoothService.curtainMotionState.value)

    val curtainProgress = bluetoothService.curtainProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    fun connect() {
        bluetoothService.connect()
    }

    fun disconnect() {
        bluetoothService.disconnect()
    }

    suspend fun sendCommand(code: String): Result<Unit> {
        return bluetoothService.sendCode(code)
    }

    fun triggerCurtain(open: Boolean) {
        bluetoothService.triggerCurtain(getApplication(), open)
    }

    fun isBluetoothAvailable() = bluetoothService.isBluetoothAvailable()
    fun isBluetoothEnabled() = bluetoothService.isBluetoothEnabled()
}
