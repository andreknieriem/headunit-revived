package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinateMapperTest {

    @Test
    fun `touch mapping uses actual input surface width`() {
        val point = TouchCoordinateMapper.map(
            rawX = 1200f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            stretchToFill = true,
            hudMirroring = false
        )

        assertEquals(960, point.x)
        assertEquals(540, point.y)
    }

    @Test
    fun `touch mapping accounts for android auto vertical margin`() {
        val point = TouchCoordinateMapper.map(
            rawX = 1200f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 194f,
            stretchToFill = true,
            hudMirroring = false
        )

        assertEquals(960, point.x)
        assertEquals(443, point.y)
    }

    @Test
    fun `touch mapping mirrors horizontal coordinates for hud mirroring`() {
        val point = TouchCoordinateMapper.map(
            rawX = 240f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            stretchToFill = true,
            hudMirroring = true
        )

        assertEquals(1728, point.x)
        assertEquals(540, point.y)
    }
}
