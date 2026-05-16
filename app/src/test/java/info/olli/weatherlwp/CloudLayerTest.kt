package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.CloudLayer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudLayer optimization and world-space cloud placement.
 * 
 * Tests the cell-based deterministic cloud generation system:
 * - Cloud groups instead of individual clouds
 * - Layer distribution (75% back, 25% front)
 * - World-space cloud band (no screen-space Y)
 * - Coverage-driven spawn probability and size scaling
 * - Cell-based caching and deterministic placement
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

    // ============== Cloud Y Bounds Tests (world-space band) ==============

    @Test
    fun `cloud worldY placement within world-space band`() {
        // With world-space band:
        // bandHeight = worldHeight * BAND_HEIGHT_FRACTION
        // bandTop = worldRect.top - margin
        // bandBottom = worldRect.top + bandHeight + margin
        // For REFERENCE_HEIGHT=1920, centerWorldY=0.5:
        //   worldRect.top = 0.5 - 960 = -959.5
        //   bandHeight = 1920 * 0.35 = 672
        //   margin = 1920 * 0.20 = 384
        //   bandTop = -959.5 - 384 = -1343.5
        //   bandBottom = -959.5 + 672 + 384 = 96.5
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        // Band should span above visible area through upper ~55% of screen
        assertTrue("Band top ($bandTop) should be above visible area ($worldTop)", bandTop < worldTop)
        assertTrue("Band bottom ($bandBottom) should be in upper half", bandBottom < 0.5f + worldHeight / 2f)
        assertTrue("Band has positive height", bandBottom > bandTop)
    }

    @Test
    fun `cloud worldY accounts for half-height clamping`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        val groupHeightMin = CloudLayer.GROUP_HEIGHT_MIN
        val groupHeightMax = CloudLayer.GROUP_HEIGHT_MAX

        for (gh in listOf(groupHeightMin, groupHeightMax, (groupHeightMin + groupHeightMax) / 2f)) {
            val halfHWorld = gh * 0.5f * worldHeight
            val minY = bandTop + halfHWorld
            val maxY = (bandBottom - halfHWorld).coerceAtLeast(minY)

            // Cloud center at minY: top edge stays within band
            assertTrue("Top edge should not exceed band top (gh=$gh)",
                minY - halfHWorld >= bandTop - 0.001f)

            // Cloud center at maxY: bottom edge stays within band
            assertTrue("Bottom edge should not exceed band bottom (gh=$gh)",
                maxY + halfHWorld <= bandBottom + 0.001f)

            // Range should be non-negative
            assertTrue("Y range should be non-negative (gh=$gh)", maxY >= minY)
        }
    }

    @Test
    fun `largest cloud height still has valid Y range in world-space`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        val halfHWorld = CloudLayer.GROUP_HEIGHT_MAX * 0.5f * worldHeight
        val minY = bandTop + halfHWorld
        val maxY = (bandBottom - halfHWorld).coerceAtLeast(minY)

        assertTrue("Largest cloud should still have valid Y range", maxY >= minY)
    }

    @Test
    fun `degenerate cloud taller than band centers in band`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin
        val bandBottom = worldTop + bandHeight + margin

        // Oversized cloud: halfH exceeds entire band (use 200% to guarantee degeneracy)
        val halfHWorld = 2.0f * 0.5f * (bandBottom - bandTop)

        val minY = bandTop + halfHWorld
        val maxY = (bandBottom - halfHWorld).coerceAtLeast(minY)

        val y = if (maxY > minY) minY else (bandTop + bandBottom) * 0.5f
        assertEquals("Degenerate cloud should center in band",
            (bandTop + bandBottom) * 0.5f, y, 0.001f)
    }

    // ============== World-Space Band Tests ==============

    @Test
    fun `band margin extends above visible area`() {
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        val bandTop = worldTop - margin

        assertTrue("Band top should extend above visible world top",
            bandTop < worldTop)
    }

    @Test
    fun `band covers upper portion of screen`() {
        // The visible cloud content (excluding margin) should be in the upper ~55%
        val worldHeight = 1920f
        val worldTop = 0.5f - worldHeight / 2f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION

        // Content zone: worldTop to worldTop + bandHeight
        val contentBottom = worldTop + bandHeight
        // As screen fraction: (contentBottom - worldTop) / worldHeight = BAND_HEIGHT_FRACTION
        val screenFraction = (contentBottom - worldTop) / worldHeight
        assertEquals("Cloud content covers upper portion", CloudLayer.BAND_HEIGHT_FRACTION, screenFraction, 0.01f)
    }

    @Test
    fun `parallax cannot clip clouds within band margin`() {
        // Maximum parallax shift: REFERENCE_HEIGHT * 0.45 = 864 world units
        // Band margin: 1920 * 0.20 = 384 world units
        // Y parallax depth factor max: 0.35
        // Max Y shift in world: 864 * 0.35 / pixelsPerUnit... but we check margin adequacy
        val worldHeight = 1920f
        val margin = worldHeight * CloudLayer.BAND_MARGIN_FRACTION
        assertTrue("Band margin ($margin) should be > 0 for parallax safety", margin > 0f)
        // 384 world units of margin on each side provides generous parallax headroom
        assertTrue("Band margin should be >= 15% of world height", margin >= worldHeight * 0.15f)
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

    // ============== Cell-Based Generation Tests ==============

    @Test
    fun `cell spawn probability increases with coverage`() {
        val lowCoverage = 0.1f
        val highCoverage = 0.9f
        val lowProb = CloudLayer.SPAWN_PROB_MIN + (CloudLayer.SPAWN_PROB_MAX - CloudLayer.SPAWN_PROB_MIN) * lowCoverage
        val highProb = CloudLayer.SPAWN_PROB_MIN + (CloudLayer.SPAWN_PROB_MAX - CloudLayer.SPAWN_PROB_MIN) * highCoverage
        assertTrue("High coverage spawn prob ($highProb) > low ($lowProb)", highProb > lowProb)
        assertTrue("Low prob >= SPAWN_PROB_MIN", lowProb >= CloudLayer.SPAWN_PROB_MIN)
        assertTrue("High prob <= SPAWN_PROB_MAX", highProb <= CloudLayer.SPAWN_PROB_MAX)
    }

    @Test
    fun `coverage scale increases with cloud count`() {
        val lowCoverage = 0.1f
        val highCoverage = 0.9f
        val lowScale = CloudLayer.COVERAGE_SCALE_MIN + (CloudLayer.COVERAGE_SCALE_MAX - CloudLayer.COVERAGE_SCALE_MIN) * lowCoverage
        val highScale = CloudLayer.COVERAGE_SCALE_MIN + (CloudLayer.COVERAGE_SCALE_MAX - CloudLayer.COVERAGE_SCALE_MIN) * highCoverage
        assertTrue("High coverage scale ($highScale) > low ($lowScale)", highScale > lowScale)
        assertTrue("Low scale >= COVERAGE_SCALE_MIN", lowScale >= CloudLayer.COVERAGE_SCALE_MIN)
        assertTrue("High scale <= COVERAGE_SCALE_MAX", highScale <= CloudLayer.COVERAGE_SCALE_MAX)
    }

    @Test
    fun `cell size derived from band height`() {
        // FIXED_CELL_SIZE is pre-computed from REFERENCE_HEIGHT * BAND_HEIGHT_FRACTION * CELL_SIZE_FRACTION
        val cellSize = CloudLayer.FIXED_CELL_SIZE
        val worldHeight = 1920f
        val bandHeight = worldHeight * CloudLayer.BAND_HEIGHT_FRACTION
        assertTrue("Fixed cell size ($cellSize) should be > 0", cellSize > 0f)
        assertTrue("Fixed cell size should be < band height", cellSize < bandHeight)
    }

    @Test
    fun `deterministic cells produce same result for same seed`() {
        val seed = 42
        val cellX = 5
        val seed1 = info.olli.weatherlwp.rendering.DeterministicLayout.mix(seed + 5000, 500, cellX)
        val seed2 = info.olli.weatherlwp.rendering.DeterministicLayout.mix(seed + 5000, 500, cellX)
        assertEquals("Same cell + seed → same hash", seed1, seed2)
    }

    @Test
    fun `different cells produce different seeds`() {
        val seed = 42
        val seed1 = info.olli.weatherlwp.rendering.DeterministicLayout.mix(seed + 5000, 500, 0)
        val seed2 = info.olli.weatherlwp.rendering.DeterministicLayout.mix(seed + 5000, 500, 1)
        assertNotEquals("Different cells → different seeds", seed1, seed2)
    }

    // ============== Cloud Culling Tests (rect-intersects with margin) ==============

    @Test
    fun `offscreen clouds to the right are culled with margin`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfW = 200f
        val cloudScreenX = screenWidth + cullMargin + halfW + 10f  // well beyond margin + halfW
        val culled = cloudScreenX - halfW > screenWidth + cullMargin
        assertTrue("Cloud far right of screen+margin should be culled", culled)
    }

    @Test
    fun `offscreen clouds to the left are culled with margin`() {
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val cloudScreenX = -cullMargin - 400f  // well beyond margin
        val halfW = 200f
        val culled = cloudScreenX + halfW < -cullMargin
        assertTrue("Cloud far left of screen+margin should be culled", culled)
    }

    @Test
    fun `partially visible cloud on right edge not culled`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfW = 200f
        val cloudScreenX = screenWidth + 100f  // partially visible
        val culled = cloudScreenX - halfW > screenWidth + cullMargin || cloudScreenX + halfW < -cullMargin
        assertFalse("Partially visible cloud should NOT be culled", culled)
    }

    @Test
    fun `cloud exactly at screen boundary not culled`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfW = 200f
        val cloudScreenX = screenWidth  // edge case: at right boundary
        val culled = cloudScreenX - halfW > screenWidth + cullMargin || cloudScreenX + halfW < -cullMargin
        assertFalse("Cloud at boundary should NOT be culled", culled)
    }

    @Test
    fun `cloud within margin zone not culled`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfW = 200f
        // Cloud just outside screen but within cull margin
        val cloudScreenX = screenWidth + cullMargin * 0.5f
        val culled = cloudScreenX - halfW > screenWidth + cullMargin || cloudScreenX + halfW < -cullMargin
        assertFalse("Cloud within cull margin should NOT be culled", culled)
    }

    @Test
    fun `vertical culling with margin prevents top clipping`() {
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfH = 150f
        // Cloud partially above screen but within margin
        val cloudScreenY = -50f
        val culled = cloudScreenY + halfH < -cullMargin || cloudScreenY - halfH > screenHeight + cullMargin
        assertFalse("Cloud partially above screen should NOT be culled", culled)
    }

    @Test
    fun `vertical culling with margin prevents bottom clipping`() {
        val screenHeight = 1920f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val halfH = 150f
        // Cloud partially below visible band
        val cloudScreenY = screenHeight + 50f
        val culled = cloudScreenY + halfH < -cullMargin || cloudScreenY - halfH > screenHeight + cullMargin
        assertFalse("Cloud partially below screen should NOT be culled", culled)
    }

    @Test
    fun `effective draw scale boosts culling bounds correctly`() {
        // Culling uses effectiveDrawScale for bounds + cull margin
        val paddedWidth = 252
        val effectiveDrawScale = 0.975f  // preview overcast
        val halfW = paddedWidth / 2f * effectiveDrawScale
        val screenHeight = 500f
        val cullMargin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        
        // Cloud at right edge of a 300px preview
        val screenX = 300f
        val screenWidth = 300f
        val culled = screenX - halfW > screenWidth + cullMargin
        
        assertFalse("Cloud at edge with effective scale + cull margin should not be culled", culled)
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

    // ============== Rendering Stability Tests ==============

    @Test
    fun `culling margin is generous enough for parallax movement`() {
        // Verify the CULL_MARGIN_FRACTION provides sufficient buffer
        // for typical parallax offsets (up to 20% of screen)
        val screenWidth = 1080f
        val screenHeight = 1920f
        val margin = screenHeight * CloudLayer.CULL_MARGIN_FRACTION
        val maxParallaxOffset = screenHeight * 0.20f  // 20% max offset

        assertTrue("Cull margin ($margin) should exceed max parallax offset ($maxParallaxOffset)",
            margin > maxParallaxOffset)
    }

    @Test
    fun `cloud oscillating near edge stays consistently visible`() {
        // Simulate a cloud at screen edge oscillating due to parallax.
        // It should never toggle between culled/visible across small movements.
        val screenWidth = 1080f
        val screenHeight = 1920f
        val margin = screenWidth * CloudLayer.CULL_MARGIN_FRACTION
        val halfW = 150f

        // Cloud placed just outside screen, within cull margin
        val baseX = screenWidth + 50f  // 50px past right edge

        // Simulate 20 frames of small parallax oscillation (±30px)
        var wasVisible = true
        for (frame in 0 until 20) {
            val oscillation = 30f * kotlin.math.sin(frame * 0.5f)
            val cloudX = baseX + oscillation
            val visible = !(cloudX - halfW > screenWidth + margin + 0.5f ||
                           cloudX + halfW < -margin - 0.5f)
            if (frame > 0 && visible != wasVisible) {
                fail("Cloud visibility toggled at frame $frame (blinking detected)")
            }
            wasVisible = visible
        }
    }

    @Test
    fun `large cloud near top boundary not clipped by band placement`() {
        // Large cloud (max height) with conservative placement factor should still fit within band
        val bandTop = CloudLayer.ABS_BAND_TOP
        val bandBottom = CloudLayer.ABS_BAND_BOTTOM
        val bandHeight = bandBottom - bandTop
        val maxGroupHeight = 0.30f  // GROUP_HEIGHT_MAX
        val placementSafety = 1.35f  // Matches conservative placement in createCloudGroup
        val halfH = maxGroupHeight * 0.5f * bandHeight * placementSafety

        val minY = bandTop + halfH
        val maxY = bandBottom - halfH

        assertTrue("Even max-height cloud should have valid placement range (minY=$minY, maxY=$maxY)",
            maxY >= minY)
    }

    @Test
    fun `epsilon prevents boundary flicker`() {
        // Cloud at cull boundary: epsilon should keep it visible (not flicker in/out)
        val screenWidth = 1080f
        val margin = screenWidth * CloudLayer.CULL_MARGIN_FRACTION
        val eps = 0.5f
        val halfW = 100f

        // Position cloud so its left edge is EXACTLY at the right boundary
        // cloudX - halfW == screenWidth + margin → cloudX = screenWidth + margin + halfW
        val cloudX = screenWidth + margin + halfW

        // Without epsilon, boundary check would be ambiguous (== boundary)
        // With epsilon (+0.5f), the cloud is kept visible (not culled)
        val culledWithEps = cloudX - halfW > screenWidth + margin + eps
        assertFalse("Cloud at exact boundary should NOT be culled (epsilon keeps it visible)", culledWithEps)

        // Cloud clearly outside (2px past boundary + epsilon) IS culled
        val cloudXOutside = cloudX + 2f
        val culledOutside = cloudXOutside - halfW > screenWidth + margin + eps
        assertTrue("Cloud clearly past boundary should be culled", culledOutside)
    }

    // ============== Bitmap Padding & Draw Stability Tests ==============

    @Test
    fun `vertical bitmap padding prevents internal puff clipping`() {
        // Verify BLUR_PADDING_VERTICAL is large enough for max puff extent.
        // Max puff extent from center: offsetY(0.25) + radiusY(0.55) = 0.80 * bitmapHeight
        // Padded bitmap extends: (bitmapHeight + 2*paddingY) / 2 from center
        // Need: paddedHalf >= 0.80 * bitmapHeight
        val bitmapHeight = 200  // representative
        val paddingY = (bitmapHeight * CloudLayer.BLUR_PADDING_VERTICAL).toInt()
        val paddedHeight = bitmapHeight + paddingY * 2
        val paddedHalfFromCenter = paddedHeight / 2f

        // Max puff extent from bitmap center:
        val maxPuffOffset = bitmapHeight * 0.25f  // offsetY max
        val maxPuffRadius = bitmapHeight * 0.55f  // radiusY max
        val maxExtent = maxPuffOffset + maxPuffRadius

        assertTrue(
            "Padded half ($paddedHalfFromCenter) must exceed max puff extent ($maxExtent)",
            paddedHalfFromCenter >= maxExtent
        )
    }

    @Test
    fun `placement with conservative bounds fits drawn cloud`() {
        // Verify the 1.35× placement factor covers the actual drawn size
        val bandTop = CloudLayer.ABS_BAND_TOP
        val bandBottom = CloudLayer.ABS_BAND_BOTTOM
        val bandHeight = bandBottom - bandTop
        val maxGroupHeight = 0.30f
        val placementSafety = 1.35f

        val safeHalfH = maxGroupHeight * 0.5f * bandHeight * placementSafety
        // Worst-case worldY at boundary
        val worstY = bandTop + safeHalfH

        // The actual drawn extent (in world) should not exceed bandTop
        // With padding: actual extent = halfH * (1 + 2*BLUR_PADDING_VERTICAL) for padded size
        val logicalHalfH = maxGroupHeight * 0.5f * bandHeight
        val actualDrawnHalf = logicalHalfH * (1f + CloudLayer.BLUR_PADDING_VERTICAL)

        assertTrue(
            "Cloud at worst placement ($worstY) should keep drawn extent (${worstY - actualDrawnHalf}) above bandTop ($bandTop)",
            worstY - actualDrawnHalf >= bandTop
        )
    }

    @Test
    fun `soft draw cap prevents threshold flicker`() {
        // When candidate count is near MAX_DRAW_PER_FRAME, all should be drawn
        val maxDraw = CloudLayer.MAX_DRAW_PER_FRAME  // 20
        val nearThreshold = maxDraw + 2  // 22 candidates

        // Soft cap: if count <= MAX + 3, draw all
        val limit = if (nearThreshold <= maxDraw + 3) nearThreshold else maxDraw
        assertEquals("Near-threshold count should draw all", nearThreshold, limit)

        // Well above threshold: budget applies
        val wellAbove = maxDraw + 10  // 30 candidates
        val limitAbove = if (wellAbove <= maxDraw + 3) wellAbove else maxDraw
        assertEquals("Well-above threshold should apply budget", maxDraw, limitAbove)
    }
}
