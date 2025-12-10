package com.andrerinas.headunitrevived.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.changeLastBit
import com.andrerinas.headunitrevived.utils.toInetAddress
import java.net.Inet4Address
import java.net.InetAddress

class NetworkListFragment : Fragment() {
    private lateinit var adapter: AddressAdapter
    private lateinit var connectivityManager: ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null // Made nullable

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        val recyclerView = view.findViewById<RecyclerView>(android.R.id.list)
        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Initialize networkCallback conditionally
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateCurrentAddress()
                }

                override fun onLost(network: Network) {
                    updateCurrentAddress()
                }
            }
        }

        adapter = AddressAdapter(requireContext(), childFragmentManager)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        return view
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            networkCallback?.let {
                connectivityManager.registerNetworkCallback(request, it)
            }
        }
        updateCurrentAddress()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
    }

    private fun updateCurrentAddress() {
        var ipAddress: InetAddress? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ipAddress = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = App.provide(requireContext()).wifiManager
            @Suppress("DEPRECATION")
            val currentIp = wifiManager.connectionInfo.ipAddress
            if (currentIp != 0) {
                ipAddress = currentIp.toInetAddress()
            }
        }

        // Ensure UI updates are on the main thread
        requireActivity().runOnUiThread {
            adapter.currentAddress = ipAddress?.changeLastBit(1)?.hostAddress ?: ""
            adapter.loadAddresses()
        }
    }

    fun addAddress(ip: InetAddress) {
        adapter.addNewAddress(ip)
    }

    private class DeviceViewHolder internal constructor(itemView: View) : RecyclerView.ViewHolder(itemView) {
        internal val removeButton = itemView.findViewById<Button>(android.R.id.button1)
        internal val startButton = itemView.findViewById<Button>(android.R.id.button2)
    }

    private class AddressAdapter(
        private val context: Context,
        private val fragmentManager: FragmentManager
    ) : RecyclerView.Adapter<DeviceViewHolder>(), View.OnClickListener {

        private val addressList = ArrayList<String>()
        var currentAddress: String = ""
        private val settings: Settings = Settings(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.list_item_device, parent, false)
            val holder = DeviceViewHolder(view)

            holder.startButton.setOnClickListener(this)
            holder.removeButton.setOnClickListener(this)
            holder.removeButton.setText(R.string.remove)
            return holder
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val ipAddress = addressList[position]

            val line1: String
            if (position == 0) {
                line1 = "Add a new address"
                holder.removeButton.visibility = View.GONE
            } else {
                line1 = ipAddress
                holder.removeButton.visibility = when (position) {
                    1 -> View.GONE
                    2 -> if (currentAddress.isNotEmpty()) View.GONE else View.VISIBLE
                    else -> View.VISIBLE
                }
            }
            holder.startButton.setTag(R.integer.key_position, position)
            holder.startButton.text = line1
            holder.startButton.setTag(R.integer.key_data, ipAddress)
            holder.removeButton.setTag(R.integer.key_data, ipAddress)
        }

        override fun getItemCount(): Int {
            return addressList.size
        }

        override fun onClick(v: View) {
            if (v.id == android.R.id.button2) {
                if (v.getTag(R.integer.key_position) == 0) {
                    var ip: InetAddress? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val activeNetwork = connectivityManager.activeNetwork
                        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                        ip = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
                    } else {
                        @Suppress("DEPRECATION")
                        val wifiManager = App.provide(context).wifiManager
                        @Suppress("DEPRECATION")
                        val currentIp = wifiManager.connectionInfo.ipAddress
                        if (currentIp != 0) {
                            ip = currentIp.toInetAddress()
                        }
                    }
                    com.andrerinas.headunitrevived.main.AddNetworkAddressDialog.show(ip, fragmentManager)
                } else {
                    context.startService(AapService.createIntent(v.getTag(R.integer.key_data) as String, context))
                }
            } else {
                this.removeAddress(v.getTag(R.integer.key_data) as String)
            }
        }

        internal fun addNewAddress(ip: InetAddress) {
            val newAddrs = HashSet(settings.networkAddresses)
            newAddrs.add(ip.hostAddress)
            settings.networkAddresses = newAddrs
            set(newAddrs)
        }

        internal fun loadAddresses() {
            set(settings.networkAddresses)
        }

        private fun set(addrs: Collection<String>) {
            addressList.clear()
            addressList.add("")
            addressList.add("127.0.0.1")
            if (currentAddress.isNotEmpty()) {
                addressList.add(currentAddress)
            }
            addressList.addAll(addrs.filterNotNull()) // Filter out any nulls
            notifyDataSetChanged()
        }

        private fun removeAddress(ipAddress: String) {
            val newAddrs = HashSet(settings.networkAddresses)
            newAddrs.remove(ipAddress)
            settings.networkAddresses = newAddrs
            set(newAddrs)
        }
    }

    companion object {
        const val TAG = "NetworkListFragment"
    }
}