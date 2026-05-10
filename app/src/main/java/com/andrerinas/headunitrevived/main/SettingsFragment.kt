package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.utils.LogExporter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import com.andrerinas.headunitrevived.connection.NativeAaHandshakeManager

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Local state to hold changes before saving
    private var pendingUseGps: Boolean? = null
    private var pendingShowNavigationNotifications: Boolean? = null
    private var pendingSyncMediaSessionAaMetadata: Boolean? = null
    private var pendingResolution: Int? = null
    private var pendingDpi: Int? = null
    private var pendingFullscreenMode: Settings.FullscreenMode? = null
    private var pendingViewMode: Settings.ViewMode? = null
    private var pendingForceSoftware: Boolean? = null
    private var pendingVideoCodec: String? = null
    private var pendingFpsLimit: Int? = null
    private var pendingBluetoothAddress: String? = null
    private var pendingEnableAudioSink: Boolean? = null
    private var pendingSeparateAudioStreams: Boolean? = null
    private var pendingUseAacAudio: Boolean? = null
    private var pendingUseNativeSsl: Boolean? = null
    private var pendingEnableRotary: Boolean? = null
    private var pendingAudioLatencyMultiplier: Int? = null
    private var pendingAudioQueueCapacity: Int? = null
    private var pendingShowFpsCounter: Boolean? = null
    private var pendingScreenOrientation: Settings.ScreenOrientation? = null
    private var pendingAppLanguage: String? = null
    private var pendingFakeSpeed: Boolean? = null
    
    private var pendingWifiConnectionMode: Int? = null
    private var pendingHelperConnectionStrategy: Int? = null
    private var pendingAutoEnableHotspot: Boolean? = null
    private var pendingAutoConnectNative: Boolean? = null
    private var pendingWaitForWifi: Boolean? = null
    private var pendingWaitForWifiTimeout: Int? = null

    // Flag to determine if the projection should stretch to fill the screen
    private var pendingStretchToFill: Boolean? = null
    private var pendingForcedScale: Boolean? = null

    private var pendingKillOnDisconnect: Boolean? = null

    // Custom Insets
    private var pendingInsetLeft: Int? = null
    private var pendingInsetTop: Int? = null
    private var pendingInsetRight: Int? = null
    private var pendingInsetBottom: Int? = null

    private var pendingMediaVolumeOffset: Int? = null
    private var pendingAssistantVolumeOffset: Int? = null
    private var pendingNavigationVolumeOffset: Int? = null

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            handleNativeAaSelection()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize local state with current values
        pendingUseGps = settings.useGpsForNavigation
        pendingShowNavigationNotifications = settings.showNavigationNotifications
        pendingSyncMediaSessionAaMetadata = settings.syncMediaSessionWithAaMetadata
        pendingResolution = settings.resolutionId
        pendingDpi = settings.dpiPixelDensity
        pendingFullscreenMode = settings.fullscreenMode
        pendingViewMode = settings.viewMode
        pendingForceSoftware = settings.forceSoftwareDecoding
        pendingVideoCodec = settings.videoCodec
        pendingFpsLimit = settings.fpsLimit
        pendingBluetoothAddress = settings.bluetoothAddress
        pendingEnableAudioSink = settings.enableAudioSink
        pendingSeparateAudioStreams = settings.separateAudioStreams
        pendingUseAacAudio = settings.useAacAudio
        pendingUseNativeSsl = settings.useNativeSsl
        pendingEnableRotary = settings.enableRotary
        pendingAudioLatencyMultiplier = settings.audioLatencyMultiplier
        pendingAudioQueueCapacity = settings.audioQueueCapacity
        pendingShowFpsCounter = settings.showFpsCounter
        pendingScreenOrientation = settings.screenOrientation
        pendingAppLanguage = settings.appLanguage
        
        // Initialize local state for stretch to fill
        pendingStretchToFill = settings.stretchToFill
        pendingForcedScale = settings.forcedScale

        pendingKillOnDisconnect = settings.killOnDisconnect
        pendingAutoEnableHotspot = settings.autoEnableHotspot
        pendingAutoConnectNative = settings.autoConnectNative
        pendingFakeSpeed = settings.fakeSpeed

        pendingWifiConnectionMode = settings.wifiConnectionMode
        pendingHelperConnectionStrategy = settings.helperConnectionStrategy
        pendingWaitForWifi = settings.waitForWifiBeforeWifiDirect
        pendingWaitForWifiTimeout = settings.waitForWifiTimeout
        
        pendingInsetLeft = settings.insetLeft
        pendingInsetTop = settings.insetTop
        pendingInsetRight = settings.insetRight
        pendingInsetBottom = settings.insetBottom

        pendingMediaVolumeOffset = settings.mediaVolumeOffset
        pendingAssistantVolumeOffset = settings.assistantVolumeOffset
        pendingNavigationVolumeOffset = settings.navigationVolumeOffset

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()

        savedInstanceState?.getParcelable<android.os.Parcelable>("recycler_scroll")?.let {
            settingsRecyclerView.layoutManager?.onRestoreInstanceState(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::settingsRecyclerView.isInitialized) {
            settingsRecyclerView.layoutManager?.onSaveInstanceState()?.let {
                outState.putParcelable("recycler_scroll", it)
            }
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        
        // Add the Save item with custom layout
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        // Get the button from the action view
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        val languageChanged = pendingAppLanguage != settings.appLanguage

        pendingUseGps?.let { settings.useGpsForNavigation = it }
        pendingShowNavigationNotifications?.let { settings.showNavigationNotifications = it }
        pendingSyncMediaSessionAaMetadata?.let { settings.syncMediaSessionWithAaMetadata = it }
        pendingResolution?.let { settings.resolutionId = it }
        pendingDpi?.let { settings.dpiPixelDensity = it }
        pendingFullscreenMode?.let { settings.fullscreenMode = it }
        pendingViewMode?.let { settings.viewMode = it }
        pendingForceSoftware?.let { settings.forceSoftwareDecoding = it }
        pendingVideoCodec?.let { settings.videoCodec = it }
        pendingFpsLimit?.let { settings.fpsLimit = it }
        pendingBluetoothAddress?.let { settings.bluetoothAddress = it }
        pendingEnableAudioSink?.let { settings.enableAudioSink = it }
        pendingSeparateAudioStreams?.let { settings.separateAudioStreams = it }
        pendingUseAacAudio?.let { settings.useAacAudio = it }
        pendingUseNativeSsl?.let { settings.useNativeSsl = it }
        pendingEnableRotary?.let { settings.enableRotary = it }
        pendingAudioLatencyMultiplier?.let { settings.audioLatencyMultiplier = it }
        pendingAudioQueueCapacity?.let { settings.audioQueueCapacity = it }
        pendingShowFpsCounter?.let { settings.showFpsCounter = it }
        pendingScreenOrientation?.let { settings.screenOrientation = it }

        pendingMediaVolumeOffset?.let { settings.mediaVolumeOffset = it }
        pendingAssistantVolumeOffset?.let { settings.assistantVolumeOffset = it }
        pendingNavigationVolumeOffset?.let { settings.navigationVolumeOffset = it }


        pendingAppLanguage?.let { settings.appLanguage = it }

        // Save the stretch to fill preference
        pendingStretchToFill?.let { settings.stretchToFill = it }
        pendingForcedScale?.let { settings.forcedScale = it }

        pendingKillOnDisconnect?.let { settings.killOnDisconnect = it }
        pendingAutoEnableHotspot?.let { settings.autoEnableHotspot = it }
        pendingFakeSpeed?.let { settings.fakeSpeed = it }

        val oldWifiMode = settings.wifiConnectionMode
        val oldHelperStrategy = settings.helperConnectionStrategy
        pendingWifiConnectionMode?.let { settings.wifiConnectionMode = it }
        pendingHelperConnectionStrategy?.let { settings.helperConnectionStrategy = it }
        pendingWaitForWifi?.let { settings.waitForWifiBeforeWifiDirect = it }
        pendingWaitForWifiTimeout?.let { settings.waitForWifiTimeout = it }
        pendingAutoConnectNative?.let { settings.autoConnectNative = it }

        pendingInsetLeft?.let { settings.insetLeft = it }
        pendingInsetTop?.let { settings.insetTop = it }
        pendingInsetRight?.let { settings.insetRight = it }
        pendingInsetBottom?.let { settings.insetBottom = it }

        settings.commit()

        if (oldWifiMode != settings.wifiConnectionMode || oldHelperStrategy != settings.helperConnectionStrategy) {
            val intent = Intent(requireContext(), AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode == 1 || mode == 2 || mode == 3)
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            requireContext().startService(intent)
        }

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                Toast.makeText(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT).show()
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }
        
        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()
        updateSettingsList()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()

        if (languageChanged) {
            requireActivity().recreate()
        }
    }

    private fun checkChanges() {
        // Check for any changes
        val anyChange = pendingUseGps != settings.useGpsForNavigation ||
                        pendingShowNavigationNotifications != settings.showNavigationNotifications ||
                        pendingSyncMediaSessionAaMetadata != settings.syncMediaSessionWithAaMetadata ||
                        pendingResolution != settings.resolutionId ||
                        pendingDpi != settings.dpiPixelDensity ||
                        pendingFullscreenMode != settings.fullscreenMode ||
                        pendingViewMode != settings.viewMode ||
                        pendingForceSoftware != settings.forceSoftwareDecoding ||
                        pendingVideoCodec != settings.videoCodec ||
                        pendingFpsLimit != settings.fpsLimit ||
                        pendingBluetoothAddress != settings.bluetoothAddress ||
                        pendingEnableAudioSink != settings.enableAudioSink ||
                        pendingSeparateAudioStreams != settings.separateAudioStreams ||
                        pendingUseAacAudio != settings.useAacAudio ||
                        pendingUseNativeSsl != settings.useNativeSsl ||
                        pendingEnableRotary != settings.enableRotary ||
                        pendingAudioLatencyMultiplier != settings.audioLatencyMultiplier ||
                        pendingAudioQueueCapacity != settings.audioQueueCapacity ||
                        pendingShowFpsCounter != settings.showFpsCounter ||
                        pendingScreenOrientation != settings.screenOrientation ||
                        pendingAppLanguage != settings.appLanguage ||
                        pendingStretchToFill != settings.stretchToFill ||
                        pendingForcedScale != settings.forcedScale ||
                        pendingInsetLeft != settings.insetLeft ||
                        pendingInsetTop != settings.insetTop ||
                        pendingInsetRight != settings.insetRight ||
                        pendingInsetBottom != settings.insetBottom ||
                        pendingMediaVolumeOffset != settings.mediaVolumeOffset ||
                        pendingAssistantVolumeOffset != settings.assistantVolumeOffset ||
                        pendingNavigationVolumeOffset != settings.navigationVolumeOffset ||
                        pendingKillOnDisconnect != settings.killOnDisconnect ||
                        pendingAutoEnableHotspot != settings.autoEnableHotspot ||
                        pendingFakeSpeed != settings.fakeSpeed ||
                        pendingWifiConnectionMode != settings.wifiConnectionMode ||
                        pendingHelperConnectionStrategy != settings.helperConnectionStrategy ||
                        pendingWaitForWifi != settings.waitForWifiBeforeWifiDirect ||
                        pendingWaitForWifiTimeout != settings.waitForWifiTimeout ||
                        pendingAutoConnectNative != settings.autoConnectNative

        hasChanges = anyChange

        // Check for restart requirement
        requiresRestart = pendingResolution != settings.resolutionId ||
                          pendingVideoCodec != settings.videoCodec ||
                          pendingFpsLimit != settings.fpsLimit ||
                          pendingDpi != settings.dpiPixelDensity ||
                          pendingForceSoftware != settings.forceSoftwareDecoding ||
                          pendingEnableRotary != settings.enableRotary ||
                          pendingEnableAudioSink != settings.enableAudioSink ||
                          pendingSeparateAudioStreams != settings.separateAudioStreams ||
                          pendingUseAacAudio != settings.useAacAudio ||
                          pendingAudioLatencyMultiplier != settings.audioLatencyMultiplier ||
                          pendingAudioQueueCapacity != settings.audioQueueCapacity ||
                          pendingUseNativeSsl != settings.useNativeSsl ||
                          pendingInsetLeft != settings.insetLeft ||
                          pendingInsetTop != settings.insetTop ||
                          pendingInsetRight != settings.insetRight ||
                          pendingInsetBottom != settings.insetBottom ||
                          pendingWifiConnectionMode != settings.wifiConnectionMode

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = settingsRecyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        // --- General Settings ---
        items.add(SettingItem.CategoryHeader("general", R.string.category_general))

        // Auto-Optimize Wizard
        items.add(SettingItem.SettingEntry(
            stableId = "autoOptimize",
            nameResId = R.string.auto_optimize,
            value = getString(R.string.auto_optimize_desc),
            onClick = { _ ->
                com.andrerinas.headunitrevived.utils.SetupWizard(requireContext()) {
                    requireActivity().recreate()
                }.start()
            }
        ))

        // Language Selector
        val availableLocales = LocaleHelper.getAvailableLocales(requireContext())
        val currentLocale = LocaleHelper.stringToLocale(pendingAppLanguage ?: "")
        val currentLanguageDisplay = if (currentLocale != null) {
            LocaleHelper.getDisplayName(currentLocale)
        } else {
            getString(R.string.system_default)
        }

        items.add(SettingItem.SettingEntry(
            stableId = "appLanguage",
            nameResId = R.string.app_language,
            value = currentLanguageDisplay,
            onClick = { _ ->
                val languageNames = mutableListOf(getString(R.string.system_default))
                val localeCodes = mutableListOf("")

                availableLocales.forEach { locale ->
                    languageNames.add(LocaleHelper.getDisplayName(locale))
                    localeCodes.add(LocaleHelper.localeToString(locale))
                }

                val currentIndex = localeCodes.indexOf(pendingAppLanguage ?: "").coerceAtLeast(0)

                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_language)
                    .setSingleChoiceItems(languageNames.toTypedArray(), currentIndex) { dialog, which ->
                        pendingAppLanguage = localeCodes[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleInfoSettings",
            nameResId = R.string.vehicle_info_settings,
            value = getString(R.string.vehicle_info_settings_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_vehicleInfoFragment)
                } catch (e: Exception) { }
            }
        ))

        // --- Wireless Connection ---
        items.add(SettingItem.CategoryHeader("wirelessConnection", R.string.category_wireless))

        // Add 2.4GHz Warning Banner
        items.add(SettingItem.InfoBanner(
            stableId = "wireless24ghzWarning",
            textResId = R.string.wireless_24ghz_warning
        ))

        val wirelessModeOptions = listOf(
            getString(R.string.wireless_mode_helper),
            getString(R.string.wireless_mode_native),
            getString(R.string.wireless_mode_server)
        )

        val wirelessSelectedIndex = when (pendingWifiConnectionMode) {
            2 -> 0 // Helper
            3 -> 1 // Native
            0, 1 -> 2 // Server
            else -> 2
        }

        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "wifiConnectionMode",
            nameResId = R.string.wireless_mode,
            options = wirelessModeOptions,
            selectedIndex = wirelessSelectedIndex,
            onOptionSelected = { index ->
                val newMode = when (index) {
                    0 -> 2 // Helper
                    1 -> 3 // Native
                    2 -> if (pendingWifiConnectionMode == 0) 0 else 1 // Keep manual/auto choice if already in server mode
                    else -> 1
                }

                if (newMode == 3) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        handleNativeAaSelection()
                    }
                } else {
                    pendingWifiConnectionMode = newMode
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        // Sub-setting for Native Wireless mode
        if (pendingWifiConnectionMode == 3) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "nativeAutoConnect",
                nameResId = R.string.auto_connect_native,
                descriptionResId = R.string.auto_connect_native_description,
                isChecked = pendingAutoConnectNative ?: true,
                onCheckedChanged = { isChecked ->
                    pendingAutoConnectNative = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }


        // Sub-setting for Headunit Server (Manual vs Auto)
        if (pendingWifiConnectionMode == 0 || pendingWifiConnectionMode == 1) {
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "serverModeSelection",
                nameResId = R.string.server_mode_label,
                options = listOf(getString(R.string.server_mode_manual), getString(R.string.server_mode_auto)),
                selectedIndex = if (pendingWifiConnectionMode == 0) 0 else 1,
                onOptionSelected = { index ->
                    pendingWifiConnectionMode = if (index == 0) 0 else 1
                    checkChanges()
                    updateSettingsList()
                }
            ))

            // Mode 1 (Auto Server) can also use the auto-hotspot feature
            if (pendingWifiConnectionMode == 1) {
                addHotspotToggle(items)
            }
        }

        // Sub-setting for Wireless Helper Strategy
        if (pendingWifiConnectionMode == 2) {
            val helperStrategies = resources.getStringArray(R.array.helper_strategies)
            items.add(SettingItem.SettingEntry(
                stableId = "helperStrategy",
                nameResId = R.string.helper_strategy_label,
                value = helperStrategies.getOrElse(pendingHelperConnectionStrategy!!) { "" },
                onClick = {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.helper_strategy_label)
                        .setSingleChoiceItems(helperStrategies, pendingHelperConnectionStrategy!!) { dialog, which ->
                            pendingHelperConnectionStrategy = which
                            checkChanges()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))

            // Mode 2 only shows Hotspot toggle for Strategy 4 (Headunit Hotspot)
            if (pendingHelperConnectionStrategy == 4) {
                addHotspotToggle(items)
            }

            if (pendingHelperConnectionStrategy == 1) { // WiFi Direct (P2P)
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "waitForWifi",
                    nameResId = R.string.wait_for_wifi,
                    descriptionResId = R.string.wait_for_wifi_description,
                    isChecked = pendingWaitForWifi ?: false,
                    onCheckedChanged = { isChecked ->
                        pendingWaitForWifi = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                ))

                if (pendingWaitForWifi == true) {
                    items.add(SettingItem.SliderSettingEntry(
                        stableId = "waitForWifiTimeout",
                        nameResId = R.string.wait_for_wifi_timeout,
                        value = "${pendingWaitForWifiTimeout}s",
                        sliderValue = (pendingWaitForWifiTimeout ?: 10).toFloat(),
                        valueFrom = 5f,
                        valueTo = 30f,
                        stepSize = 1f,
                        onValueChanged = { value ->
                            pendingWaitForWifiTimeout = value.toInt()
                            checkChanges()
                            updateSettingsList()
                        }
                    ))
                }
            }
        }

        // --- Dark Mode ---
        items.add(SettingItem.CategoryHeader("darkMode", R.string.category_dark_mode))

        val appThemeTitles = resources.getStringArray(R.array.app_theme)
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        val darkModeValue = "${getString(R.string.app_theme_short)}: ${appThemeTitles[settings.appTheme.value]} · " +
                "${getString(R.string.night_mode_short)}: ${nightModeTitles[settings.nightMode.value]}"
        items.add(SettingItem.SettingEntry(
            stableId = "darkModeSettings",
            nameResId = R.string.dark_mode_settings,
            value = darkModeValue,
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_darkModeFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        // --- Automation ---
        items.add(SettingItem.CategoryHeader("automation", R.string.category_automation))

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartSettings",
            nameResId = R.string.auto_start_settings,
            value = getString(R.string.auto_start_settings_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_autoStartFragment)
                } catch (e: Exception) { }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "autoConnectSettings",
            nameResId = R.string.auto_connect_settings,
            value = getAutoConnectSummary(),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_autoConnectFragment)
                } catch (e: Exception) { }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "killOnDisconnect",
            nameResId = R.string.kill_on_disconnect,
            descriptionResId = R.string.kill_on_disconnect_description,
            isChecked = pendingKillOnDisconnect!!,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    val conflicts = getKillOnDisconnectConflicts()
                    val hasAutoStartOnBoot = settings.autoStartOnBoot
                    val hasAutoStartOnScreenOn = settings.autoStartOnScreenOn
                    if (conflicts.isNotEmpty() || hasAutoStartOnBoot || hasAutoStartOnScreenOn) {
                        pendingKillOnDisconnect = true
                        updateSettingsList()
                        showKillOnDisconnectWarning(conflicts, hasAutoStartOnBoot, hasAutoStartOnScreenOn)
                    } else {
                        pendingKillOnDisconnect = true
                        checkChanges()
                        updateSettingsList()
                    }
                } else {
                    pendingKillOnDisconnect = false
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        // --- Navigation Settings ---
        items.add(SettingItem.CategoryHeader("navigation", R.string.category_navigation))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "gpsNavigation",
            nameResId = R.string.gps_for_navigation,
            descriptionResId = R.string.gps_for_navigation_description,
            isChecked = pendingUseGps!!,
            onCheckedChanged = { isChecked ->
                pendingUseGps = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showNavigationNotifications",
            nameResId = R.string.show_navigation_notifications,
            descriptionResId = R.string.show_navigation_notifications_description,
            isChecked = pendingShowNavigationNotifications!!,
            onCheckedChanged = { isChecked ->
                pendingShowNavigationNotifications = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "fakeSpeed",
            nameResId = R.string.fake_speed_title,
            descriptionResId = R.string.fake_speed_description,
            isChecked = pendingFakeSpeed!!,
            onCheckedChanged = { isChecked ->
                pendingFakeSpeed = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))

        items.add(SettingItem.SettingEntry(
            stableId = "resolution",
            nameResId = R.string.resolution,
            value = Settings.Resolution.fromId(pendingResolution!!)?.resName ?: "",
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_resolution)
                    .setSingleChoiceItems(Settings.Resolution.allRes, pendingResolution!!) { dialog, which ->
                        pendingResolution = which
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "dpiPixelDensity",
            nameResId = R.string.dpi,
            value = if (pendingDpi == 0) getString(R.string.auto) else pendingDpi.toString(),
            onClick = { _ ->
                showNumericInputDialog(
                    title = getString(R.string.enter_dpi_value),
                    message = null,
                    initialValue = pendingDpi ?: 0,
                    onConfirm = { newVal ->
                        pendingDpi = newVal
                        checkChanges()
                        updateSettingsList()
                    }
                )
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "customInsets",
            nameResId = R.string.custom_insets,
            value = "${pendingInsetLeft ?: 0}, ${pendingInsetTop ?: 0}, ${pendingInsetRight ?: 0}, ${pendingInsetBottom ?: 0}",
            onClick = {
                showCustomInsetsDialog()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            value = when (pendingFullscreenMode) {
                Settings.FullscreenMode.NONE -> getString(R.string.fullscreen_none)
                Settings.FullscreenMode.IMMERSIVE -> getString(R.string.fullscreen_immersive)
                Settings.FullscreenMode.STATUS_ONLY -> getString(R.string.fullscreen_status_only)
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> getString(R.string.fullscreen_immersive_avoid_notch)
                else -> getString(R.string.auto)
            },
            onClick = {
                val modes = arrayOf(
                    getString(R.string.fullscreen_none),
                    getString(R.string.fullscreen_immersive),
                    getString(R.string.fullscreen_status_only),
                    getString(R.string.fullscreen_immersive_avoid_notch)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.start_in_fullscreen_mode)
                    .setSingleChoiceItems(modes, pendingFullscreenMode?.value ?: 0) { dialog, which ->
                        val newMode = Settings.FullscreenMode.fromInt(which) ?: Settings.FullscreenMode.NONE
                        pendingFullscreenMode = newMode
                        
                        // PERSIST IMMEDIATELY (Rescue Mode)
                        settings.fullscreenMode = newMode
                        settings.commit()
                        
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                        
                        // Apply immediately to current UI
                        requireActivity().recreate()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = when (pendingViewMode) {
                Settings.ViewMode.SURFACE -> getString(R.string.surface_view)
                Settings.ViewMode.TEXTURE -> getString(R.string.texture_view)
                Settings.ViewMode.GLES -> getString(R.string.gles_view)
                else -> getString(R.string.surface_view)
            },
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view), getString(R.string.gles_view))
                val currentIdx = pendingViewMode!!.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                        pendingViewMode = Settings.ViewMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = resources.getStringArray(R.array.screen_orientation)[pendingScreenOrientation!!.value],
            onClick = { _ ->
                val orientationOptions = resources.getStringArray(R.array.screen_orientation)
                val currentIdx = pendingScreenOrientation!!.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_screen_orientation)
                    .setSingleChoiceItems(orientationOptions, currentIdx) { dialog, whiches ->
                        val newOrientation = Settings.ScreenOrientation.fromInt(whiches) ?: Settings.ScreenOrientation.SYSTEM
                        pendingScreenOrientation = newOrientation
                        
                        // Apply immediately
                        settings.screenOrientation = newOrientation
                        settings.commit()
                        
                        requireActivity().requestedOrientation = newOrientation.androidOrientation
                        requireContext().sendBroadcast(Intent(AapService.ACTION_ORIENTATION_CHANGED).apply {
                            setPackage(requireContext().packageName)
                        })
                        
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Add the toggle for Stretch to Fill
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "stretchToFill",
            nameResId = R.string.pref_stretch_screen_title,
            descriptionResId = R.string.pref_stretch_screen_summary,
            isChecked = pendingStretchToFill!!,
            onCheckedChanged = { isChecked ->
                pendingStretchToFill = isChecked
                requiresRestart = true // Requires a reconnect to apply the new rendering bounds
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingViewMode == Settings.ViewMode.SURFACE) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "forcedScale",
                nameResId = R.string.forced_scale,
                descriptionResId = R.string.forced_scale_description,
                isChecked = pendingForcedScale!!,
                onCheckedChanged = { isChecked ->
                    pendingForcedScale = isChecked
                    requiresRestart = true
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // --- Video Settings ---
        items.add(SettingItem.CategoryHeader("video", R.string.category_video))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "forceSoftwareDecoding",
            nameResId = R.string.force_software_decoding,
            descriptionResId = R.string.force_software_decoding_description,
            isChecked = pendingForceSoftware!!,
            onCheckedChanged = { isChecked ->
                pendingForceSoftware = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = R.string.video_codec,
            value = pendingVideoCodec!!,
            onClick = { _ ->
                val codecs = arrayOf("Auto", "H.264", "H.265")
                val currentCodecIndex = codecs.indexOf(pendingVideoCodec)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.video_codec)
                    .setSingleChoiceItems(codecs, currentCodecIndex) { dialog, which ->
                        pendingVideoCodec = codecs[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "fpsLimit",
            nameResId = R.string.fps_limit,
            value = "${pendingFpsLimit} FPS",
            onClick = { _ ->
                val fpsOptions = arrayOf("30", "60")
                val currentFpsIndex = fpsOptions.indexOf(pendingFpsLimit.toString())
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.fps_limit)
                    .setSingleChoiceItems(fpsOptions, currentFpsIndex) { dialog, which ->
                        pendingFpsLimit = fpsOptions[which].toInt()
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Input Settings ---
        items.add(SettingItem.CategoryHeader("input", R.string.category_input))

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description),
            onClick = { _ ->
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_keymapFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableRotary",
            nameResId = R.string.enable_rotary,
            descriptionResId = R.string.enable_rotary_description,
            isChecked = pendingEnableRotary ?: false,
            onCheckedChanged = { isChecked ->
                pendingEnableRotary = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Audio Settings ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableAudioSink",
            nameResId = R.string.enable_audio_sink,
            descriptionResId = R.string.enable_audio_sink_description,
            isChecked = pendingEnableAudioSink!!,
            onCheckedChanged = { isChecked ->
                pendingEnableAudioSink = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "separateAudioStreams",
            nameResId = R.string.separate_audio_streams,
            descriptionResId = R.string.separate_audio_streams_description,
            isChecked = pendingSeparateAudioStreams ?: true,
            onCheckedChanged = { isChecked ->
                pendingSeparateAudioStreams = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAacAudio",
            nameResId = R.string.use_aac_audio,
            descriptionResId = R.string.use_aac_audio_description,
            isChecked = pendingUseAacAudio!!,
            onCheckedChanged = { isChecked ->
                pendingUseAacAudio = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "syncMediaSessionAaMetadata",
            nameResId = R.string.sync_media_session_aa_metadata,
            descriptionResId = R.string.sync_media_session_aa_metadata_description,
            isChecked = pendingSyncMediaSessionAaMetadata!!,
            onCheckedChanged = { isChecked ->
                pendingSyncMediaSessionAaMetadata = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "micSettings",
            nameResId = R.string.microphone_settings,
            value = getString(R.string.microphone_settings_description),
            onClick = { _ ->
                findNavController().navigate(R.id.action_settingsFragment_to_micSettingsFragment)
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioVolumeOffsets",
            nameResId = R.string.audio_volume_offset,
            value = "${(100 + (pendingMediaVolumeOffset ?: 0))}% / ${(100 + (pendingAssistantVolumeOffset ?: 0))}% / ${(100 + (pendingNavigationVolumeOffset ?: 0))}%",
            onClick = {
                showAudioOffsetsDialog()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioLatencyMultiplier",
            nameResId = R.string.audio_latency_multiplier,
            value = "${pendingAudioLatencyMultiplier}x",
            onClick = { _ ->
                val options = arrayOf("1x (Lowest Latency)", "2x (Low Latency)", "4x (High Latency)", "8x (Very High Latency)")
                val values = intArrayOf(1, 2, 4, 8)
                val currentIndex = values.indexOf(pendingAudioLatencyMultiplier ?: 8).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_latency_multiplier)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        pendingAudioLatencyMultiplier = values[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioQueueCapacity",
            nameResId = R.string.audio_queue_capacity,
            value = if (pendingAudioQueueCapacity == 0) "Unbounded (Legacy)" else "${pendingAudioQueueCapacity} chunks",
            onClick = { _ ->
                val options = arrayOf("10 chunks (Low Latency)", "20 chunks (Balanced)", "50 chunks (High Latency)", "Unbounded (Max Backlog)")
                val values = intArrayOf(10, 20, 50, 0)
                val currentIndex = values.indexOf(pendingAudioQueueCapacity ?: 0).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_queue_capacity)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        pendingAudioQueueCapacity = values[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showFpsCounter",
            nameResId = R.string.show_fps_counter,
            descriptionResId = R.string.show_fps_counter_description,
            isChecked = pendingShowFpsCounter!!,
            onCheckedChanged = { isChecked ->
                pendingShowFpsCounter = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        val logLevels = LogExporter.LogLevel.entries
        val logLevelNames = logLevels.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logLevel",
            nameResId = R.string.log_level,
            value = settings.exporterLogLevel.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = {
                val currentIndex = logLevels.indexOf(settings.exporterLogLevel)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_level)
                    .setSingleChoiceItems(logLevelNames, currentIndex) { dialog, which ->
                        val newLevel = logLevels[which]
                        settings.exporterLogLevel = newLevel
                        if (newLevel == LogExporter.LogLevel.SILENT) {
                            settings.exporterCaptureEnabled = false
                            if (LogExporter.isCapturing) {
                                LogExporter.stopCapture()
                            }
                        }
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "captureLog",
            nameResId = if (LogExporter.isCapturing) R.string.stop_log_capture else R.string.start_log_capture,
            value = when {
                settings.exporterLogLevel == LogExporter.LogLevel.SILENT -> getString(R.string.start_log_capture_description)
                LogExporter.isCapturing -> getString(R.string.stop_log_capture_description)
                else -> getString(R.string.start_log_capture_description)
            },
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    Toast.makeText(context, getString(R.string.start_log_capture_in_silent), Toast.LENGTH_LONG).show()
                    return@SettingEntry
                }

                if (LogExporter.isCapturing) {
                    LogExporter.stopCapture()
                    settings.exporterCaptureEnabled = false
                } else {
                    LogExporter.startCapture(context, exporterLevel)
                    settings.exporterCaptureEnabled = true
                }
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    Toast.makeText(context, getString(R.string.failed_export_in_silent_logs), Toast.LENGTH_LONG).show()
                    return@SettingEntry
                }

                if (LogExporter.isCapturing) {
                    LogExporter.stopCapture()
                }
                val logFile = LogExporter.saveLogToPublicFile(context, exporterLevel)
                updateSettingsList()

                if (logFile != null) {
                    MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                        .setTitle(R.string.logs_exported)
                        .setMessage(getString(R.string.log_saved_to, logFile.absolutePath))
                        .setPositiveButton(R.string.share) { _, _ ->
                            LogExporter.shareLogFile(context, logFile)
                        }
                        .setNegativeButton(R.string.close) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(context, getString(R.string.failed_export_logs), Toast.LENGTH_SHORT).show()
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useNativeSsl",
            nameResId = R.string.use_native_ssl,
            descriptionResId = R.string.use_native_ssl_description,
            isChecked = pendingUseNativeSsl!!,
            onCheckedChanged = { isChecked ->
                pendingUseNativeSsl = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Info Settings ---
        items.add(SettingItem.CategoryHeader("info", R.string.category_info))

        items.add(SettingItem.SettingEntry(
            stableId = "version",
            nameResId = R.string.version,
            value = BuildConfig.VERSION_NAME,
            onClick = { /* Read only */ }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "about",
            nameResId = R.string.about,
            value = getString(R.string.about_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        // Add a dedicated Save button at the bottom if there are changes
        if (hasChanges) {
            items.add(SettingItem.ActionButton(
                stableId = "bottomSaveButton",
                textResId = if (requiresRestart) R.string.save_and_restart else R.string.save,
                onClick = { saveSettings() }
            ))
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { settingsRecyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun showAudioOffsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_offsets, null)
        
        val seekMedia = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_media)
        val seekAssistant = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_assistant)
        val seekNavigation = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_navigation)
        
        val textMedia = dialogView.findViewById<android.widget.TextView>(R.id.text_media_val)
        val textAssistant = dialogView.findViewById<android.widget.TextView>(R.id.text_assistant_val)
        val textNavigation = dialogView.findViewById<android.widget.TextView>(R.id.text_navigation_val)

        // Mapping: 0 to 100 on SeekBar -> 0% to 200% Gain. Default is 50 (100% Gain, 0 Offset)
        // Offset = (seekValue - 50) * 2
        // seekValue = (offset / 2) + 50

        seekMedia.progress = ((pendingMediaVolumeOffset ?: 0) / 2) + 50
        seekAssistant.progress = ((pendingAssistantVolumeOffset ?: 0) / 2) + 50
        seekNavigation.progress = ((pendingNavigationVolumeOffset ?: 0) / 2) + 50

        val updateLabels = {
            textMedia.text = "${(seekMedia.progress * 2)}%"
            textAssistant.text = "${(seekAssistant.progress * 2)}%"
            textNavigation.text = "${(seekNavigation.progress * 2)}%"
        }
        updateLabels()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        seekMedia.setOnSeekBarChangeListener(listener)
        seekAssistant.setOnSeekBarChangeListener(listener)
        seekNavigation.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_volume_offset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingMediaVolumeOffset = (seekMedia.progress - 50) * 2
                pendingAssistantVolumeOffset = (seekAssistant.progress - 50) * 2
                pendingNavigationVolumeOffset = (seekNavigation.progress - 50) * 2
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                val intent = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingAutoEnableHotspot = true
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showCustomInsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_insets, null)
        
        val inputLeft = dialogView.findViewById<EditText>(R.id.input_left)
        val inputTop = dialogView.findViewById<EditText>(R.id.input_top)
        val inputRight = dialogView.findViewById<EditText>(R.id.input_right)
        val inputBottom = dialogView.findViewById<EditText>(R.id.input_bottom)

        // Set initial values from pending state
        inputLeft.setText((pendingInsetLeft ?: 0).toString())
        inputTop.setText((pendingInsetTop ?: 0).toString())
        inputRight.setText((pendingInsetRight ?: 0).toString())
        inputBottom.setText((pendingInsetBottom ?: 0).toString())

        // Helper to update pending values and UI preview
        fun updatePreview() {
            val l = inputLeft.text.toString().toIntOrNull() ?: 0
            val t = inputTop.text.toString().toIntOrNull() ?: 0
            val r = inputRight.text.toString().toIntOrNull() ?: 0
            val b = inputBottom.text.toString().toIntOrNull() ?: 0
            
            pendingInsetLeft = l
            pendingInsetTop = t
            pendingInsetRight = r
            pendingInsetBottom = b
            
            // Live Preview: Set padding on the root view of the Activity
            val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
            root?.setPadding(l, t, r, b)
        }

        // Helper to bind buttons
        fun bindButton(btnId: Int, input: EditText, delta: Int) {
            dialogView.findViewById<View>(btnId).setOnClickListener {
                val current = input.text.toString().toIntOrNull() ?: 0
                val newVal = (current + delta).coerceAtLeast(0)
                input.setText(newVal.toString())
                updatePreview()
            }
        }

        bindButton(R.id.btn_left_minus, inputLeft, -10)
        bindButton(R.id.btn_left_plus, inputLeft, 10)
        bindButton(R.id.btn_top_minus, inputTop, -10)
        bindButton(R.id.btn_top_plus, inputTop, 10)
        bindButton(R.id.btn_right_minus, inputRight, -10)
        bindButton(R.id.btn_right_plus, inputRight, 10)
        bindButton(R.id.btn_bottom_minus, inputBottom, -10)
        bindButton(R.id.btn_bottom_plus, inputBottom, 10)

        // Text Watchers? Maybe overkill, buttons are safer.
        // Let's add simple focus change listener to update preview on manual entry
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updatePreview()
        }
        inputLeft.onFocusChangeListener = focusListener
        inputTop.onFocusChangeListener = focusListener
        inputRight.onFocusChangeListener = focusListener
        inputBottom.onFocusChangeListener = focusListener

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.custom_insets)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val l = inputLeft.text.toString().toIntOrNull() ?: 0
                val t = inputTop.text.toString().toIntOrNull() ?: 0
                val r = inputRight.text.toString().toIntOrNull() ?: 0
                val b = inputBottom.text.toString().toIntOrNull() ?: 0
                
                // PERSIST IMMEDIATELY (Rescue Mode)
                settings.insetLeft = l
                settings.insetTop = t
                settings.insetRight = r
                settings.insetBottom = b
                settings.commit()
                
                // Update pending to keep UI in sync
                pendingInsetLeft = l
                pendingInsetTop = t
                pendingInsetRight = r
                pendingInsetBottom = b
                
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
                
                // Refresh activity to apply padding immediately
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                // Revert Preview immediately
                val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
                root?.setPadding(
                    settings.insetLeft, settings.insetTop, 
                    settings.insetRight, settings.insetBottom
                )
                // Reset pending to old values
                pendingInsetLeft = settings.insetLeft
                pendingInsetTop = settings.insetTop
                pendingInsetRight = settings.insetRight
                pendingInsetBottom = settings.insetBottom
                
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh settings list when returning from sub-screens (e.g. AutoConnectFragment, DarkModeFragment)
        if (::settingsAdapter.isInitialized) {
            settings = App.provide(requireContext()).settings
            updateSettingsList()
        }
    }

    private fun getKillOnDisconnectConflicts(): List<String> {
        val conflicts = mutableListOf<String>()
        // Only reconnection-related settings conflict with close-on-disconnect.
        // Initial connection settings (auto-connect last session, single USB,
        // self mode, auto-start on USB) should keep working when the car starts.
        if (settings.reopenOnReconnection) {
            conflicts.add(getString(R.string.reopen_on_reconnection_label))
        }
        return conflicts
    }

    private fun showKillOnDisconnectWarning(conflicts: List<String>, hasAutoStartOnBoot: Boolean, hasAutoStartOnScreenOn: Boolean = false) {
        val message = buildString {
            if (conflicts.isNotEmpty()) {
                val conflictList = conflicts.joinToString("\n") { "• $it" }
                append(getString(R.string.kill_on_disconnect_warning, conflictList))
            }
            if (hasAutoStartOnBoot) {
                if (conflicts.isNotEmpty()) append("\n\n")
                append(getString(R.string.kill_on_disconnect_boot_warning))
            }
            if (hasAutoStartOnScreenOn) {
                if (conflicts.isNotEmpty() || hasAutoStartOnBoot) append("\n\n")
                append(getString(R.string.kill_on_disconnect_screen_on_warning))
            }
        }

        var confirmed = false

        val hasDisableableConflicts = conflicts.isNotEmpty()
        val positiveTextRes = if (hasDisableableConflicts) {
            R.string.kill_on_disconnect_disable_and_enable
        } else {
            R.string.kill_on_disconnect_enable_anyway
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.kill_on_disconnect_warning_title)
            .setMessage(message)
            .setPositiveButton(positiveTextRes) { _, _ ->
                confirmed = true
                if (hasDisableableConflicts) {
                    disableKillOnDisconnectConflicts()
                    Toast.makeText(context, getString(R.string.kill_on_disconnect_conflicts_disabled), Toast.LENGTH_LONG).show()
                }
                pendingKillOnDisconnect = true
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        // Disable the positive button and show a countdown
        val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        positiveButton.alpha = 0.4f
        val baseText = getString(positiveTextRes)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var remaining = 4

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    positiveButton.text = "$baseText (${remaining}s)"
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    positiveButton.text = baseText
                    positiveButton.isEnabled = true
                    positiveButton.alpha = 1.0f
                }
            }
        }
        handler.post(countdownRunnable)

        dialog.setOnDismissListener {
            handler.removeCallbacks(countdownRunnable)
            if (!confirmed) {
                pendingKillOnDisconnect = false
                checkChanges()
                updateSettingsList()
            }
        }
    }

    private fun disableKillOnDisconnectConflicts() {
        // Only disable reconnection-related settings.
        // Initial connection settings are kept so they work when the car starts.
        settings.reopenOnReconnection = false
    }

    private fun showHotspotPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showHotspotExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingAutoEnableHotspot = true
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }
    private fun addHotspotToggle(items: MutableList<SettingItem>) {
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoEnableHotspot",
            nameResId = R.string.auto_enable_hotspot,
            descriptionResId = R.string.auto_enable_hotspot_description,
            isChecked = pendingAutoEnableHotspot ?: false,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= 23 && !SystemSettings.System.canWrite(requireContext())) {
                        showPermissionDialog()
                    } else {
                        showExperimentalWarning()
                    }
                } else {
                    pendingAutoEnableHotspot = false
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))
    }


    private fun getAutoConnectSummary(): String {
        val order = settings.autoConnectPriorityOrder
        val enabledNames = order.mapNotNull { id ->
            val isEnabled = when (id) {
                Settings.AUTO_CONNECT_LAST_SESSION -> settings.autoConnectLastSession
                Settings.AUTO_CONNECT_SELF_MODE -> settings.autoStartSelfMode
                Settings.AUTO_CONNECT_SINGLE_USB -> settings.autoConnectSingleUsbDevice
                else -> false
            }
            if (isEnabled) {
                when (id) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> getString(R.string.auto_connect_last_session)
                    Settings.AUTO_CONNECT_SELF_MODE -> getString(R.string.auto_start_self_mode)
                    Settings.AUTO_CONNECT_SINGLE_USB -> getString(R.string.auto_connect_single_usb)
                    else -> null
                }
            } else null
        }
        return if (enabledNames.isEmpty()) {
            getString(R.string.auto_connect_all_disabled)
        } else {
            enabledNames.joinToString(" → ")
        }
    }

    private fun showNumericInputDialog(
        title: String,
        message: String?,
        initialValue: Int,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initialValue == 0 && title.contains("DPI", true)) "" else initialValue.toString())
        }

        // Use a container to add padding around the EditText
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newVal = (editView.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                onConfirm(newVal)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleNativeAaSelection() {
        if (NativeAaHandshakeManager.checkCompatibility(requireContext())) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.supported_nativeaa)
                .setMessage(R.string.supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    pendingWifiConnectionMode = 3
                    checkChanges()
                    updateSettingsList()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.not_supported_nativeaa)
                .setMessage(R.string.not_supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    pendingWifiConnectionMode = 3
                    checkChanges()
                    updateSettingsList()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    companion object {
        private val SAVE_ITEM_ID = 1001
    }
}
