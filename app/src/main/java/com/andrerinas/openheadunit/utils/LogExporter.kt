package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LogExporter {

    enum class LogLevel(val filter: String, val logLevel: Int) {
        VERBOSE("*:V", Log.VERBOSE),
        DEBUG("*:D", Log.DEBUG),
        INFO("*:I", Log.INFO),
        WARNING("*:W", Log.WARN),
        ERROR("*:E", Log.ERROR),
        /** Do not perform any background capture. */
        SILENT("", Int.MAX_VALUE)
    }

    private var captureProcess: Process? = null
    private var captureThread: Thread? = null
    private var captureFile: File? = null
    private var captureVerbosity: LogLevel = LogLevel.DEBUG
    private var captureRestarts = 0
    private const val MAX_RESTARTS = 5

    /**
     * Ceiling for one capture file. Sits under [LogFilesHelper]'s 50 MB budget for the whole
     * directory, so a single runaway capture cannot consume it.
     */
    private const val MAX_CAPTURE_BYTES = 16L * 1024 * 1024

    val isCapturing: Boolean get() = captureProcess != null

    /** Current capture verbosity while capturing, or null when no capture is active. */
    val currentLevel: LogLevel?
        get() = if (isCapturing) captureVerbosity else null

    /**
     * Starts a continuous logcat process writing to a timestamped file.
     * Unlike [saveLogToPublicFile], this captures everything from the moment it is called,
     * bypassing the small shared ring buffer.
     */
    fun startCapture(context: Context, verbosity: LogLevel) {
        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            AppLog.w("LogExporter: log source is APPLOG_FILE; logcat capture is disabled")
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        // If SILENT requested, ensure capture is stopped and don't start a new one.
        if (verbosity == LogLevel.SILENT) {
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        stopCapture()
        val settings = Settings(context)
        val logDir = LogFilesHelper.resolveLogDirectory(context, settings, allowInternalFallback = false) ?: return
        LogFilesHelper.rotateLogs(logDir)

        val file = LogFilesHelper.createTimestampedLogFile(logDir)
        captureFile = file
        captureVerbosity = verbosity
        captureRestarts = 0

        launchLogcatPipe(file, verbosity)
    }

    /**
     * Spawns a logcat process piping stdout into [file] (append mode).
     * When the process exits unexpectedly, restarts automatically up to [MAX_RESTARTS] times
     * so a system-killed logcat doesn't silently stop the capture.
     */
    private fun launchLogcatPipe(file: File, verbosity: LogLevel) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", verbosity.filter)
            )
            captureProcess = process
            captureThread = Thread {
                var capped = false
                try {
                    FileOutputStream(file, true).use { out ->
                        // Not copyTo(): at VERBOSE the filter is the whole system, and rotateLogs
                        // only runs when a capture starts, so an unattended capture used to grow
                        // until the disk did. Count what we write and stop at a bound instead.
                        var written = file.length()
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = process.inputStream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written >= MAX_CAPTURE_BYTES) {
                                out.write(
                                    "\n--- capture stopped: reached ${MAX_CAPTURE_BYTES / (1024 * 1024)} MB ---\n"
                                        .toByteArray()
                                )
                                capped = true
                                break
                            }
                        }
                    }
                } catch (_: IOException) { }

                if (capped) {
                    // Clear the process reference before destroying it so the restart below sees the
                    // capture as intentionally ended. Deliberately not stopCapture(): that joins
                    // captureThread, which is this thread.
                    captureProcess = null
                    process.destroy()
                    AppLog.w(
                        "LogExporter: capture stopped at ${MAX_CAPTURE_BYTES / (1024 * 1024)} MB. " +
                            "Export this log and start a new capture if you still need one."
                    )
                    return@Thread
                }

                // the read loop ended — logcat process died or was intentionally stopped
                if (captureProcess === process && captureRestarts < MAX_RESTARTS) {
                    captureRestarts++
                    AppLog.w("Log capture process exited, restarting (attempt $captureRestarts/$MAX_RESTARTS)")
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    launchLogcatPipe(file, verbosity)
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: IOException) {
            AppLog.e("Failed to start log capture", e)
            captureFile = null
        }
    }

    /** Stops the continuous capture process. */
    fun stopCapture() {
        captureProcess?.destroy()
        captureProcess = null
        captureThread?.join(2000)
        captureThread = null
    }

    /**
     * Writes logs to a timestamped file and returns it.
     * - If a capture file is available (capture was started, active or already stopped):
     *   copies its content into a fresh export file so the original capture file is preserved.
     * - Otherwise: dumps the current logcat ring buffer.
     */
    fun saveLogToPublicFile(context: Context, verbosity: LogLevel): File? {
        if (verbosity == LogLevel.SILENT) {
            AppLog.w("LogExporter: export requested while SILENT; skipping export")
            return null
        }

        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            return (AppLog.currentLogFile ?: AppLog.lastLogFile)
                ?.takeIf { it.exists() && it.length() > 0 }
        }

        val settings = Settings(context)
        val logDir = LogFilesHelper.resolveLogDirectory(context, settings, allowInternalFallback = false) ?: return null
        LogFilesHelper.ensureDirectory(logDir)

        val source = captureFile
        if (source != null && source.exists() && source.length() > 0) {
            return source
        }

        return try {
            LogFilesHelper.rotateLogs(logDir)
            val logFile = LogFilesHelper.createTimestampedLogFile(logDir)
            // Use stdout piping instead of -f flag; -f is unreliable on Android 4.4.
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", verbosity.filter)
            )
            FileOutputStream(logFile).use { out ->
                process.inputStream.copyTo(out)
            }
            process.waitFor()
            logFile
        } catch (e: Exception) {
            AppLog.e("Failed to save logs", e)
            null
        }
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Log File")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}