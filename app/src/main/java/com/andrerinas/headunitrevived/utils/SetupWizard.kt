package com.andrerinas.headunitrevived.utils

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean

class SetupWizard(private val context: Context, private val onFinished: () -> Unit) {

    private val settings = App.provide(context).settings
    private val optimizer = SystemOptimizer(context)
    
    private var selectedSize: SystemOptimizer.DisplaySizePreset = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait: Boolean = false

    fun start() {
        showWelcome()
    }

    private fun showWelcome() {
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_welcome_title)
            .setMessage(R.string.setup_welcome_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.setup_start) { _, _ -> 
                showSizeSelection()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> 
                settings.hasCompletedSetupWizard = true // Don't show again even if cancelled
                onFinished() 
            }
            .show()
    }

    private fun showSizeSelection() {
        val options = arrayOf(
            context.getString(R.string.setup_size_phone),     // 4-6"
            context.getString(R.string.setup_size_small),     // 7-8"
            context.getString(R.string.setup_size_standard),  // 9-10"
            context.getString(R.string.setup_size_large)      // 11"+
        )

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_size_title)
            .setCancelable(false)
            .setItems(options) { _, which ->
                selectedSize = when (which) {
                    0 -> SystemOptimizer.DisplaySizePreset.PHONE_4_6
                    1 -> SystemOptimizer.DisplaySizePreset.SMALL_7_8
                    2 -> SystemOptimizer.DisplaySizePreset.STANDARD_9_10
                    else -> SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS
                }
                showOrientationSelection()
            }
            .setNeutralButton(R.string.back) { _, _ -> showWelcome() }
            .setNegativeButton(R.string.cancel) { _, _ -> 
                settings.hasCompletedSetupWizard = true
                onFinished() 
            }
            .show()
    }

    private fun showOrientationSelection() {
        val options = arrayOf(
            context.getString(R.string.setup_orientation_landscape),
            context.getString(R.string.setup_orientation_portrait)
        )

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_orientation_title)
            .setCancelable(false)
            .setItems(options) { _, which ->
                selectedPortrait = (which == 1)
                runOptimization()
            }
            .setNeutralButton(R.string.back) { _, _ -> showSizeSelection() }
            .setNegativeButton(R.string.cancel) { _, _ -> 
                settings.hasCompletedSetupWizard = true
                onFinished() 
            }
            .show()
    }

    private fun runOptimization() {
        val result = optimizer.calculateOptimalSettings(selectedSize, selectedPortrait)
        
        val summary = StringBuilder()
        summary.append("${context.getString(R.string.resolution)}: ${Settings.Resolution.fromId(result.recommendedResolutionId)?.resName}\n")
        summary.append("${context.getString(R.string.video_codec)}: ${result.recommendedVideoCodec}\n")
        summary.append("${context.getString(R.string.view_mode)}: ${result.recommendedViewMode.name}\n")
        summary.append("${context.getString(R.string.dpi)}: ${result.recommendedDpi}\n")
        
        if (result.isWidescreen) {
            summary.append("${context.getString(R.string.setup_widescreen_detected)}\n")
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.setup_result_title)
            .setMessage(summary.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.setup_apply) { _, _ ->
                applySettings(result)
            }
            .setNeutralButton(R.string.back) { _, _ -> showOrientationSelection() }
            .setNegativeButton(R.string.cancel) { _, _ -> 
                settings.hasCompletedSetupWizard = true
                onFinished() 
            }
            .show()
    }

    private fun applySettings(result: SystemOptimizer.OptimizationResult) {
        settings.resolutionId = result.recommendedResolutionId
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.dpiPixelDensity = result.recommendedDpi
        settings.screenOrientation = result.suggestedOrientation
        settings.hasCompletedSetupWizard = true
        
        onFinished()
    }
}
