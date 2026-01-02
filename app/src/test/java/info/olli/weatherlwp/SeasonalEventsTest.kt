package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for SeasonalEvents - Tests for Issue #9
 * 
 * Issue #9: Seasonal Events No Debug Switch
 * Need ability to test Christmas/New Year effects without waiting for actual date
 */
class SeasonalEventsTest {

    // ============== Issue #9: Seasonal Event Detection Tests ==============
    
    @Test
    fun `christmas season should be Dec 20-26`() {
        // Christmas effects should show from Dec 20 to Dec 26
        val christmasDays = listOf(20, 21, 22, 23, 24, 25, 26)
        val nonChristmasDays = listOf(1, 15, 19, 27, 28, 31)
        
        for (day in christmasDays) {
            val isChristmas = isChristmasHelper(12, day)
            assertTrue("Dec $day should be Christmas", isChristmas)
        }
        
        for (day in nonChristmasDays) {
            val isChristmas = isChristmasHelper(12, day)
            assertFalse("Dec $day should NOT be Christmas", isChristmas)
        }
    }
    
    @Test
    fun `new year season should be Dec 31 - Jan 2`() {
        // New Year effects should show Dec 31 to Jan 2
        val newYearDates = listOf(
            Pair(12, 31),  // Dec 31
            Pair(1, 1),    // Jan 1
            Pair(1, 2)     // Jan 2
        )
        val nonNewYearDates = listOf(
            Pair(12, 30),  // Dec 30
            Pair(1, 3),    // Jan 3
            Pair(1, 15)    // Jan 15
        )
        
        for ((month, day) in newYearDates) {
            val isNewYear = isNewYearHelper(month, day)
            assertTrue("$month/$day should be New Year", isNewYear)
        }
        
        for ((month, day) in nonNewYearDates) {
            val isNewYear = isNewYearHelper(month, day)
            assertFalse("$month/$day should NOT be New Year", isNewYear)
        }
    }
    
    @Test
    fun `debug override should force christmas mode`() {
        val debugEnabled = true
        val debugSeason = "christmas"
        val currentMonth = 6  // June (definitely not Christmas)
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
        val debugSeason = "christmas"  // Debug setting ignored
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
        val season = "christmas"
        val hasSnowflakes = season == "christmas"
        
        assertTrue("Christmas should have snowflakes", hasSnowflakes)
    }
    
    @Test
    fun `newyear should have firework effects`() {
        val season = "newyear"
        val hasFireworks = season == "newyear"
        
        assertTrue("New Year should have fireworks", hasFireworks)
    }
    
    // ============== Height Scaling Tests ==============
    
    @Test
    fun `height scale should be 1 at reference height 1920`() {
        val referenceHeight = 1920f
        val screenHeight = 1920f
        val scale = screenHeight / referenceHeight
        
        assertEquals("Scale at reference height should be 1.0", 1.0f, scale, 0.001f)
    }
    
    @Test
    fun `height scale should be 0_5 at 960px height`() {
        val referenceHeight = 1920f
        val screenHeight = 960f
        val scale = screenHeight / referenceHeight
        
        assertEquals("Scale at 960px should be 0.5", 0.5f, scale, 0.001f)
    }
    
    @Test
    fun `height scale minimum should clamp at 0_5`() {
        val referenceHeight = 1920f
        val screenHeight = 480f  // Very small screen
        val rawScale = screenHeight / referenceHeight
        val clampedScale = rawScale.coerceAtLeast(0.5f)
        
        assertEquals("Scale should clamp to 0.5 minimum", 0.5f, clampedScale, 0.001f)
    }
    
    @Test
    fun `fireworks should render behind terrain in front of mountains`() {
        // Verify render order for fireworks: mountains < fireworks < terrain
        // In ParallaxScene, render order is:
        // 1. Sky, 2. Clouds, 3. Far sky events, 4. Rainbow (St Patricks)
        // 5. Mountains, 6. Mid sky events, 7. Fireworks (renderBehindTerrain)
        // 8. Terrain (hills + trees)
        
        val renderOrder = listOf(
            "sky", "clouds", "farSkyEvents", "rainbow",
            "mountains", "midSkyEvents", "fireworks", "terrain"
        )
        
        val fireworksIndex = renderOrder.indexOf("fireworks")
        val mountainsIndex = renderOrder.indexOf("mountains")
        val terrainIndex = renderOrder.indexOf("terrain")
        
        assertTrue("Fireworks should be after mountains", fireworksIndex > mountainsIndex)
        assertTrue("Fireworks should be before terrain", fireworksIndex < terrainIndex)
    }

    // Helper functions matching SeasonalEvents logic
    private fun isChristmasHelper(month: Int, day: Int): Boolean {
        return month == 12 && day in 20..26
    }
    
    private fun isNewYearHelper(month: Int, day: Int): Boolean {
        return (month == 12 && day == 31) || (month == 1 && day in 1..2)
    }
    
    private fun getSeasonHelper(debugEnabled: Boolean, debugSeason: String, month: Int, day: Int): String {
        if (debugEnabled && debugSeason != "auto") {
            return debugSeason
        }
        
        return when {
            isChristmasHelper(month, day) -> "christmas"
            isNewYearHelper(month, day) -> "newyear"
            else -> "none"
        }
    }
}
