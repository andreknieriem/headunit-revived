package com.andrerinas.openheadunit.aap

/**
 * Whether a given WiFi mode/strategy combination uses [com.andrerinas.openheadunit.connection.WifiDirectManager]
 * to run a WiFi Direct P2P group. Shared between [AapService.initWifiMode] (stop it on a
 * *settings change*) and [AapService.onDisconnected] (stop it on a *user disconnect*) so the
 * two call sites can't drift out of sync.
 */
object WifiModePolicy {
    /**
     * [transport] applies to mode 3 only, and is the whole reason this takes a third argument:
     * on the hotspot route the answer must be false, because the caller reacts to a true by
     * force-disabling the hotspot before starting P2P — which would tear down the very access
     * point the route is about to advertise.
     */
    fun usesWifiDirect(
        mode: Int,
        strategy: Int,
        transport: NativeTransport = NativeTransport.WIFI_DIRECT
    ): Boolean =
        (mode == 3 && transport == NativeTransport.WIFI_DIRECT) || (mode == 2 && strategy == 1)
}
