package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Names the status codes the phone answers WPP messages with.
 *
 * Recovered from a serialized protobuf descriptor carrying Google's own
 * `androidauto.bluetooth.proto` naming, so these are the schema's names and not a reconstruction.
 * Only `== 0` is branched on; the point is that a log says WIFI_INCORRECT_CREDENTIALS, not -3.
 *
 * Deliberately a lookup rather than a proto enum. Every status field in `wireless.proto` is a plain
 * int32 because a sint32 declaration once rendered a real -8 as 2147483644, and a value the enum
 * does not know would land in unknown fields where the log could not see it at all.
 */
object WppStatus {

    private val NAMES = mapOf(
        1 to "UNSOLICITED_MESSAGE",
        0 to "SUCCESS",
        -1 to "NO_COMPATIBLE_VERSION",
        -2 to "WIFI_INACCESSIBLE_CHANNEL",
        -3 to "WIFI_INCORRECT_CREDENTIALS",
        -4 to "PROJECTION_ALREADY_STARTED",
        -5 to "WIFI_DISABLED",
        -6 to "WIFI_NOT_YET_STARTED",
        -7 to "INVALID_HOST",
        -8 to "NO_SUPPORTED_WIFI_CHANNELS",
        -9 to "INSTRUCT_USER_TO_CHECK_THE_PHONE",
        -10 to "PHONE_WIFI_DISABLED"
    )

    /** [status] as `NAME(n)`, `unknown(n)` for one not in the table, or "-" where absent. */
    fun describe(status: Int?): String {
        if (status == null) return "-"
        return "${NAMES[status] ?: "unknown"}($status)"
    }
}
