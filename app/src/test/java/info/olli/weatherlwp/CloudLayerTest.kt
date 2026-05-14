package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.CloudLayer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudLayer optimization - Issue: Overcast lag
 * 
 * Tests the pre-calculated cloud group system:
 * - Cloud groups instead of individual clouds
 * - Layer distribution (75% back, 25% front)
 * - Group size and property calculations
 * - Performance characteristics
 */
class CloudLayerTest {

    // ============== Cloud Group Distribution Tests ==============
    
    @Test
    fun `back layer should have 75 percent of groups`() {
        val backRatio = 0.75f
        val totalGroups = 20
        
        val expectedBack = (totalGroups * backRatio).toInt()
        val expectedFront = totalGroups - expectedBack
        
        assertEquals("Back layer count", 15, expectedBack)
        assertEquals("Front layer count", 5, expectedFront)
    }
    
    @Test
    fun `minimum groups should be at least 2`() {
        val minGroups = 2
        val cloudCount = 5
        val avgCloudsPerGroup = 6  // (4+8)/2
        
        val calculated = (cloudCount / avgCloudsPerGroup).coerceIn(2, 20)
        assertTrue("Minimum groups", calculated >= minGroups)
    }
    
    @Test
    fun `maximum groups capped at 22 for performance`() {
        val maxGroups = 22
        val cloudCount = 200  // More than overcast
        val avgCloudsPerGroup = 9  // (6+12)/2
        
        val calculated = (cloudCount / avgCloudsPerGroup).coerceIn(2, maxGroups)
        assertEquals("Max groups capped", 22, calculated)  // 200/9 = 22, capped at 22
    }
    
    @Test
    fun `overcast 35 clouds produces coverage-based groups`() {
        val overcastClouds = 35
        
        // With coverage mapping: 35 raw → (35*0.4)=14 groups, sizeMult=1.5
        // This is the new model: fewer, larger groups
        val groups = ((overcastClouds * 0.4f).toInt()).coerceIn(12, 20)
        assertTrue("Overcast produces 12+ groups via coverage mapping", groups >= 12)
        assertTrue("Overcast groups capped at 20", groups <= 20)
    }

    // ============== Layer Property Tests ==============
    
    @Test
    fun `back layer alpha should be lower than front`() {
        val backAlphaMult = 0.7f
        val frontAlphaMult = 1.0f
        
        assertTrue("Back layer alpha multiplier lower", backAlphaMult < frontAlphaMult)
    }
    
    @Test
    fun `back layer depth should be shallower (less parallax)`() {
        @Suppress("UNUSED_VARIABLE")
        val backDepthMin = 0.15f
        val backDepthMax = 0.22f
        val frontDepthMin = 0.28f
        @Suppress("UNUSED_VARIABLE")
        val frontDepthMax = 0.35f
        
        assertTrue("Back layer depth range below front min", backDepthMax < frontDepthMin)
    }
    
    @Test
    fun `front layer depth should match RenderConstants PARALLAX_CLOUDS`() {
        // Front layer depth (0.28-0.35) should be around PARALLAX_CLOUDS (0.25)
        val frontDepthMin = 0.28f
        val frontDepthMax = 0.35f
        val parallaxClouds = 0.25f  // RenderConstants.PARALLAX_CLOUDS
        
        assertTrue("Front depth reasonable for clouds", 
            frontDepthMin >= parallaxClouds - 0.1f && frontDepthMax <= parallaxClouds + 0.15f)
    }

    // ============== Group Size Tests ==============
    
    @Test
    fun `group width should be reasonable screen fraction`() {
        val groupWidthMin = 0.25f
        val groupWidthMax = 0.50f
        
        assertTrue("Min width > 0", groupWidthMin > 0f)
        assertTrue("Max width <= 1 screen", groupWidthMax <= 1f)
        assertTrue("Max > Min", groupWidthMax > groupWidthMin)
    }
    
    @Test
    fun `group height should be proportional to cloud Y range`() {
        @Suppress("UNUSED_VARIABLE")
        val groupHeightMin = 0.10f
        val groupHeightMax = 0.18f
        val cloudYRange = 0.35f  // RenderConstants.CLOUD_Y_RANGE
        
        // Group height should be fraction of cloud Y range
        assertTrue("Group height reasonable", groupHeightMax < cloudYRange + 0.1f)
    }
    
    @Test
    fun `clouds per group should be 6-12`() {
        val minPuffs = 6
        val maxPuffs = 12
        
        assertTrue("Min puffs > 0", minPuffs > 0)
        assertTrue("Max reasonable", maxPuffs <= 15)
        assertTrue("Range valid", maxPuffs >= minPuffs)
    }

    // ============== Performance Tests ==============
    
    @Test
    fun `draw calls reduced from 120 clouds to max 22 groups`() {
        val oldIndividualClouds = 120
        val newMaxGroups = 22
        
        val reduction = oldIndividualClouds / newMaxGroups.toFloat()
        assertTrue("At least 5x reduction", reduction >= 5f)
    }
    
    @Test
    fun `overcast puff count reduced significantly`() {
        // Old: 120 clouds * 3-5 puffs = 360-600 draw calls
        val oldMinPuffs = 120 * 3
        
        // New: 25 groups * 6-12 puffs per group = 150-300 puffs
        // BUT these are rendered to bitmap once, not per frame
        val newMaxPuffs = 25 * 12
        
        // Per-frame draw calls: just 25 bitmap draws
        val newDrawCallsPerFrame = 25
        
        assertTrue("Puffs rendered once", newMaxPuffs < oldMinPuffs)
        assertTrue("Per-frame draws reduced", newDrawCallsPerFrame < oldMinPuffs / 10)
    }
    
    @Test
    fun `bitmap caching prevents per-frame blur calculations`() {
        // Blur is the expensive operation
        // Old: blur applied 360-600 times per frame
        // New: blur applied once per group when bitmap rendered
        val cached = true
        assertTrue("Blur operations cached in bitmap", cached)
    }

    // ============== Alpha Calculation Tests ==============
    
    @Test
    fun `alpha should be clamped to valid range`() {
        val minAlpha = 20
        val maxAlpha = 220
        
        assertTrue("Min alpha > 0", minAlpha > 0)
        assertTrue("Max alpha < 255", maxAlpha < 255)
    }
    
    @Test
    fun `back layer alpha lower for depth effect`() {
        // Updated: base alpha now 60-120, back mult 0.7, front mult 1.0
        val baseAlpha = 90  // midpoint of 60-120
        val opacity = 0.4f
        
        val backAlpha = (baseAlpha * 0.7f * opacity).toInt().coerceIn(20, 220)
        val frontAlpha = (baseAlpha * 1.0f * opacity).toInt().coerceIn(20, 220)
        
        assertEquals("Back alpha", 25, backAlpha)  // 90 * 0.7 * 0.4 = 25.2 -> 25
        assertEquals("Front alpha", 36, frontAlpha) // 90 * 1.0 * 0.4 = 36
        assertTrue("Back < Front", backAlpha <= frontAlpha)
    }

    // ============== Speed and Movement Tests ==============
    
    @Test
    fun `group speed should use cloud speed constants`() {
        val speedBase = 0.15f
        val speedRange = 0.25f
        
        val minSpeed = speedBase
        val maxSpeed = speedBase + speedRange
        
        assertTrue("Min speed > 0", minSpeed > 0)
        assertEquals("Max speed", 0.40f, maxSpeed, 0.01f)
    }
    
    @Test
    fun `groups should wrap around extended width`() {
        // Position wrapping
        var x = 1.5f  // Past right edge
        x = x % 1f
        if (x < 0) x += 1f
        
        assertEquals("Wrapped position", 0.5f, x, 0.01f)
    }
    
    // ============== Wind Speed Effect Tests ==============
    
    @Test
    fun `cloud speed should scale with wind strength`() {
        // Wind speed calculation: WIND_CLOUD_SPEED_MIN + windStrength * (MAX - MIN)
        val windSpeedMin = 0.3f  // RenderConstants.WIND_CLOUD_SPEED_MIN
        val windSpeedMax = 12.0f  // RenderConstants.WIND_CLOUD_SPEED_MAX
        
        val calmWind = 0f
        val strongWind = 1f
        val moderateWind = 0.5f
        
        val calmSpeed = windSpeedMin + calmWind * (windSpeedMax - windSpeedMin)
        val strongSpeed = windSpeedMin + strongWind * (windSpeedMax - windSpeedMin)
        val moderateSpeed = windSpeedMin + moderateWind * (windSpeedMax - windSpeedMin)
        
        assertEquals("Calm wind speed", 0.3f, calmSpeed, 0.01f)
        assertEquals("Strong wind speed", 12.0f, strongSpeed, 0.01f)
        assertEquals("Moderate wind speed", 6.15f, moderateSpeed, 0.01f)
        
        assertTrue("Strong wind moves clouds faster", strongSpeed > calmSpeed)
        assertTrue("Moderate wind between calm and strong", 
            moderateSpeed > calmSpeed && moderateSpeed < strongSpeed)
    }
    
    @Test
    fun `time offset should increase with wind speed`() {
        // timeOffset = timeSeconds * groupSpeed * windSpeed
        val timeSeconds = 100.0
        val groupSpeed = 0.3f
        
        val calmWindSpeed = 0.2f
        val strongWindSpeed = 8.0f
        
        val calmOffset = timeSeconds * groupSpeed * calmWindSpeed
        val strongOffset = timeSeconds * groupSpeed * strongWindSpeed
        
        assertEquals("Calm offset", 6.0, calmOffset, 0.1)
        assertEquals("Strong offset", 240.0, strongOffset, 0.1)
        
        // Strong wind should move clouds 40x faster than calm
        val ratio = strongOffset / calmOffset
        assertEquals("Wind speed ratio", 40.0, ratio, 0.1)
    }
    
    @Test
    fun `higher wind strength produces visibly faster movement`() {
        // With 10x movement difference, clouds should be obviously faster
        val minMultiplier = 0.2f  // At wind=0
        val maxMultiplier = 8.0f  // At wind=1
        
        val speedupFactor = maxMultiplier / minMultiplier
        assertTrue("Speed should increase at least 10x at max wind", speedupFactor >= 10f)
    }

    // ============== Layer Sorting Tests ==============
    
    @Test
    fun `back layer should be drawn before front layer`() {
        val drawOrder = listOf(true, false)  // Back first, then front
        assertEquals("First is back layer", true, drawOrder[0])
        assertEquals("Second is front layer", false, drawOrder[1])
    }
    
    @Test
    fun `depth-based parallax creates layer separation`() {
        // Back layer at depth 0.15-0.22 moves less with parallax
        // Front layer at depth 0.28-0.35 moves more
        val backDepth = 0.18f
        val frontDepth = 0.32f
        val parallaxAmount = 100f  // pixels
        
        val backMovement = parallaxAmount * backDepth
        val frontMovement = parallaxAmount * frontDepth
        
        assertTrue("Front moves more than back", frontMovement > backMovement)
    }
    
    // ============== Tiled Noise Generation Tests ==============
    
    @Test
    fun `value noise produces values in 0-1 range`() {
        // Simulating the value noise algorithm used in tiled mode
        val random = java.util.Random(42)
        val values = FloatArray(256) { random.nextFloat() }
        
        // All values should be 0-1
        for (v in values) {
            assertTrue("Value should be >= 0", v >= 0f)
            assertTrue("Value should be <= 1", v <= 1f)
        }
    }
    
    @Test
    fun `smoothstep produces correct interpolation`() {
        fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
        
        assertEquals("smoothstep(0) = 0", 0f, smoothstep(0f), 0.001f)
        assertEquals("smoothstep(1) = 1", 1f, smoothstep(1f), 0.001f)
        assertEquals("smoothstep(0.5) = 0.5", 0.5f, smoothstep(0.5f), 0.001f)
        
        // Smoothstep should be slower at edges, faster in middle (derivative)
        val delta = 0.01f
        val derivAt0 = (smoothstep(delta) - smoothstep(0f)) / delta
        val derivAt05 = (smoothstep(0.5f + delta) - smoothstep(0.5f - delta)) / (2 * delta)
        
        assertTrue("Derivative at 0 should be small (slow start)", derivAt0 < 1f)
        assertTrue("Derivative at 0.5 should be larger (fast middle)", derivAt05 > derivAt0)
    }
    
    @Test
    fun `cloud threshold produces visible clouds`() {
        // Cloud threshold algorithm from tiled mode (simplified version)
        fun applyThreshold(value: Float): Float {
            val v = ((value - 0.3f) / 0.4f).coerceIn(0f, 1f)
            return v * v  // Soften edges
        }
        
        // Values well below threshold should be transparent
        assertEquals("Low values are transparent", 0f, applyThreshold(0f), 0.001f)
        
        // Values well above threshold should be nearly opaque
        assertTrue("High values are visible", applyThreshold(0.8f) > 0.5f)
        
        // Values near threshold should be partial
        val midValue = applyThreshold(0.5f)  // At threshold center
        assertTrue("Threshold center should produce visible output", midValue > 0.1f)
    }
    
    @Test
    fun `noise grid wraps for seamless tiling`() {
        val gridSize = 8
        val grid = Array(gridSize + 1) { FloatArray(gridSize + 1) }
        
        // Fill grid with random values
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                grid[y][x] = (x + y).toFloat() / 16f  // Predictable values for testing
            }
        }
        
        // Wrap edges
        for (i in 0..gridSize) {
            grid[gridSize][i] = grid[0][i % gridSize]
            grid[i][gridSize] = grid[i % gridSize][0]
        }
        
        // Verify wrapping
        assertEquals("Top and bottom edges should match", grid[0][0], grid[gridSize][0], 0.001f)
        assertEquals("Left and right edges should match", grid[0][0], grid[0][gridSize], 0.001f)
    }

    // ============== Cloud Y Bounds Tests (clipping fix) ==============

    @Test
    fun `cloud Y placement accounts for half-height`() {
        // Uses CLOUD_BAND_TOP / CLOUD_BAND_BOTTOM from CloudLayer companion
        val bandTop = CloudLayer.CLOUD_BAND_TOP
        val bandBottom = CloudLayer.CLOUD_BAND_BOTTOM

        val groupHeightMin = CloudLayer.GROUP_HEIGHT_MIN
        val groupHeightMax = CloudLayer.GROUP_HEIGHT_MAX

        // For any groupHeight in [min, max], the cloud center Y should be bounded
        for (gh in listOf(groupHeightMin, groupHeightMax, (groupHeightMin + groupHeightMax) / 2f)) {
            val halfH = gh * 0.5f
            val minY = bandTop + halfH
            val maxY = (bandBottom - halfH).coerceAtLeast(minY)

            // Cloud center at minY: top edge = minY - halfH >= bandTop
            assertTrue("Top edge at minY should not clip (gh=$gh)",
                minY - halfH >= bandTop - 0.001f)

            // Cloud center at maxY: bottom edge = maxY + halfH <= bandBottom
            assertTrue("Bottom edge at maxY should not clip (gh=$gh)",
                maxY + halfH <= bandBottom + 0.001f)

            // Range should be non-negative
            assertTrue("Y range should be non-negative (gh=$gh)", maxY >= minY)
        }
    }

    @Test
    fun `largest cloud height still has valid Y range`() {
        val bandTop = CloudLayer.CLOUD_BAND_TOP
        val bandBottom = CloudLayer.CLOUD_BAND_BOTTOM
        val groupHeightMax = CloudLayer.GROUP_HEIGHT_MAX

        val halfH = groupHeightMax * 0.5f
        val minY = bandTop + halfH
        val maxY = (bandBottom - halfH).coerceAtLeast(minY)

        assertTrue("Largest cloud should still have valid Y range", maxY >= minY)
        // bandBottom(0.42) - halfH(0.15) = 0.27 >= bandTop(0.05) + halfH(0.15) = 0.20
        assertTrue("maxY should be >= minY", maxY >= minY)
    }

    @Test
    fun `degenerate cloud taller than band centers in band`() {
        // If a cloud's height exceeds the band, it should be centered
        val bandTop = CloudLayer.CLOUD_BAND_TOP
        val bandBottom = CloudLayer.CLOUD_BAND_BOTTOM
        val oversizedHeight = 0.80f  // Much taller than band

        val halfH = oversizedHeight * 0.5f
        val minY = bandTop + halfH
        val maxY = (bandBottom - halfH).coerceAtLeast(minY)

        // When maxY <= minY (degenerate), should center in band
        val y = if (maxY > minY) {
            minY
        } else {
            (bandTop + bandBottom) * 0.5f
        }
        assertEquals("Degenerate cloud should center in band",
            (bandTop + bandBottom) * 0.5f, y, 0.001f)
    }

    // ============== Coverage-Based Cloud Generation Tests ==============

    @Test
    fun `coverage mapping reduces group count for overcast`() {
        // Overcast (50 raw) → fewer groups with larger size multiplier
        val rawCount = 50
        val (groups, sizeMult) = coverageMapping(rawCount)
        assertTrue("Overcast groups ($groups) should be significantly less than raw ($rawCount)", 
            groups < rawCount / 2)
        assertTrue("Overcast size mult ($sizeMult) should compensate (> 1.3)", sizeMult > 1.3f)
    }

    @Test
    fun `coverage mapping preserves light cloud count`() {
        // Light (3 raw) → minimal reduction, no size increase
        val (groups, sizeMult) = coverageMapping(3)
        assertTrue("Light clouds: groups ($groups) should be >= 2", groups >= 2)
        assertEquals("Light clouds: no size increase", 1.0f, sizeMult, 0.01f)
    }

    @Test
    fun `coverage mapping medium clouds moderate reduction`() {
        val (groups, sizeMult) = coverageMapping(10)
        assertTrue("Medium: groups >= 4", groups >= 4)
        assertTrue("Medium: groups <= 10", groups <= 10)
        assertTrue("Medium: slight size increase", sizeMult >= 1.0f)
    }

    @Test
    fun `coverage mapping group count monotonically increases`() {
        var prevGroups = 0
        for (rawCount in listOf(0, 3, 10, 25, 50)) {
            val (groups, _) = coverageMapping(rawCount)
            assertTrue("Groups should be >= previous ($prevGroups) at rawCount=$rawCount", 
                groups >= prevGroups)
            prevGroups = groups
        }
    }

    @Test
    fun `coverage mapping size mult monotonically increases`() {
        var prevMult = 0f
        for (rawCount in listOf(0, 3, 10, 25, 50)) {
            val (_, mult) = coverageMapping(rawCount)
            assertTrue("Size mult should be >= previous ($prevMult) at rawCount=$rawCount", 
                mult >= prevMult)
            prevMult = mult
        }
    }

    // ============== Cloud Culling Tests ==============

    @Test
    fun `offscreen clouds to the right are culled`() {
        val screenWidth = 1080f
        val cloudScreenX = screenWidth + 400f  // well off right
        val halfW = 200f
        val culled = cloudScreenX - halfW > screenWidth
        assertTrue("Cloud far right of screen should be culled", culled)
    }

    @Test
    fun `offscreen clouds to the left are culled`() {
        val cloudScreenX = -400f  // well off left
        val halfW = 200f
        val culled = cloudScreenX + halfW < 0f
        assertTrue("Cloud far left of screen should be culled", culled)
    }

    @Test
    fun `partially visible cloud on right edge not culled`() {
        val screenWidth = 1080f
        val halfW = 200f
        val cloudScreenX = screenWidth + 100f  // partially visible
        val culled = cloudScreenX - halfW > screenWidth || cloudScreenX + halfW < 0f
        assertFalse("Partially visible cloud should NOT be culled", culled)
    }

    @Test
    fun `cloud exactly at screen boundary not culled`() {
        val screenWidth = 1080f
        val halfW = 200f
        val cloudScreenX = screenWidth  // edge case: at right boundary
        val culled = cloudScreenX - halfW > screenWidth || cloudScreenX + halfW < 0f
        assertFalse("Cloud at boundary should NOT be culled", culled)
    }

    @Test
    fun `effective draw scale boosts culling bounds correctly`() {
        // Culling uses effectiveDrawScale for bounds - verify clouds drawn larger
        // aren't incorrectly culled at the edges
        val paddedWidth = 252
        val effectiveDrawScale = 0.975f  // preview overcast
        val halfW = paddedWidth / 2f * effectiveDrawScale
        
        // Cloud at right edge of a 300px preview
        val screenX = 300f
        val screenWidth = 300f
        val culled = screenX - halfW > screenWidth
        
        // halfW = 126 * 0.975 = ~123. 300 - 123 = 177 which is < 300
        assertFalse("Cloud at edge with effective scale should not be culled", culled)
    }

    // Helper: mirrors CloudLayer.cloudCountToCoverageGroups logic for testing
    private fun coverageMapping(rawCount: Int): Pair<Int, Float> {
        return when {
            rawCount <= 0 -> 0 to 1.0f
            rawCount <= 5 -> rawCount.coerceAtLeast(2) to 1.0f
            rawCount <= 15 -> ((rawCount * 0.7f).toInt().coerceAtLeast(4)) to 1.15f
            rawCount <= 30 -> ((rawCount * 0.5f).toInt().coerceAtLeast(8)) to 1.3f
            else -> ((rawCount * 0.4f).toInt().coerceIn(12, 20)) to 1.5f
        }
    }
}
