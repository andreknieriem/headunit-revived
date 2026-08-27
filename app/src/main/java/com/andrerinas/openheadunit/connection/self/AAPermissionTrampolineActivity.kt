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
 * AA's activity closes immediately when everything is already granted, so a fast return is the
 * success case, not a failure. It used to be read the other way round - anything under a second
 * counted as failed - which fired a "Failed to start Android Auto" toast on every healthy start.
 *
 * The only failure this can actually observe is not being able to start the activity at all.
 */
class PermissionTrampolineActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_AA = 1001

        private var onResultCallback: ((permissionCheckRan: Boolean) -> Unit)? = null

        /**
         * @param onResult `true` when AA's permission activity ran and returned, whether it closed
         *   at once or the user worked through it - either way permissions are not the problem.
         *   `false` when it could not be started, which is the caller's cue to work out why.
         */
        fun launch(context: Context, onResult: (permissionCheckRan: Boolean) -> Unit) {
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
            finishWith(permissionCheckRan = false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_AA) {
            val durationMs = SystemClock.elapsedRealtime() - launchTimestamp
            AppLog.i("SelfMode: AA permissions request took $durationMs ms")
            finishWith(permissionCheckRan = true)
        }
    }

    private fun finishWith(permissionCheckRan: Boolean) {
        val callback = onResultCallback
        cleanup()
        callback?.invoke(permissionCheckRan)
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
