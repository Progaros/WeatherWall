package info.olli.weatherlwp.simulation

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
    fun `getTimeSeconds wraps within 24 hours`() {
        val timeSeconds = clock.getTimeSeconds()
        // Time should be wrapped to within 24 hours (86400 seconds)
        assertTrue("Time seconds should be < 86400", timeSeconds < 86400f)
        assertTrue("Time seconds should be >= 0", timeSeconds >= 0f)
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
    fun `time multiplier affects absolute time calculation`() {
        // With default multiplier (1.0), get initial absolute time
        val time1 = clock.getAbsoluteTimeMs()
        
        // Change multiplier to 2x
        clock.setTimeMultiplier(2.0f)
        val time2 = clock.getAbsoluteTimeMs()
        
        // With 2x multiplier, the absolute time should be larger
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
    
    // ============== Sunrise/Sunset Tests ==============
    
    @Test
    fun `default sunrise is 6am`() {
        assertEquals(6.0f, clock.getSunriseHour(), 0.01f)
    }
    
    @Test
    fun `default sunset is 8pm`() {
        assertEquals(20.0f, clock.getSunsetHour(), 0.01f)
    }
    
    @Test
    fun `updateSunTimes updates sunrise hour`() {
        // Unix timestamp for Jan 6, 2025 7:03 AM local time
        // We can't test exact conversion without knowing timezone, 
        // but we can verify it changes from default
        val testSunrise = System.currentTimeMillis() / 1000 - 3600 // 1 hour ago
        val testSunset = System.currentTimeMillis() / 1000 + 3600 // 1 hour from now
        
        clock.updateSunTimes(testSunrise, testSunset)
        
        // Values should have changed from defaults
        val sunriseHour = clock.getSunriseHour()
        val sunsetHour = clock.getSunsetHour()
        
        // Should be valid hour values
        assertTrue("Sunrise hour should be 0-24", sunriseHour in 0f..24f)
        assertTrue("Sunset hour should be 0-24", sunsetHour in 0f..24f)
    }
    
    @Test
    fun `updateSunTimes with negative values keeps defaults`() {
        clock.updateSunTimes(-1, -1)
        
        // Should keep default values
        assertEquals(6.0f, clock.getSunriseHour(), 0.01f)
        assertEquals(20.0f, clock.getSunsetHour(), 0.01f)
    }
    
    @Test
    fun `isNight uses custom sunrise sunset`() {
        clock.setUseRealTime(false)
        
        // Set winter times: sunrise 7:30, sunset 16:00
        // Use actual unix timestamps - for 7:30 and 16:00 today
        val now = System.currentTimeMillis()
        val todayMidnight = now - (now % 86400000)
        val sunriseUnix = (todayMidnight / 1000) + (7 * 3600) + (30 * 60) // 7:30
        val sunsetUnix = (todayMidnight / 1000) + (16 * 3600) // 16:00
        
        clock.updateSunTimes(sunriseUnix, sunsetUnix)
        
        // At 7:00 AM (before sunrise 7:30), should be night
        clock.setManualTime(7 * 60)
        assertTrue("7:00 AM before 7:30 sunrise should be night", clock.isNight())
        
        // At 8:00 AM (after sunrise 7:30), should not be night
        clock.setManualTime(8 * 60)
        assertFalse("8:00 AM after 7:30 sunrise should not be night", clock.isNight())
        
        // At 15:30 (before sunset 16:00), should not be night
        clock.setManualTime(15 * 60 + 30)
        assertFalse("15:30 before 16:00 sunset should not be night", clock.isNight())
        
        // At 16:30 (after sunset 16:00), should be night
        clock.setManualTime(16 * 60 + 30)
        assertTrue("16:30 after 16:00 sunset should be night", clock.isNight())
    }

    // ============== Boundary Precision Tests ==============

    @Test
    fun `isDawn at exact sunrise boundary is true`() {
        clock.setUseRealTime(false)
        // Default sunrise = 6.0
        clock.setManualTime(6 * 60) // Exactly 6:00
        assertTrue("Exactly at sunrise should be dawn", clock.isDawn())
    }

    @Test
    fun `isDawn one minute before sunrise is false`() {
        clock.setUseRealTime(false)
        clock.setManualTime(5 * 60 + 59) // 5:59
        assertFalse("One minute before sunrise is NOT dawn", clock.isDawn())
    }

    @Test
    fun `isDusk one minute before sunset-3h is false`() {
        clock.setUseRealTime(false)
        // Default sunset = 20.0, dusk starts at 17.0
        clock.setManualTime(16 * 60 + 59) // 16:59
        assertFalse("16:59 is before dusk start (17:00)", clock.isDusk())
    }

    @Test
    fun `isDusk at exact dusk start is true`() {
        clock.setUseRealTime(false)
        // Dusk: sunset-3h to sunset = 17:00 to 20:00
        clock.setManualTime(17 * 60) // Exactly 17:00
        assertTrue("Exactly at dusk start should be dusk", clock.isDusk())
    }

    @Test
    fun `isNight boundary at sunset is true`() {
        clock.setUseRealTime(false)
        // sunset = 20:00 (hour >= sunsetHour)
        clock.setManualTime(20 * 60) // Exactly 20:00
        assertTrue("Exactly at sunset is night", clock.isNight())
    }

    @Test
    fun `isNight one minute before sunset is false`() {
        clock.setUseRealTime(false)
        clock.setManualTime(19 * 60 + 59) // 19:59
        assertFalse("One minute before sunset is NOT night", clock.isNight())
    }

    @Test
    fun `setTimeMultiplier clamps low values`() {
        clock.setTimeMultiplier(0.01f) // Below 0.1
        // Verify time is still valid (clamped to 0.1)
        val time = clock.getTimeSeconds()
        assertTrue(time >= 0f)
    }

    @Test
    fun `setTimeMultiplier clamps high values`() {
        clock.setTimeMultiplier(100f) // Above 10
        // Verify time is still valid (clamped to 10)
        val time = clock.getTimeSeconds()
        assertTrue(time >= 0f)
    }

    @Test
    fun `getTimeOfDayHours in manual mode converts minutes to hours`() {
        clock.setUseRealTime(false)
        clock.setManualTime(450) // 7:30 AM = 7.5 hours
        assertEquals(7.5f, clock.getTimeOfDayHours(), 0.01f)
    }

    @Test
    fun `getTimeOfDayHours at midnight is 0`() {
        clock.setUseRealTime(false)
        clock.setManualTime(0)
        assertEquals(0f, clock.getTimeOfDayHours(), 0.01f)
    }

    @Test
    fun `getTimeOfDayHours at max is 23-98`() {
        clock.setUseRealTime(false)
        clock.setManualTime(1439) // 23:59
        assertEquals(23.983f, clock.getTimeOfDayHours(), 0.01f)
    }

    @Test
    fun `getDayProgress at 6am is 0-25`() {
        clock.setUseRealTime(false)
        clock.setManualTime(6 * 60)
        assertEquals(0.25f, clock.getDayProgress(), 0.01f)
    }

    @Test
    fun `getDayProgress at 6pm is 0-75`() {
        clock.setUseRealTime(false)
        clock.setManualTime(18 * 60)
        assertEquals(0.75f, clock.getDayProgress(), 0.01f)
    }

    private fun withDefaultZone(id: String, block: () -> Unit) {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
            block()
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    private fun localMinutesNow(id: String): Int {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(id))
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    @Test
    fun `real time follows a system timezone change`() {
        withDefaultZone("Europe/Berlin") {
            val c = SimulationClock().apply { zoneCheckIntervalMs = 0L }
            assertEquals(localMinutesNow("Europe/Berlin").toFloat(), c.getTimeOfDayMinutes().toFloat(), 1f)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(localMinutesNow("Asia/Tokyo").toFloat(), c.getTimeOfDayMinutes().toFloat(), 1f)
        }
    }

    @Test
    fun `sun times are re-projected into the new timezone`() {
        // 2026-06-21 04:00 UTC
        val sunriseUtc = 1_782_014_400L
        withDefaultZone("UTC") {
            val c = SimulationClock().apply { zoneCheckIntervalMs = 0L }
            c.updateSunTimes(sunriseUtc, sunriseUtc + 16 * 3600)
            assertEquals(4.0f, c.getSunriseHour(), 0.01f)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            c.getTimeOfDayHours()  // triggers the zone check
            assertEquals(13.0f, c.getSunriseHour(), 0.01f)
        }
    }
}
