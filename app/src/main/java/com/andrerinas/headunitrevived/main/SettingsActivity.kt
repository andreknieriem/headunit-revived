package com.andrerinas.headunitrevived.main

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.navigation.fragment.NavHostFragment
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SystemUI

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation
        
        setContentView(R.layout.activity_settings)
        
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as NavHostFragment
        val navController = navHostFragment.navController
        
        // Set the start destination to settingsFragment instead of homeFragment
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.startDestination = R.id.settingsFragment
        navController.graph = navGraph
        
        val root = findViewById<View>(R.id.settings_nav_host)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val appSettings = Settings(this)
            val root = findViewById<View>(R.id.settings_nav_host)
            SystemUI.apply(window, root, appSettings.fullscreenMode)
        }
    }
}
