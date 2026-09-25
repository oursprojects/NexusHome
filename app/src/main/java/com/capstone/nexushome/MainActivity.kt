package com.capstone.nexushome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.TouchDelegate
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capstone.nexushome.bluetooth.ConnectionState
import com.capstone.nexushome.bluetooth.DeviceStatus
import com.capstone.nexushome.data.AppDatabase
import com.capstone.nexushome.data.CommandLog
import com.capstone.nexushome.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private val database by lazy { AppDatabase.getInstance(this) }

    private var isUpdatingUI = false
    private var pendingConnectRequest = false
    private var lastToastMessage: String? = null
    private val uiPrefs by lazy { getSharedPreferences("nexus_ui_prefs", MODE_PRIVATE) }
    private val commandTimeFormatter by lazy { SimpleDateFormat("hh:mm:ss a", Locale.getDefault()) }
    private val statusTimeFormatter by lazy { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    private enum class LocalCurtainState { IDLE, OPENING, CLOSING }
    private var localCurtainState = LocalCurtainState.IDLE

    private var fanLock = false
    private var lightLock = false
    private var modeLock = false
    private var keepSplashOnScreen = true
    private var entranceAnimationPlayed = false

    companion object {
        private const val CONTROL_COOLDOWN_MS = 1000L
        private const val KEY_PERMISSION_PROMPTED_ONCE = "permission_prompted_once"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            applyPermissionGate(hasMissingPermissions = false)
            showUserMessage(getString(R.string.permissions_granted))
            if (pendingConnectRequest) {
                pendingConnectRequest = false
                onConnectClicked()
            }
        } else {
            applyPermissionGate(hasMissingPermissions = true)
            setConnectionStatus(
                getString(R.string.permission_required),
                R.color.status_error_text,
                R.drawable.bg_status_error
            )
            setConnectionHint(getString(R.string.connection_hint_permissions))
            showUserMessage(getString(R.string.permission_required), Toast.LENGTH_LONG)
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            lifecycleScope.launch {
                delay(1000)
                if (pendingConnectRequest) {
                    pendingConnectRequest = false
                    onConnectClicked()
                }
            }
        } else {
            setConnectionStatus(
                getString(R.string.bt_not_enabled),
                R.color.status_error_text,
                R.drawable.bg_status_error
            )
            setConnectionHint(getString(R.string.connection_hint_bluetooth_disabled))
            showUserMessage(getString(R.string.bt_not_enabled))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSplashScreen(splashScreen, isFirstLaunch = savedInstanceState == null)

        val baseStart = binding.dashboardContent.paddingStart
        val baseTop = binding.dashboardContent.paddingTop
        val baseEnd = binding.dashboardContent.paddingEnd
        val baseBottom = binding.dashboardContent.paddingBottom
        val footerBasePadBottom = binding.footerContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.dashboardContent.setPadding(
                baseStart + bars.left,
                baseTop + bars.top,
                baseEnd + bars.right,
                baseBottom
            )
            binding.footerContainer.setPadding(
                bars.left,
                0,
                bars.right,
                footerBasePadBottom + bars.bottom
            )
            insets
        }

        setDisconnectedState()
        setConnectionStatus(
            getString(R.string.status_disconnected),
            R.color.status_disconnected_text,
            R.drawable.bg_status_neutral
        )
        setConnectionHint(getString(R.string.connection_hint_disconnected))
        observeState()
        setupListeners()
        expandTouchTargets()
        checkPermissions()
    }

    private fun setupSplashScreen(splashScreen: SplashScreen, isFirstLaunch: Boolean) {
        if (!isFirstLaunch) {
            keepSplashOnScreen = false
            return
        }

        // Prime dashboard content offstage for seamless reveal
        binding.dashboardContent.alpha = 0f
        binding.dashboardContent.translationY = 24f
        binding.ivLogo.scaleX = 0.85f
        binding.ivLogo.scaleY = 0.85f

        // Keep splash screen visible for a comfortable duration
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }

        lifecycleScope.launch {
            delay(1000L)
            keepSplashOnScreen = false
        }

        // Custom exit animation: smooth icon expansion & fade-out, followed by dashboard slide-in
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            val splashView = splashScreenViewProvider.view
            val iconView = runCatching { splashScreenViewProvider.iconView }.getOrNull()

            val animators = mutableListOf<android.animation.Animator>()

            if (iconView != null) {
                val scaleX = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 1.18f)
                val scaleY = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 1.18f)
                val iconAlpha = ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f)
                animators.add(scaleX)
                animators.add(scaleY)
                animators.add(iconAlpha)
            }

            val viewAlpha = ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f)
            animators.add(viewAlpha)

            AnimatorSet().apply {
                duration = 380L
                interpolator = DecelerateInterpolator()
                playTogether(animators)
                doOnEnd {
                    splashScreenViewProvider.remove()
                    if (!entranceAnimationPlayed) {
                        entranceAnimationPlayed = true
                        playEntranceAnimation()
                    }
                }
                start()
            }
        }
    }

    private fun playEntranceAnimation() {
        binding.dashboardContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(550L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        binding.ivLogo.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(550L)
            .setStartDelay(80L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collectLatest(::handleConnectionState)
                }
                launch {
                    viewModel.deviceStatus.collectLatest(::handleDeviceStatus)
                }
            }
        }
    }

    private fun handleConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Disconnected -> {
                setDisconnectedState()
                setConnectionStatus(
                    getString(R.string.status_disconnected),
                    R.color.status_disconnected_text,
                    R.drawable.bg_status_neutral
                )
                setConnectionHint(getString(R.string.connection_hint_disconnected))
            }
            is ConnectionState.Connecting -> {
                setConnectingState()
                setConnectionStatus(
                    getString(R.string.status_connecting),
                    R.color.status_connecting,
                    R.drawable.bg_status_warning
                )
                setConnectionHint(getString(R.string.connection_hint_connecting))
            }
            is ConnectionState.Reconnecting -> {
                setConnectingState()
                setConnectionStatus(
                    getString(R.string.status_reconnecting),
                    R.color.status_connecting,
                    R.drawable.bg_status_warning
                )
                setConnectionHint(getString(R.string.connection_hint_reconnecting))
            }
            is ConnectionState.Connected -> {
                setConnectedState()
                setConnectionStatus(
                    getString(R.string.status_connected),
                    R.color.status_connected_text,
                    R.drawable.bg_status_positive
                )
                setConnectionHint(getString(R.string.connection_hint_connected))
            }
            is ConnectionState.BluetoothDisabled -> {
                setDisconnectedState()
                binding.btnConnect.text = getString(R.string.btn_enable_bluetooth)
                binding.btnConnect.setIconResource(R.drawable.ic_lucide_bluetooth_off)
                setConnectionStatus(
                    getString(R.string.bt_not_enabled),
                    R.color.status_error_text,
                    R.drawable.bg_status_error
                )
                setConnectionHint(getString(R.string.connection_hint_bluetooth_disabled))
                showUserMessage(getString(R.string.bt_not_enabled))
            }
            is ConnectionState.Failed -> {
                setDisconnectedState()
                setConnectionStatus(state.message, R.color.status_error_text, R.drawable.bg_status_error)
                setConnectionHint(getString(R.string.connection_hint_disconnected))
                showUserMessage(state.message, Toast.LENGTH_LONG)
            }
        }
    }

    private fun handleDeviceStatus(status: DeviceStatus) {
        if (isUpdatingUI) return
        renderDeviceStatus(
            status = status,
            markFreshUpdate = viewModel.connectionState.value is ConnectionState.Connected
        )
    }

    @SuppressLint("MissingPermission")
    private fun setupListeners() {
        binding.btnGrantPermissions.setOnClickListener {
            val needed = getNeededPermissions()
            if (needed.isEmpty()) {
                applyPermissionGate(hasMissingPermissions = false)
                return@setOnClickListener
            }

            val canAskSystem = needed.any { shouldShowRequestPermissionRationale(it) }
            val promptedOnce = uiPrefs.getBoolean(KEY_PERMISSION_PROMPTED_ONCE, false)

            if (canAskSystem || !promptedOnce) {
                permissionLauncher.launch(needed.toTypedArray())
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.permission_denied_title))
                    .setMessage(getString(R.string.permission_settings_msg))
                    .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                        runCatching {
                            startActivity(
                                Intent(
Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)
                                )
                            )
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }

        binding.btnHelp.setOnClickListener { showGuideDialog() }
        binding.cardMode.setOnClickListener { showModeInfoDialog() }

        binding.btnHistory.setOnClickListener {
            runCatching {
                startActivity(Intent(this, CommandHistoryActivity::class.java))
            }.onFailure {
                showUserMessage(getString(R.string.msg_failed_to_open_history), Toast.LENGTH_LONG)
            }
        }

        binding.btnConnect.setOnClickListener { onConnectClicked() }

        binding.switchFan.setOnCheckedChangeListener { toggle, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            withConnectionGuard {
                fanLock = true
                setControlCooldown(toggle)
                toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendControlCommand(if (isChecked) "B" else "b", if (isChecked) "Fan ON" else "Fan OFF")
                renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                
                binding.mainRoot.postDelayed({
                    fanLock = false
                    renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                }, 800L)
            }
        }

        binding.switchLight.setOnCheckedChangeListener { toggle, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            withConnectionGuard {
                lightLock = true
                setControlCooldown(toggle)
                toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendControlCommand(if (isChecked) "A" else "a", if (isChecked) "Light ON" else "Light OFF")
                renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                
                binding.mainRoot.postDelayed({
                    lightLock = false
                    renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                }, 800L)
            }
        }

        binding.switchCurtain.setOnCheckedChangeListener { toggle, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            withConnectionGuard {
                toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val targetCommand = if (isChecked) "D" else "d"
                val targetLabel = if (isChecked) "Curtain Open" else "Curtain Close"
                val targetState = if (isChecked) LocalCurtainState.OPENING else LocalCurtainState.CLOSING

                localCurtainState = targetState
                renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                
                lifecycleScope.launch {
                    viewModel.sendCommand(targetCommand)
                        .onSuccess {
                            logCommand(targetLabel)
                            binding.mainRoot.postDelayed({
                                if (localCurtainState == targetState) {
                                    localCurtainState = LocalCurtainState.IDLE
                                    if (viewModel.connectionState.value is ConnectionState.Connected) {
                                        binding.switchCurtain.isEnabled = true
                                    }
                                    renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                                }
                            }, 5000L)
                        }
                        .onFailure { throwable ->
                            localCurtainState = LocalCurtainState.IDLE
                            showUserMessage(
                                throwable.message ?: getString(R.string.connection_failed),
                                Toast.LENGTH_LONG
                            )
                            renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = false)
                        }
                }
            }
        }

        binding.switchMode.setOnCheckedChangeListener { toggle, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            withConnectionGuard {
                modeLock = true
                setControlCooldown(toggle)
                toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendControlCommand(if (isChecked) "C" else "c", if (isChecked) "Auto Mode" else "Manual Mode")
                renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                
                binding.mainRoot.postDelayed({
                    modeLock = false
                    renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = true)
                }, 800L)
            }
        }

        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun onConnectClicked() {
        if (
            viewModel.connectionState.value is ConnectionState.Connecting ||
            viewModel.connectionState.value is ConnectionState.Reconnecting
        ) {
            pendingConnectRequest = false
            viewModel.disconnect()
            showUserMessage(getString(R.string.msg_connection_cancelled))
            return
        }

        val needed = getNeededPermissions()
        if (needed.isNotEmpty()) {
            val canAskSystem = needed.any { shouldShowRequestPermissionRationale(it) }
            val promptedOnce = uiPrefs.getBoolean(KEY_PERMISSION_PROMPTED_ONCE, false)

            if (canAskSystem || !promptedOnce) {
                pendingConnectRequest = true
                applyPermissionGate(hasMissingPermissions = true)
                permissionLauncher.launch(needed.toTypedArray())
            } else {
                applyPermissionGate(hasMissingPermissions = true)
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.permission_denied_title))
                    .setMessage(getString(R.string.permission_settings_msg))
                    .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                        runCatching {
                            startActivity(
                                Intent(
Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)
                                )
                            )
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
            return
        }

        if (!viewModel.bluetoothService.isBluetoothEnabled()) {
            pendingConnectRequest = true
            setConnectionStatus(
                getString(R.string.bt_not_enabled),
                R.color.status_error_text,
                R.drawable.bg_status_error
            )
            setConnectionHint(getString(R.string.connection_hint_bluetooth_disabled))
            runCatching {
                bluetoothEnableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }.onFailure {
                pendingConnectRequest = false
                showUserMessage(getString(R.string.msg_failed_to_request_bluetooth), Toast.LENGTH_LONG)
            }
            return
        }

        val pairedDevice = runCatching { viewModel.bluetoothService.getPairedDevice() }
            .onFailure {
                showUserMessage(getString(R.string.permission_required), Toast.LENGTH_LONG)
            }.getOrNull()

        if (pairedDevice == null) {
            showPairingRequiredDialog()
            return
        }

        pendingConnectRequest = false
        viewModel.connect()
    }

    private fun showPairingRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pairing_required_title))
            .setMessage(getString(R.string.pairing_required_message))
            .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }.onFailure {
                    showUserMessage(getString(R.string.msg_failed_to_open_bluetooth_settings), Toast.LENGTH_LONG)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun withConnectionGuard(action: () -> Unit) {
        if (viewModel.connectionState.value !is ConnectionState.Connected) {
            showUserMessage(getString(R.string.msg_not_connected))
            renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = false)
            return
        }
        action()
    }

    private fun setControlCooldown(toggle: CompoundButton) {
        toggle.isEnabled = false
        toggle.postDelayed({
            if (viewModel.connectionState.value is ConnectionState.Connected) {
                toggle.isEnabled = true
            }
        }, CONTROL_COOLDOWN_MS)
    }

    private fun sendControlCommand(code: String, actionLabel: String) {
        lifecycleScope.launch {
            viewModel.sendCommand(code)
                .onSuccess { logCommand(actionLabel) }
                .onFailure { throwable ->
                    showUserMessage(
                        throwable.message ?: getString(R.string.connection_failed),
                        Toast.LENGTH_LONG
                    )
                    renderDeviceStatus(viewModel.deviceStatus.value, markFreshUpdate = false)
                }
        }
    }

    private fun expandTouchTargets() {
        val button = binding.btnConnect
        val parent = button.parent as? View ?: return
        button.post {
            val targetPx = (48 * resources.displayMetrics.density).toInt()
            val extra = (targetPx - button.height).coerceAtLeast(0)
            val extraTop = extra / 2
            val extraBottom = extra - extraTop
            val rect = Rect(
                button.left,
                button.top - extraTop,
                button.right,
                button.bottom + extraBottom
            )
            parent.touchDelegate = TouchDelegate(rect, button)
        }
    }

    private fun setConnectionStatus(message: String, colorRes: Int, backgroundRes: Int) {
        binding.tvConnectionStatus.text = message
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.tvConnectionStatus.setBackgroundResource(backgroundRes)
    }

    private fun setConnectionHint(message: String) {
        binding.tvConnectionHint.text = message
    }

    private fun renderDeviceStatus(status: DeviceStatus, markFreshUpdate: Boolean) {
        val wasUpdating = isUpdatingUI
        isUpdatingUI = true
        try {
            val isDefaultIdle = !markFreshUpdate && status == DeviceStatus()
            binding.tvTemperature.text = if (isDefaultIdle) {
                getString(R.string.temp_default)
            } else {
                getString(R.string.temp_format, status.temperature)
            }

            updateTempIcon(status.temperature)
            updateTempColor(status.temperature)
            updateTemperatureSummary(status.temperature, isDefaultIdle)
            updateLastUpdated(if (markFreshUpdate) Date() else null)

            val showFanOn = if (fanLock) binding.switchFan.isChecked else status.fanOn
            binding.switchFan.isChecked = showFanOn
            updateFanIcon(showFanOn)
            updateFanState(showFanOn)

            val showLightOn = if (lightLock) binding.switchLight.isChecked else status.lightOn
            binding.switchLight.isChecked = showLightOn
            updateLightIcon(showLightOn)
            updateLightState(showLightOn)

            val isCurtainOpen = when (localCurtainState) {
                LocalCurtainState.OPENING -> true
                LocalCurtainState.CLOSING -> false
                LocalCurtainState.IDLE -> status.curtainOpen
            }
            binding.switchCurtain.isChecked = isCurtainOpen
            updateCurtainIcon(isCurtainOpen)
            when (localCurtainState) {
                LocalCurtainState.OPENING -> {
                    binding.switchCurtain.isEnabled = false
                    binding.tvCurtainState.text = getString(R.string.curtain_state_opening)
                }
                LocalCurtainState.CLOSING -> {
                    binding.switchCurtain.isEnabled = false
                    binding.tvCurtainState.text = getString(R.string.curtain_state_closing)
                }
                LocalCurtainState.IDLE -> {
                    if (viewModel.connectionState.value is ConnectionState.Connected) {
                        binding.switchCurtain.isEnabled = true
                    }
                    updateCurtainState(isCurtainOpen)
                }
            }

            val showAutoMode = if (modeLock) binding.switchMode.isChecked else status.autoMode
            binding.switchMode.isChecked = showAutoMode
            updateModeIcon(showAutoMode)
        } finally {
            isUpdatingUI = wasUpdating
        }
    }

    private fun updateLastUpdated(date: Date?) {
        binding.tvLastUpdated.text = if (date == null) {
            getString(R.string.last_sync_waiting)
        } else {
            getString(R.string.last_sync_format, statusTimeFormatter.format(date))
        }
    }

    private fun updateTemperatureSummary(tempStr: String, isDefaultIdle: Boolean) {
        if (isDefaultIdle) {
            binding.tvTemperatureSummary.visibility = View.GONE
            return
        }
        binding.tvTemperatureSummary.visibility = View.VISIBLE

        val temp = tempStr.toFloatOrNull() ?: 0f
        val (labelRes, colorRes) = when {
            temp < 20f -> R.string.temperature_state_cool to R.color.nexus_blue
            temp < 25f -> R.string.temperature_state_comfortable to R.color.accent_green
            temp < 30f -> R.string.temperature_state_warm to R.color.accent_orange
            else -> R.string.temperature_state_hot to R.color.accent_red
        }
        binding.tvTemperatureSummary.text = getString(labelRes)
        binding.tvTemperatureSummary.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateFanState(on: Boolean) {
        binding.tvFanState.text = getString(if (on) R.string.fan_state_on else R.string.fan_state_off)
    }

    private fun updateLightState(on: Boolean) {
        binding.tvLightState.text = getString(if (on) R.string.light_state_on else R.string.light_state_off)
    }

    private fun updateCurtainIcon(open: Boolean) {
        binding.ivCurtainIcon.setImageResource(if (open) R.drawable.curtain_open else R.drawable.curtain_closed)
        binding.ivCurtainIcon.imageTintList = null
    }

    private fun updateCurtainState(open: Boolean) {
        binding.tvCurtainState.text = getString(if (open) R.string.curtain_state_open else R.string.curtain_state_closed)
    }

    private fun updateModeDetails(auto: Boolean) {
        binding.tvModeState.text = getString(if (auto) R.string.mode_state_auto else R.string.mode_state_manual)
    }

    private fun showUserMessage(message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (lastToastMessage == message) return
        lastToastMessage = message
        Toast.makeText(this, message, duration).show()
        binding.mainRoot.postDelayed({ lastToastMessage = null }, 1200L)
    }

    private fun updateLightIcon(on: Boolean) {
        binding.ivLightIcon.setImageResource(if (on) R.drawable.light_on else R.drawable.light_off)
        binding.ivLightIcon.imageTintList = null
    }

    private fun updateFanIcon(on: Boolean) {
        binding.ivFanIcon.setImageResource(if (on) R.drawable.fan_on else R.drawable.fan_off)
        binding.ivFanIcon.imageTintList = null
    }

    private fun updateTempColor(tempStr: String) {
        val temp = tempStr.toFloatOrNull() ?: 0f
        val color = when {
            temp < 20f -> ContextCompat.getColor(this, R.color.nexus_blue)
            temp < 25f -> ContextCompat.getColor(this, R.color.accent_green)
            temp < 30f -> ContextCompat.getColor(this, R.color.accent_orange)
            else -> ContextCompat.getColor(this, R.color.accent_red)
        }
        binding.tvTemperature.setTextColor(color)
        binding.ivTempIcon.imageTintList = null
    }

    private fun updateModeIcon(auto: Boolean) {
        binding.ivModeIcon.setImageResource(if (auto) R.drawable.mode_auto else R.drawable.mode_manual)
        binding.ivModeIcon.imageTintList = null
        binding.tvModeTitle.text = if (auto) getString(R.string.mode_automatic_title) else getString(R.string.mode_manual_title)
        updateModeDetails(auto)
    }

    private fun updateTempIcon(tempStr: String) {
        val temp = tempStr.toDoubleOrNull() ?: 0.0
        binding.ivTempIcon.setImageResource(if (temp >= 30.0) R.drawable.temp_hot else R.drawable.temp_cold)
        binding.ivTempIcon.imageTintList = null
    }

    private fun showGuideDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_guide, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setBackground(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            .create()

        dialogView.findViewById<View>(R.id.btnCloseGuide).setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
                dialog.dismiss()
            }.start()
        }

        dialog.show()
    }

    private fun showModeInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mode_info_title))
            .setMessage(getString(R.string.mode_info_message))
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun setDisconnectedState() {
        binding.btnConnect.visibility = View.VISIBLE
        binding.btnConnect.text = getString(R.string.btn_connect)
        binding.btnConnect.setIconResource(R.drawable.ic_lucide_bluetooth_off)
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.visibility = View.GONE
        setControlsEnabled(false)
        renderDeviceStatus(DeviceStatus(), markFreshUpdate = false)
    }

    private fun setConnectingState() {
        binding.btnConnect.visibility = View.VISIBLE
        binding.btnConnect.text = getString(R.string.btn_connect_tap_cancel)
        binding.btnConnect.setIconResource(R.drawable.ic_lucide_hand)
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.visibility = View.GONE
        setControlsEnabled(false)
    }

    private fun setConnectedState() {
        binding.btnConnect.visibility = View.GONE
        binding.btnDisconnect.visibility = View.VISIBLE
        binding.permissionCard.visibility = View.GONE
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        binding.switchLight.isEnabled = enabled
        binding.switchFan.isEnabled = enabled
        binding.switchCurtain.isEnabled = enabled && localCurtainState == LocalCurtainState.IDLE
        binding.switchMode.isEnabled = enabled
        binding.switchLight.alpha = alpha
        binding.switchFan.alpha = alpha
        binding.switchCurtain.alpha = if (enabled && localCurtainState != LocalCurtainState.IDLE) 0.7f else alpha
        binding.switchMode.alpha = alpha
    }

    private fun logCommand(action: String) {
        val ts = commandTimeFormatter.format(Date())
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    database.commandLogDao().insert(CommandLog(timestamp = ts, action = action))
                }
            }.onFailure {
                showUserMessage(getString(R.string.msg_log_failed))
            }
        }
    }

    private fun checkPermissions() {
        val needed = getNeededPermissions()
        val hasMissingPermissions = needed.isNotEmpty()
        applyPermissionGate(hasMissingPermissions)

        val promptedOnce = uiPrefs.getBoolean(KEY_PERMISSION_PROMPTED_ONCE, false)
        if (hasMissingPermissions && !promptedOnce) {
            uiPrefs.edit { putBoolean(KEY_PERMISSION_PROMPTED_ONCE, true) }
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun applyPermissionGate(hasMissingPermissions: Boolean) {
        val gatedVisibility = if (hasMissingPermissions) View.GONE else View.VISIBLE

        if (hasMissingPermissions) {
            pendingConnectRequest = false
            if (viewModel.connectionState.value !is ConnectionState.Disconnected) {
                viewModel.disconnect()
            }
            setConnectionStatus(
                getString(R.string.permission_required),
                R.color.status_error_text,
                R.drawable.bg_status_error
            )
            setConnectionHint(getString(R.string.connection_hint_permissions))
            binding.btnConnect.visibility = View.VISIBLE
            binding.btnConnect.text = getString(R.string.btn_connect)
            binding.btnConnect.setIconResource(R.drawable.ic_lucide_bluetooth_off)
            binding.btnDisconnect.visibility = View.GONE
        }

        binding.cardTemp.visibility = gatedVisibility
        binding.tvControlsHeader.visibility = gatedVisibility
        binding.controlFlow.visibility = gatedVisibility
        binding.cardFan.visibility = gatedVisibility
        binding.cardLight.visibility = gatedVisibility
        binding.cardCurtain.visibility = gatedVisibility
        binding.cardMode.visibility = gatedVisibility
        binding.permissionCard.visibility = if (hasMissingPermissions) View.VISIBLE else View.GONE

        if (!hasMissingPermissions) {
            handleConnectionState(viewModel.connectionState.value)
            renderDeviceStatus(
                viewModel.deviceStatus.value,
                markFreshUpdate = viewModel.connectionState.value is ConnectionState.Connected
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.bluetoothService.shutdown()
        }
        super.onDestroy()
    }

    private fun getNeededPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed
    }
}
