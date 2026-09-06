package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiCountryPolicyTest {

    // --- normalise ---

    @Test
    fun `a country is upper-cased and trimmed`() {
        assertEquals("EG", WifiCountryPolicy.normalise("eg"))
        assertEquals("CN", WifiCountryPolicy.normalise("  cn  "))
        assertEquals("DE", WifiCountryPolicy.normalise("DE"))
    }

    @Test
    fun `the world domain is an absent country, not a country`() {
        assertNull(WifiCountryPolicy.normalise("00"))
        assertNull(WifiCountryPolicy.normalise("WW"))
        assertNull(WifiCountryPolicy.normalise("ww"))
    }

    @Test
    fun `anything that is not two letters names nothing`() {
        assertNull(WifiCountryPolicy.normalise(null))
        assertNull(WifiCountryPolicy.normalise(""))
        assertNull(WifiCountryPolicy.normalise("   "))
        assertNull(WifiCountryPolicy.normalise("USA"))
        assertNull(WifiCountryPolicy.normalise("E"))
        assertNull(WifiCountryPolicy.normalise("E1"))
        // What a failed read puts in the map: it must never be mistaken for a country.
        assertNull(WifiCountryPolicy.normalise("err: SecurityException"))
    }

    @Test
    fun `the world domain is told apart from a source that said nothing`() {
        assertTrue(WifiCountryPolicy.isWorldDomain("00"))
        assertTrue(WifiCountryPolicy.isWorldDomain(" ww "))
        assertFalse(WifiCountryPolicy.isWorldDomain("EG"))
        assertFalse(WifiCountryPolicy.isWorldDomain(null))
    }

    // --- choose: first source that names one wins ---

    @Test
    fun `the earliest source that names a country is the answer`() {
        val sources = linkedMapOf(
            "telephony" to null,
            "settings" to "  ",
            "property" to "cn",
            "locale" to "US",
        )
        assertEquals("CN", WifiCountryPolicy.choose(sources))
    }

    @Test
    fun `a world domain earlier in the order does not win over a real country later`() {
        val sources = linkedMapOf("telephony" to "00", "property" to "eg")
        assertEquals("EG", WifiCountryPolicy.choose(sources))
    }

    @Test
    fun `nothing named means null`() {
        assertNull(WifiCountryPolicy.choose(linkedMapOf("a" to null, "b" to "", "c" to "00")))
        assertNull(WifiCountryPolicy.choose(emptyMap()))
    }

    // --- describe: the three outcomes a log line has to distinguish ---

    @Test
    fun `a named country reads as itself`() {
        assertEquals(
            "regulatory domain CN",
            WifiCountryPolicy.describe(linkedMapOf("property" to "cn"))
        )
    }

    @Test
    fun `an unset domain is reported as the finding it is`() {
        val why = WifiCountryPolicy.describe(linkedMapOf("telephony" to null, "property" to "00"))
        assertTrue(why, why.contains("world domain"))
    }

    @Test
    fun `silence from every source is not claimed to be the world domain`() {
        val why = WifiCountryPolicy.describe(linkedMapOf("telephony" to null, "property" to ""))
        assertTrue(why, why.contains("unknown"))
        assertFalse(why, why.contains("world domain"))
    }
}
