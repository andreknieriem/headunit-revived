package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.contract.KeyIntent
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.GlProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI
import android.content.IntentFilter
import com.andrerinas.headunitrevived.view.ProjectionViewScaler
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private var isSurfaceSet = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false
    private var fpsTextView: TextView? = null
    
    private var isOrientationReceiverRegistered = false
    private var isNightModeReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isKeyEventReceiverRegistered = false

    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            val loadingOverlay = findViewById<View>(R.id.loading_overlay)
            if (loadingOverlay?.visibility == View.VISIBLE && commManager.isConnected) {
                // If the decoder already rendered something, hide the overlay immediately
                if (videoDecoder.lastFrameRenderedMs > 0) {
                    AppLog.i("Watchdog: Decoder is already rendering frames. Hiding overlay.")
                    loadingOverlay.visibility = View.GONE
                    overlayState = OverlayState.HIDDEN
                    return
                }

                AppLog.w("Watchdog: No video received yet. Requesting Keyframe (Unsolicited Focus)...")
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                watchdogHandler.postDelayed(this, 1500)
            }
        }
    }
    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            // Only run watchdog if we are actually supposed to be connected
            if (commManager.connectionState.value !is CommManager.ConnectionState.HandshakeComplete) {
                return
            }
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay
                watchdogHandler.postDelayed(this, 2000)
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastFrame
            if (overlayState == OverlayState.HIDDEN && gap > 10000) {
                showReconnectingOverlay()
            } else if (overlayState == OverlayState.RECONNECTING && gap < 2000) {
                hideReconnectingOverlay()
            }
            watchdogHandler.postDelayed(this, 2000)
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
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
        } else if (projectionView is GlProjectionView) {
             val gles = projectionView as GlProjectionView
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

    private val nightModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isNight = intent.getBooleanExtra("isNight", false)
            updateDesaturation(isNight)
        }
    }

    private fun updateDesaturation(isNight: Boolean) {
        if (settings.aaMonochromeEnabled && projectionView is GlProjectionView) {
            val level = if (isNight) settings.aaDesaturationLevel / 100f else 0f
            (projectionView as GlProjectionView).setDesaturation(level)
        } else if (projectionView is GlProjectionView) {
            (projectionView as GlProjectionView).setDesaturation(0f)
        }
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("AapProjectionActivity: Orientation change broadcast received. Updating.")
                applyOrientationSettings()
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = IntentCompat.getParcelableExtra(intent, KeyIntent.extraEvent, KeyEvent::class.java)
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("AapProjectionActivity: Received finish request. Closing.")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientationSettings()


        setContentView(R.layout.activity_headunit)

        if (settings.showFpsCounter) {
            val container = findViewById<FrameLayout>(R.id.container)
            fpsTextView = TextView(this).apply {
                setTextColor(Color.YELLOW)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(10, 5, 10, 5)
                text = "FPS: --"
                // Lift it above everything
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 100f
                    translationZ = 100f
                }
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(20, 20, 0, 0)
            }
            container.addView(fpsTextView, params)

            videoDecoder.onFpsChanged = { fps ->
                runOnUiThread { fpsTextView?.text = "FPS: $fps" }
            }
        }

        videoDecoder.dimensionsListener = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        var isFirstEmission = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    val first = isFirstEmission
                    isFirstEmission = false
                    
                    if (first && state is CommManager.ConnectionState.Disconnected) {
                        AppLog.i("AapProjectionActivity: Ignoring initial Disconnected state from StateFlow replay.")
                        return@collect
                    }

                    when (state) {
                        is CommManager.ConnectionState.Disconnected -> {
                            watchdogHandler.removeCallbacksAndMessages(null)
                            if (!state.isClean && !state.isUserExit) {
                                AppLog.w("AapProjectionActivity: Disconnected unexpectedly.")
                                Toast.makeText(this@AapProjectionActivity, getString(R.string.wifi_disconnect_toast), Toast.LENGTH_LONG).show()
                            }
                            // Only finish immediately if the user explicitly exited or it was a clean close.
                            if (state.isUserExit || state.isClean) {
                                AppLog.i("AapProjectionActivity: Finishing because state isUserExit=${state.isUserExit}, isClean=${state.isClean}")
                                hideReconnectingOverlay()
                                finish()
                            } else {
                                // For unexpected disconnects (especially Wireless), wait a tiny bit to see if service restarts it
                                watchdogHandler.postDelayed({
                                    if (commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
                                        AppLog.i("AapProjectionActivity: Finishing after delay due to Disconnected state.")
                                        hideReconnectingOverlay()
                                        finish()
                                    }
                                }, 2000)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            // Lock the resolution so that orientation changes don't cause re-negotiation
                            HeadUnitScreenConfig.lockResolution()
                            
                            // Handshake done. If the surface is already ready (e.g. reconnect
                            // while the activity is in the foreground), start reading immediately.
                            // If not, onSurfaceChanged() will call startReading() when the surface
                            // becomes available.
                            if (isSurfaceSet) {
                                commManager.startReading()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
        
        ContextCompat.registerReceiver(this, finishReceiver, android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"), ContextCompat.RECEIVER_NOT_EXPORTED)
        isFinishReceiverRegistered = true

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
            container.setBackgroundColor(Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as View
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
        
        // [FIX] If we are already connected and frames are flowing (e.g. activity recreation),
        // hide the overlay immediately to prevent the "Android Auto is starting" flicker.
        if (commManager.isConnected && videoDecoder.lastFrameRenderedMs > 0) {
            loadingOverlay?.visibility = View.GONE
            overlayState = OverlayState.HIDDEN
        }

        // Ensure loading overlay is on top of everything
        loadingOverlay?.bringToFront()

        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener {
            commManager.disconnect()
        }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                loadingOverlay?.visibility = View.GONE
                overlayState = OverlayState.HIDDEN

                // Show one-time gesture hint
                if (!settings.gestureHintShown) {
                    Toast.makeText(this@AapProjectionActivity, R.string.gesture_hint, Toast.LENGTH_LONG).show()
                    settings.gestureHintShown = true
                }
            }
        }

        commManager.onUpdateUiConfigReplyReceived = {
            AppLog.i("[UI_DEBUG_FIX] UpdateUiConfig reply received. AA acknowledged new margins.")
        }
    }

    override fun onPause() {
        isForeground = false
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
        if (isNightModeReceiverRegistered) {
            unregisterReceiver(nightModeReceiver)
            isNightModeReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        AppLog.i("AapProjectionActivity: onResume")
        applyStickyOrientation()
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)


        if (!isKeyEventReceiverRegistered) {
            ContextCompat.registerReceiver(this, keyEventReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_EXPORTED)
            isKeyEventReceiverRegistered = true
        }

        // Register orientation receiver
        ContextCompat.registerReceiver(this, orientationReceiver, IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // Register night mode receiver for AA monochrome filter
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isNightModeReceiverRegistered = true

        // Request current night mode state for initial desaturation
        sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(packageName)
        })

        setFullscreen()
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

    private fun showReconnectingOverlay() {
        AppLog.i("Showing reconnecting overlay")
        overlayState = OverlayState.RECONNECTING
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val title = findViewById<TextView>(R.id.overlay_text)
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.VISIBLE
        title?.text = getString(R.string.connection_interrupted)
        detail?.text = getString(R.string.connection_interrupted_detail)
        detail?.visibility = View.VISIBLE
        button?.visibility = View.VISIBLE
    }

    private fun hideReconnectingOverlay() {
        AppLog.i("Hiding reconnecting overlay — frames resumed")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, settings.fullscreenMode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        SystemUI.apply(window, container, settings.fullscreenMode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }
                    }, 2000)
                }
            }
        }
    }

    private data class ExitOption(val titleResId: Int, val iconResId: Int, val iconColor: Int)

    private fun showExitDialog() {
        val options = mutableListOf<ExitOption>()
        options.add(ExitOption(R.string.exit_dialog_stop, R.drawable.ic_stop, Color.RED))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.add(ExitOption(R.string.exit_dialog_pip, R.drawable.ic_pip, Color.LTGRAY))
        }
        
        options.add(ExitOption(R.string.exit_dialog_background, R.drawable.ic_home, Color.LTGRAY))

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.dialog_exit_item, parent, false)
                val option = options[position]
                val iconView = view.findViewById<android.widget.ImageView>(R.id.icon)
                val textView = view.findViewById<android.widget.TextView>(R.id.text)
                
                textView.setText(option.titleResId)
                iconView.setImageResource(option.iconResId)
                iconView.setColorFilter(option.iconColor)
                
                return view
            }
        }

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setAdapter(adapter) { _, which ->
                val selected = options[which]
                when (selected.titleResId) {
                    R.string.exit_dialog_stop -> {
                        commManager.disconnect(sendByeBye = true)
                        finish()
                    }
                    R.string.exit_dialog_pip -> {
                        enterPiP()
                    }
                    R.string.exit_dialog_background -> {
                        moveToBackground()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                var width = videoDecoder.videoWidth.coerceAtLeast(1).toFloat()
                var height = videoDecoder.videoHeight.coerceAtLeast(1).toFloat()
                val ratio = width / height
                
                // Android supports PiP aspect ratios between 1/2.39 (0.418) and 2.39.
                // If we exceed this (e.g. on ultrawide headunits), PiP entry will fail.
                if (ratio > 2.39f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too wide, clamping to 2.39")
                    width = height * 2.39f
                } else if (ratio < 0.418f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too narrow, clamping to 0.418")
                    height = width / 0.418f
                }

                val paramsBuilder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(width.toInt(), height.toInt()))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Smooth transition for Android 12+
                    paramsBuilder.setAutoEnterEnabled(true)
                    paramsBuilder.setSeamlessResizeEnabled(true)
                }

                App.isPiPActive = true
                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                AppLog.e("Failed to enter PiP mode: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this, "PiP failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            AppLog.w("PiP mode not supported on this Android version (SDK < 26)")
        }
    }

    private fun moveToBackground() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        App.isPiPActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Hide UI elements during PiP (like FPS counter, loading overlay)
            findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
            fpsTextView?.visibility = View.GONE
        } else {
            // Restore UI if needed
            fpsTextView?.visibility = if (settings.showFpsCounter) View.VISIBLE else View.GONE
            setFullscreen()
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home
        
        // For now, we only enter via dialog as requested.
        super.onUserLeaveHint()
    }

    private val commManager get() = App.provide(this).commManager

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 1. 2-finger swipe detection from the left edge (to open exit menu)
        if (ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    initialX = ev.getX(0)
                    initialY = ev.getY(0)
                    isPotentialGesture = initialX < 100
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPotentialGesture) {
                        val deltaX = ev.getX(0) - initialX
                        val deltaY = Math.abs(ev.getY(0) - initialY)
                        if (deltaX > 200 && deltaY < 100) {
                            isPotentialGesture = false
                            showExitDialog()
                            return true // Consume
                        }
                    }
                }
            }
        }
        
        // 2. Legacy Touch handling for older devices (API < 19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(ev)
        }
        
        return super.dispatchTouchEvent(ev)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true
        
        videoDecoder.setSurface(surface)

        // --- Surface Mismatch Detection ---
        // Compare actual surface dimensions with what HeadUnitScreenConfig negotiated.
        // If they differ (e.g. system bars appeared/disappeared), update margins.
        val prevUsableW = HeadUnitScreenConfig.getUsableWidth()
        val prevUsableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.updateSurfaceDimensions(width, height)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch! Expected: ${prevUsableW}x${prevUsableH}, Actual: ${width}x${height}")

            // Cache the real surface size for next session
            settings.cachedSurfaceWidth = width
            settings.cachedSurfaceHeight = height
            settings.cachedSurfaceSettingsHash = HeadUnitScreenConfig.computeSettingsHash(settings)

            if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                // AA is already running → send corrected per-side margins dynamically
                commManager.sendUpdateUiConfigRequest(
                    HeadUnitScreenConfig.getLeftMargin(),
                    HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(),
                    HeadUnitScreenConfig.getBottomMargin()
                )
                AppLog.i("[UI_DEBUG_FIX] AA is already running, send corrected via sendUpdateUiConfigRequest")
            }
            // If transport not started yet, ServiceDiscoveryResponse will use the corrected values automatically.
        }

        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> {
                // AapService should have started the handshake already, but as a fallback
                // (e.g. service restarted) kick it off here. The HandshakeComplete observer
                // will call startReading() once the handshake finishes.
                lifecycleScope.launch { commManager.startHandshake() }
            }
            is CommManager.ConnectionState.StartingTransport -> {
                // Handshake is in progress. The HandshakeComplete observer will call
                // startReading() when it finishes.
            }
            is CommManager.ConnectionState.HandshakeComplete -> {
                // Handshake already done before surface was ready — start reading now.
                lifecycleScope.launch { commManager.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                // Surface recreated while transport was already running; request a keyframe.
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            else -> {
                commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
            }
        }

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                ProjectionViewScaler.updateScale(projectionView as View, currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }


    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val videoW = HeadUnitScreenConfig.getNegotiatedWidth()
        val videoH = HeadUnitScreenConfig.getNegotiatedHeight()

        if (videoW <= 0 || videoH <= 0 || projectionView !is View) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config or view not ready.")
            return
        }

        val view = projectionView as View
        // Use the container's "Anchor" dimensions (full touch surface) as the reference, 
        // not the potentially resized projectionView's dimensions.
        val viewW = HeadUnitScreenConfig.getUsableWidth().toFloat()
        val viewH = HeadUnitScreenConfig.getUsableHeight().toFloat()

        if (viewW <= 0 || viewH <= 0) return

        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()

        val uiW = videoW - marginW
        val uiH = videoH - marginH

        // Logic check: When forcedScale is active, the visual behavior of 'stretchToFill' 
        // is inverted (True = Aspect Ratio Centered, False = Stretched to Screen).
        // We adjust the touch mapping to match this visual reality.
        val isStretch = if (HeadUnitScreenConfig.forcedScale) {
            !settings.stretchToFill 
        } else {
            settings.stretchToFill
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val px = event.getX(pointerIndex)
            val py = event.getY(pointerIndex)
            
            var videoX = 0f
            var videoY = 0f

            if (isStretch) {
                videoX = (px / viewW) * uiW
                videoY = (py / viewH) * uiH
            } else {
                val uiRatio = uiW / uiH
                val viewRatio = viewW / viewH

                var displayedUiW = viewW
                var displayedUiH = viewH

                if (viewRatio > uiRatio) {
                    displayedUiW = viewH * uiRatio
                } else {
                    displayedUiH = viewW / uiRatio
                }

                val uiLeft = (viewW - displayedUiW) / 2f
                val uiTop = (viewH - displayedUiH) / 2f

                val localX = px - uiLeft
                val localY = py - uiTop

                videoX = (localX / displayedUiW) * uiW
                videoY = (localY / displayedUiH) * uiH
            }

            // Clamp to negotiated bounds to prevent out-of-bounds touches
            val correctedX = videoX.toInt().coerceIn(0, videoW)
            val correctedY = videoY.toInt().coerceIn(0, videoH)

            pointerData.add(Triple(pointerId, correctedX, correctedY))
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }

        // 1. Let the system handle volume and back keys
        if (event.keyCode == KeyEvent.KEYCODE_BACK || 
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || 
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // 2. Funnel all other keys to CommManager
        commManager.sendKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        // Broadcasts (e.g. from CarKeyReceiver) still use this path.
        commManager.sendKey(keyCode, isPress)
    }

    private fun applyStickyOrientation() {
        if (settings.screenOrientation == Settings.ScreenOrientation.AUTO && HeadUnitScreenConfig.isResolutionLocked) {
            val target = if (HeadUnitScreenConfig.getNegotiatedWidth() > HeadUnitScreenConfig.getNegotiatedHeight()) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (requestedOrientation != target) {
                AppLog.i("[UI_DEBUG] Sticky Orientation: Session active, forcing orientation to $target")
                requestedOrientation = target
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        App.isPiPActive = false
        videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        @Volatile var isForeground = false

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
    private fun applyOrientationSettings() {
        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            applyStickyOrientation()
            if (!HeadUnitScreenConfig.isResolutionLocked) {
                // Initial start: lock to current orientation at launch
                if (Build.VERSION.SDK_INT >= 18) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                }
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }
    }
}