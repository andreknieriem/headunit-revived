package com.andrerinas.openheadunit.view

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int, touchView: View? = null) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        val settings = App.provide(view.context).settings
        HeadUnitScreenConfig.init(view.context, view.resources.displayMetrics, settings)

        val usableW = HeadUnitScreenConfig.getUsableWidth()
        val usableH = HeadUnitScreenConfig.getUsableHeight()

        val mirrorFactor = if (settings.hudMirroring) -1.0f else 1.0f

        if (HeadUnitScreenConfig.forcedScale && view is ProjectionView) {
            val lp = view.layoutParams
            var paramsChanged = false

            // NOTE: For legacy forcedScale (SurfaceView), the 'stretchToFill' setting logic
            // is historically inverted compared to its name.
            if (settings.stretchToFill) {
                // stretchToFill = TRUE results in Aspect Ratio preservation (Centered with bars)
                val targetW = HeadUnitScreenConfig.getAdjustedWidth()
                val targetH = HeadUnitScreenConfig.getAdjustedHeight()

                if (lp.width != targetW || lp.height != targetH) {
                    lp.width = targetW
                    lp.height = targetH
                    paramsChanged = true
                }

                // Center the view in the usable area
                if (lp is FrameLayout.LayoutParams) {
                    if (lp.gravity != Gravity.CENTER) {
                        lp.gravity = Gravity.CENTER
                        paramsChanged = true
                    }
                }

                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f * mirrorFactor
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                touchView?.let { tv ->
                    val tlp = tv.layoutParams
                    if (tlp.width != targetW || tlp.height != targetH) {
                        tlp.width = targetW
                        tlp.height = targetH
                        tv.layoutParams = tlp
                    }
                    if (tlp is FrameLayout.LayoutParams && tlp.gravity != Gravity.CENTER) {
                        tlp.gravity = Gravity.CENTER
                        tv.layoutParams = tlp
                    }
                    tv.scaleX = 1.0f
                    tv.scaleY = 1.0f
                }

                AppLog.i("[UI_DEBUG] FORCED & STRETCH On: Resized view to ${targetW}x${targetH} (centered)")
            } else {
                // Mode B: Stretch to fill the usable area exactly (ignores aspect ratio)
                if (lp.width != usableW || lp.height != usableH) {
                    lp.width = usableW
                    lp.height = usableH
                    paramsChanged = true
                }

                if (lp is FrameLayout.LayoutParams) {
                    val targetGravity = Gravity.TOP or Gravity.START
                    if (lp.gravity != targetGravity) {
                        lp.gravity = targetGravity
                        paramsChanged = true
                    }
                }

                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f * mirrorFactor
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                touchView?.let { tv ->
                    val tlp = tv.layoutParams
                    if (tlp.width != usableW || tlp.height != usableH) {
                        tlp.width = usableW
                        tlp.height = usableH
                        tv.layoutParams = tlp
                    }
                    tv.scaleX = 1.0f
                    tv.scaleY = 1.0f
                }

                AppLog.i("[UI_DEBUG] FORCED & STRETCH Off: Resized view to match screen exactly: ${usableW}x${usableH}")
            }
        } else {
            // Modern way / TextureView: Use View scaling properties on a full-screen view
            val finalScaleX = HeadUnitScreenConfig.getScaleX() * mirrorFactor
            val finalScaleY = HeadUnitScreenConfig.getScaleY()

            val lp = view.layoutParams
            var paramsChanged = false

            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                paramsChanged = true
            }

            if (lp is FrameLayout.LayoutParams) {
                if (lp.gravity != Gravity.NO_GRAVITY) {
                    lp.gravity = Gravity.NO_GRAVITY
                    paramsChanged = true
                }
            }

            if (paramsChanged) {
                view.layoutParams = lp
            }

            // Normal centering for non-forced modes
            view.translationX = 0f
            view.translationY = 0f

            if (view is IProjectionView) {
                view.setVideoScale(finalScaleX, finalScaleY)
            } else {
                view.scaleX = finalScaleX
                view.scaleY = finalScaleY
            }

            // Ultrawide Sidebar Fix: Ensure touch overlay matches the physical projection area
            touchView?.let { tv ->
                if (HeadUnitScreenConfig.isUltrawideEnabled() && HeadUnitScreenConfig.getUsableWidth() >= 1700) {
                    val tlp = tv.layoutParams
                    val targetW = HeadUnitScreenConfig.getUsableWidth()
                    val targetH = HeadUnitScreenConfig.getUsableHeight()
                    if (tlp.width != targetW || tlp.height != targetH) {
                        tlp.width = targetW
                        tlp.height = targetH
                        tv.layoutParams = tlp
                    }
                    if (tlp is FrameLayout.LayoutParams && tlp.gravity != Gravity.CENTER) {
                        tlp.gravity = Gravity.CENTER
                        tv.layoutParams = tlp
                    }
                    tv.scaleX = 1.0f
                    tv.scaleY = 1.0f
                } else {
                    tv.scaleX = Math.abs(finalScaleX)
                    tv.scaleY = finalScaleY
                }
            }
            AppLog.i("[UI_DEBUG] Normal Scale. scaleX: $finalScaleX, scaleY: $finalScaleY")
        }
    }
}
