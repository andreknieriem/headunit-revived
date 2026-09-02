package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** Whether this handshake tells the phone where to reach us over TCP, and why not when it does not. */
sealed class WppEndpointDecision {

    /** Advertise the endpoint on [port]. */
    data class Advertise(val port: Int) : WppEndpointDecision()

    /** Send the version request without an endpoint. [reason] is written to be logged as-is. */
    data class Withhold(val reason: String) : WppEndpointDecision()
}

/**
 * Decides whether to advertise the WPP-over-TCP endpoint.
 *
 * The phone stores that endpoint alongside the network we hand it and from then on joins that
 * network instead of running the Bluetooth handshake again, with no fallback when it is gone. So an
 * endpoint is only safe on a network whose name and BSSID outlive the record: our own access point,
 * never a WiFi Direct group, which is renamed and re-addressed on every create and cannot be made
 * persistent here.
 *
 * Withholding is not a cure, only a way of not causing it. An endpoint the phone was given earlier
 * survives for the life of its Android Auto process and is dialled in preference to Bluetooth, so
 * the WiFi Direct refusal says how to clear one.
 */
object WppEndpointPolicy {

    fun decide(strategy: NativeStrategy, listeningPort: Int?): WppEndpointDecision = when {
        strategy != NativeStrategy.HOTSPOT -> WppEndpointDecision.Withhold(
            "a WiFi Direct group is renamed every time it is created, and the phone would keep " +
                "dialling the one it stored. Withholding one does not clear one the phone already " +
                "has: Android Auto keeps an endpoint it was given on this head unit's hotspot for " +
                "as long as it is running, and dials it in preference to Bluetooth, so if a WiFi " +
                "Direct connection will not start, forget this head unit on the phone"
        )
        listeningPort == null -> WppEndpointDecision.Withhold("the WPP TCP server is not listening")
        else -> WppEndpointDecision.Advertise(listeningPort)
    }
}
