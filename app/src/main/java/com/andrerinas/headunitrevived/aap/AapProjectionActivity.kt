package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private var isSurfaceSet = false
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            val loadingOverlay = findViewById<View>(R.id.loading_overlay)
            if (loadingOverlay?.visibility == View.VISIBLE && AapService.isConnected) {
                AppLog.w("Watchdog: No video received. Requesting Keyframe (Unsolicited Focus)...")
                transport.send(VideoFocusEvent(gain = true, unsolicited = true))
                watchdogHandler.postDelayed(this, 3000)
            }
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
        }
    }
    private var retryButton: Button? = null
    private val retryTimerRunnable = Runnable {
        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        if (loadingOverlay?.visibility == View.VISIBLE) {
            retryButton?.visibility = View.VISIBLE
        }
    }

    private fun checkAndForceSurface() {
        AppLog.i("Watchdog: checkAndForceSurface executing...")
        if (projectionView is TextureView) {
            val tv = projectionView as TextureView
            if (tv.isAvailable) {
                AppLog.w("Watchdog: TextureView IS available. Forcing onSurfaceChanged.")
                onSurfaceChanged(android.view.Surface(tv.surfaceTexture), tv.width, tv.height)
            } else {
                AppLog.e("Watchdog: TextureView NOT available. Vis=${tv.visibility}, W=${tv.width}, H=${tv.height}")
            }
        } else if (projectionView is com.andrerinas.headunitrevived.view.GlProjectionView) {
             val gles = projectionView as com.andrerinas.headunitrevived.view.GlProjectionView
             if (gles.isSurfaceValid()) {
                 AppLog.w("Watchdog: GlProjectionView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(gles.getSurface()!!, gles.width, gles.height)
             } else {
                 AppLog.e("Watchdog: GlProjectionView NOT valid.")
             }
        } else if (projectionView is ProjectionView) {
             val sv = projectionView as ProjectionView
             if (sv.holder.surface.isValid) {
                 AppLog.w("Watchdog: SurfaceView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(sv.holder.surface, sv.width, sv.height)
             } else {
                 AppLog.e("Watchdog: SurfaceView NOT valid.")
             }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLog.i("AapProjectionActivity received disconnect signal, finishing.")
            finish()
        }
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            enableEdgeToEdge()
        }
        super.onCreate(savedInstanceState)

        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            // AUTO mode: lock to current orientation at launch (existing behavior)
            if (Build.VERSION.SDK_INT >= 18) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }

        setContentView(R.layout.activity_headunit)

        // Register disconnect receiver safely for Android 14+
        ContextCompat.registerReceiver(this, disconnectReceiver, IntentFilters.disconnect, ContextCompat.RECEIVER_NOT_EXPORTED)

        videoDecoder.dimensionsListener = this

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid., since 2025 André Rinas All Rights Reserved...")

        val container = findViewById<android.widget.FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(android.graphics.Color.BLACK)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = com.andrerinas.headunitrevived.view.GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(android.graphics.Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as android.view.View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as android.view.View
        container.addView(view)

        projectionView.addCallback(this)

        val overlayView = OverlayTouchView(this)
        overlayView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        overlayView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    overlayView.requestFocus()
                }
                sendTouchEvent(event)
                true
            }

        container.addView(overlayView)
        overlayView.requestFocus()
        setFullscreen() // Call setFullscreen here as well

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        // Ensure loading overlay is on top of everything
        loadingOverlay?.bringToFront()
        
        retryButton = findViewById(R.id.retry_button)
        retryButton?.setOnClickListener { showRetryDialog() }
        watchdogHandler.postDelayed(retryTimerRunnable, RETRY_DELAY_MS)

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                loadingOverlay?.visibility = View.GONE
                retryButton?.visibility = View.GONE
                watchdogHandler.removeCallbacks(retryTimerRunnable)
            }
        }
    }

    override fun onPause() {
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(retryTimerRunnable)
        unregisterReceiver(keyCodeReceiver)
        // Disconnect receiver is unregistered in onDestroy
    }

    override fun onResume() {
        AppLog.i("AapProjectionActivity: onResume")
        super.onResume()
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        if (loadingOverlay?.visibility == View.VISIBLE && retryButton?.visibility != View.VISIBLE) {
            watchdogHandler.postDelayed(retryTimerRunnable, RETRY_DELAY_MS)
        }
        
        // Register key event receiver safely for Android 14+
        ContextCompat.registerReceiver(this, keyCodeReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        setFullscreen() // Call setFullscreen here as well

        // Pre-emptively request audio focus to push background apps away
        App.provide(this).transport.aapAudio?.requestFocusChange(
            android.media.AudioManager.STREAM_MUSIC,
            com.andrerinas.headunitrevived.aap.protocol.proto.Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE,
            android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
                AppLog.i("Activity AudioFocus pre-emptive change: $focusChange")
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AapProjectionActivity: onNewIntent received")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
        }
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, settings.fullscreenMode)

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        SystemUI.apply(window, container, settings.fullscreenMode)
                    }, 2000)
                }
            }
        }
    }

    val transport: AapTransport
        get() = App.provide(this).transport

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true
        
        // Reduced delay from 750ms to 150ms to catch the first I-Frame
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            AppLog.i("Delayed setting surface to decoder")
            videoDecoder.setSurface(surface)

            // Simply request focus to ensure stream is active
            transport.send(VideoFocusEvent(gain = true, unsolicited = false))
            
        }, 150)

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                projectionView.setVideoScale(HeadUnitScreenConfig.getScaleX(), HeadUnitScreenConfig.getScaleY())
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        transport.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            projectionView.setVideoScale(HeadUnitScreenConfig.getScaleX(), HeadUnitScreenConfig.getScaleY())
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val horizontalCorrection = HeadUnitScreenConfig.getHorizontalCorrection()
        val verticalCorrection = HeadUnitScreenConfig.getVerticalCorrection()

        if (horizontalCorrection <= 0 || verticalCorrection <= 0) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config not ready yet.")
            return
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)

            val correctedX = (x * horizontalCorrection).toInt()
            val correctedY = (y * verticalCorrection).toInt()

            pointerData.add(Triple(pointerId, correctedX, correctedY))
        }

        transport.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event)
        }
        onKeyEvent(keyCode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyUp(keyCode, event)
        }
        onKeyEvent(keyCode, false)
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // On older devices (API < 19), the system often consumes the first touch 
        // to show system bars. By handling it here, we ensure it's sent to AA.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        transport.send(keyCode, isPress)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        unregisterReceiver(disconnectReceiver)
        videoDecoder.dimensionsListener = null

        // Note: Disconnect is now only handled via explicit user action (Exit button)
        // or when the phone closes the connection. This prevents accidental closes
        // during task switching or launcher interaction.
    }

    private fun showRetryDialog() {
        val isUsb = settings.lastConnectionType == Settings.CONNECTION_TYPE_USB

        val options = mutableListOf(getString(R.string.retry_connection_option))
        if (isUsb) {
            options.add(getString(R.string.reset_usb_option))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.retry_connection_title)
            .setItems(options.toTypedArray()) { _, which ->
                val action = when (which) {
                    0 -> AapService.ACTION_RETRY_CONNECTION
                    1 -> AapService.ACTION_RESET_USB
                    else -> return@setItems
                }
                val intent = Intent(this, AapService::class.java).apply {
                    this.action = action
                }
                startService(intent)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        private const val RETRY_DELAY_MS = 10_000L

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
}