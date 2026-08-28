package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.ActivityLaunchPolicy.LaunchStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLaunchStrategyTest {

    @Test
    fun `direct launch on Android 9 and below regardless of overlay permission`() {
        assertEquals(LaunchStrategy.DIRECT, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 28, canDrawOverlays = false))
        assertEquals(LaunchStrategy.DIRECT, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 28, canDrawOverlays = true))
    }

    @Test
    fun `overlay trampoline on Android 10 plus when permission granted`() {
        assertEquals(LaunchStrategy.OVERLAY, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 29, canDrawOverlays = true))
    }

    @Test
    fun `notification fallback on Android 10 plus without overlay permission`() {
        assertEquals(LaunchStrategy.NOTIFICATION, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 29, canDrawOverlays = false))
    }
}
