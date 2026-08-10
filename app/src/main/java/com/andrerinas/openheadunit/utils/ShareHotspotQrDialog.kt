package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import com.andrerinas.openheadunit.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder

object ShareHotspotQrDialog {

    fun show(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_share_hotspot_qr, null)
        val layoutQrContainer = dialogView.findViewById<View>(R.id.layout_qr_container)
        val imgQr = dialogView.findViewById<ImageView>(R.id.img_qr_code)
        val tvError = dialogView.findViewById<TextView>(R.id.tv_qr_error_message)
        val switchShowQr = dialogView.findViewById<Switch>(R.id.switch_show_qr)
        val tvScanInstruction = dialogView.findViewById<View>(R.id.tv_scan_instruction)

        val handler = Handler(Looper.getMainLooper())
        var retries = 0

        fun loadAndRender() {
            if (switchShowQr == null || !switchShowQr.isChecked) return
            val systemConfig = HotspotConfigReader.getSystemHotspotConfig(context)
            if (systemConfig != null && systemConfig.first.isNotEmpty()) {
                val ssid = systemConfig.first
                val pass = systemConfig.second
                try {
                    val encodedSsid = URLEncoder.encode(ssid, "UTF-8")
                    val encodedPass = URLEncoder.encode(pass, "UTF-8")
                    val uri = "wirelesshelper://config?ssid=$encodedSsid&pass=$encodedPass"
                    val bitmap = QrCodeGenerator.generateQrCode(uri, 500)
                    if (bitmap != null) {
                        imgQr.setImageBitmap(bitmap)
                        if (switchShowQr.isChecked) {
                            layoutQrContainer.visibility = View.VISIBLE
                            tvError.visibility = View.GONE
                        }
                        return
                    }
                } catch (e: Exception) {
                    AppLog.e("ShareHotspotQrDialog: Failed to generate QR: ${e.message}", e)
                }
            }

            if (retries < 5) {
                retries++
                handler.postDelayed({ loadAndRender() }, 1000)
            } else {
                if (switchShowQr.isChecked) {
                    imgQr.setImageDrawable(null)
                    layoutQrContainer.visibility = View.GONE
                    tvError.text = context.getString(R.string.share_hotspot_qr_error)
                    tvError.visibility = View.VISIBLE
                }
            }
        }

        switchShowQr.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tvScanInstruction.visibility = View.VISIBLE
                loadAndRender()
            } else {
                layoutQrContainer.visibility = View.GONE
                tvScanInstruction.visibility = View.GONE
            }
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.share_hotspot_qr_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
