package com.andrerinas.openheadunit.connection.usb

/**
 * Picks the endpoint pair to run AAP over. AOAP gives 0x2D00 one interface with exactly two bulk
 * endpoints, so bulk is what we want; a device that also exposes an interrupt endpoint would
 * otherwise hand us that one and every transfer would fail. Falls back to any type rather than
 * refusing, which is what both transports did before.
 */
object UsbEndpointSelectionPolicy {

    /** One endpoint, reduced to what the choice turns on. */
    data class Endpoint(val isInbound: Boolean, val isBulk: Boolean)

    /** Indices into the interface's endpoint list; null means that direction has none. */
    data class Selection(val inIndex: Int?, val outIndex: Int?) {
        val isComplete: Boolean get() = inIndex != null && outIndex != null
    }

    fun select(endpoints: List<Endpoint>): Selection {
        var inIndex: Int? = null
        var outIndex: Int? = null
        for (bulkOnly in listOf(true, false)) {
            for (index in endpoints.indices) {
                val endpoint = endpoints[index]
                if (bulkOnly && !endpoint.isBulk) continue
                if (endpoint.isInbound) {
                    if (inIndex == null) inIndex = index
                } else {
                    if (outIndex == null) outIndex = index
                }
            }
            if (inIndex != null && outIndex != null) break
        }
        return Selection(inIndex, outIndex)
    }
}
