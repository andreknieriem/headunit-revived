package com.andrerinas.headunitrevived.utils

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemUI {

    fun apply(window: Window, root: View, mode: Settings.FullscreenMode) {
        // Always keep screen on for Headunit functionality
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        val controllerCompat = WindowInsetsControllerCompat(window, window.decorView)

        // Handle Immersive Mode for modern APIs (30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                when (mode) {
                    Settings.FullscreenMode.IMMERSIVE -> {
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                    Settings.FullscreenMode.STATUS_ONLY -> {
                        controller.hide(WindowInsets.Type.statusBars())
                        controller.show(WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                    Settings.FullscreenMode.NONE -> {
                        controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    }
                }
            }
        } else {
            // Legacy Flags (Jelly Bean API 16 and above)
            @Suppress("DEPRECATION")
            when (mode) {
                Settings.FullscreenMode.IMMERSIVE -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LOW_PROFILE)
                    } else {
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LOW_PROFILE
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    }
                }
                Settings.FullscreenMode.STATUS_ONLY -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LOW_PROFILE)
                    } else {
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LOW_PROFILE
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    }
                }
                Settings.FullscreenMode.NONE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    } else {
                        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    }
                }
            }
        }

        // Fix for Non-Immersive: Force black bars on older devices
        if (mode != Settings.FullscreenMode.IMMERSIVE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = Color.BLACK
                    if (mode == Settings.FullscreenMode.NONE) {
                        window.navigationBarColor = Color.BLACK
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                if (mode == Settings.FullscreenMode.NONE) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }
            }
            controllerCompat.isAppearanceLightStatusBars = false
            controllerCompat.isAppearanceLightNavigationBars = false
        }

        val settings = Settings(root.context)

        // IMMEDIATE APPLICATION
        val manualL = settings.insetLeft
        val manualT = settings.insetTop
        val manualR = settings.insetRight
        val manualB = settings.insetBottom

        root.setPadding(manualL, manualT, manualR, manualB)
        HeadUnitScreenConfig.updateInsets(manualL, manualT, manualR, manualB)

        // Set up listener for dynamic system bars (API 21+)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insetsCompat ->
            if (mode == Settings.FullscreenMode.IMMERSIVE) {
                v.setPadding(manualL, manualT, manualR, manualB)
                HeadUnitScreenConfig.updateInsets(manualL, manualT, manualR, manualB)
            } else if (mode == Settings.FullscreenMode.STATUS_ONLY) {
                val bars = insetsCompat.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.setPadding(manualL + bars.left, manualT, manualR + bars.right, manualB + bars.bottom)
                HeadUnitScreenConfig.updateInsets(manualL + bars.left, manualT, manualR + bars.right, manualB + bars.bottom)
            } else {
                val bars = insetsCompat.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)
                HeadUnitScreenConfig.updateInsets(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)
            }
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.requestApplyInsets(root)
        root.requestLayout()
    }
}