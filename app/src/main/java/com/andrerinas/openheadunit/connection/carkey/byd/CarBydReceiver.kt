package com.andrerinas.openheadunit.connection.carkey.byd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.BydPanelKey
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Panel and steering-wheel keys on a BYD head unit.
 *
 * These units deliver their media buttons as raw byte frames on an unprotected broadcast from
 * `com.byd.multimediaservice`, never as Android key events — see [BydPanelKey] for why the standard
 * path cannot see them at all. That makes this the one car-key receiver that has to decode a
 * payload rather than read a key code out of an extra, which is why it lives apart from
 * [com.andrerinas.openheadunit.connection.carkey.CarKeyBroadcastReceiver] and its table of OEM
 * actions.
 *
 * The frame carries a press with no matching release, so each one becomes a full click.
 * `CommManager` de-duplicates and decides whether the key reaches Android Auto at all, including
 * dropping every one of these while nothing is projecting — which is this app's version of the
 * gating the stock player does when another audio source owns the unit.
 */
class CarBydReceiver : BroadcastReceiver(), CarKeyReceiver {

    companion object {
        /** The service that broadcasts the frames, for the diagnostic line in [register]. */
        private const val MULTIMEDIA_PACKAGE = "com.byd.multimediaservice"
    }

    private var context: Context? = null

    /**
     * Always registered, unlike the FYT receiver's system-property probe.
     *
     * There is no reliable BYD marker to test: the units this was captured on are a Freescale board
     * running Android 4.4 with no vendor property naming the manufacturer, and probing for
     * [MULTIMEDIA_PACKAGE] would silently disable the feature on any firmware variant that renamed
     * it — a false negative here is a head unit whose media buttons do nothing, which is far worse
     * than an idle receiver on a unit that never sends the action. Registering costs one
     * `IntentFilter` with one vendor-specific action that no other head unit broadcasts, and
     * [BydPanelKey.panelKeyCode] rejects anything that is not a well-formed key frame, so a stray
     * broadcast of the same name cannot inject a key.
     */
    override val isSupported = true

    override val isSUNeeded = false

    override fun register(context: Context) {
        this.context = context

        // Exported because the sender is another app. This matches how the app already receives
        // MEDIA_BUTTON and the OEM key actions.
        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(BydPanelKey.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )

        val installed = runCatching {
            context.packageManager.getPackageInfo(MULTIMEDIA_PACKAGE, 0)
        }.isSuccess
        AppLog.i(
            "CarKeyReceiver: Listening for BYD panel keys on ${BydPanelKey.ACTION} " +
                "($MULTIMEDIA_PACKAGE ${if (installed) "present" else "not found"})",
        )
    }

    override fun unregister() {
        this.context?.unregisterReceiver(this)
        this.context = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != BydPanelKey.ACTION) return

        // Read the payload once and check its type, rather than reaching for a typed getter that
        // might not match. `getIntArrayExtra` on a `byte[]` makes the framework log a warning and a
        // full stack trace for every frame, and this channel is busy — in the original capture that
        // mistake produced 67 of 69 logcat lines.
        val payload = intent.extras?.get(BydPanelKey.EXTRA_FRAME)
        if (payload !is ByteArray) return

        val panelCode = BydPanelKey.panelKeyCode(payload)
        if (panelCode == BydPanelKey.NO_KEY) {
            // Source changes, ACC state, launcher metadata and the service's echo of what other
            // apps send it all share this action. None of it is ours.
            return
        }

        val keyCode = BydPanelKey.toKeyCode(panelCode)
        // A virtual key code gets its own name; a confirmed one already has a platform name. Always
        // with the raw frame, so a button nobody has catalogued yet is recoverable from a user's log.
        AppLog.i(
            "CarKeyReceiver: BYD panel key 0x%02X -> %s [%s]".format(
                panelCode,
                BydPanelKey.label(keyCode) ?: KeyEvent.keyCodeToString(keyCode),
                BydPanelKey.hex(payload),
            ),
        )

        // Feed the keymap screen's debugger, the way the OEM broadcast receiver does, so an
        // uncatalogued button is visible before it is mapped.
        context.sendBroadcast(
            Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
                setPackage(context.packageName)
                putExtra("action", BydPanelKey.ACTION)
                putExtra("keyCode", keyCode)
                putExtra("byd_panel_code", "0x%02X".format(panelCode))
                putExtra("byd_frame", BydPanelKey.hex(payload))
            },
        )

        // One frame per press, with no release to follow, so the click is synthesised here.
        handleClick(context, keyCode)
    }
}
