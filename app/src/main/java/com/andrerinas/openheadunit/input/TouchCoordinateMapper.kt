package com.andrerinas.openheadunit.input

import kotlin.math.roundToInt

data class TouchCoordinate(
    val x: Int,
    val y: Int
)

object TouchCoordinateMapper {
    fun map(
        rawX: Float,
        rawY: Float,
        inputSurfaceWidth: Float,
        inputSurfaceHeight: Float,
        negotiatedWidth: Int,
        negotiatedHeight: Int,
        marginWidth: Float,
        marginHeight: Float,
        stretchToFill: Boolean,
        hudMirroring: Boolean
    ): TouchCoordinate {
        val surfaceW = inputSurfaceWidth.coerceAtLeast(1f)
        val surfaceH = inputSurfaceHeight.coerceAtLeast(1f)
        val px = if (hudMirroring) surfaceW - rawX else rawX

        val uiW = negotiatedWidth - marginWidth
        val uiH = negotiatedHeight - marginHeight

        val videoX: Float
        val videoY: Float

        if (stretchToFill) {
            // Fix for Wide Pixel Aspect Ratio (Ultrawide 1920x720 using 1280 buffer)
            // If the surface is 1920 and the buffer is 1280, the stretch factor is 1.5.
            // A touch at 1920 should map to 1280.
            // (1920 / 1920) * 1280 = 1280.
            videoX = (px / surfaceW) * uiW
            videoY = (rawY / surfaceH) * uiH
        } else {
            val uiRatio = uiW / uiH
            val viewRatio = surfaceW / surfaceH

            var displayedUiW = surfaceW
            var displayedUiH = surfaceH

            if (viewRatio > uiRatio) {
                displayedUiW = surfaceH * uiRatio
            } else {
                displayedUiH = surfaceW / uiRatio
            }

            val uiLeft = (surfaceW - displayedUiW) / 2f
            val uiTop = (surfaceH - displayedUiH) / 2f

            val localX = px - uiLeft
            val localY = rawY - uiTop

            videoX = (localX / displayedUiW) * uiW
            videoY = (localY / displayedUiH) * uiH
        }

        return TouchCoordinate(
            x = videoX.roundToInt().coerceIn(0, negotiatedWidth),
            y = videoY.roundToInt().coerceIn(0, negotiatedHeight)
        )
    }
}
