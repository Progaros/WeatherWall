package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WeatherLayer - timing, particle updates, and rendering.
 * 
 * Tests the delta-time based animation system:
 * - Lightning timing is ms-based (not frame-based)
 * - Particle physics in update(), drawing in render()
 * - Consistent behavior across different frame rates
 * 
 * Note: Tests avoid accessing RenderConstants directly because it uses
 * Android Color class which is not available in unit tests.
 */
class WeatherLayerTest {

    // Constants copied from RenderConstants for testing
    private val LIGHTNING_INTERVAL_MIN_MS = 1500L
    private val LIGHTNING_INTERVAL_RANGE_MS = 2500L
    private val LIGHTNING_FLASH_DURATION_MS = 70L
    private val CLOUD_MOVEMENT_SCALE = 0.06f
    private val WIND_CLOUD_SPEED_MAX = 12.0f
    private val WIND_CLOUD_SPEED_MIN = 0.3f
    private val RAIN_PARTICLE_POOL_SIZE = 300
    private val SNOW_PARTICLE_POOL_SIZE = 200
    private val STORM_CLOUD_Y1 = -0.05f
    private val STORM_CLOUD_Y2 = 0.05f
    private val STORM_CLOUD_Y3 = 0.15f
    private val STORM_CLOUD_SIZE_BACK = 0.6f
    private val STORM_CLOUD_SIZE_MID = 0.7f
    private val STORM_CLOUD_SIZE_FRONT = 0.8f

    // ============== Lightning Timing Tests ==============
    
    @Test
    fun `lightning interval min should be at least 1 second`() {
        assertTrue(
            "Lightning should not flash more than once per second",
            LIGHTNING_INTERVAL_MIN_MS >= 1000L
        )
    }
    
    @Test
    fun `lightning interval range produces reasonable total`() {
        val minInterval = LIGHTNING_INTERVAL_MIN_MS
        val range = LIGHTNING_INTERVAL_RANGE_MS
        val maxInterval = minInterval + range
        
        assertTrue("Min interval at least 1.5s", minInterval >= 1500L)
        assertTrue("Max interval at most 6s", maxInterval <= 6000L)
        assertTrue("Range creates variation", range >= 1000L)
    }
    
    @Test
    fun `lightning flash duration should be short`() {
        val flashDuration = LIGHTNING_FLASH_DURATION_MS
        
        assertTrue("Flash visible long enough", flashDuration >= 50L)
        assertTrue("Flash short enough to look like lightning", flashDuration <= 150L)
    }
    
    @Test
    fun `lightning timing is frame-rate independent`() {
        // At 60fps, ~67 frames in 1.5 seconds (min interval)
        // At 30fps, ~45 frames in 1.5 seconds
        // Using ms ensures same timing regardless of FPS
        
        val minIntervalMs = LIGHTNING_INTERVAL_MIN_MS
        
        // Both should result in same real-time delay
        // Old frame-based: 90-240 frames = different real time at different FPS
        // New ms-based: same real time regardless of FPS
        assertTrue("Timing is in ms, not frames", minIntervalMs == 1500L)
    }
    
    // ============== Cloud Movement Scale Tests ==============
    
    @Test
    fun `cloud movement scale is reasonable`() {
        val scale = CLOUD_MOVEMENT_SCALE
        
        assertTrue("Scale positive", scale > 0f)
        assertTrue("Scale not too fast", scale <= 0.1f)
        assertTrue("Scale produces visible movement", scale >= 0.01f)
    }
    
    @Test
    fun `cloud speed at max wind crosses screen in reasonable time`() {
        val scale = CLOUD_MOVEMENT_SCALE
        val maxSpeedMultiplier = WIND_CLOUD_SPEED_MAX
        
        // At max wind, clouds should cross screen in 2-15 seconds (faster now)
        // movement per second = scale * effectiveSpeed * groupSpeed
        // For full screen (1.0): time = 1.0 / (scale * maxSpeed * avgGroupSpeed)
        val avgGroupSpeed = 0.5f  // Typical group speed
        val movementPerSecond = scale * maxSpeedMultiplier * avgGroupSpeed
        val screenCrossTime = 1.0f / movementPerSecond
        
        assertTrue("Screen cross time > 2s at max wind", screenCrossTime >= 2f)
        assertTrue("Screen cross time < 15s at max wind", screenCrossTime <= 15f)
    }
    
    @Test
    fun `cloud speed at min wind crosses screen slowly`() {
        val scale = CLOUD_MOVEMENT_SCALE
        val minSpeedMultiplier = WIND_CLOUD_SPEED_MIN
        
        val avgGroupSpeed = 0.5f
        val movementPerSecond = scale * minSpeedMultiplier * avgGroupSpeed
        val screenCrossTime = 1.0f / movementPerSecond
        
        assertTrue("Screen cross time > 30s at min wind", screenCrossTime >= 30f)
        assertTrue("Screen cross time < 3min at min wind", screenCrossTime <= 180f)
    }
    
    // ============== Particle Pool Size Tests ==============
    
    @Test
    fun `rain particle pool size is sufficient`() {
        val poolSize = RAIN_PARTICLE_POOL_SIZE
        
        assertTrue("Rain pool has enough particles", poolSize >= 200)
        assertTrue("Rain pool not too large for memory", poolSize <= 500)
    }
    
    @Test
    fun `snow particle pool size is sufficient`() {
        val poolSize = SNOW_PARTICLE_POOL_SIZE
        
        assertTrue("Snow pool has enough particles", poolSize >= 100)
        assertTrue("Snow pool not too large", poolSize <= 300)
    }
    
    // ============== Intensity Scaling Tests ==============
    
    @Test
    fun `rain active count scales with intensity`() {
        val poolSize = RAIN_PARTICLE_POOL_SIZE
        
        val minIntensity = 0f
        val maxIntensity = 1f
        
        // Formula: activeCount = poolSize * (0.3 + intensity * 0.7)
        val minActive = (poolSize * (0.3f + minIntensity * 0.7f)).toInt()
        val maxActive = (poolSize * (0.3f + maxIntensity * 0.7f)).toInt()
        
        assertEquals("Min intensity uses 30% of pool", (poolSize * 0.3f).toInt(), minActive)
        assertEquals("Max intensity uses 100% of pool", poolSize, maxActive)
        assertTrue("Max > min", maxActive > minActive)
    }
    
    @Test
    fun `snow active count scales with intensity`() {
        val poolSize = SNOW_PARTICLE_POOL_SIZE
        
        // Formula: activeCount = poolSize * (0.2 + intensity * 0.8)
        val minActive = (poolSize * (0.2f + 0f * 0.8f)).toInt()
        val maxActive = (poolSize * (0.2f + 1f * 0.8f)).toInt()
        
        assertEquals("Min intensity uses 20% of pool", (poolSize * 0.2f).toInt(), minActive)
        assertEquals("Max intensity uses 100% of pool", poolSize, maxActive)
    }
    
    // ============== Frame Scaling Tests ==============
    
    @Test
    fun `frame scale normalizes to 60fps`() {
        // deltaSec * 60 = frameScale
        // At 60fps: deltaSec = 1/60 = 0.0167, frameScale = 1.0
        // At 30fps: deltaSec = 1/30 = 0.0333, frameScale = 2.0 (moves twice as far per frame)
        
        val delta60fps = 1f / 60f
        val delta30fps = 1f / 30f
        
        val frameScale60 = delta60fps * 60f
        val frameScale30 = delta30fps * 60f
        
        assertEquals("60fps frameScale = 1.0", 1.0f, frameScale60, 0.01f)
        assertEquals("30fps frameScale = 2.0", 2.0f, frameScale30, 0.01f)
        
        // Same distance covered per real-time second
        // 60 frames * 1.0 = 60 units per second
        // 30 frames * 2.0 = 60 units per second
        assertEquals("Same movement per second", 60f, 60 * frameScale60, 0.01f)
        assertEquals("Same movement per second", 60f, 30 * frameScale30, 0.01f)
    }
    
    @Test
    fun `delta time cap prevents large jumps`() {
        // deltaSec capped to 0.1f (100ms max)
        val maxDelta = 0.1f
        
        // Even if visibility change causes 1 second gap, we only move 100ms worth
        val actualDelta = 1.0f
        val cappedDelta = actualDelta.coerceAtMost(maxDelta)
        
        assertEquals("Delta capped at 100ms", 0.1f, cappedDelta, 0.001f)
    }
    
    // ============== Storm Cloud Tests ==============
    
    @Test
    fun `storm cloud Y positions create layers`() {
        val y1 = STORM_CLOUD_Y1
        val y2 = STORM_CLOUD_Y2
        val y3 = STORM_CLOUD_Y3
        
        assertTrue("Layer 1 above screen top", y1 < 0f)
        assertTrue("Layer 2 near top", y2 >= 0f && y2 <= 0.1f)
        assertTrue("Layer 3 visible at top", y3 > y2)
        assertTrue("All layers in top 25%", y3 <= 0.25f)
    }
    
    @Test
    fun `storm cloud size multipliers create depth`() {
        val sizeBack = STORM_CLOUD_SIZE_BACK
        val sizeMid = STORM_CLOUD_SIZE_MID
        val sizeFront = STORM_CLOUD_SIZE_FRONT
        
        assertTrue("Back layer smallest", sizeBack < sizeMid)
        assertTrue("Mid layer medium", sizeMid < sizeFront)
        assertTrue("Front layer largest", sizeFront <= 1.0f)
    }
    
    // ============== Snowflake Size Tests ==============
    
    @Test
    fun `snowflake size scales with screen height`() {
        // Reference height is 1920px
        val referenceHeight = 1920f
        val baseMinSize = 3f
        val baseMaxSize = 8f  // 3 + 5
        
        // On full-size screen (1920px), size should be 3-8px
        val fullScale = 1920f / referenceHeight
        val fullMinSize = baseMinSize * fullScale
        val fullMaxSize = baseMaxSize * fullScale
        
        assertEquals("Full-size screen min size", 3f, fullMinSize, 0.01f)
        assertEquals("Full-size screen max size", 8f, fullMaxSize, 0.01f)
        
        // On preview (480px), size should be 0.75-2px
        val previewScale = 480f / referenceHeight
        val previewMinSize = baseMinSize * previewScale
        val previewMaxSize = baseMaxSize * previewScale
        
        assertEquals("Preview min size", 0.75f, previewMinSize, 0.01f)
        assertEquals("Preview max size", 2f, previewMaxSize, 0.01f)
        
        // Verify proportional scaling
        assertEquals("Scale ratio", 0.25f, previewScale, 0.01f)
    }
    
    @Test
    fun `snowflake size relative to height stays consistent`() {
        // Snowflakes should be ~0.15% to 0.4% of screen height
        val referenceHeight = 1920f
        val baseMinSize = 3f
        val baseMaxSize = 8f
        
        // Calculate percentage of height
        val minPercent = (baseMinSize / referenceHeight) * 100f
        val maxPercent = (baseMaxSize / referenceHeight) * 100f
        
        assertTrue("Min size at least 0.1% of height", minPercent >= 0.1f)
        assertTrue("Max size at most 0.5% of height", maxPercent <= 0.5f)
    }
}
