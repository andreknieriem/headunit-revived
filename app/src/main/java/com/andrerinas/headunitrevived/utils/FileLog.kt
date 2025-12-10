package com.andrerinas.headunitrevived.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLog {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var isLoggingEnabled: Boolean = false // New property

    fun init(context: Context, enable: Boolean) { // Modified signature
        isLoggingEnabled = enable // Set the flag
        if (isLoggingEnabled && logFile == null) { // Only create file if enabled and not already created
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            logFile = File(logDir, "app_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
            try {
                logFile?.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun appendLog(tag: String, message: String) {
        if (isLoggingEnabled && logFile != null) { // Check flag before appending
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.append("${dateFormat.format(Date())} $tag: $message\n")
                }
            }
            catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}