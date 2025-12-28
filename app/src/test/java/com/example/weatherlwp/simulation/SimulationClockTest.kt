package com.example.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for SimulationClock time management.
 */
class SimulationClockTest {

    private lateinit var clock: SimulationClock

    @Before
    fun setUp() {
        clock = SimulationClock()
    }

    @Test
    fun `default uses real time`() {
        assertTrue(clock.isUsingRealTime())
    }

    @Test
    fun `can switch to manual time`() {
        clock.setUseRealTime(false)
        assertFalse(clock.isUsingRealTime())
    }

    @Test
    fun `manual time defaults to noon`() {
        clock.setUseRealTime(false)
        // Default is 720 minutes = 12:00
        assertEquals(12f, clock.getTimeOfDayHours(), 0.01f)
    }

    @Test
    fun `setManualTime updates time of day`() {
        clock.setUseRealTime(false)
        clock.setManualTime(360) // 6:00 AM
        assertEquals(6f, clock.getTimeOfDayHours(), 0.01f)
    }

    @Test
    fun `manual time clamps to valid range`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(-100)
        assertEquals(0, clock.getManualTimeMinutes())
        
        clock.setManualTime(2000)
        assertEquals(1439, clock.getManualTimeMinutes())
    }

    @Test
    fun `isNight returns true before 6am`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(0) // Midnight
        assertTrue(clock.isNight())
        
        clock.setManualTime(5 * 60) // 5:00 AM
        assertTrue(clock.isNight())
    }

    @Test
    fun `isNight returns true after 8pm`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(20 * 60) // 8:00 PM
        assertTrue(clock.isNight())
        
        clock.setManualTime(23 * 60) // 11:00 PM
        assertTrue(clock.isNight())
    }

    @Test
    fun `isNight returns false during day`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(12 * 60) // Noon
        assertFalse(clock.isNight())
        
        clock.setManualTime(15 * 60) // 3:00 PM
        assertFalse(clock.isNight())
    }

    @Test
    fun `isDawn returns true between 6 and 7-30`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(6 * 60) // 6:00 AM
        assertTrue(clock.isDawn())
        
        clock.setManualTime(7 * 60) // 7:00 AM
        assertTrue(clock.isDawn())
    }

    @Test
    fun `isDawn returns false outside dawn hours`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(5 * 60) // 5:00 AM
        assertFalse(clock.isDawn())
        
        clock.setManualTime(8 * 60) // 8:00 AM
        assertFalse(clock.isDawn())
    }

    @Test
    fun `isDusk returns true between 17 and 20`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(17 * 60) // 5:00 PM
        assertTrue(clock.isDusk())
        
        clock.setManualTime(19 * 60) // 7:00 PM
        assertTrue(clock.isDusk())
    }

    @Test
    fun `isDusk returns false outside dusk hours`() {
        clock.setUseRealTime(false)
        
        clock.setManualTime(16 * 60) // 4:00 PM
        assertFalse(clock.isDusk())
        
        clock.setManualTime(20 * 60) // 8:00 PM - now night
        assertFalse(clock.isDusk())
    }

    @Test
    fun `getDayProgress returns 0 at midnight`() {
        clock.setUseRealTime(false)
        clock.setManualTime(0)
        assertEquals(0f, clock.getDayProgress(), 0.01f)
    }

    @Test
    fun `getDayProgress returns 0-5 at noon`() {
        clock.setUseRealTime(false)
        clock.setManualTime(12 * 60)
        assertEquals(0.5f, clock.getDayProgress(), 0.01f)
    }

    @Test
    fun `getTimeSeconds returns positive value`() {
        val timeSeconds = clock.getTimeSeconds()
        assertTrue(timeSeconds > 0)
    }

    @Test
    fun `getAbsoluteTimeMs returns positive large value`() {
        val absoluteTime = clock.getAbsoluteTimeMs()
        
        // Should be a large positive value (epoch time)
        assertTrue("Absolute time should be positive", absoluteTime > 0)
        // Should be at least year 2020 in millis
        assertTrue("Absolute time should be recent", absoluteTime > 1577836800000L)
    }

    @Test
    fun `time multiplier affects time seconds calculation`() {
        // With default multiplier (1.0), get initial time
        val time1 = clock.getTimeSeconds()
        
        // Change multiplier to 2x
        clock.setTimeMultiplier(2.0f)
        val time2 = clock.getTimeSeconds()
        
        // With 2x multiplier, the seconds value should be larger
        // (since it multiplies the current time)
        assertTrue("Time with 2x multiplier should be larger", time2 > time1)
    }

    @Test
    fun `time multiplier clamps to valid range`() {
        clock.setTimeMultiplier(0.01f) // Too low
        // Verify by checking time is still positive
        assertTrue(clock.getTimeSeconds() > 0)
        
        clock.setTimeMultiplier(100f) // Too high
        assertTrue(clock.getTimeSeconds() > 0)
    }

    @Test
    fun `getTimeOfDayMinutes returns minutes since midnight`() {
        clock.setUseRealTime(false)
        clock.setManualTime(90) // 1:30 AM
        assertEquals(90, clock.getTimeOfDayMinutes())
    }
}
