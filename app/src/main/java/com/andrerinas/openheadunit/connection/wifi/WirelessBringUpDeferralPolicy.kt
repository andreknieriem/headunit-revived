package com.andrerinas.openheadunit.connection.wifi

/**
 * Whether the wireless stack should stand back and let an in-flight USB session win the start.
 *
 * `onCreate` arms WiFi Direct, the 5288 server and, on Native mode, a Bluetooth HFP poke before it
 * has looked at the USB bus, so a dongle already plugged in gets a whole wireless bring-up raised
 * and torn down around it. [UsbSessionQuiescePolicy] fixes that at connect time, which is too late.
 *
 * The question is deliberately narrow. "Is a USB device attached" would be the obvious one and is
 * wrong: `UsbDeviceIdentityPolicy` accepts an unnamed vendor-class interface rather than guessing,
 * so a peripheral that is permanently plugged in - a dock, an audio or Bluetooth adapter - could
 * hold wireless off on every start. An accessory-mode device or a live switch claim cannot be
 * anything but a projection attempt.
 */
object WirelessBringUpDeferralPolicy {

    /**
     * How long wireless waits. The backstop is a dongle that reaches accessory mode and then never
     * negotiates, which the rig saw when no phone was paired to it: without a bound, that unit
     * would never get its wireless stack.
     */
    const val DEFER_BUDGET_MS = 8_000L

    /**
     * @param accessoryDeviceOnBus  something is enumerated as 0x18D1:0x2D00/0x2D01
     * @param switchInFlight        an AOA switch is running, or an activity has claimed one
     * @param msSinceFirstDeferral  0 on the first call for a given start
     */
    fun shouldDefer(
        accessoryDeviceOnBus: Boolean,
        switchInFlight: Boolean,
        msSinceFirstDeferral: Long,
    ): Boolean = (accessoryDeviceOnBus || switchInFlight) && msSinceFirstDeferral < DEFER_BUDGET_MS
}
