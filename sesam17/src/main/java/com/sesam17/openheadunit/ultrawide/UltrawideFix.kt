package com.sesam17.openheadunit.ultrawide

import android.content.Context

object UltrawideFix {

    fun isUltrawideEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("openheadunit", Context.MODE_PRIVATE)
        return prefs.getBoolean("optimize-ultrawide", true)
    }

    fun shouldForce720pAndStretch(screenWidthPx: Int, realScreenWidthPx: Int): Boolean {
        return screenWidthPx >= 1700 || realScreenWidthPx >= 1700
    }

    fun getUsableWidth(screenWidthPx: Int): Int {
        return if (screenWidthPx >= 1700) screenWidthPx else 0
    }

    fun getBufferWindowMultiplier(): Int {
        return 3
    }
}
