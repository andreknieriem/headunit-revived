package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.toInetAddress
import android.view.View // Added import
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.net.Inet4Address

class MainActivity : FragmentActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null
    private val viewModel: MainViewModel by viewModels()

    private lateinit var video_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var ipView: TextView
    private lateinit var backButton: Button
    private lateinit var mainButtonsContainer: FrameLayout
    private lateinit var mainContentFrame: FrameLayout
    private lateinit var headerContainer: LinearLayout // Added headerContainer declaration

    private var networkCallback: ConnectivityManager.NetworkCallback? = null // Made nullable

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AppLog.d("MainActivity: handleOnBackPressed - backStackEntryCount: ${supportFragmentManager.backStackEntryCount}")
                if (supportFragmentManager.backStackEntryCount > 0) {
                    AppLog.d("MainActivity: handleOnBackPressed - popping back stack")
                    supportFragmentManager.popBackStack()
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    AppLog.d("MainActivity: handleOnBackPressed - finishing activity")
                    finish()
                } else {
                    AppLog.d("MainActivity: handleOnBackPressed - showing exit toast")
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        video_button = findViewById(R.id.video_button)
        usb = findViewById(R.id.usb_button)
        settings = findViewById(R.id.settings_button)
        wifi = findViewById(R.id.wifi_button)
        ipView = findViewById(R.id.ip_address)
        backButton = findViewById(R.id.back_button)
        mainButtonsContainer = findViewById(R.id.main_buttons_container)
        mainContentFrame = findViewById(R.id.main_content)
        headerContainer = findViewById(R.id.header_container) // Initialized headerContainer

        backButton.setOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }

        // Initialize networkCallback conditionally
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateIpAddressView()
                }

                override fun onLost(network: Network) {
                    updateIpAddressView()
                }
            }
        }

        video_button.setOnClickListener {
            if (App.provide(this).transport.isAlive) {
                val aapIntent = Intent(this@MainActivity, AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                Toast.makeText(this, getString(R.string.no_android_auto_device_connected), Toast.LENGTH_LONG).show()
            }
        }

        usb.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, UsbListFragment())
                .addToBackStack(null) // Added to back stack
                .commit()
        }

        settings.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, SettingsFragment())
                .addToBackStack(null) // Added to back stack
                .commit()
        }

        wifi.setOnClickListener {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, NetworkListFragment())
                .addToBackStack(null) // Added to back stack
                .commit()
        }

        viewModel.register()

        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
            ), permissionRequestCode
        )

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_content, HomeFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            AppLog.d("MainActivity: onBackStackChanged - backStackEntryCount: ${supportFragmentManager.backStackEntryCount}")
            updateBackButtonVisibility()
        }
        updateBackButtonVisibility() // Initial check
    }

    private fun updateBackButtonVisibility() {
        val isFragmentOnStack = supportFragmentManager.backStackEntryCount > 0
        backButton.visibility = if (isFragmentOnStack) View.VISIBLE else View.GONE
        ipView.visibility = if (isFragmentOnStack) View.GONE else View.VISIBLE // IP visible only on main screen
        mainButtonsContainer.visibility = if (isFragmentOnStack) View.GONE else View.VISIBLE
        mainContentFrame.visibility = if (isFragmentOnStack) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            networkCallback?.let {
                connectivityManager.registerNetworkCallback(request, it)
            }
        }
        updateIpAddressView() // Call this regardless of API level
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
    }

    private fun updateIpAddressView() {
        var ipAddress: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ipAddress = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
        } else {
            val wifiManager = App.provide(this).wifiManager
            @Suppress("DEPRECATION")
            val currentIp = wifiManager.connectionInfo.ipAddress
            if (currentIp != 0) {
                ipAddress = currentIp.toInetAddress().hostAddress
            }
        }

        runOnUiThread {
            ipView.text = ipAddress ?: ""
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyDown: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the system handle the back button, which will trigger onBackPressedDispatcher
            return super.onKeyDown(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyUp: %d", keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the system handle the back button, which will trigger onBackPressedDispatcher
            return super.onKeyUp(keyCode, event)
        }
        return keyListener?.onKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val permissionRequestCode = 97
    }
}
