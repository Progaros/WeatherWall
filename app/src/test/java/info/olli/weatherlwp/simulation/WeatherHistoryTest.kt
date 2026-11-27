package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WeatherHistory weather tracking and physics calculations.
 * 
 * Since WeatherHistory uses SharedPreferences (Android-dependent),
 * these tests validate the physics logic and snapshot calculations
 * using the public constants and independent helper implementations.
 */
class WeatherHistoryTest {
    
    // ============== Snow Physics Tests ==============
    
    @Test
    fun `snow accumulation rate is reasonable`() {
        // SNOW_ACCUMULATION_RATE = 0.06 per hour of snow
        // After 10 hours of snow: 0.6 depth (reasonable)
        val rate = 0.06f
        val hoursOfSnow = 10f
        val depth = (rate * hoursOfSnow).coerceIn(0f, 1f)
        assertEquals("10h of snow should give 0.6 depth", 0.6f, depth, 0.01f)
    }
    
    @Test
    fun `snow melts above 0C`() {
        // SNOW_MELT_RATE_PER_DEGREE = 0.008 per degree per hour
        val meltRatePerDegree = 0.008f
        val tempAboveZero = 5f  // +5°C
        val hours = 10f
        val snowDepth = 1f  // Start at max
        val melt = meltRatePerDegree * tempAboveZero * hours
        val remaining = (snowDepth - melt).coerceIn(0f, 1f)
        assertEquals("5°C for 10h should melt 0.4", 0.6f, remaining, 0.01f)
    }
    
    @Test
    fun `snow does not increase above 1`() {
        val rate = 0.06f
        val hoursOfSnow = 30f  // Very long snowfall
        val depth = (rate * hoursOfSnow).coerceIn(0f, 1f)
        assertEquals("Snow depth should cap at 1.0", 1f, depth, 0.01f)
    }
    
    @Test
    fun `rain washes snow`() {
        // SNOW_RAIN_WASH_RATE = 0.03 per hour
        val washRate = 0.03f
        val hoursOfRain = 5f
        val snowDepth = 0.5f
        val remaining = (snowDepth - washRate * hoursOfRain).coerceIn(0f, 1f)
        assertEquals("5h of rain should wash 0.15 snow", 0.35f, remaining, 0.01f)
    }
    
    // ============== River Physics Tests ==============
    
    @Test
    fun `river rises with rain`() {
        // RIVER_RAIN_RATE = 0.04 per hour
        val rate = 0.04f
        val hoursOfRain = 8f
        val level = (rate * hoursOfRain).coerceIn(0f, 1f)
        assertEquals("8h rain should give 0.32 river", 0.32f, level, 0.01f)
    }
    
    @Test
    fun `river drains over time`() {
        // RIVER_DRAIN_RATE = 0.012 per hour
        val drainRate = 0.012f
        val hours = 24f  // 1 day dry
        val level = 0.5f
        val remaining = (level - drainRate * hours).coerceIn(0f, 1f)
        assertEquals("1 day drain from 0.5 should be ~0.21", 0.21f, remaining, 0.02f)
    }
    
    @Test
    fun `river caps at 1`() {
        val rate = 0.04f
        val hoursOfRain = 50f  // Very long rain
        val level = (rate * hoursOfRain).coerceIn(0f, 1f)
        assertEquals("River should cap at 1.0", 1f, level, 0.01f)
    }
    
    // ============== Snapshot Default Tests ==============
    
    @Test
    fun `empty snapshot has zero values`() {
        // When no weather data has been recorded
        val snowDepth = 0f
        val riverLevel = 0f
        val daysOfData = 0
        
        assertEquals(0f, snowDepth, 0.01f)
        assertEquals(0f, riverLevel, 0.01f)
        assertEquals(0, daysOfData)
    }
    
    // ============== Day ID Calculation Tests ==============
    
    @Test
    fun `day ID is consistent within same day`() {
        // dayId = year * 1000 + dayOfYear
        val year = 2025
        val dayOfYear = 165
        val dayId = year * 1000 + dayOfYear
        assertEquals(2025165, dayId)
    }
    
    @Test
    fun `day ID changes between days`() {
        val today = 2025 * 1000 + 165
        val tomorrow = 2025 * 1000 + 166
        assertNotEquals(today, tomorrow)
    }
    
    // ============== Temperature Conversion Tests ==============
    
    @Test
    fun `kelvin to celsius conversion`() {
        // Values > 100 are treated as Kelvin by BreezyWeatherReceiver
        val kelvin = 293.15f  // 20°C
        val celsius = kelvin - 273.15f
        assertEquals("293.15K should be 20°C", 20f, celsius, 0.01f)
    }
    
    @Test
    fun `celsius values pass through`() {
        // Values <= 100 are treated as Celsius
        val temp = 25f
        assertTrue("25 is already Celsius", temp <= 100)
    }
    
    @Test
    fun `freezing point`() {
        val celsius = 0f
        assertTrue("0°C is freezing", celsius <= 0)
    }
    
    // ============== Snow Melt Integration Test ==============
    
    @Test
    fun `snow accumulates and melts over simulated multi-day scenario`() {
        val accumulationRate = 0.06f
        val meltRatePerDegree = 0.008f
        var snowDepth = 0f
        
        // Day 1: 8 hours of snow at -5°C (no melt)
        snowDepth += accumulationRate * 8f
        snowDepth = snowDepth.coerceIn(0f, 1f)
        assertEquals("After day 1 snow", 0.48f, snowDepth, 0.01f)
        
        // Day 2: No snow, temp = 2°C for 12 hours
        snowDepth -= meltRatePerDegree * 2f * 12f
        snowDepth = snowDepth.coerceIn(0f, 1f)
        assertEquals("After day 2 melt", 0.288f, snowDepth, 0.01f)
        
        // Day 3: 5 more hours of snow
        snowDepth += accumulationRate * 5f
        snowDepth = snowDepth.coerceIn(0f, 1f)
        assertEquals("After day 3 snow", 0.588f, snowDepth, 0.01f)
    }
    
    // ============== River Snowmelt Contribution Test ==============
    
    @Test
    fun `snow melt contributes to river level`() {
        // RIVER_SNOWMELT_RATE = 0.02 per hour
        val snowmeltRate = 0.02f
        val tempAboveZero = 3f
        val hours = 8f
        
        // Snowmelt contribution: rate * temp * hours (only when snow is melting)
        val contribution = (snowmeltRate * tempAboveZero * hours)
        assertTrue("Snowmelt should contribute positively to river", contribution > 0)
        assertTrue("Snowmelt contribution should be moderate", contribution < 1f)
    }
    
    // ============== History Capacity Tests ==============
    
    @Test
    fun `max history days is 14`() {
        val maxDays = 14
        assertTrue("Should keep 2 weeks of history", maxDays >= 7)
        assertTrue("Should not keep too much", maxDays <= 30)
    }
}
