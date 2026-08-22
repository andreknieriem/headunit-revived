package com.andrerinas.openheadunit.utils

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.SettingsActivity

/**
 * Says, in a place that survives the drive, that this head unit cannot describe its own network to
 * the phone and which setting fixes it.
 *
 * The verdicts behind this were already exact and already logged. What they were not was reachable:
 * they arrive mid-handshake, as one line in a log and one toast over the projection screen, and the
 * user is looking at the road. The toast stays - it is the fastest signal when somebody *is*
 * watching - and this sits behind it for when nobody was.
 *
 * Deliberately not a dialog. There is no activity of ours in front of the user at that moment, and
 * an activity launched to interrupt Android Auto is the wrong thing to put on a screen in a moving
 * car. A notification waits instead, and opens Settings when it is safe to read.
 */
object CredentialsNotice {

    /**
     * One id per condition, and an id repeated so a second failure of the same kind replaces the
     * first rather than stacking.
     *
     * Two rather than one, because the conditions are independent and are cleared independently: a
     * user who has set a manual hotspot name gets credentials published while the configuration is
     * still unreadable, and a single id meant clearing one of these cancelled the other's standing
     * notice along with it.
     */
    private const val HOTSPOT_CONFIG_ID = 4801
    private const val BSSID_ID = 4802

    /** The device will not name its own access point; the hotspot route cannot start without help. */
    fun showHotspotConfigUnreadable(context: Context) = show(
        context, HOTSPOT_CONFIG_ID,
        R.string.credentials_notice_hotspot_title, R.string.credentials_notice_hotspot_text
    )

    /** No usable BSSID, which the WiFi Direct route aborts on rather than sending. */
    fun showBssidUnavailable(context: Context) = show(
        context, BSSID_ID,
        R.string.credentials_notice_bssid_title, R.string.credentials_notice_bssid_text
    )

    /** Safe to call when none was raised. */
    fun clearHotspotConfigUnreadable(context: Context) = clear(context, HOTSPOT_CONFIG_ID)

    /** Safe to call when none was raised. */
    fun clearBssidUnavailable(context: Context) = clear(context, BSSID_ID)

    private fun clear(context: Context, id: Int) {
        try {
            notificationManager(context)?.cancel(id)
        } catch (e: Exception) {
            AppLog.d("CredentialsNotice: could not clear the notice: ${e.message}")
        }
    }

    private fun show(context: Context, notificationId: Int, titleRes: Int, textRes: Int) {
        try {
            val app = context.applicationContext
            val intent = Intent(app, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getActivity(app, notificationId, intent, flags)

            val text = app.getString(textRes)
            val notification = NotificationCompat.Builder(app, App.setupNeededChannel)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentTitle(app.getString(titleRes))
                .setContentText(text)
                // The remedy names two settings and does not fit one line on a head unit panel.
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()

            notificationManager(app)?.notify(notificationId, notification)
        } catch (e: Exception) {
            // Never worth taking a connection attempt down for. POST_NOTIFICATIONS can be denied on
            // API 33+, and the log line this accompanies has already been written.
            AppLog.w("CredentialsNotice: could not post the notice: ${e.message}")
        }
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
