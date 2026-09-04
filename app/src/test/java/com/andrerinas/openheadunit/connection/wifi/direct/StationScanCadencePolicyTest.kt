package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationScanCadencePolicyTest {

    @Test
    fun `no scans produces no line, so a quiet unit says nothing`() {
        assertNull(StationScanCadencePolicy.summarise(emptyList(), 30_000L))
    }

    @Test
    fun `a single scan has no cadence to report yet`() {
        val line = StationScanCadencePolicy.summarise(listOf(1_000L), 30_000L)
        assertNotNull(line)
        assertTrue(line!!.contains("1 in 30000ms"))
        assertTrue(line.contains("no cadence yet"))
    }

    @Test
    fun `a steady ten second cadence is reported as ten seconds`() {
        val scans = (0..3).map { 1_000L + it * 10_000L }
        val line = StationScanCadencePolicy.summarise(scans, 30_000L)!!
        assertTrue(line, line.contains("4 in 30000ms"))
        assertTrue(line, line.contains("every 10.0s"))
    }

    @Test
    fun `the shortest and longest gaps are both named`() {
        val scans = listOf(0L, 2_000L, 12_000L, 30_000L)
        val line = StationScanCadencePolicy.summarise(scans, 30_000L)!!
        assertTrue(line, line.contains("shortest 2.0s"))
        assertTrue(line, line.contains("longest 18.0s"))
    }

    @Test
    fun `the reported window is the real one, not the nominal one`() {
        val line = StationScanCadencePolicy.summarise(listOf(0L, 10_000L), 31_411L)!!
        assertTrue(line, line.contains("in 31411ms"))
    }
}
