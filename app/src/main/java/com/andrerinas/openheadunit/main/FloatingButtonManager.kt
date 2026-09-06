package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.roundToInt

@SuppressLint("StaticFieldLeak")
object FloatingButtonManager {

    private var overlayView: View? = null
    private var isAppForeground: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onAppForegroundChanged(context: Context, isForeground: Boolean) {
        isAppForeground = isForeground
        mainHandler.post {
            update(context)
        }
    }

    fun update(context: Context) {
        val settings = App.provide(context).settings

        val enabled = settings.enableFloatingButton
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AndroidSettings.canDrawOverlays(context)
        } else {
            true
        }

        // Button should ONLY show if enabled, permission granted, and app is in BACKGROUND
        val shouldShow = enabled && hasPermission && !isAppForeground

        if (!shouldShow) {
            removeOverlay(context)
            return
        }

        val windowManager = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        val density = displayMetrics.density

        val sizePx = (settings.floatingButtonSizeDp * density).roundToInt().coerceAtLeast((32 * density).roundToInt())
        val maxX = (displayMetrics.widthPixels - sizePx).coerceAtLeast(0)
        val maxY = (displayMetrics.heightPixels - sizePx).coerceAtLeast(0)

        val xPx = ((settings.floatingButtonXPercent / 100f) * maxX).roundToInt()
        val yPx = ((settings.floatingButtonYPercent / 100f) * maxY).roundToInt()
        val alpha = (settings.floatingButtonOpacityPercent / 100f).coerceIn(0.0f, 1.0f)

        if (overlayView == null) {
            val button = ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(R.drawable.bg_floating_button)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 8f * density
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
            }

            val layoutParams = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = xPx
                y = yPx
            }

            var lastClickTime = 0L
            button.setOnClickListener {
                if (settings.floatingButtonDoubleTap) {
                    val uptime = SystemClock.uptimeMillis()
                    if (uptime - lastClickTime < ViewConfiguration.getDoubleTapTimeout()) {
                        lastClickTime = 0L
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(intent)
                    } else {
                        lastClickTime = uptime
                    }
                } else {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                }
            }

            button.alpha = alpha

            try {
                windowManager.addView(button, layoutParams)
                overlayView = button
                AppLog.i("FloatingButtonManager: Added floating button overlay")
            } catch (e: Exception) {
                AppLog.w("FloatingButtonManager: Failed to add overlay view (${e.message})")
            }
        } else {
            // Update existing view properties
            val button = (overlayView as? ImageView) ?: return
            val layoutParams = (button.layoutParams as? WindowManager.LayoutParams) ?: return

            layoutParams.width = sizePx
            layoutParams.height = sizePx
            layoutParams.x = xPx
            layoutParams.y = yPx
            button.alpha = alpha

            try {
                windowManager.updateViewLayout(button, layoutParams)
            } catch (e: Exception) {
                AppLog.w("FloatingButtonManager: Failed to update overlay layout (${e.message})")
            }
        }
    }

    fun removeOverlay(context: Context) {
        val view = overlayView ?: return
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.removeView(view)
            AppLog.i("FloatingButtonManager: Removed floating button overlay")
        } catch (e: Exception) {
            AppLog.w("FloatingButtonManager: Failed to remove overlay view (${e.message})")
        } finally {
            overlayView = null
        }
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !AndroidSettings.canDrawOverlays(context)) {
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
