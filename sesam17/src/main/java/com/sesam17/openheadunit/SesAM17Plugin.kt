package com.sesam17.openheadunit

import android.content.Context
import com.sesam17.openheadunit.floatingbutton.FloatingButtonManager
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
}
