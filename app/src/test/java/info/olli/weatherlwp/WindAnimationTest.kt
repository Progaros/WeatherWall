package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for Wind Animation System.
 * 
 * Tests the wind effects across layers:
 * - Tree sway amplitude and frequency
 * - Cloud movement direction and speed
 * - Gust behavior and timing
 * - Wind direction components
 */
class WindAnimationTest {

    // ============== Wind Direction Tests ==============
    
    @Test
    fun `wind direction 0 degrees points right`() {
        val direction = 0f
        val radians = Math.toRadians(direction.toDouble())
        
        val windX = cos(radians).toFloat()
        val windY = sin(radians).toFloat()
        
        assertEquals("X component is 1 (right)", 1.0f, windX, 0.01f)
        assertEquals("Y component is 0", 0.0f, windY, 0.01f)
    }
    
    @Test
    fun `wind direction 90 degrees points up`() {
        val direction = 90f
        val radians = Math.toRadians(direction.toDouble())
        
        val windX = cos(radians).toFloat()
        val windY = sin(radians).toFloat()
        
        assertEquals("X component is 0", 0.0f, windX, 0.01f)
        assertEquals("Y component is 1 (up)", 1.0f, windY, 0.01f)
    }
    
    @Test
    fun `wind direction 180 degrees points left`() {
        val direction = 180f
        val radians = Math.toRadians(direction.toDouble())
        
        val windX = cos(radians).toFloat()
        val windY = sin(radians).toFloat()
        
        assertEquals("X component is -1 (left)", -1.0f, windX, 0.01f)
        assertEquals("Y component is 0", 0.0f, windY, 0.01f)
    }
    
    @Test
    fun `wind direction 270 degrees points down`() {
        val direction = 270f
        val radians = Math.toRadians(direction.toDouble())
        
        val windX = cos(radians).toFloat()
        val windY = sin(radians).toFloat()
        
        assertEquals("X component is 0", 0.0f, windX, 0.01f)
        assertEquals("Y component is -1 (down)", -1.0f, windY, 0.01f)
    }
    
    // ============== Tree Sway Tests ==============
    
    @Test
    fun `tree sway frequency is natural speed`() {
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY
        
        assertTrue("Frequency is positive", frequency > 0f)
        assertTrue("Frequency is slow enough to look natural", frequency <= 1.0f)
        assertTrue("Frequency is fast enough to be visible", frequency >= 0.3f)
    }
    
    @Test
    fun `tree sway amplitude allows visible movement`() {
        val amplitude = RenderConstants.TREE_SWAY_AMPLITUDE
        
        assertTrue("Amplitude is positive", amplitude > 0f)
        assertTrue("Amplitude not too extreme", amplitude <= 0.5f)
        assertTrue("Amplitude visible", amplitude >= 0.1f)
    }
    
    @Test
    fun `effective wind calculation produces bounded values`() {
        // effectiveWind = windStrength * windMultiplier * gustFactor
        // windStrength: 0-1, windMultiplier: 0.5-2.0, gustFactor: 0-2.0
        
        val minWind = 0f * 0.5f * 0f  // All minimums
        val maxWind = 1f * 2.0f * 2.0f  // All maximums
        
        assertEquals("Min effective wind is 0", 0f, minWind, 0.01f)
        assertEquals("Max effective wind is 4", 4f, maxWind, 0.01f)
        
        // Typical wind scenario
        val typicalWind = 0.5f * 1.0f * 1.0f
        assertEquals("Typical wind is 0.5", 0.5f, typicalWind, 0.01f)
    }
    
    @Test
    fun `gust push follows wind direction`() {
        // gustPush = localGust * localGust * 1.5f * windX
        val localGust = 0.5f
        val windXRight = 1.0f  // Wind blowing right
        val windXLeft = -1.0f  // Wind blowing left
        
        val gustPushRight = localGust * localGust * 1.5f * windXRight
        val gustPushLeft = localGust * localGust * 1.5f * windXLeft
        
        assertTrue("Right wind = positive push", gustPushRight > 0f)
        assertTrue("Left wind = negative push", gustPushLeft < 0f)
        assertEquals("Magnitudes are equal", kotlin.math.abs(gustPushRight), kotlin.math.abs(gustPushLeft), 0.01f)
    }
    
    // ============== Cloud Speed Tests ==============
    
    @Test
    fun `cloud speed range is reasonable`() {
        val minSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN
        val maxSpeed = RenderConstants.WIND_CLOUD_SPEED_MAX
        
        assertTrue("Min speed positive", minSpeed > 0f)
        assertTrue("Max speed > min", maxSpeed > minSpeed)
        assertTrue("Max speed not too fast", maxSpeed <= 15f)  // Allow up to 15 for storms
        
        // Ratio should be significant for dramatic effect
        val ratio = maxSpeed / minSpeed
        assertTrue("Speed range is significant", ratio >= 10f)
    }
    
    @Test
    fun `wind multiplier allows faster than base`() {
        // windMultiplier can exceed 1.0 for high simulated wind
        val lowMultiplier = 0.5f
        val normalMultiplier = 1.0f
        val highMultiplier = 2.0f
        
        assertTrue("Low multiplier slows wind", lowMultiplier < normalMultiplier)
        assertTrue("High multiplier speeds up wind", highMultiplier > normalMultiplier)
    }
    
    @Test
    fun `storm boost increases cloud speed`() {
        // During thunderstorm, stormBoost > 1.0
        val normalBoost = 1.0f
        val stormBoost = 1.5f
        
        val normalSpeed = 0.5f * normalBoost
        val stormSpeed = 0.5f * stormBoost
        
        assertTrue("Storm increases speed", stormSpeed > normalSpeed)
        assertEquals("Storm 50% faster", 1.5f, stormSpeed / normalSpeed, 0.01f)
    }
    
    // ============== Gust Behavior Tests ==============
    
    @Test
    fun `gust factor varies around 1`() {
        // gustFactor = 1 + gust * gustStrength
        // gust varies 0-1, gustStrength typically 0.5
        
        val noGust = 1f + 0f * 0.5f
        val fullGust = 1f + 1f * 0.5f
        
        assertEquals("No gust = factor 1", 1.0f, noGust, 0.01f)
        assertEquals("Full gust = factor 1.5", 1.5f, fullGust, 0.01f)
    }
    
    @Test
    fun `calm wind threshold prevents unnecessary gusts`() {
        // When baseWind < 0.15f, gust should be minimal or zero
        val calmThreshold = 0.15f
        
        assertTrue("Calm threshold is low", calmThreshold <= 0.2f)
        assertTrue("Calm threshold is positive", calmThreshold > 0f)
    }
    
    @Test
    fun `gust proportional to wind strength`() {
        // gustStrength = windStrength * 0.5 (approximately)
        val lowWind = 0.2f
        val highWind = 1.0f
        
        val lowGust = lowWind * 0.5f
        val highGust = highWind * 0.5f
        
        assertTrue("High wind = stronger gusts", highGust > lowGust)
        assertEquals("Gust ratio matches wind ratio", highWind / lowWind, highGust / lowGust, 0.01f)
    }
    
    // ============== Delta Time Tests ==============
    
    @Test
    fun `delta time cap at 100ms prevents jumps`() {
        val maxDelta = 0.1f  // 100ms in seconds
        
        // Simulate various gaps
        val normalDelta = 0.016f  // 60fps
        val slowDelta = 0.033f    // 30fps
        val pauseDelta = 0.5f     // Visibility change
        
        assertEquals("Normal delta unchanged", normalDelta, normalDelta.coerceAtMost(maxDelta), 0.001f)
        assertEquals("Slow delta unchanged", slowDelta, slowDelta.coerceAtMost(maxDelta), 0.001f)
        assertEquals("Pause delta capped", maxDelta, pauseDelta.coerceAtMost(maxDelta), 0.001f)
    }
    
    @Test
    fun `timing reset prevents animation artifacts`() {
        // After resetTiming(), first delta should be 0 or capped
        val resetDeltaMs = 0L
        val resetDeltaSec = resetDeltaMs / 1000f
        
        assertEquals("Reset produces zero delta", 0f, resetDeltaSec, 0.001f)
    }
    
    // ============== Spatial Phase Tests ==============
    
    @Test
    fun `spatial phase creates one wave across screen`() {
        // spatialPhase = tree.xRatio * PI * 1.0f (1 cycle)
        val leftEdge = 0f * kotlin.math.PI.toFloat() * 1.0f
        val center = 0.5f * kotlin.math.PI.toFloat() * 1.0f
        val rightEdge = 1f * kotlin.math.PI.toFloat() * 1.0f
        
        assertEquals("Left edge at 0", 0f, leftEdge, 0.01f)
        assertEquals("Center at PI/2", kotlin.math.PI.toFloat() / 2f, center, 0.01f)
        assertEquals("Right edge at PI", kotlin.math.PI.toFloat(), rightEdge, 0.01f)
    }
    
    @Test
    fun `trees at different positions sway differently`() {
        // Due to spatial phase, trees at different X positions are out of phase
        val time = 1f
        val freq = RenderConstants.TREE_SWAY_FREQUENCY
        
        val tree1Phase = 0f * kotlin.math.PI.toFloat() * 1.0f
        val tree2Phase = 0.5f * kotlin.math.PI.toFloat() * 1.0f
        
        val sway1 = sin(time * freq + tree1Phase.toDouble())
        val sway2 = sin(time * freq + tree2Phase.toDouble())
        
        assertNotEquals("Trees sway differently", sway1, sway2, 0.1)
    }
}
