package com.sesam17.openheadunit.redirect

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class RedirectAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RedirectAccessService"
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "RedirectAccessibilityService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        AppRedirectManager.handleWindowStateChanged(this, packageName)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (AppRedirectManager.handleHomeKeyEvent(this, event)) {
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "RedirectAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "RedirectAccessibilityService destroyed")
    }
}
