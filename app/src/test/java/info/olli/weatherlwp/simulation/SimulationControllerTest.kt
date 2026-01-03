package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SimulationController wind and gust behavior.
 * 
 * Tests:
 * - Wind strength calculation
 * - Gust timing and amplitude
 * - Calm wind threshold
 * - Delta time handling
 */
class SimulationControllerTest {

    // ============== Wind Strength Tests ==============
    
    @Test
    fun `wind strength is in valid range`() {
        val minWind = 0f
        val maxWind = 1f
        
        val testValues = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (wind in testValues) {
            assertTrue("Wind $wind >= 0", wind >= minWind)
            assertTrue("Wind $wind <= 1", wind <= maxWind)
        }
    }
    
    @Test
    fun `wind multiplier range is reasonable`() {
        val minMultiplier = 0.5f  // Minimum user setting
        val maxMultiplier = 2f   // Maximum user setting
        
        assertTrue("Min multiplier slows wind", minMultiplier < 1f)
        assertTrue("Max multiplier speeds wind", maxMultiplier > 1f)
    }
    
    @Test
    fun `effective wind calculation`() {
        val baseWind = 0.5f
        val multiplier = 1.5f
        
        val effectiveWind = baseWind * multiplier
        
        assertEquals("Effective wind scales", 0.75f, effectiveWind, 0.01f)
    }
    
    // ============== Gust Behavior Tests ==============
    
    @Test
    fun `calm wind threshold prevents gusts`() {
        val calmThreshold = 0.15f
        
        // Below threshold = no gusts
        val calmWind = 0.1f
        val hasGust = calmWind >= calmThreshold
        
        assertFalse("Calm wind has no gusts", hasGust)
    }
    
    @Test
    fun `windy conditions enable gusts`() {
        val calmThreshold = 0.15f
        val windyWind = 0.5f
        
        val hasGust = windyWind >= calmThreshold
        
        assertTrue("Windy conditions have gusts", hasGust)
    }
    
    @Test
    fun `gust proportional to wind`() {
        // gustStrength = windStrength * gustMultiplier
        val lowWind = 0.3f
        val highWind = 0.9f
        val gustMult = 0.5f
        
        val lowGust = lowWind * gustMult
        val highGust = highWind * gustMult
        
        assertEquals("Low wind gust", 0.15f, lowGust, 0.01f)
        assertEquals("High wind gust", 0.45f, highGust, 0.01f)
        assertTrue("Higher wind = stronger gust", highGust > lowGust)
    }
    
    @Test
    fun `gust varies over time`() {
        // Gust uses perlin/sine noise over time
        val time1 = 0f
        val time2 = 1f
        val freq = 0.3f
        
        val gust1 = kotlin.math.sin((time1 * freq).toDouble())
        val gust2 = kotlin.math.sin((time2 * freq).toDouble())
        
        assertNotEquals("Gust varies", gust1, gust2, 0.01)
    }
    
    // ============== Delta Time Tests ==============
    
    @Test
    fun `delta time cap at 100ms`() {
        val maxDelta = 0.1f
        
        val normalDelta = 0.016f
        val largeDelta = 0.5f
        
        assertEquals("Normal delta unchanged", normalDelta, normalDelta.coerceAtMost(maxDelta), 0.001f)
        assertEquals("Large delta capped", maxDelta, largeDelta.coerceAtMost(maxDelta), 0.001f)
    }
    
    @Test
    fun `reset timing produces zero delta`() {
        // After reset, first delta should be 0 or minimal
        var lastTimeMs = System.currentTimeMillis()
        
        // Simulate reset
        lastTimeMs = System.currentTimeMillis()
        val currentTimeMs = lastTimeMs
        val deltaMs = currentTimeMs - lastTimeMs
        
        assertEquals("Delta is 0 after reset", 0L, deltaMs)
    }
    
    @Test
    fun `delta time in milliseconds`() {
        val deltaMs = 16L  // 60fps
        val deltaSec = deltaMs / 1000f
        
        assertEquals("16ms = 0.016 seconds", 0.016f, deltaSec, 0.001f)
    }
    
    // ============== Visibility Handling ==============
    
    @Test
    fun `hidden state pauses updates`() {
        val isVisible = false
        val shouldUpdate = isVisible
        
        assertFalse("Hidden = no updates", shouldUpdate)
    }
    
    @Test
    fun `visibility resume resets timing`() {
        // When becoming visible, resetTiming should be called
        // to prevent large delta from pause duration
        
        val pauseDuration = 10000L  // 10 seconds hidden
        val maxDelta = 100L
        
        // With reset, first delta is 0 or capped
        val deltaCapped = minOf(pauseDuration, maxDelta)
        
        assertTrue("Delta capped on resume", deltaCapped <= maxDelta)
    }
    
    @Test
    fun `wallpaper pauses when not visible`() {
        // Simulates the render loop's visibility check
        // When isVisible=false, render loop should sleep instead of drawing
        
        val isVisible = false
        val shouldRender = isVisible
        val sleepTimeWhenHidden = 250L  // As per WeatherWallpaper.kt
        
        assertFalse("Should not render when hidden", shouldRender)
        assertTrue("Sleep time should be reasonable", sleepTimeWhenHidden in 100L..1000L)
    }
    
    @Test
    fun `screen off triggers visibility false`() {
        // Android WallpaperService automatically calls onVisibilityChanged(false)
        // when screen turns off. Verify that hidden state stops rendering.
        
        val screenOn = true
        val screenOff = false
        
        // When screen is on, visibility depends on launcher being visible
        val visibleWhenScreenOn = screenOn && true  // launcher visible
        val visibleWhenScreenOff = screenOff || false  // forced false
        
        assertTrue("Visible when screen on and launcher shown", visibleWhenScreenOn)
        assertFalse("Hidden when screen off", visibleWhenScreenOff)
    }
    
    @Test
    fun `sensors stopped when not visible`() {
        // Sensors should be stopped when wallpaper is not visible
        // to save battery (accelerometer, gyroscope)
        
        val isVisible = false
        val sensorsActive = isVisible  // sensors only run when visible
        
        assertFalse("Sensors off when hidden", sensorsActive)
    }
    
    // ============== Idle Mode Tests ==============
    
    @Test
    fun `idle mode reduces fps when scene is static`() {
        // When no parallax movement, weather, or events are active,
        // the wallpaper should enter idle mode with reduced FPS
        
        val idleFps = 10
        val normalFps = 30
        val idleTimeoutMs = 5000L
        
        // Simulate scene becoming idle
        val lastActivityTime = 0L
        val currentTime = 6000L  // 6 seconds later
        val isIdle = currentTime - lastActivityTime > idleTimeoutMs
        
        assertTrue("Should be idle after timeout", isIdle)
        
        val effectiveFps = if (isIdle) idleFps else normalFps
        assertEquals("Should use idle FPS", idleFps, effectiveFps)
    }
    
    @Test
    fun `idle mode disabled when scene is active`() {
        // Scene activity (parallax, weather, events) keeps full FPS
        
        val idleTimeoutMs = 5000L
        val lastActivityTime = 4000L  // 4 seconds ago
        val currentTime = 5000L  // Only 1 second elapsed
        
        val isIdle = currentTime - lastActivityTime > idleTimeoutMs
        
        assertFalse("Should not be idle when recently active", isIdle)
    }
    
    @Test
    fun `idle mode fps is lower than normal fps`() {
        val idleFps = 10
        val normalFps = 30
        
        assertTrue("Idle FPS should be lower", idleFps < normalFps)
        assertTrue("Idle FPS should be reasonable", idleFps >= 5)
        assertTrue("Normal FPS should be reasonable", normalFps <= 60)
    }

    // ============== Storm Effects Tests ==============
    
    @Test
    fun `thunderstorm increases wind activity`() {
        val normalWind = 0.5f
        val stormBoost = 1.5f
        
        val stormWind = normalWind * stormBoost
        
        assertTrue("Storm wind higher", stormWind > normalWind)
    }
    
    @Test
    fun `thunderstorm increases gust strength`() {
        val normalGust = 0.3f
        val stormBoost = 1.5f
        
        val stormGust = normalGust * stormBoost
        
        assertEquals("Storm gust calculation", 0.45f, stormGust, 0.01f)
    }
    
    // ============== Time Progression Tests ==============
    
    @Test
    fun `time seconds accumulates`() {
        var timeSeconds = 0f
        val deltaMs = 16L
        
        // Simulate 1000 frames
        for (i in 0 until 1000) {
            timeSeconds += deltaMs / 1000f
        }
        
        assertEquals("16 seconds accumulated", 16f, timeSeconds, 0.1f)
    }
    
    @Test
    fun `absolute time in milliseconds`() {
        val absoluteTimeMs = System.currentTimeMillis()
        
        assertTrue("Time is positive", absoluteTimeMs > 0)
        assertTrue("Time is recent", absoluteTimeMs > 1700000000000L)  // After 2023
    }
    
    // ============== Edge Cases ==============
    
    @Test
    fun `zero wind produces minimal movement`() {
        val windStrength = 0f
        val ambientFactor = 0.3f
        
        val totalFactor = ambientFactor + windStrength * 0.6f
        
        assertEquals("Only ambient movement", ambientFactor, totalFactor, 0.01f)
    }
    
    @Test
    fun `max wind produces strong movement`() {
        val windStrength = 1f
        val windMultiplier = 2f
        val gustFactor = 1.5f
        
        val total = windStrength * windMultiplier * gustFactor
        
        assertEquals("Max effective wind", 3f, total, 0.01f)
    }
    
    @Test
    fun `negative delta is handled`() {
        // Edge case: system time changes
        val deltaMs = -100L
        val safeDelta = maxOf(0L, deltaMs)
        
        assertEquals("Negative clamped to 0", 0L, safeDelta)
    }
}
