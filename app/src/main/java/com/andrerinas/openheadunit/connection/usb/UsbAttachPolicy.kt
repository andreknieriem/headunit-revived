package com.andrerinas.openheadunit.connection.usb

/**
 * Decides whether an attached Android phone should be switched into accessory mode.
 *
 * The gate this replaces asked only whether the vendor id was Google's, so a Pixel connected and
 * every other make fell through to the manual USB button. An empty allow list means "never
 * configured", not "nothing is permitted" - the same fallback single-USB auto-connect makes.
 */
object UsbAttachPolicy {

    /**
     * @param isGoogleVendor        vendor id is 0x18D1; a Pixel, or an Android Auto dongle
     * @param autoStartOnUsb        the user asked us to start on any USB attach
     * @param allowListConfigured   the user has marked at least one device as allowed
     * @param deviceAllowed         this device is on that list
     */
    fun shouldAttemptAoaSwitch(
        isGoogleVendor: Boolean,
        autoStartOnUsb: Boolean,
        allowListConfigured: Boolean,
        deviceAllowed: Boolean,
    ): Boolean = when {
        isGoogleVendor -> true
        autoStartOnUsb -> true
        !allowListConfigured -> true
        else -> deviceAllowed
    }
}
