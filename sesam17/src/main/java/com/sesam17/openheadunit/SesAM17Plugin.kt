package com.sesam17.openheadunit

import android.content.Context
import com.sesam17.openheadunit.floatingbutton.FloatingButtonManager
import com.sesam17.openheadunit.redirect.AppRedirectManager
import com.sesam17.openheadunit.ultrawide.UltrawideFix

/**
 * Main extension entry point for Sesam17 Custom Headunit Features.
 */
object SesAM17Plugin {

    fun onAppForegroundChanged(context: Context, isForeground: Boolean) {
        FloatingButtonManager.onAppForegroundChanged(context, isForeground)
    }

    fun onProjectionStateChanged(context: Context, isProjecting: Boolean) {
        if (isProjecting) {
            FloatingButtonManager.update(context)
        } else {
            FloatingButtonManager.removeOverlay(context)
        }
    }

    fun onDestroy(context: Context) {
        FloatingButtonManager.removeOverlay(context)
    }

    fun isUltrawideEnabled(context: Context): Boolean {
        return UltrawideFix.isUltrawideEnabled(context)
    }

    fun shouldForce720pAndStretch(screenWidthPx: Int, realScreenWidthPx: Int): Boolean {
        return UltrawideFix.shouldForce720pAndStretch(screenWidthPx, realScreenWidthPx)
    }

    fun getUsableWidth(screenWidthPx: Int): Int {
        return UltrawideFix.getUsableWidth(screenWidthPx)
    }

    fun getBufferWindowMultiplier(): Int {
        return UltrawideFix.getBufferWindowMultiplier()
    }

    // Redirect Plugin API
    fun isRedirectEnabled(context: Context): Boolean = AppRedirectManager.isRedirectEnabled(context)
    fun setRedirectEnabled(context: Context, enabled: Boolean) = AppRedirectManager.setRedirectEnabled(context, enabled)

    fun getSelectedRedirectApps(context: Context): Set<String> = AppRedirectManager.getSelectedRedirectApps(context)
    fun setSelectedRedirectApps(context: Context, apps: Set<String>) = AppRedirectManager.setSelectedRedirectApps(context, apps)

    fun isDoubleTapHomeEnabled(context: Context): Boolean = AppRedirectManager.isDoubleTapHomeEnabled(context)
    fun setDoubleTapHomeEnabled(context: Context, enabled: Boolean) = AppRedirectManager.setDoubleTapHomeEnabled(context, enabled)

    fun isAccessibilityServiceEnabled(context: Context): Boolean = AppRedirectManager.isAccessibilityServiceEnabled(context)
    fun openAccessibilitySettings(context: Context) = AppRedirectManager.openAccessibilitySettings(context)

    fun showAppMultiSelectDialog(context: Context, onAppsSelected: ((Set<String>) -> Unit)? = null) {
        AppRedirectManager.showAppMultiSelectDialog(context, onAppsSelected)
    }

    fun getRedirectAppsSummary(context: Context): String = AppRedirectManager.getRedirectAppsSummary(context)
}
