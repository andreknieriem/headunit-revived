package com.andrerinas.openheadunit.connection.wifi.direct

import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reaches `WifiP2pManager.setWifiP2pChannels`, which is hidden on every API level but is the only way
 * a pre-Android-10 device can be told which frequency to host its group on.
 *
 * The same reflection shape as `setDeviceName` in [WifiDirectManager], for the same reason: the
 * method is `@hide` rather than absent, and the non-SDK blocklist that would refuse it only starts at
 * API 28, below which this is the only lever there is. See [WifiP2pOperatingChannelPolicy] for what the
 * platform does with the value and why the listen channel must be left alone.
 *
 * Every failure here is non-fatal by construction. A group on the platform's own choice of channel is
 * the behaviour that shipped; a group that never forms because a reflection threw is not, so the
 * caller is told and carries on either way.
 */
object WifiP2pChannelCompat {

    private const val METHOD = "setWifiP2pChannels"

    /**
     * How long the platform gets to answer before we assume it never will.
     *
     * Some drivers reload the P2P interface to apply the channel, which drops the pending listener
     * on the floor: the request neither succeeds nor fails and group creation, which only continues
     * from the callback, stops there. Measured on a Qualcomm sm6150 unit on API 28, where the whole
     * of Native AA wireless was unreachable because of it.
     */
    private const val ANSWER_TIMEOUT_MS = 2_000L

    /**
     * Requests [operatingChannel], leaving the listen channel untouched.
     *
     * @param handler used to bound how long the platform's answer is waited for.
     * @param onResult invoked exactly once, with the platform's own verdict where there is one and
     *   with the reflection's where there is not. [onResult] is what continues group creation, so it
     *   must run on every path - including the one where the method does not exist, and the one
     *   where the platform simply never calls back.
     */
    fun setOperatingChannel(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        operatingChannel: Int,
        handler: Handler,
        onResult: (applied: Boolean, detail: String) -> Unit,
    ) {
        if (!WifiP2pOperatingChannelPolicy.isRequestable(operatingChannel)) {
            onResult(false, "channel $operatingChannel is outside what the platform accepts")
            return
        }
        // One latch shared by the listener, the timeout and the failure paths below, so whichever
        // arrives first is the only one that answers.
        val answered = AtomicBoolean(false)
        // Held so answering early can cancel it; postDelayed's token overload is API 28 and this
        // path exists for the devices below that.
        var timeout: Runnable? = null
        val answer = { applied: Boolean, detail: String ->
            if (answered.compareAndSet(false, true)) {
                timeout?.let { handler.removeCallbacks(it) }
                onResult(applied, detail)
            }
        }
        try {
            val method = manager.javaClass.getMethod(
                METHOD,
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() = answer(true, "accepted")
                override fun onFailure(reason: Int) = answer(false, "refused (reason=$reason)")
            }
            val onTimeout = Runnable { answer(false, "no answer in ${ANSWER_TIMEOUT_MS}ms") }
            timeout = onTimeout
            handler.postDelayed(onTimeout, ANSWER_TIMEOUT_MS)
            method.invoke(
                manager,
                channel,
                WifiP2pOperatingChannelPolicy.LISTEN_CHANNEL_UNCHANGED,
                operatingChannel,
                listener,
            )
        } catch (e: NoSuchMethodException) {
            answer(false, "this platform has no $METHOD")
        } catch (e: Throwable) {
            // A SecurityException belongs here too: the manager-level call asks only for
            // CHANGE_WIFI_STATE, but the service side is free to want more, and a unit that says no
            // should still get a group.
            AppLog.d("WifiP2pChannelCompat: $METHOD threw: ${e.message}")
            answer(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Gives the frequency list back to the platform.
     *
     * The restriction outlives the group and the process that set it - it is state in the supplicant,
     * not in this app - so leaving it in place would follow the user into whatever else the unit does
     * with WiFi Direct. Called on teardown for that reason, not for tidiness.
     */
    fun clearOperatingChannel(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        handler: Handler,
        onResult: (applied: Boolean, detail: String) -> Unit = { _, _ -> },
    ) = setOperatingChannel(
        manager,
        channel,
        WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED,
        handler,
        onResult,
    )
}
