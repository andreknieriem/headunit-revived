package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Test

class WppStatusTest {

    @Test
    fun `every recovered status is named`() {
        assertEquals("SUCCESS(0)", WppStatus.describe(0))
        assertEquals("UNSOLICITED_MESSAGE(1)", WppStatus.describe(1))
        assertEquals("NO_COMPATIBLE_VERSION(-1)", WppStatus.describe(-1))
        assertEquals("WIFI_INACCESSIBLE_CHANNEL(-2)", WppStatus.describe(-2))
        assertEquals("WIFI_INCORRECT_CREDENTIALS(-3)", WppStatus.describe(-3))
        assertEquals("PROJECTION_ALREADY_STARTED(-4)", WppStatus.describe(-4))
        assertEquals("WIFI_DISABLED(-5)", WppStatus.describe(-5))
        assertEquals("WIFI_NOT_YET_STARTED(-6)", WppStatus.describe(-6))
        assertEquals("INVALID_HOST(-7)", WppStatus.describe(-7))
        assertEquals("NO_SUPPORTED_WIFI_CHANNELS(-8)", WppStatus.describe(-8))
        assertEquals("INSTRUCT_USER_TO_CHECK_THE_PHONE(-9)", WppStatus.describe(-9))
        assertEquals("PHONE_WIFI_DISABLED(-10)", WppStatus.describe(-10))
    }

    @Test
    fun `an unknown code still carries its number`() {
        // A future status must remain readable, which is the reason this is not a proto enum.
        assertEquals("unknown(-42)", WppStatus.describe(-42))
        assertEquals("unknown(99)", WppStatus.describe(99))
    }

    @Test
    fun `an absent status is not reported as a code`() {
        assertEquals("-", WppStatus.describe(null))
    }
}
