package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class AddNetworkAddressDialog : DialogFragment() {

    private val dialogJob = Job()
    private val dialogScope = CoroutineScope(Dispatchers.Main + dialogJob)

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val builder = AlertDialog.Builder(activity, R.style.DarkAlertDialog)
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.fragment_add_network_address, null, false)

        val first = content.findViewById<EditText>(R.id.first)
        val second = content.findViewById<EditText>(R.id.second)
        val third = content.findViewById<EditText>(R.id.third)
        val fourth = content.findViewById<EditText>(R.id.fourth)

        val ip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable("ip", InetAddress::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable("ip") as? InetAddress
        }
        if (ip != null) {
            val addr = ip.address
            first.setText("${addr[0].toInt() and 0xFF}")
            second.setText("${addr[1].toInt() and 0xFF}")
            third.setText("${addr[2].toInt() and 0xFF}")
        }

        fourth.requestFocus()

        builder
            .setView(content)
            .setTitle("Enter ip address")
            .setPositiveButton("Add") { _, _ ->
                val newAddr = ByteArray(4)
                try {
                    newAddr[0] = strToByte(first.text.toString())
                    newAddr[1] = strToByte(second.text.toString())
                    newAddr[2] = strToByte(third.text.toString())
                    newAddr[3] = strToByte(fourth.text.toString())

                    val f = parentFragment as? NetworkListFragment
                    f?.addAddress(InetAddress.getByAddress(newAddr))
                } catch (e: java.net.UnknownHostException) {
                    AppLog.e(e)
                } catch (e: NumberFormatException) {
                    AppLog.e(e)
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
            .setNeutralButton("Scan", null)

        val dialog = builder.create()
        dialog.setOnShowListener {
            val scanButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            scanButton.setOnClickListener {
                val p1 = first.text?.toString()?.toIntOrNull()
                val p2 = second.text?.toString()?.toIntOrNull()
                val p3 = third.text?.toString()?.toIntOrNull()
                if (p1 == null || p2 == null || p3 == null || p1 !in 0..255 || p2 !in 0..255 || p3 !in 0..255) {
                    Toast.makeText(requireContext(), "Enter first 3 octets", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                Toast.makeText(requireContext(), "Scanning subnet...", Toast.LENGTH_SHORT).show()

                dialogScope.launch(Dispatchers.IO) {
                    val found = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val jobs = (1..254).map { last ->
                        kotlinx.coroutines.async(Dispatchers.IO) {
                            val ip = "$p1.$p2.$p3.$last"
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(ip, 5277), 150)
                                    found.add(ip)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    jobs.forEach { it.await() }

                    withContext(Dispatchers.Main) {
                        if (found.isEmpty()) {
                            Toast.makeText(requireContext(), "No devices found", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                                .setTitle("Found devices")
                                .setItems(found.toTypedArray()) { _, which ->
                                    try {
                                        val f = parentFragment as? NetworkListFragment
                                        f?.addAddress(InetAddress.getByName(found[which]))
                                    } catch (e: Exception) {
                                        AppLog.e(e)
                                    }
                                }
                                .show()
                        }
                    }
                }
            }
        }
        return dialog
    }

    companion object {

        fun show(ip: InetAddress?, manager: FragmentManager) {
            create(ip).show(manager, "AddNetworkAddressDialog")
        }

        fun create(ip: InetAddress?) = AddNetworkAddressDialog().apply {
            arguments = Bundle()
            if (ip != null) {
                arguments!!.putSerializable("ip", ip)
            }
        }

        fun strToByte(str: String): Byte {
            val i = Integer.valueOf(str)
            return i.toByte()
        }
    }
}
