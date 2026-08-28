package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrowBandProfilePolicyTest {

    @Test
    fun `the one case worth naming - no 5 GHz band, wireless, and the full frame rate`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true
        )
        assertNotNull(advice)
    }

    @Test
    fun `the advice names both settings a user can reach`() {
        val advice = NarrowBandProfilePolicy.advice(
            supports5Ghz = false, fpsLimit = 60, wirelessSession = true
        )!!
        assertTrue(advice, advice.contains("FPS Limit"))
        assertTrue(advice, advice.contains("AAC"))
        // It has to say it did nothing, or a reader takes it for a change already made.
        assertTrue(advice, advice.contains("Nothing here has been changed for you"))
    }

    @Test
    fun `a wired session is silent, whatever the radio can do`() {
        assertNull(NarrowBandProfilePolicy.advice(supports5Ghz = false, fpsLimit = 60, wirelessSession = false))
    }

    @Test
    fun `a unit that already asked for 30 has taken the advice and is not told again`() {
        assertNull(NarrowBandProfilePolicy.advice(supports5Ghz = false, fpsLimit = 30, wirelessSession = true))
    }

    @Test
    fun `only a no speaks - a yes and an unknown are both silent`() {
        // A yes describes the station side and a null means the platform would not answer. Neither
        // is grounds for telling somebody their hardware is the problem.
        assertNull(NarrowBandProfilePolicy.advice(supports5Ghz = true, fpsLimit = 60, wirelessSession = true))
        assertNull(NarrowBandProfilePolicy.advice(supports5Ghz = null, fpsLimit = 60, wirelessSession = true))
    }

    @Test
    fun `every combination that is not the one case is silent`() {
        for (supports in listOf(true, false, null)) {
            for (fps in listOf(30, 60)) {
                for (wireless in listOf(true, false)) {
                    val advice = NarrowBandProfilePolicy.advice(supports, fps, wireless)
                    val isTheCase = supports == false && fps == 60 && wireless
                    if (isTheCase) assertNotNull("$supports/$fps/$wireless", advice)
                    else assertNull("$supports/$fps/$wireless", advice)
                }
            }
        }
    }
}
