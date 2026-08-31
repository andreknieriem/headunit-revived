package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton

object HomeUiHelper {

    fun applyButtonScale(
        rootView: View,
        scalePercent: Int,
        isPortrait: Boolean,
        density: Float
    ) {
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val scaleFactor = validScale / 100.0f

        val selfBtn = rootView.findViewById<View>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<View>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<View>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<View>(R.id.settings_button)
        val buttons = listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn)

        if (isPortrait) {
            val basePaddingDp = 12f
            val adjustedPaddingPx = ((basePaddingDp * (2.0f - scaleFactor)).coerceIn(4f, 16f) * density).toInt()
            buttons.forEach { button ->
                (button.parent as? View)?.setPadding(adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx)
            }
        } else {
            val baseMarginDp = 40f
            val adjustedMarginPx = ((baseMarginDp * (2.0f - scaleFactor)).coerceIn(12f, 48f) * density).toInt()
            buttons.forEach { button ->
                val params = button.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    params.setMargins(adjustedMarginPx, adjustedMarginPx, adjustedMarginPx, adjustedMarginPx)
                    button.layoutParams = params
                }
            }
        }

        val mainButtonsLayout = rootView.findViewById<View>(R.id.main_buttons_layout)
        if (mainButtonsLayout != null) {
            mainButtonsLayout.scaleX = scaleFactor
            mainButtonsLayout.scaleY = scaleFactor
        }
    }

    fun applyButtonStyles(
        context: Context,
        rootView: View,
        settings: Settings,
        isNightActive: Boolean
    ) {
        val selfBtn = rootView.findViewById<MaterialButton>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<MaterialButton>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<MaterialButton>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<MaterialButton>(R.id.settings_button)
        val buttons = listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn)

        val isDarkTheme = settings.appTheme == Settings.AppTheme.DARK ||
                settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                isNightActive

        if (isDarkTheme && settings.monochromeIcons) {
            val monochromeBackground = ContextCompat.getDrawable(context, R.drawable.gradient_monochrome)
            val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
            buttons.forEach { button ->
                button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
                button.iconTint = grayTint
            }
        } else {
            val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val configs = listOf(
                Triple(selfBtn, R.drawable.gradient_blue, settings.customSelfModeButtonColor),
                Triple(usbBtn, R.drawable.gradient_orange, settings.customUsbButtonColor),
                Triple(wifiBtn, R.drawable.gradient_purple, settings.customWifiButtonColor),
                Triple(settingsBtn, R.drawable.gradient_darkblue, settings.customSettingsButtonColor)
            )
            configs.forEach { (button, defaultDrawableRes, customColor) ->
                if (button != null) {
                    if (customColor != 0) {
                        button.background = ColorUtils.createGradientDrawable(customColor, 32f, context)
                    } else {
                        button.background = ContextCompat.getDrawable(context, defaultDrawableRes)
                    }
                    button.iconTint = whiteTint
                }
            }
        }
    }
}
