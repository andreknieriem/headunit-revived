package com.andrerinas.openheadunit.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ProjectionQrPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows Android Auto's own setup QR, which provisions this head unit on the phone without a
 * Bluetooth handshake: one scan writes our network and TCP endpoint into its known-car record.
 *
 * The refusals are worth as much as the code. Every one of them names something the user can act on,
 * and drawing a QR the phone would store and then fail to use is the outcome this exists to avoid.
 */
object ProjectionSetupQrDialog {

    fun show(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_projection_setup_qr, null)
        val container = view.findViewById<View>(R.id.layout_qr_container)
        val image = view.findViewById<ImageView>(R.id.img_qr_code)
        val error = view.findViewById<TextView>(R.id.tv_qr_error_message)
        val instruction = view.findViewById<TextView>(R.id.tv_scan_instruction)

        when (val decision = ProjectionQrPolicy.decide(AapService.instance?.projectionQrSnapshot())) {
            is ProjectionQrPolicy.Result.Show -> {
                val bitmap = QrCodeGenerator.generateQrCode(decision.url, 500)
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    container.visibility = View.VISIBLE
                    instruction.visibility = View.VISIBLE
                } else {
                    error.setText(R.string.native_aa_setup_qr_not_drawn)
                    error.visibility = View.VISIBLE
                }
            }
            is ProjectionQrPolicy.Result.Refuse -> {
                AppLog.i("ProjectionSetupQr: no setup QR to show (${decision.refusal}).")
                error.setText(reasonRes(decision.refusal))
                error.visibility = View.VISIBLE
            }
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.native_aa_setup_qr_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun reasonRes(refusal: ProjectionQrPolicy.Refusal): Int = when (refusal) {
        ProjectionQrPolicy.Refusal.NOT_RUNNING -> R.string.native_aa_setup_qr_not_running
        ProjectionQrPolicy.Refusal.NOT_HOTSPOT -> R.string.native_aa_setup_qr_not_hotspot
        ProjectionQrPolicy.Refusal.TRANSPORT_NOT_APPLIED -> R.string.native_aa_setup_qr_transport_not_applied
        ProjectionQrPolicy.Refusal.NOT_LISTENING -> R.string.native_aa_setup_qr_not_listening
        ProjectionQrPolicy.Refusal.NO_CREDENTIALS -> R.string.native_aa_setup_qr_no_credentials
        ProjectionQrPolicy.Refusal.NO_BSSID -> R.string.native_aa_setup_qr_no_bssid
        ProjectionQrPolicy.Refusal.NO_ADDRESS -> R.string.native_aa_setup_qr_no_address
        ProjectionQrPolicy.Refusal.NO_BLUETOOTH_IDENTITY -> R.string.native_aa_setup_qr_no_bluetooth
        ProjectionQrPolicy.Refusal.DONGLE_IDENTITY -> R.string.native_aa_setup_qr_dongle
    }
}
