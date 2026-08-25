package com.andrerinas.openheadunit.connection.self

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

// Display android auto's built-in activity that checks for missing permissions
// It immediately closes if all needed ones are granted
class PermissionTrampolineActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_AA = 1001
        private const val IMMEDIATE_THRESHOLD_MS = 1000L

        private var onFailCallback: (() -> Unit)? = null

        fun launch(context: Context, onFail: () -> Unit) {
            onFailCallback = onFail
            val intent = Intent(context, PermissionTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var launchTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTimestamp = SystemClock.elapsedRealtime()

        val intent = Intent().apply {
            setClassName(
                SelfLauncherManager.AA_PACKAGE,
                "com.google.android.projection.gearhead.companion.RequestManifestPermissionsActivity"
            )
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_AA)
        } catch (_: Exception) {
            triggerFail()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_AA) {
            val durationMs = SystemClock.elapsedRealtime() - launchTimestamp

            AppLog.i("SelfMode: AA permissions request took $durationMs ms")

            if (durationMs < IMMEDIATE_THRESHOLD_MS) {
                triggerFail()
            } else {
                cleanup()
                finish()
            }
        }
    }

    private fun triggerFail() {
        onFailCallback?.invoke()
        cleanup()
        finish()
    }

    private fun cleanup() {
        onFailCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup() // Prevent memory leaks
    }
}
