package com.andrerinas.openheadunit.connection.projection

/**
 * Whether a connection to [host] needs an interface chosen for it.
 *
 * Loopback traffic never leaves the device, so binding it to a network is meaningless and the
 * lookup is not free: on a unit with no WiFi the search waits out its full 1500 ms timeout, and
 * Self Mode pays that on every attempt.
 */
object LoopbackBindPolicy {

    fun needsNetworkBinding(host: String): Boolean = !isLoopback(host)

    private fun isLoopback(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h == "localhost") return true
        // IPv6 loopback, written out or shortened.
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        // The whole of 127.0.0.0/8, not just 127.0.0.1 - Android Auto's head unit server is
        // reached on 127.0.0.1, but a hand-typed address anywhere in the range is still local.
        val labels = h.split(".")
        if (labels.size !in 2..4) return false
        if (labels.any { it.isEmpty() || !it.all(Char::isDigit) }) return false
        return labels[0].toIntOrNull() == 127
    }
}
