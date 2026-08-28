package com.andrerinas.openheadunit.connection.self

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Shows Android Auto's own permission check, to find out whether missing permissions are why Self
 * Mode did not start.
 *
 * AA's activity closes immediately (within [IMMEDIATE_THRESHOLD_MS]) when everything is already granted.
 * If the duration is below [IMMEDIATE_THRESHOLD_MS] (or if starting the activity failed), the user already
 * had all permissions (or check failed), so the cause must be something else (e.g. system app status).
 *
 * If the duration is at or above [IMMEDIATE_THRESHOLD_MS], AA's permission activity was displayed to request
 * missing permissions.
 */
class PermissionTrampolineActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_AA = 1001
        private const val IMMEDIATE_THRESHOLD_MS = 1000L

        private var onResultCallback: ((hadMissingPermissions: Boolean) -> Unit)? = null

        /**
         * @param onResult `true` when AA's permission activity was displayed for missing permissions
         *   (duration >= [IMMEDIATE_THRESHOLD_MS]).
         *   `false` when duration was below [IMMEDIATE_THRESHOLD_MS] (permissions already granted)
         *   or when the permission activity could not be started at all.
         */
        fun launch(context: Context, onResult: (hadMissingPermissions: Boolean) -> Unit) {
            onResultCallback = onResult
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
        } catch (e: Exception) {
            AppLog.w("SelfMode: could not start AA's permission activity: ${e.message}")
            finishWith(hadMissingPermissions = false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_AA) {
            val durationMs = SystemClock.elapsedRealtime() - launchTimestamp
            AppLog.i("SelfMode: AA permissions request took $durationMs ms")
            val hadMissingPermissions = durationMs >= IMMEDIATE_THRESHOLD_MS
            finishWith(hadMissingPermissions = hadMissingPermissions)
        }
    }

    private fun finishWith(hadMissingPermissions: Boolean) {
        val callback = onResultCallback
        cleanup()
        callback?.invoke(hadMissingPermissions)
        finish()
    }

    private fun cleanup() {
        onResultCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup() // Prevent memory leaks
    }
}
