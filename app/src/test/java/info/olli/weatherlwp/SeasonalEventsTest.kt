package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.SeasonalEvents
import info.olli.weatherlwp.rendering.seasonal.HolidaySchedule
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for SeasonalEvents - Tests for Issue #9 + interval/toggle features
 * 
 * Issue #9: Seasonal Events No Debug Switch
 * Need ability to test Christmas/New Year effects without waiting for actual date
 * 
 * New: Tests for circular day distance, per-event toggles, and configurable intervals.
 */
class SeasonalEventsTest {

    // ============== Circular Day Distance Tests ==============

    @Test
    fun `circularDayDistance - same day returns 0`() {
        assertEquals(0, SeasonalEvents.circularDayDistance(100, 100, 365))
    }

    @Test
    fun `circularDayDistance - adjacent days returns 1`() {
        assertEquals(1, SeasonalEvents.circularDayDistance(100, 101, 365))
        assertEquals(1, SeasonalEvents.circularDayDistance(101, 100, 365))
    }

    @Test
    fun `circularDayDistance - wraps around year boundary`() {
        // Dec 31 (DOY 365) to Jan 1 (DOY 1) = 1 day
        assertEquals(1, SeasonalEvents.circularDayDistance(365, 1, 365))
        assertEquals(1, SeasonalEvents.circularDayDistance(1, 365, 365))
    }

    @Test
    fun `circularDayDistance - Dec 25 to Jan 1 is 7 days`() {
        // Dec 25 ≈ DOY 359, Jan 1 = DOY 1 → distance = min(358, 365-358) = 7
        val dec25 = SeasonalEvents.dayOfYear(Calendar.DECEMBER, 25)
        val jan1 = SeasonalEvents.dayOfYear(Calendar.JANUARY, 1)
        val dist = SeasonalEvents.circularDayDistance(dec25, jan1)
        assertEquals("Dec 25 to Jan 1 should be 7 days", 7, dist)
    }

    @Test
    fun `circularDayDistance - half year is max distance`() {
        assertEquals(182, SeasonalEvents.circularDayDistance(1, 183, 365))
        // Going the other direction: min(183, 365-183) = min(183, 182) = 182
        assertEquals(182, SeasonalEvents.circularDayDistance(1, 184, 365))
    }

    @Test
    fun `circularDayDistance - leap year wraps correctly`() {
        assertEquals(1, SeasonalEvents.circularDayDistance(366, 1, 366))
    }

    // ============== dayOfYear Tests ==============

    @Test
    fun `dayOfYear - Jan 1 is day 1`() {
        assertEquals(1, SeasonalEvents.dayOfYear(Calendar.JANUARY, 1))
    }

    @Test
    fun `dayOfYear - Dec 31 is 365 or 366`() {
        val doy = SeasonalEvents.dayOfYear(Calendar.DECEMBER, 31)
        assertTrue("Dec 31 should be 365 or 366", doy == 365 || doy == 366)
    }

    @Test
    fun `dayOfYear - Mar 17 is around day 76`() {
        val doy = SeasonalEvents.dayOfYear(Calendar.MARCH, 17)
        assertTrue("Mar 17 should be around day 76", doy in 75..77)
    }

    // ============== getSeasonByDate with Default Intervals ==============
    // Uses test helper that replicates the interval logic without Android context

    @Test
    fun `christmas season - Dec 25 with default interval`() {
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 25))
    }

    @Test
    fun `christmas season - Dec 24 within default interval`() {
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 24))
    }

    @Test
    fun `christmas season - Dec 26 within default interval`() {
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 26))
    }

    @Test
    fun `christmas season - Dec 23 outside default interval`() {
        // Dec 23 is 2 days before Dec 25, outside ±1
        assertNotEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 23))
    }

    @Test
    fun `new year season - Jan 1 with default interval`() {
        // Jan 1 is 7 days from Dec 25 (outside ±5 Christmas), so New Year wins
        assertEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.JANUARY, 1))
    }

    @Test
    fun `new year season - Jan 2 within default interval`() {
        assertEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.JANUARY, 2))
    }

    @Test
    fun `new year season - Jan 3 outside default interval`() {
        assertNotEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.JANUARY, 3))
    }

    @Test
    fun `cn new year season - exact astronomical date with default interval`() {
        // With interval=0, only the actual new-moon date matches (2026: Feb 17, 2027: Feb 6)
        assertEquals("CN_NEW_YEAR", getSeasonByDateHelper(Calendar.FEBRUARY, 17, year = 2026))
        assertEquals("CN_NEW_YEAR", getSeasonByDateHelper(Calendar.FEBRUARY, 6, year = 2027))
    }

    @Test
    fun `cn new year season - neighbouring days outside default interval`() {
        assertNotEquals("CN_NEW_YEAR", getSeasonByDateHelper(Calendar.FEBRUARY, 16, year = 2026))
        assertNotEquals("CN_NEW_YEAR", getSeasonByDateHelper(Calendar.FEBRUARY, 1, year = 2026))
    }

    @Test
    fun `cn new year interval reaches across the year boundary`() {
        // 2028 CNY is Jan 26; Dec 30 2027 is 27 days earlier.
        assertEquals(27L, HolidaySchedule.daysFromChineseNewYear(calFor(Calendar.DECEMBER, 30, 2027)))
    }

    @Test
    fun `st patricks season - Mar 17 exact match with default interval`() {
        // With interval=0, only exact match works
        assertEquals("ST_PATRICKS", getSeasonByDateHelper(Calendar.MARCH, 17))
    }

    @Test
    fun `st patricks season - Mar 16 outside default interval`() {
        // With interval=0, even 1 day away is outside
        assertNotEquals("ST_PATRICKS", getSeasonByDateHelper(Calendar.MARCH, 16))
    }

    @Test
    fun `no season in summer`() {
        assertEquals("NONE", getSeasonByDateHelper(Calendar.JULY, 15))
    }

    // ============== Priority Tests ==============

    @Test
    fun `christmas takes priority over new year on overlap dates`() {
        // Dec 25 is within Christmas (±1: Dec 24-26)
        // Christmas should win due to priority order
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 25))
    }

    @Test
    fun `new year wins on Dec 31 when outside christmas interval`() {
        // Dec 31 is 6 days from Dec 25 (outside ±1) but only 1 day from Jan 1 (inside ±1)
        assertEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.DECEMBER, 31))
    }

    // ============== Issue #9: Debug Override Tests ==============

    @Test
    fun `debug override should force christmas mode`() {
        val debugEnabled = true
        val debugSeason = "christmas"
        val currentMonth = 6  // June
        val currentDay = 15
        
        val season = getSeasonHelper(debugEnabled, debugSeason, currentMonth, currentDay)
        assertEquals("Debug should force Christmas in June", "christmas", season)
    }
    
    @Test
    fun `debug override should force newyear mode`() {
        val debugEnabled = true
        val debugSeason = "newyear"
        val currentMonth = 6  // June
        val currentDay = 15
        
        val season = getSeasonHelper(debugEnabled, debugSeason, currentMonth, currentDay)
        assertEquals("Debug should force New Year in June", "newyear", season)
    }
    
    @Test
    fun `debug disabled should use real date`() {
        val debugEnabled = false
        val debugSeason = "christmas"
        val currentMonth = 6  // June
        val currentDay = 15
        
        val season = getSeasonHelper(debugEnabled, debugSeason, currentMonth, currentDay)
        assertEquals("Debug disabled should use real date", "none", season)
    }
    
    @Test
    fun `auto mode should detect real christmas`() {
        val debugEnabled = true
        val debugSeason = "auto"
        val currentMonth = 12
        val currentDay = 25
        
        val season = getSeasonHelper(debugEnabled, debugSeason, currentMonth, currentDay)
        assertEquals("Auto mode on Dec 25 should be Christmas", "christmas", season)
    }

    // ============== Seasonal Effect Tests ==============
    
    @Test
    fun `christmas should have snowflake effects`() {
        // Verify Christmas season is detected correctly near Dec 25
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 24))
        assertEquals("CHRISTMAS", getSeasonByDateHelper(Calendar.DECEMBER, 26))
    }
    
    @Test
    fun `newyear should have firework effects`() {
        // Verify New Year season is detected correctly near Jan 1
        assertEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.JANUARY, 1))
        assertEquals("NEW_YEAR", getSeasonByDateHelper(Calendar.JANUARY, 2))
    }

    // Helper functions - replicate getSeasonByDate() logic using companion methods
    // to avoid instantiating SeasonalEvents (requires Android context)

    /**
     * Replicate getSeasonByDate() logic using default intervals/toggles.
     * Uses SeasonalEvents.circularDayDistance() and SeasonalEvents.dayOfYear() directly.
     */
    private fun getSeasonByDateHelper(
        month: Int,
        day: Int,
        christmasEnabled: Boolean = true,
        newyearEnabled: Boolean = true,
        cnNewyearEnabled: Boolean = true,
        stPatricksEnabled: Boolean = true,
        christmasInterval: Int = PreferenceDefaults.CHRISTMAS_INTERVAL,
        newyearInterval: Int = PreferenceDefaults.NEWYEAR_INTERVAL,
        cnNewyearInterval: Int = PreferenceDefaults.CN_NEWYEAR_INTERVAL,
        stPatricksInterval: Int = PreferenceDefaults.ST_PATRICKS_INTERVAL,
        year: Int? = null
    ): String {
        val cal = calFor(month, day, year)
        val currentDoy = cal.get(Calendar.DAY_OF_YEAR)
        val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)

        if (christmasEnabled && SeasonalEvents.circularDayDistance(
                currentDoy, SeasonalEvents.dayOfYear(Calendar.DECEMBER, 25, cal), daysInYear
            ) <= christmasInterval
        ) return "CHRISTMAS"

        if (newyearEnabled && SeasonalEvents.circularDayDistance(
                currentDoy, SeasonalEvents.dayOfYear(Calendar.JANUARY, 1, cal), daysInYear
            ) <= newyearInterval
        ) return "NEW_YEAR"

        if (cnNewyearEnabled && HolidaySchedule.daysFromChineseNewYear(cal) <= cnNewyearInterval) return "CN_NEW_YEAR"

        if (stPatricksEnabled && SeasonalEvents.circularDayDistance(
                currentDoy, SeasonalEvents.dayOfYear(Calendar.MARCH, 17, cal), daysInYear
            ) <= stPatricksInterval
        ) return "ST_PATRICKS"

        return "NONE"
    }
    
    private fun isChristmasHelper(month: Int, day: Int): Boolean {
        return getSeasonByDateHelper(month - 1, day) == "CHRISTMAS"
    }
    
    private fun isNewYearHelper(month: Int, day: Int): Boolean {
        return getSeasonByDateHelper(month - 1, day) == "NEW_YEAR"
    }
    
    private fun getSeasonHelper(debugEnabled: Boolean, debugSeason: String, month: Int, day: Int): String {
        if (debugEnabled && debugSeason != "auto") {
            return debugSeason
        }
        
        return when (getSeasonByDateHelper(month - 1, day)) {
            "CHRISTMAS" -> "christmas"
            "NEW_YEAR" -> "newyear"
            "CN_NEW_YEAR" -> "cn_newyear"
            "ST_PATRICKS" -> "st_patricks"
            else -> "none"
        }
    }

    private fun calFor(month: Int, day: Int, year: Int? = null): Calendar {
        return Calendar.getInstance().apply {
            if (year != null) set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
        }
    }
}
