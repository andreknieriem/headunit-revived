package com.andrerinas.openheadunit.main

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The multi-select list of paired devices behind every "choose a device" row. The caller owns the
 * permission and enable-Bluetooth requests, which need a fragment's launchers, and asks again after.
 */
object BluetoothDevicePicker {

    /** Alias first, hardware name in brackets when they differ. */
    fun labelFor(device: BluetoothDevice): String {
        val hardwareName = device.name ?: "Unknown Device"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val alias = device.alias
            if (!alias.isNullOrEmpty() && alias != hardwareName) {
                return "$alias ($hardwareName)"
            }
            return alias ?: hardwareName
        }
        return hardwareName
    }

    /**
     * Shows the list with [selectedMacs] ticked. OK commits the working set, "Remove" the empty set,
     * Cancel nothing. Returns false when there was nothing to show: no adapter, off, or unpaired.
     */
    fun show(
        context: Context,
        @StringRes titleResId: Int,
        selectedMacs: Set<String>,
        onCommit: (Set<String>) -> Unit
    ): Boolean {
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) return false

        val bondedDevices = adapter.bondedDevices.toList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(context, R.string.no_paired_bt_devices, Toast.LENGTH_LONG).show()
            return false
        }

        val deviceNames = bondedDevices.map { labelFor(it) }.toTypedArray()
        val checkedItems = bondedDevices.map { selectedMacs.contains(it.address) }.toBooleanArray()
        val working = selectedMacs.toMutableSet()

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setMultiChoiceItems(deviceNames, checkedItems) { _, which, isChecked ->
                val device = bondedDevices[which]
                if (isChecked) working.add(device.address) else working.remove(device.address)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onCommit(working.toSet()) }
            .setNeutralButton(R.string.remove) { _, _ -> onCommit(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    /**
     * The row summary for [macs], or null when nothing valid is selected; unpaired devices stop
     * counting. [offlineName] answers for a single address when the adapter cannot be asked.
     */
    fun summaryFor(context: Context, macs: Set<String>, offlineName: (String) -> String? = { null }): String? {
        if (macs.isEmpty()) return null

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        val hasConnectPermission = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val adapterUsable = adapter?.isEnabled == true && hasConnectPermission

        val bondedAddresses = if (adapterUsable) {
            try { adapter!!.bondedDevices.map { it.address }.toSet() } catch (e: SecurityException) { null }
        } else null

        val valid = if (bondedAddresses != null) macs.filter { it in bondedAddresses } else macs.toList()
        if (valid.isEmpty()) return null
        if (valid.size > 1) return "${valid.size} ${context.getString(R.string.bt_devices_selected)}"

        val mac = valid.first()
        if (adapterUsable) {
            try {
                val device = adapter!!.getRemoteDevice(mac)
                val hardwareName = device.name ?: mac
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias ?: hardwareName else hardwareName
            } catch (e: Exception) {
                return mac
            }
        }
        return offlineName(mac) ?: mac
    }
}
