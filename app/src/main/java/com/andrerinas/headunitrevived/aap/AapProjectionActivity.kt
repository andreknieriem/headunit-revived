package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters

class AapProjectionActivity : SurfaceActivity(), SurfaceHolder.Callback {

    private lateinit var surface: SurfaceView // Added lateinit var for surface
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_headunit) // Set content view

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid. All Rights Reserved...")

        surface = findViewById(R.id.surface) // Initialize surface
        surface.holder.addCallback(this) // Changed setSurfaceCallback to holder.addCallback
        surface.setOnTouchListener { _, event ->
            sendTouchEvent(event)
            true
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(disconnectReceiver)
        unregisterReceiver(keyCodeReceiver)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(disconnectReceiver, IntentFilters.disconnect, RECEIVER_NOT_EXPORTED)
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, IntentFilters.disconnect)
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent)
        }
    }

    val transport: AapTransport
        get() = App.provide(this).transport

    override fun surfaceCreated(holder: SurfaceHolder) {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        AppLog.i("AapProjectionActivity: surfaceCreated width=$width, height=$height")
        videoDecoder.onSurfaceHolderAvailable(holder, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        transport.send(VideoFocusEvent(gain = true, unsolicited = false))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        transport.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val displayMetrics = resources.displayMetrics
        val scaleX = displayMetrics.widthPixels.toFloat() / surface.width.toFloat()
        val scaleY = displayMetrics.heightPixels.toFloat() / surface.height.toFloat()

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val x = event.getX(pointerIndex) * scaleX
            val y = event.getY(pointerIndex) * scaleY

            // Boundary check against display metrics
            if (x < 0 || x >= displayMetrics.widthPixels || y < 0 || y >= displayMetrics.heightPixels) {
                AppLog.i("Touch event out of bounds, skipping.")
                return
            }
            pointerData.add(Triple(pointerId, x.toInt(), y.toInt()))
        }

        transport.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("KeyCode: %d", keyCode)
        // PRes navigation on the screen
        onKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyUp: %d", keyCode)
        onKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        transport.send(keyCode, isPress)
    }

    companion object {
        const val EXTRA_FOCUS = "focus"

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
}
