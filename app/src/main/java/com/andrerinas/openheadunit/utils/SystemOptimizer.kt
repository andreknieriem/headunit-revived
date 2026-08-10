package com.andrerinas.openheadunit.utils

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.sqrt

class SystemOptimizer(private val context: Context) {

    data class OptimizationResult(
        val recommendedResolutionId: Int,
        val recommendedDpi: Int,
        val recommendedVideoCodec: String,
        val recommendedViewMode: Settings.ViewMode,
        val isWidescreen: Boolean,
        val suggestedOrientation: Settings.ScreenOrientation,
        val h265Support: Boolean
    )

    enum class DisplaySizePreset(val diagonalInch: Float) {
        PHONE_4_6(6.0f),
        SMALL_7_8(7.5f),
        STANDARD_9_10(10.0f),
        LARGE_11_PLUS(12.5f)
    }

    fun checkH265HardwareSupport(): Boolean {
        return com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcSupported()
    }

    fun calculateOptimalSettings(
        sizePreset: DisplaySizePreset,
        isPortraitTarget: Boolean
    ): OptimizationResult {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.display?.getRealMetrics(metrics)
            // getRealMetrics is API 17; on API 16 it does not exist, so fall back to getMetrics.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                windowManager.defaultDisplay.getRealMetrics(metrics)
            else -> windowManager.defaultDisplay.getMetrics(metrics)
        }

        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val aspectRatio = if (width > height) width / height else height / width

        val hasH265 = checkH265HardwareSupport()

        // 1. Resolution: the single "panel ceiling" source of truth (see panelCeiling), so the
        // recommended resolution, the runtime cap and the DPI below all agree.
        val panelCeil = panelCeiling(metrics.widthPixels, metrics.heightPixels, hasH265)

        // 2. DPI Strategy. Derive the density from the resolution Android Auto will actually draw
        // (the panel-capped resolution), not the raw panel pixels: when we downscale a small
        // high-DPI panel, the surface AA renders is smaller, so using the panel pixels over-reports
        // the density (issue #767). Diagonal of the effective surface / physical inches, nudged up
        // a little for the car viewing distance.
        val effectiveDiagonalPx = sqrt(
            (panelCeil.width * panelCeil.width + panelCeil.height * panelCeil.height).toFloat()
        )
        val calculatedDpi = (effectiveDiagonalPx / sizePreset.diagonalInch) * LEGIBILITY_FACTOR
        var recDpi = calculatedDpi.toInt()

        // 3. View Mode Recommendation
        val recViewMode = when {
            // Very old devices (Android 4.x) often have distortion issues with SurfaceView,
            // so we recommend TextureView instead.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP -> Settings.ViewMode.TEXTURE

            // 5.0 - 8.1: start on the safe, observable TextureView rather than forcing SurfaceView
            // up front. SurfaceView renders directly but is a blind spot for the display-stall
            // watchdog (it reports no drawn frames), so if it happens to be broken on a device the
            // user is stranded with no automatic recovery (issue #767). TextureView failures ARE
            // observable, so on the MediaTek MDP devices where the external-texture path collapses
            // to a few fps (issue #650) the watchdog detects the stall and escalates to SurfaceView
            // on its own. GLES is only kept for devices without hardware HEVC, which may fall back
            // to the software YUV sink that GLES provides.
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ->
                if (hasH265) Settings.ViewMode.TEXTURE else Settings.ViewMode.GLES

            // Modern devices are usually fine with the default TextureView
            else -> Settings.ViewMode.TEXTURE
        }
        AppLog.i("SystemOptimizer: SoC=${Build.HARDWARE}/${Build.BOARD} API=${Build.VERSION.SDK_INT} hasHwHevc=$hasH265 -> recommended viewMode=$recViewMode")

        // 4. Apply orientation-based caps
        if (isPortraitTarget) {
            recDpi = recDpi.coerceAtMost(190)
        } else {
            recDpi = recDpi.coerceAtMost(240)
        }

        recDpi = recDpi.coerceAtLeast(110)

        // Default to H.264. Many head units report HEVC support they cannot actually play back, so
        // only recommend H.265 when the panel is above Full HD (1440p/4K), where H.264 bandwidth
        // stops being practical and the saving is worth the risk. panelCeil.width > 1920 means the
        // panel warrants more than 1080p.
        val recommendedCodec = if (hasH265 && panelCeil.width > 1920) "H.265" else "H.264"

        return OptimizationResult(
            recommendedResolutionId = panelCeil.id,
            recommendedDpi = recDpi,
            recommendedVideoCodec = recommendedCodec,
            recommendedViewMode = recViewMode,
            isWidescreen = aspectRatio > 1.7f,
            suggestedOrientation = if (isPortraitTarget) Settings.ScreenOrientation.PORTRAIT else Settings.ScreenOrientation.LANDSCAPE,
            h265Support = hasH265
        )
    }

    companion object {
        /**
         * Small upward nudge on the computed density so the Android Auto UI reads well at the car
         * viewing distance (the panel is farther from the eyes than a phone). Kept modest so the
         * result stays near the ~150-170 dpi range that works well on head units instead of the
         * larger, more cramped UI a raw physical-DPI value would give.
         */
        private const val LEGIBILITY_FACTOR = 1.1f

        /**
         * The single source of truth for the largest resolution a physical panel warrants, shared
         * by the recommended resolution, the runtime resolution cap (HeadUnitScreenConfig) and the
         * DPI calculation, so all three always agree (issue #767). Bucketed by the panel's real
         * pixels, in landscape terms (long side x short side); 1440p/4K are gated behind hardware
         * HEVC on a recent enough Android, matching what the phone will actually stream. Mild
         * downscaling (e.g. 1080p onto a 1024x600 panel) is allowed on purpose; only genuinely
         * small panels are dropped to 720p/480p, which avoids the heavy per-frame downscale that
         * overloads the MediaTek MDP scaler (issue #650).
         */
        fun panelCeiling(realWidthPx: Int, realHeightPx: Int, canHevc: Boolean): Settings.Resolution {
            val longSide = maxOf(realWidthPx, realHeightPx)
            val shortSide = minOf(realWidthPx, realHeightPx)
            val hevcHiRes = canHevc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            return when {
                longSide <= 0 -> Settings.Resolution._1280x720
                longSide <= 800 && shortSide <= 480 -> Settings.Resolution._800x480
                (longSide >= 3840 || shortSide >= 2160) && hevcHiRes -> Settings.Resolution._3840x2160
                (longSide >= 2560 || shortSide >= 1440) && hevcHiRes -> Settings.Resolution._2560x1440
                longSide > 1280 || shortSide > 720 -> Settings.Resolution._1920x1080
                else -> Settings.Resolution._1280x720
            }
        }

        /**
         * The resolution the panel warrants. Thin wrapper over [panelCeiling] (the shared source of
         * truth) so the recommended label, the wizard and the runtime cap never diverge.
         */
        fun recommendedResolution(realWidthPx: Int, realHeightPx: Int): Settings.Resolution =
            panelCeiling(realWidthPx, realHeightPx, com.andrerinas.openheadunit.decoder.VideoDecoder.isHevcSupported())
    }
}
