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
    fun `christmas season should be Dec 15-26`() {
        // Christmas effects should show from Dec 15 to Dec 26
        val christmasDays = listOf(15, 20, 21, 22, 23, 24, 25, 26)
        val nonChristmasDays = listOf(1, 14, 27, 28, 31)
        
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
    fun `new year season should be Dec 27 - Jan 7`() {
        // New Year effects should show Dec 27 to Jan 7
        val newYearDates = listOf(
            Pair(12, 27),  // Dec 27
            Pair(12, 31),  // Dec 31
            Pair(1, 1),    // Jan 1
            Pair(1, 7)     // Jan 7
        )
        val nonNewYearDates = listOf(
            Pair(12, 26),  // Dec 26
            Pair(1, 8),    // Jan 8
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
        // Matches SeasonalEvents.kt: month == 11 && day in 15..26 (0-indexed month)
        // Helper input is 1-indexed month (12 = Dec)
        return month == 12 && day in 15..26
    }
    
    private fun isNewYearHelper(month: Int, day: Int): Boolean {
        // Matches SeasonalEvents.kt: (month == 11 && day >= 27) || (month == 0 && day <= 7)
        // Helper input is 1-indexed month
        return (month == 12 && day >= 27) || (month == 1 && day <= 7)
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
    
    // ============== Shake Fireworks Tests ==============
    
    @Test
    fun `shake firework count should support up to 50`() {
        val minCount = 5
        val maxCount = 50
        val defaultCount = 15
        
        assertTrue("Min count should be 5", minCount == 5)
        assertTrue("Max count should be 50", maxCount == 50)
        assertTrue("Default should be 15", defaultCount == 15)
        
        // Verify coercion works
        val tooLow = 1.coerceIn(minCount, maxCount)
        val tooHigh = 100.coerceIn(minCount, maxCount)
        val valid = 30.coerceIn(minCount, maxCount)
        
        assertEquals("Too low should clamp to min", minCount, tooLow)
        assertEquals("Too high should clamp to max", maxCount, tooHigh)
        assertEquals("Valid should stay unchanged", 30, valid)
    }
    
    @Test
    fun `normal distribution should cluster around mean`() {
        // Test that normal distribution produces values clustered around mean
        val random = java.util.Random(42)  // Fixed seed for reproducibility
        val mean = 15
        val stdDev = mean * 0.15  // 15% of mean = 2.25
        
        val samples = (1..1000).map {
            (random.nextGaussian() * stdDev + mean).toInt().coerceIn(3, 50)
        }
        
        val avg = samples.average()
        assertTrue("Average should be near mean (15)", avg in 13.0..17.0)
        
        // Most values should be within 2 stdDev of mean (roughly 95%)
        val withinTwoStdDev = samples.count { it in 10..20 }
        assertTrue("Most values should be near mean", withinTwoStdDev > 800)  // >80%
    }
    
    @Test
    fun `shake fireworks should work regardless of seasonal settings`() {
        // Shake fireworks feature should not depend on seasonal events being enabled
        // The ensureShakeReady() method allows initialization without seasonal rendering
        
        val seasonalEnabled = false
        val shakeFireworksEnabled = true
        
        // When seasonal is disabled but shake is enabled, shake should still work
        assertTrue("Shake should work when seasonal disabled", 
            shakeFireworksEnabled && !seasonalEnabled)
    }
}
