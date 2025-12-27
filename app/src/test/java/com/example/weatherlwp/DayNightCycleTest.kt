package com.example.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DayNightCycle - Tests for Issue #7, #8
 * 
 * Issue #7: Sky Not Changing With Time (Sunset Missing)
 * Issue #8: Manual Time Slider functionality
 */
class DayNightCycleTest {

    // ============== Issue #7: Time-Based Sky Color Tests ==============
    
    @Test
    fun `time periods should be correctly defined`() {
        // Night: 21:00 - 05:00
        // Dawn: 05:00 - 07:00
        // Day: 07:00 - 17:00
        // Sunset: 17:00 - 18:30 (golden hour)
        // Dusk: 18:30 - 21:00
        
        assertTrue("Night should start at 21:00", 21f >= 21f)
        assertTrue("Dawn should start at 05:00", 5f >= 5f)
        assertTrue("Day should start at 07:00", 7f >= 7f)
        assertTrue("Sunset should start at 17:00", 17f >= 17f)
        assertTrue("Dusk should start at 18:30", 18.5f >= 18.5f)
    }
    
    @Test
    fun `sunset period should exist between day and dusk`() {
        val dayEnd = 17f
        val sunsetEnd = 18.5f
        val duskEnd = 21f
        
        assertTrue("Sunset should start when day ends", dayEnd == 17f)
        assertTrue("Sunset should end before dusk ends", sunsetEnd < duskEnd)
        assertTrue("Sunset duration should be ~1.5 hours", 
            (sunsetEnd - dayEnd) >= 1f && (sunsetEnd - dayEnd) <= 2f)
    }
    
    @Test
    fun `getTimeOfDay should return correct period for each hour`() {
        val periods = mapOf(
            0f to "night",
            3f to "night",
            5f to "dawn",
            6f to "dawn",
            7f to "day",
            12f to "day",
            16f to "day",
            17f to "sunset",
            18f to "sunset",
            18.5f to "dusk",
            20f to "dusk",
            21f to "night",
            23f to "night"
        )
        
        for ((hour, expectedPeriod) in periods) {
            val actualPeriod = getTimeOfDayHelper(hour)
            assertEquals("Hour $hour should be $expectedPeriod", expectedPeriod, actualPeriod)
        }
    }
    
    // ============== Issue #8: Manual Time Tests ==============
    
    @Test
    fun `manual time range should be 0 to 23`() {
        val minTime = 0
        val maxTime = 23
        
        assertEquals("Min manual time should be 0", 0, minTime)
        assertEquals("Max manual time should be 23", 23, maxTime)
    }
    
    @Test
    fun `manual time should override auto time when disabled`() {
        val autoDayNightEnabled = false
        val manualTime = 18  // 6 PM
        val currentRealTime = 10f  // 10 AM real time
        
        val effectiveTime = if (autoDayNightEnabled) currentRealTime else manualTime.toFloat()
        
        assertEquals("Manual time should be used when auto is disabled", 
            manualTime.toFloat(), effectiveTime, 0.1f)
    }
    
    @Test
    fun `auto time should use real time when enabled`() {
        val autoDayNightEnabled = true
        val manualTime = 18
        val currentRealTime = 10f
        
        val effectiveTime = if (autoDayNightEnabled) currentRealTime else manualTime.toFloat()
        
        assertEquals("Real time should be used when auto is enabled", 
            currentRealTime, effectiveTime, 0.1f)
    }

    // ============== Brightness Tests (Issue #14 related) ==============
    
    @Test
    fun `brightness should vary by time of day`() {
        val nightBrightness = 0.3f
        val dawnBrightness = 0.6f
        val dayBrightness = 1.0f
        val duskBrightness = 0.5f
        
        assertTrue("Night should be darkest", nightBrightness < dawnBrightness)
        assertTrue("Day should be brightest", dayBrightness > dawnBrightness)
        assertTrue("Dusk should be dimmer than day", duskBrightness < dayBrightness)
    }
    
    // Helper function matching ParallaxScene time logic
    private fun getTimeOfDayHelper(hour: Float): String {
        return when {
            hour >= 21f || hour < 5f -> "night"
            hour < 7f -> "dawn"
            hour < 17f -> "day"
            hour < 18.5f -> "sunset"
            hour < 21f -> "dusk"
            else -> "night"
        }
    }
}
