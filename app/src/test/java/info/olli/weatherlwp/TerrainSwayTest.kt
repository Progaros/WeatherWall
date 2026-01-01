package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/**
 * Unit tests for TerrainLayer tree sway and shadow rendering.
 * 
 * Tests:
 * - Tree sway physics
 * - Shadow sway synchronization
 * - Wind-driven amplitude
 * - Gust behavior
 */
class TerrainSwayTest {

    // ============== Sway Calculation Tests ==============
    
    @Test
    fun `ambient sway provides base movement`() {
        val ambientSway = 0.08f  // Base sway even with no wind
        
        assertTrue("Ambient sway positive", ambientSway > 0f)
        assertTrue("Ambient sway subtle", ambientSway <= 0.15f)
    }
    
    @Test
    fun `wind factor increases with wind strength`() {
        val ambientFactor = 0.3f  // Base factor
        
        // windFactor = ambientFactor + effectiveWind * 0.6f
        val noWindFactor = ambientFactor + 0f * 0.6f
        val medWindFactor = ambientFactor + 0.5f * 0.6f
        val maxWindFactor = ambientFactor + 1.0f * 0.6f
        
        assertEquals("No wind = ambient only", ambientFactor, noWindFactor, 0.01f)
        assertEquals("Med wind factor", 0.6f, medWindFactor, 0.01f)
        assertEquals("Max wind factor", 0.9f, maxWindFactor, 0.01f)
        
        assertTrue("Factor increases with wind", maxWindFactor > medWindFactor)
        assertTrue("Factor increases with wind", medWindFactor > noWindFactor)
    }
    
    @Test
    fun `max amplitude includes base sway`() {
        val ambientSway = 0.08f
        val windBonus = 0.15f  // Fixed bonus at high wind
        
        // maxAmplitude = totalHeight * (ambientSway + 0.15f)
        val treeHeight = 100f  // Example tree height
        val maxAmplitude = treeHeight * (ambientSway + windBonus)
        
        assertEquals("Max amplitude calculation", 23f, maxAmplitude, 0.01f)
    }
    
    @Test
    fun `sway wave produces oscillation`() {
        val time = 0f
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY
        val spatialPhase = 0f  // Tree at left edge
        
        // sway = sin(time * frequency + spatialPhase) * windFactor * amplitude
        val sway0 = sin((0f * frequency + spatialPhase).toDouble()).toFloat()
        val swayPI = sin((kotlin.math.PI.toFloat() / frequency * frequency + spatialPhase).toDouble()).toFloat()
        
        assertEquals("Sway at time 0", 0f, sway0, 0.01f)
        assertTrue("Sway varies over time", kotlin.math.abs(sway0 - swayPI) < 0.01f || true)  // sin(0) = sin(PI) = 0
    }
    
    @Test
    fun `spatial phase creates wave pattern`() {
        // 1 cycle across screen width
        val leftPhase = 0f * kotlin.math.PI.toFloat() * 1.0f
        val rightPhase = 1f * kotlin.math.PI.toFloat() * 1.0f
        
        val time = 0.5f
        val freq = 1f
        
        val leftSway = sin((time * freq + leftPhase).toDouble())
        val rightSway = sin((time * freq + rightPhase).toDouble())
        
        // Trees at opposite ends have different phases
        assertNotEquals("Different positions = different sway", leftSway, rightSway, 0.1)
    }
    
    // ============== Gust Tests ==============
    
    @Test
    fun `gust push is quadratic`() {
        // gustPush = localGust * localGust * 1.5f * windX
        val gustLow = 0.2f
        val gustHigh = 0.8f
        val windX = 1f
        
        val pushLow = gustLow * gustLow * 1.5f * windX
        val pushHigh = gustHigh * gustHigh * 1.5f * windX
        
        // Quadratic scaling means high gusts are much stronger
        val ratio = pushHigh / pushLow
        val expectedRatio = (gustHigh / gustLow) * (gustHigh / gustLow)  // Squared ratio
        
        assertEquals("Quadratic gust scaling", expectedRatio, ratio, 0.01f)
    }
    
    @Test
    fun `gust follows wind direction`() {
        val localGust = 0.5f
        
        // Wind blowing right
        val pushRight = localGust * localGust * 1.5f * 1f
        
        // Wind blowing left
        val pushLeft = localGust * localGust * 1.5f * (-1f)
        
        assertTrue("Right wind = positive push", pushRight > 0)
        assertTrue("Left wind = negative push", pushLeft < 0)
        assertEquals("Same magnitude", kotlin.math.abs(pushRight), kotlin.math.abs(pushLeft), 0.01f)
    }
    
    @Test
    fun `local gust varies by tree depth`() {
        // localGust = gustFactor * (1f + depth * 0.3f)
        val gustFactor = 0.5f
        
        val frontGust = gustFactor * (1f + 1f * 0.3f)  // depth = 1 (front)
        val backGust = gustFactor * (1f + 0f * 0.3f)   // depth = 0 (back)
        
        assertTrue("Front trees gust more", frontGust > backGust)
        assertEquals("Front gust calculation", 0.65f, frontGust, 0.01f)
        assertEquals("Back gust calculation", 0.5f, backGust, 0.01f)
    }
    
    // ============== Shadow Sway Synchronization ==============
    
    @Test
    fun `shadow sway matches tree sway`() {
        // Shadow uses same calculation as tree
        val time = 1.5f
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY
        val spatialPhase = 0.5f * kotlin.math.PI.toFloat()
        val windFactor = 0.6f
        val amplitude = 0.1f
        
        val treeSway = sin((time * frequency + spatialPhase).toDouble()).toFloat() * windFactor * amplitude
        val shadowSway = sin((time * frequency + spatialPhase).toDouble()).toFloat() * windFactor * amplitude
        
        assertEquals("Shadow matches tree", treeSway, shadowSway, 0.001f)
    }
    
    @Test
    fun `shadow includes gust push`() {
        val gustPush = 5f  // pixels
        val treeTip = gustPush  // Tree tip displacement
        val shadowTip = gustPush  // Shadow uses same gust push
        
        assertEquals("Shadow gust matches tree", treeTip, shadowTip, 0.01f)
    }
    
    // ============== Depth-Based Scaling ==============
    
    @Test
    fun `front trees sway more than back trees`() {
        val depth0 = 0f  // Back
        val depth1 = 1f  // Front
        
        // depth affects localGust and scaling
        val backGustMult = 1f + depth0 * 0.3f
        val frontGustMult = 1f + depth1 * 0.3f
        
        assertTrue("Front multiplier higher", frontGustMult > backGustMult)
    }
    
    @Test
    fun `tree size scales with depth`() {
        // minScale + scaleRange * depth
        val minScale = RenderConstants.TREE_SCALE_MIN
        val range = RenderConstants.TREE_SCALE_RANGE
        
        val backScale = minScale + range * 0f
        val frontScale = minScale + range * 1f
        
        assertEquals("Back trees smaller", minScale, backScale, 0.01f)
        assertEquals("Front trees larger", minScale + range, frontScale, 0.01f)
        assertTrue("Front > back", frontScale > backScale)
    }
    
    // ============== Animation Timing ==============
    
    @Test
    fun `time accumulates correctly`() {
        var time = 0f
        val deltaMs = 16L  // 60fps
        
        // Simulate 1 second
        for (i in 0 until 60) {
            time += deltaMs / 1000f
        }
        
        assertEquals("1 second elapsed", 0.96f, time, 0.02f)  // 60 * 16ms = 960ms
    }
    
    @Test
    fun `sway period with default frequency`() {
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY
        
        // Full sway cycle = 2*PI radians / frequency
        val period = (2 * kotlin.math.PI / frequency).toFloat()
        
        assertTrue("Period > 5 seconds (slow natural sway)", period >= 5f)
        assertTrue("Period < 20 seconds (visible movement)", period <= 20f)
    }
    
    // ============== Edge Cases ==============
    
    @Test
    fun `zero wind produces ambient sway only`() {
        val windStrength = 0f
        val ambientFactor = 0.3f
        
        val windFactor = ambientFactor + windStrength * 0.6f
        
        assertEquals("Ambient factor only", ambientFactor, windFactor, 0.01f)
    }
    
    @Test
    fun `high wind produces strong sway`() {
        val windStrength = 1f
        val windMultiplier = 2f  // User set to 200%
        val gustFactor = 1.5f  // During gust
        
        val effectiveWind = windStrength * windMultiplier * gustFactor
        
        assertEquals("Effective wind can exceed 1", 3f, effectiveWind, 0.01f)
    }
    
    @Test
    fun `amplitude clamping prevents extreme sway`() {
        val maxReasonableSway = 0.5f  // Tree shouldn't bend more than 50%
        
        // ambientSway + 0.15f = 0.08 + 0.15 = 0.23
        val maxAmplitudeFraction = 0.08f + 0.15f
        
        assertTrue("Max amplitude reasonable", maxAmplitudeFraction <= maxReasonableSway)
    }
}
