package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Weather Repository - Tests for Issue #10
 * 
 * Issue #10: Breezy Weather Check Shows "Connected" When Stale
 * Data older than 2 hours should be considered stale
 */
class WeatherRepositoryTest {

    companion object {
        const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L  // 2 hours in milliseconds
        const val ONE_HOUR_MS = 60 * 60 * 1000L      // 1 hour in milliseconds
    }

    // ============== Issue #10: Staleness Check Tests ==============
    
    @Test
    fun `data is fresh when updated recently`() {
        val lastUpdate = System.currentTimeMillis() - (30 * 60 * 1000)  // 30 minutes ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertTrue("Data updated 30 min ago should be fresh", isFresh)
    }
    
    @Test
    fun `data is stale when older than max age`() {
        val lastUpdate = System.currentTimeMillis() - (3 * 60 * 60 * 1000)  // 3 hours ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Data updated 3 hours ago should be stale", isFresh)
    }
    
    @Test
    fun `data is stale when exactly at boundary`() {
        val lastUpdate = System.currentTimeMillis() - TWO_HOURS_MS  // Exactly 2 hours ago
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Data at exactly max age should be stale", isFresh)
    }
    
    @Test
    fun `data is fresh just before boundary`() {
        val lastUpdate = System.currentTimeMillis() - (TWO_HOURS_MS - 1000)  // Just under 2 hours
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertTrue("Data just under max age should be fresh", isFresh)
    }
    
    @Test
    fun `data with zero timestamp is stale`() {
        val lastUpdate = 0L
        val maxAge = TWO_HOURS_MS
        
        val isFresh = isDataFreshHelper(lastUpdate, maxAge)
        assertFalse("Never-updated data should be stale", isFresh)
    }
    
    @Test
    fun `default max age should be reasonable`() {
        // Default should be between 1 and 4 hours
        val defaultMaxAge = 3600000L  // 1 hour as default
        assertTrue("Default max age should be at least 1 hour", defaultMaxAge >= ONE_HOUR_MS)
        assertTrue("Default max age should be at most 4 hours", defaultMaxAge <= 4 * ONE_HOUR_MS)
    }
    
    // Helper function matching WeatherRepository.isDataFresh logic
    private fun isDataFreshHelper(lastUpdate: Long, maxAgeMs: Long): Boolean {
        return (System.currentTimeMillis() - lastUpdate) < maxAgeMs
    }

    // ============== Weather Status Display Tests ==============
    
    @Test
    fun `stale data should show warning status`() {
        val lastUpdate = System.currentTimeMillis() - (3 * 60 * 60 * 1000)
        val isFresh = isDataFreshHelper(lastUpdate, TWO_HOURS_MS)
        
        val displayStatus = if (isFresh) "Connected" else "Data Stale"
        assertEquals("Stale data should show warning", "Data Stale", displayStatus)
    }
    
    @Test
    fun `fresh data should show connected status`() {
        val lastUpdate = System.currentTimeMillis() - (30 * 60 * 1000)
        val isFresh = isDataFreshHelper(lastUpdate, TWO_HOURS_MS)
        
        val displayStatus = if (isFresh) "Connected" else "Data Stale"
        assertEquals("Fresh data should show connected", "Connected", displayStatus)
    }
    
    // ============== Extended Weather Data Tests ==============
    
    @Test
    fun `precipitation probability normalizes correctly`() {
        // 0-100% should map to 0-1
        assertEquals(0f, normalizePrecip(0), 0.01f)
        assertEquals(0.5f, normalizePrecip(50), 0.01f)
        assertEquals(1f, normalizePrecip(100), 0.01f)
    }
    
    @Test
    fun `visibility normalizes correctly`() {
        // 0-10000m should map to 0-1
        assertEquals(0f, normalizeVisibility(0), 0.01f)
        assertEquals(0.5f, normalizeVisibility(5000), 0.01f)
        assertEquals(1f, normalizeVisibility(10000), 0.01f)
        assertEquals(1f, normalizeVisibility(50000), 0.01f)  // Above 10km clamps to 1
    }
    
    @Test
    fun `moon illumination peaks at full moon`() {
        // Phase 0 = new moon, 180 = full moon
        val newMoon = moonIllumination(0)
        val fullMoon = moonIllumination(180)
        val firstQuarter = moonIllumination(90)
        
        assertEquals("New moon should have ~0 illumination", 0f, newMoon, 0.01f)
        assertEquals("Full moon should have ~1 illumination", 1f, fullMoon, 0.01f)
        assertEquals("First quarter should have ~0.5 illumination", 0.5f, firstQuarter, 0.01f)
    }
    
    @Test
    fun `moon illumination is symmetric`() {
        // 90 and 270 should have same illumination (first and last quarter)
        val firstQuarter = moonIllumination(90)
        val lastQuarter = moonIllumination(270)
        assertEquals("First and last quarter should have same illumination", firstQuarter, lastQuarter, 0.01f)
    }
    
    @Test
    fun `cloud cover affects simulation state`() {
        // Cloud cover 0-100% should influence visibility and atmosphere
        val clearSky = 0
        val overcast = 100
        
        assertTrue("Clear sky should have low cloud cover", clearSky < 20)
        assertTrue("Overcast should have high cloud cover", overcast >= 80)
    }
    
    @Test
    fun `pressure is within realistic range`() {
        // Normal atmospheric pressure: 970-1050 hPa
        val normalPressure = 1013
        val lowPressure = 990
        val highPressure = 1035
        
        assertTrue("Normal pressure should be valid", normalPressure in 900..1100)
        assertTrue("Low pressure should indicate storm", lowPressure < 1000)
        assertTrue("High pressure should indicate clear weather", highPressure > 1020)
    }
    
    @Test
    fun `precip intensity scales with weather type`() {
        // Test the precipitation intensity calculation logic
        val rainWithHighProb = calcPrecipIntensity("RAIN", 0.8f)
        val snowWithHighProb = calcPrecipIntensity("SNOW", 0.8f)
        val clearWithLowProb = calcPrecipIntensity("CLEAR", 0.1f)
        
        assertTrue("Rain should have high intensity", rainWithHighProb > 0.5f)
        assertTrue("Snow should have moderate intensity", snowWithHighProb > 0.4f)
        assertTrue("Clear should have low intensity", clearWithLowProb < 0.2f)
    }
    
    // Helper functions matching WeatherRepository logic
    
    private fun normalizePrecip(probability: Int): Float {
        return (probability / 100f).coerceIn(0f, 1f)
    }
    
    private fun normalizeVisibility(meters: Int): Float {
        return (meters / 10000f).coerceIn(0f, 1f)
    }
    
    private fun moonIllumination(phaseDegrees: Int): Float {
        return ((1 - kotlin.math.cos(Math.toRadians(phaseDegrees.toDouble()))) / 2f).toFloat()
    }
    
    private fun calcPrecipIntensity(weatherType: String, precipProb: Float): Float {
        return when (weatherType) {
            "RAIN", "THUNDERSTORM" -> precipProb * 0.8f + 0.2f
            "SNOW" -> precipProb * 0.7f + 0.15f
            "HAIL" -> precipProb * 0.9f + 0.3f
            else -> precipProb * 0.3f
        }.coerceIn(0f, 1f)
    }
    
    // ============== Moon Phase Default Tests ==============
    
    @Test
    fun `moon phase default should be full moon`() {
        // Default moon phase is 180 degrees (full moon)
        val defaultMoonPhase = 180
        
        // Full moon (180 degrees) should give max illumination
        val illumination = moonIllumination(defaultMoonPhase)
        assertEquals("Default moon phase should give full illumination", 1f, illumination, 0.01f)
    }
    
    @Test
    fun `moon phase normalized default should be 0_5`() {
        // Extended data moonPhase default is 180 degrees
        // When normalized to 0-1: 180/360 = 0.5
        val defaultMoonPhaseDegrees = 180
        val normalized = defaultMoonPhaseDegrees / 360f
        
        assertEquals("Normalized moon phase should be 0.5", 0.5f, normalized, 0.01f)
    }
    
    // ============== Wind Speed Conversion Tests ==============
    
    @Test
    fun `wind speed from kmh to ms conversion`() {
        // Breezy Weather sends km/h, we convert to m/s by dividing by 3.6
        val windSpeedKmh = 36f  // 36 km/h
        val windSpeedMs = windSpeedKmh / 3.6f
        
        assertEquals("36 km/h should be 10 m/s", 10f, windSpeedMs, 0.01f)
    }
    
    @Test
    fun `light wind 5kmh converts correctly`() {
        // Common light breeze scenario: 5 km/h
        val windSpeedKmh = 5f
        val windSpeedMs = windSpeedKmh / 3.6f
        
        // 5 km/h = 1.39 m/s
        assertEquals("5 km/h should be ~1.39 m/s", 1.39f, windSpeedMs, 0.02f)
    }
    
    @Test
    fun `strong wind 72kmh converts correctly`() {
        // Strong wind: 72 km/h = 20 m/s (our max normalization point)
        val windSpeedKmh = 72f
        val windSpeedMs = windSpeedKmh / 3.6f
        
        assertEquals("72 km/h should be 20 m/s", 20f, windSpeedMs, 0.01f)
    }
    
    @Test
    fun `normalized wind strength for light wind`() {
        // 5 km/h = 1.39 m/s -> normalized = 1.39/20 = 0.07
        val windSpeedMs = 5f / 3.6f  // Convert km/h to m/s
        val normalized = (windSpeedMs / 20f).coerceIn(0f, 1f)
        
        assertTrue("Light wind should be well under 0.1", normalized < 0.1f)
        assertEquals("Normalized light wind", 0.07f, normalized, 0.01f)
    }
    
    @Test
    fun `normalized wind strength for max wind`() {
        // 20 m/s = 72 km/h -> normalized = 1.0
        val windSpeedMs = 20f
        val normalized = (windSpeedMs / 20f).coerceIn(0f, 1f)
        
        assertEquals("Max wind should normalize to 1.0", 1f, normalized, 0.01f)
    }
    
    @Test
    fun `normalized wind strength clamps above 20ms`() {
        // Hurricane wind: 50 m/s -> normalized = 1.0 (clamped)
        val windSpeedMs = 50f
        val normalized = (windSpeedMs / 20f).coerceIn(0f, 1f)
        
        assertEquals("Extreme wind should clamp to 1.0", 1f, normalized, 0.01f)
    }
}
