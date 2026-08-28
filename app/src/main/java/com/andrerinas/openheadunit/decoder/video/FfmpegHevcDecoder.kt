package com.andrerinas.openheadunit.decoder.video

import android.os.SystemClock
import android.view.Surface
import androidx.annotation.Keep
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

@Keep
class FfmpegHevcDecoder(
    private val surface: Surface?,
    private val yuvFrameSink: SoftwareYuvFrameSink?,
    private val width: Int,
    private val height: Int
) {
    private var nativeHandle: Long = 0

    val isStarted: Boolean
        get() = nativeHandle != 0L

    fun start(): Boolean {
        if (!libraryLoaded) {
            AppLog.e("FFmpeg HEVC decoder native library is not loaded")
            return false
        }
        if (!isAvailable()) {
            AppLog.e("FFmpeg HEVC decoder is not available. Package FFmpeg headers/libs for this ABI.")
            return false
        }
        if (yuvFrameSink == null && surface?.isValid != true) {
            AppLog.e("FFmpeg HEVC decoder cannot start: surface is invalid")
            return false
        }

        val threadCount = recommendedThreadCount()
        AppLog.i("FFmpeg HEVC decoder thread count: $threadCount")
        nativeHandle = nativeCreate(surface, this, yuvFrameSink != null, width, height, threadCount)
        if (nativeHandle == 0L) {
            AppLog.e("FFmpeg HEVC decoder failed to initialize")
            return false
        }
        return true
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int): Int {
        val handle = nativeHandle
        if (handle == 0L) return 0
        return nativeDecode(handle, buffer, offset, size, SystemClock.elapsedRealtimeNanos() / 1000L)
    }

    fun stop() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L && libraryLoaded) {
            nativeRelease(handle)
        }
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 6)
    }

    @Keep
    @Suppress("unused")
    private fun onNativeYuv420Frame(
        width: Int,
        height: Int,
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer,
        uStride: Int,
        vPlane: ByteBuffer,
        vStride: Int
    ): Boolean {
        return yuvFrameSink?.renderYuv420Frame(width, height, yPlane, yStride, uPlane, uStride, vPlane, vStride) == true
    }

    companion object {
        private val libraryLoaded: Boolean = try {
            System.loadLibrary("hur_soft_hevc")
            true
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e("Failed to load hur_soft_hevc", e)
            false
        }

        fun isAvailable(): Boolean {
            return libraryLoaded && nativeIsAvailable()
        }

        private external fun nativeIsAvailable(): Boolean
        private external fun nativeCreate(surface: Surface?, callback: FfmpegHevcDecoder, useYuvCallback: Boolean, width: Int, height: Int, threadCount: Int): Long
        private external fun nativeDecode(handle: Long, buffer: ByteArray, offset: Int, size: Int, presentationTimeUs: Long): Int
        private external fun nativeRelease(handle: Long)
    }
}
