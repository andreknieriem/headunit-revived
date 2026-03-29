package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.Toast
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import android.net.ConnectivityManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import android.content.res.Configuration
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.utils.Settings

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            requireContext().startService(Intent(requireContext(), com.andrerinas.headunitrevived.aap.DummyVpnService::class.java))
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var wifi_text_view: TextView
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false

    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            wifi_text_view.text = getString(R.string.searching)
            wifi.alpha = 0.6f
        } else {
            wifi_text_view.text = getString(R.string.wifi)
            wifi.alpha = 1.0f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        self_mode_button = view.findViewById(R.id.self_mode_button)
        usb = view.findViewById(R.id.usb_button)
        settings = view.findViewById(R.id.settings_button)
        wifi = view.findViewById(R.id.wifi_button)
        wifi_text_view = view.findViewById(R.id.wifi_text)
        exitButton = view.findViewById(R.id.exit_button)
        self_mode_text = view.findViewById(R.id.self_mode_text)

        setupListeners()
        updateProjectionButtonText()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { updateProjectionButtonText() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        // Ensure the foreground service is running when wake-detection settings
        // are enabled. The service must be alive to catch SCREEN_ON broadcasts
        // (which can only be received by dynamically registered receivers).
        // Without this, turning off the car and back on won't auto-start the app
        // because no one is listening for SCREEN_ON.
        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        // Execute auto-connect methods in user-defined priority order
        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        attemptAutoConnect()
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if (appSettings.autoStartSelfMode && !hasAutoStarted && !commManager.isConnected) {
                        hasAutoStarted = true
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        attemptSingleUsbAutoConnect()
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting DummyVpnService.")
                requireContext().startService(Intent(requireContext(), com.andrerinas.headunitrevived.aap.DummyVpnService::class.java))
            }
        }
        startSelfModeInternal()
    }

    private fun attemptAutoConnect() {
        val appSettings = App.provide(requireContext()).settings

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return
        }

        when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                val ip = appSettings.lastConnectionIp
                if (ip.isNotEmpty()) {
                    AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                    Toast.makeText(requireContext(), getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                    val ctx = requireContext()
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                    }
                }
            }
        }
    }

    private fun attemptSingleUsbAutoConnect() {
        val appSettings = App.provide(requireContext()).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
    }

    private val originalBackgrounds = mapOf(
        R.id.self_mode_button to R.drawable.gradient_blue,
        R.id.usb_button to R.drawable.gradient_orange,
        R.id.wifi_button to R.drawable.gradient_purple,
        R.id.settings_button to R.drawable.gradient_darkblue
    )

    private fun applyMonochromeStyle() {
        val monochromeBackground = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_monochrome)
        val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
        listOf(self_mode_button, usb, wifi, settings).forEach { button ->
            button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = grayTint
        }
    }

    private fun restoreOriginalStyle() {
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        val buttons = listOf(self_mode_button, usb, wifi, settings)
        val ids = listOf(R.id.self_mode_button, R.id.usb_button, R.id.wifi_button, R.id.settings_button)
        buttons.zip(ids).forEach { (button, id) ->
            originalBackgrounds[id]?.let { drawableRes ->
                button.background = ContextCompat.getDrawable(requireContext(), drawableRes)
            }
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = whiteTint
        }
    }

    private fun updateButtonStyle() {
        val appSettings = App.provide(requireContext()).settings
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = appSettings.appTheme == Settings.AppTheme.DARK ||
                          appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                          isNightActive
        if (isDarkTheme && appSettings.monochromeIcons) {
            applyMonochromeStyle()
        } else {
            restoreOriginalStyle()
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            // Keep the service alive when auto-start is enabled:
            // - autoStartOnBoot: service must survive hibernate to detect SCREEN_ON wake
            // - autoStartOnScreenOn: service catches every SCREEN_ON for always-on head units
            // - autoStartOnUsb + reopenOnReconnection: service detects USB reconnections
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            if (keepServiceAlive) {
                val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(requireContext(), disconnectIntent)
            } else {
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                startSelfMode()
            }
        }

        usb.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
            }
        }

        settings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        wifi.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val mode = appSettings.wifiConnectionMode
            when {
                mode == 1 -> { // Auto (Headunit Server) - One-Shot Scan
                    if (commManager.isConnected) {
                        // Already connected, no toast needed
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT).show()
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                appSettings.isHelperMode -> { // Helper (Wireless Launcher) or Helper via Hotspot
                    if (commManager.isConnected) {
                        // Already connected, no toast needed
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                else -> { // Manual (0) -> Open List
                    val controller = findNavController()
                    if (controller.currentDestination?.id == R.id.homeFragment) {
                        controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                    }
                }
            }
        }

        wifi.setOnLongClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_networkListFragment)
            }
            true
        }
    }

    private fun updateProjectionButtonText() {
        if (commManager.isConnected) {
            self_mode_text.text = getString(R.string.to_android_auto)
        } else {
            self_mode_text.text = getString(R.string.self_mode)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateProjectionButtonText()
        updateButtonStyle()
        updateTextColors()
    }

    private fun updateTextColors() {
        val appSettings = App.provide(requireContext()).settings
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES

        val labelViews = listOf(self_mode_text, wifi_text_view,
            view?.findViewById<TextView>(R.id.usb_text),
            view?.findViewById<TextView>(R.id.settings_text))

        if (appSettings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        // Exit button always has a dark background, so text must always be white
        exitButton.setTextColor(Color.WHITE)
    }

    companion object {
        private var hasAutoStarted = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}