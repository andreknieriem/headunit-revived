package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import java.net.InetAddress

class AddNetworkAddressDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val builder = AlertDialog.Builder(activity)
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

        builder.setView(content)
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
        return builder.create()
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
