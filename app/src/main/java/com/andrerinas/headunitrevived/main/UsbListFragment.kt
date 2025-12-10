package com.andrerinas.headunitrevived.main

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.app.UsbAttachedActivity
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.utils.Settings

/**
 * @author algavris
 * *
 * @date 05/11/2016.
 */

class UsbListFragment : Fragment() {
    private lateinit var adapter: DeviceAdapter
    private lateinit var settings: Settings
    private lateinit var noUsbDeviceTextView: TextView
    private lateinit var recyclerView: RecyclerView

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        recyclerView = view.findViewById(android.R.id.list)
        noUsbDeviceTextView = view.findViewById(R.id.no_usb_device_text)

        settings = Settings(requireContext())
        adapter = DeviceAdapter(requireContext(), settings)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel.usbDevices.observe(viewLifecycleOwner, Observer {
            val allowDevices = settings.allowedDevices
            adapter.setData(it, allowDevices)

            if (it.isEmpty()) {
                noUsbDeviceTextView.visibility = VISIBLE
                recyclerView.visibility = GONE
            } else {
                noUsbDeviceTextView.visibility = GONE
                recyclerView.visibility = VISIBLE
            }
        })
    }

    override fun onPause() {
        super.onPause()
        settings.commit()
    }

    private class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val allowButton = itemView.findViewById<Button>(android.R.id.button1)
        val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class DeviceAdapter(private val mContext: Context, private val mSettings: Settings) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {
        private var allowedDevices: MutableSet<String> = mutableSetOf()
        private var deviceList: List<UsbDeviceCompat> = listOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(mContext).inflate(R.layout.list_item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]

            holder.startButton.text = Html.fromHtml(String.format(
                    java.util.Locale.US, "<b>%1\$s</b><br/>%2\$s",
                    device.uniqueName, device.deviceName
            ))
            holder.startButton.tag = position
            holder.startButton.setOnClickListener(this)

            if (device.isInAccessoryMode) {
                holder.allowButton.setText(R.string.allowed)
                holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                holder.allowButton.isEnabled = false
            } else {
                if (allowedDevices.contains(device.uniqueName)) {
                    holder.allowButton.setText(R.string.allowed)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700))
                } else {
                    holder.allowButton.setText(R.string.ignored)
                    holder.allowButton.setTextColor(ContextCompat.getColor(mContext, R.color.material_orange_700))
                }
                holder.allowButton.tag = position
                holder.allowButton.isEnabled = true
                holder.allowButton.setOnClickListener(this)
            }
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        override fun onClick(v: View) {
            val device = deviceList.get(v.tag as Int)
            if (v.id == android.R.id.button1) {
                if (allowedDevices.contains(device.uniqueName)) {
                    allowedDevices.remove(device.uniqueName)
                } else {
                    allowedDevices.add(device.uniqueName)
                }
                mSettings.allowedDevices = allowedDevices
                notifyDataSetChanged()
            } else {
                if (App.provide(mContext).transport.isAlive) {
                    // If transport is already running, just go to projection
                    val aapIntent = Intent(mContext, AapProjectionActivity::class.java)
                    aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                    mContext.startActivity(aapIntent)
                } else if (device.isInAccessoryMode) {
                    mContext.startService(AapService.createIntent(device.wrappedDevice, mContext))
                } else {
                    val usbManager = mContext.getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(device.wrappedDevice)) {
                        val usbMode = UsbAccessoryMode(usbManager)
                        if (usbMode.connectAndSwitch(device.wrappedDevice)) {
                            Toast.makeText(mContext, "Success", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(mContext, "Failed", Toast.LENGTH_SHORT).show()
                        }
                        notifyDataSetChanged()
                    } else {
                        Toast.makeText(mContext, "USB Permission is missing", Toast.LENGTH_SHORT).show()
                        usbManager.requestPermission(device.wrappedDevice, PendingIntent.getActivity(
                            mContext, 500, Intent(mContext, UsbAttachedActivity::class.java),
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
            }
        }

        fun setData(deviceList: List<UsbDeviceCompat>, allowedDevices: Set<String>) {
            this.allowedDevices = allowedDevices.toMutableSet()
            this.deviceList = deviceList
            notifyDataSetChanged()
        }
    }

}
