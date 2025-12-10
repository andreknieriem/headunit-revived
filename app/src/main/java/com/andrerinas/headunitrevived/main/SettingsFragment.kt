package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.utils.Settings

// Removed import kotlinx.android.synthetic.main.fragment_settings.*

/**
 * @author algavris
 * @date 13/06/2017
 */
class SettingsFragment : Fragment() {
    lateinit var settings: Settings

    private lateinit var keymapButton: Button
    private lateinit var gpsNavigationButton: Button
    private lateinit var micSampleRateButton: Button
    private lateinit var nightModeButton: Button
    private lateinit var btAddressButton: Button
    private lateinit var debugModeButton: Button // Added debug mode button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // Call super.onViewCreated

        keymapButton = view.findViewById(R.id.keymapButton)
        gpsNavigationButton = view.findViewById(R.id.gpsNavigationButton)
        micSampleRateButton = view.findViewById(R.id.micSampleRateButton)
        nightModeButton = view.findViewById(R.id.nightModeButton)
        btAddressButton = view.findViewById(R.id.btAddressButton)
        debugModeButton = view.findViewById(R.id.debugModeButton) // Initialize debug mode button

        keymapButton.setOnClickListener {
            parentFragmentManager.
                beginTransaction()
                        .replace(R.id.main_content, KeymapFragment())
                        .addToBackStack(null) // Added to back stack
                        .commit()
        }

        settings = Settings(requireContext()) // Use requireContext()

        gpsNavigationButton.text = getString(R.string.gps_for_navigation, if (settings.useGpsForNavigation) getString(R.string.enabled) else getString(R.string.disabled) )
        gpsNavigationButton.tag = settings.useGpsForNavigation
        gpsNavigationButton.setOnClickListener {
            val newValue = !(it.tag as Boolean) // Type-safe cast
            it.tag = newValue
            settings.useGpsForNavigation = newValue
            (it as Button).text = getString(R.string.gps_for_navigation, if (newValue) getString(R.string.enabled) else getString(R.string.disabled) )
        }

        val sampleRate = settings.micSampleRate
        micSampleRateButton.text = getString(R.string.mic_sample_rate, sampleRate/1000)
        micSampleRateButton.tag = sampleRate
        micSampleRateButton.setOnClickListener {
            val newValue = Settings.MicSampleRates[it.tag as Int]!! // Type-safe cast

            val recorder: MicRecorder? = try { MicRecorder(newValue, requireContext().applicationContext) } catch (e: Exception) { null }

            if (recorder == null) {
                Toast.makeText(activity, "Value not supported: $newValue", Toast.LENGTH_LONG).show()
            } else {
                settings.micSampleRate = newValue
                (it as Button).text = getString(R.string.mic_sample_rate, newValue / 1000)
                it.tag = newValue
            }
        }


        val nightMode = settings.nightMode
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        nightModeButton.text = getString(R.string.night_mode, nightModeTitles[nightMode.value])
        nightModeButton.tag = nightMode.value
        nightModeButton.setOnClickListener {
            val newValue = Settings.NightModes[it.tag as Int]!! // Type-safe cast
            val newMode = Settings.NightMode.fromInt(newValue)!!
            (it as Button).text = getString(R.string.night_mode, nightModeTitles[newMode.value])
            it.tag = newValue
            settings.nightMode = newMode
        }

        btAddressButton.text = getString(R.string.bluetooth_address_s, settings.bluetoothAddress)
        btAddressButton.setOnClickListener {
            val editView = EditText(activity)
            editView.setText(settings.bluetoothAddress)
            AlertDialog.Builder(activity)
                .setTitle(R.string.enter_bluetooth_mac)
                .setView(editView)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    settings.bluetoothAddress = editView.text.toString().trim()
                    dialog.dismiss()
                }.show()
        }

        debugModeButton.text = getString(R.string.debug_mode, if (settings.debugMode) getString(R.string.enabled) else getString(R.string.disabled))
        debugModeButton.tag = settings.debugMode
        debugModeButton.setOnClickListener {
            val newValue = !(it.tag as Boolean)
            it.tag = newValue
            settings.debugMode = newValue
            (it as Button).text = getString(R.string.debug_mode, if (newValue) getString(R.string.enabled) else getString(R.string.disabled))
        }
    }
}