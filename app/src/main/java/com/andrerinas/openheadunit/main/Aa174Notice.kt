package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-time notice for Android Auto 17.4+ breaking changes.
 *
 * Informs users about Google's restrictions on third-party wireless triggers
 * and self-mode, directing them to the Headunit Server, Native Mode, or USB Dongle,
 * with a direct link to the guide on the official website.
 */
object Aa174Notice {

    private var dialog: AlertDialog? = null

    fun maybeShow(activity: Activity?, settings: Settings) {
        if (activity == null || activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        if (settings.aa174NoticeShown) return
        // If rename notice is still pending or showing, let it show first
        if (!settings.renameNoticeShown) return
        if (dialog?.isShowing == true) return
        if (settings.onboardingVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION) return

        try {
            dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
                .setIcon(R.drawable.ic_warning_white)
                .setTitle(R.string.aa174_notice_title)
                .setMessage(activity.getText(R.string.aa174_notice_message))
                .setCancelable(false)
                .setPositiveButton(R.string.aa174_notice_button_confirm) { d: DialogInterface, _: Int ->
                    settings.aa174NoticeShown = true
                    d.dismiss()
                }
                .setNeutralButton(R.string.aa174_notice_button_guide) { d: DialogInterface, _: Int ->
                    settings.aa174NoticeShown = true
                    d.dismiss()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://headunit.andrerinas.com/guides/wireless/"))
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        AppLog.e("Aa174Notice: Failed to launch guide URL: ${e.message}")
                    }
                }
                .show()
        } catch (e: WindowManager.BadTokenException) {
            AppLog.w("Aa174Notice: Window token invalid when showing dialog: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("Aa174Notice: Failed to show dialog: ${e.message}")
        }
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        } finally {
            dialog = null
        }
    }
}
