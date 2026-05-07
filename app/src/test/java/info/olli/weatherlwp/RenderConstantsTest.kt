package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RenderConstants and scene layout calculations.
 * 
 * Tests:
 * - Extended canvas calculations for parallax
 * - Parallax depth factors
 * - Time thresholds
 * 
 * Note: Tests avoid accessing RenderConstants directly because it uses
 * Android Color class which is not available in unit tests.
 * Instead, we test the mathematical concepts.
 */
class RenderConstantsTest {

    // ============== Extended Canvas Tests ==============
    
    @Test
    fun `extended width stays centered around visible width`() {
        val screenWidth = 1080f
        val sideMarginRatio = 0.38f
        val sideMargin = screenWidth * sideMarginRatio
        
        val extended = screenWidth + sideMargin * 2f
        val expected = 1900.8f
        
        assertEquals("Extended width = width + centered side margins", expected, extended, 0.01f)
    }
    
    @Test
    fun `extended width adds safety margin`() {
        val screenWidth = 3000f  // Very wide screen
        val sideMargin = screenWidth * 0.38f
        
        val extended = screenWidth + sideMargin * 2f
        
        assertTrue("Extended exceeds screen width", extended > screenWidth)
    }
    
    @Test
    fun `extended offset centers content`() {
        val screenWidth = 1080f
        val extendedWidth = 3840f
        
        val offset = (extendedWidth - screenWidth) / 2f
        val expected = (extendedWidth - screenWidth) / 2f
        
        assertEquals("Offset centers extended canvas", expected, offset, 0.01f)
    }
    
    @Test
    fun `extended margin ratio is reasonable`() {
        val marginRatio = 0.38f  // EXTENDED_SIDE_MARGIN_RATIO
        
        assertTrue("Margin ratio > 0 for parallax content", marginRatio > 0f)
        assertTrue("Margin ratio stays performance-friendly", marginRatio <= 0.50f)
    }
    
    // ============== Parallax Depth Factor Tests ==============
    
    @Test
    fun `parallax factors are ordered by depth`() {
        // Values from RenderConstants
        val sky = 0.05f
        val stars = 0.10f
        val clouds = 0.15f
        val mountainMin = 0.20f
        val mountainMax = 0.35f
        val hillsMin = 0.50f
        @Suppress("UNUSED_VARIABLE")
        val hillsMax = 0.80f
        
        assertTrue("Sky is farthest", sky < stars)
        assertTrue("Stars behind clouds", stars < clouds)
        assertTrue("Clouds behind mountains", clouds < mountainMin)
        assertTrue("Mountains behind hills", mountainMax < hillsMin)
    }
    
    @Test
    fun `parallax factors in valid range`() {
        val factors = listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.35f, 0.50f, 0.80f)
        
        for (factor in factors) {
            assertTrue("Factor >= 0", factor >= 0f)
            assertTrue("Factor <= 1", factor <= 1f)
        }
    }
    
    @Test
    fun `hills have most parallax movement`() {
        val hillsMax = 0.80f
        val allOthers = listOf(0.05f, 0.10f, 0.15f, 0.35f)
        
        for (other in allOthers) {
            assertTrue("Hills move more than $other", hillsMax > other)
        }
    }
    
    // ============== Time Threshold Tests ==============
    
    @Test
    fun `time thresholds are in order`() {
        val nightEnd = 5.5f
        val dawnStart = 5.5f
        val dawnEnd = 7.0f
        val dayEnd = 17.5f
        val duskEnd = 20.0f
        
        assertEquals("Night end = dawn start", nightEnd, dawnStart, 0.01f)
        assertTrue("Dawn ends before day ends", dawnEnd < dayEnd)
        assertTrue("Day ends before dusk ends", dayEnd < duskEnd)
        assertTrue("All within 24 hours", duskEnd <= 24f)
    }
    
    @Test
    fun `dawn is in morning`() {
        val dawnStart = 5.5f
        val dawnEnd = 7.0f
        
        assertTrue("Dawn starts early", dawnStart >= 4f && dawnStart <= 7f)
        assertTrue("Dawn ends in morning", dawnEnd >= 6f && dawnEnd <= 9f)
    }
    
    @Test
    fun `dusk is in evening`() {
        val dayEnd = 17.5f
        val duskEnd = 20.0f
        
        assertTrue("Day ends in late afternoon", dayEnd >= 16f && dayEnd <= 19f)
        assertTrue("Dusk ends in evening", duskEnd >= 19f && duskEnd <= 22f)
    }
    
    // ============== Terrain Generation Tests ==============
    
    @Test
    fun `hill height parameters are valid`() {
        val base = 0.35f  // HILL_BASE_HEIGHT
        val range = 0.4f  // HILL_HEIGHT_RANGE
        
        assertTrue("Base in lower half", base >= 0.3f && base <= 0.5f)
        assertTrue("Range provides variation", range >= 0.2f && range <= 0.5f)
    }
    
    @Test
    fun `tree scale parameters are valid`() {
        val minScale = 0.7f  // TREE_SCALE_MIN
        val range = 0.3f     // TREE_SCALE_RANGE
        val maxScale = minScale + range
        
        assertTrue("Min scale reasonable", minScale >= 0.5f)
        assertTrue("Max scale not too large", maxScale <= 1.5f)
    }
    
    @Test
    fun `mountain parameters create visible peaks`() {
        val heightMin = 0.3f  // MOUNTAIN_HEIGHT_MIN
        val heightRange = 0.3f // MOUNTAIN_HEIGHT_RANGE
        val widthMin = 0.8f   // MOUNTAIN_WIDTH_MIN
        
        assertTrue("Mountains tall enough", heightMin >= 0.2f)
        assertTrue("Mountain height varies", heightRange >= 0.2f)
        assertTrue("Mountains wide enough", widthMin >= 0.5f)
    }
    
    // ============== Cloud Generation Tests ==============
    
    @Test
    fun `cloud Y position in upper screen`() {
        val yMin = 0.05f  // CLOUD_Y_MIN
        val yRange = 0.35f // CLOUD_Y_RANGE
        val yMax = yMin + yRange
        
        assertTrue("Clouds start near top", yMin <= 0.1f)
        assertTrue("Clouds don't go below middle", yMax <= 0.5f)
    }
    
    @Test
    fun `cloud size is visible but not huge`() {
        val sizeMin = 0.08f  // CLOUD_SIZE_MIN
        val sizeRange = 0.25f // CLOUD_SIZE_RANGE
        val sizeMax = sizeMin + sizeRange
        
        assertTrue("Clouds visible", sizeMin >= 0.05f)
        assertTrue("Clouds not too large", sizeMax <= 0.5f)
    }
    
    @Test
    fun `cloud speed parameters allow movement`() {
        val speedBase = 0.15f   // CLOUD_SPEED_BASE
        val speedRange = 0.25f  // CLOUD_SPEED_RANGE
        
        assertTrue("Base speed positive", speedBase > 0f)
        assertTrue("Speed varies", speedRange > 0f)
    }
    
    // ============== Fog Color Tests ==============
    
    @Test
    fun `fog color is light gray`() {
        val r = 210  // FOG_R
        val g = 215  // FOG_G
        val b = 225  // FOG_B
        
        assertTrue("Fog R is light", r >= 180 && r <= 240)
        assertTrue("Fog G is light", g >= 180 && g <= 240)
        assertTrue("Fog B is light", b >= 180 && b <= 240)
    }
    
    // ============== Haze Tests ==============
    
    @Test
    fun `haze starts in lower half`() {
        val startY = 0.55f  // HAZE_START_Y
        val endY = 0.85f    // HAZE_END_Y
        
        assertTrue("Haze starts in middle", startY >= 0.4f && startY <= 0.7f)
        assertTrue("Haze ends near bottom", endY >= 0.7f && endY <= 1.0f)
        assertTrue("End > start", endY > startY)
    }
    
    @Test
    fun `haze alpha is subtle`() {
        val alpha = 25  // HAZE_ALPHA
        
        assertTrue("Haze visible", alpha >= 10)
        assertTrue("Haze not too opaque", alpha <= 50)
    }
    
    // ============== New Utility Function Tests ==============
    
    @Test
    fun `hill parallax depth calculation concept`() {
        // Tests the concept used in getHillParallaxDepth()
        val hillsMin = 0.50f  // PARALLAX_HILLS_MIN
        val hillsMax = 0.80f  // PARALLAX_HILLS_MAX
        
        // At normalized depth 0.0 (closest)
        val depthAtZero = hillsMin + 0.0f * (hillsMax - hillsMin)
        assertEquals("At 0 = min", hillsMin, depthAtZero, 0.001f)
        
        // At normalized depth 1.0 (furthest)
        val depthAtOne = hillsMin + 1.0f * (hillsMax - hillsMin)
        assertEquals("At 1 = max", hillsMax, depthAtOne, 0.001f)
        
        // At normalized depth 0.5 (middle)
        val depthAtHalf = hillsMin + 0.5f * (hillsMax - hillsMin)
        val expectedMid = (hillsMin + hillsMax) / 2f
        assertEquals("At 0.5 = middle", expectedMid, depthAtHalf, 0.001f)
    }
    
    @Test
    fun `hill parallax depth monotonically increases`() {
        val hillsMin = 0.50f
        val hillsMax = 0.80f
        
        var lastDepth = -1f
        for (i in 0..10) {
            val normalizedDepth = i / 10f
            val depth = hillsMin + normalizedDepth * (hillsMax - hillsMin)
            assertTrue("Depth increases with distance: $normalizedDepth", depth > lastDepth)
            lastDepth = depth
        }
    }
    
    @Test
    fun `atmospheric perspective blend amount concept`() {
        // Tests the concept used in applyAmbientTint()
        // More distant objects get more tint (atmospheric perspective)
        val minBlend = 0.05f  // At distance 0
        val maxBlend = 0.45f  // At distance 1
        
        // Distance 0 (foreground) - minimal tint
        val blendAtZero = minBlend + 0.0f * 0.4f
        assertEquals("Foreground minimal tint", minBlend, blendAtZero, 0.001f)
        
        // Distance 1 (background) - more tint
        val blendAtOne = minBlend + 1.0f * 0.4f
        assertEquals("Background more tint", maxBlend, blendAtOne, 0.001f)
    }
    
    @Test
    fun `height scale calculation concept`() {
        // Tests the concept used in getHeightScale()
        val referenceHeight = 1920f
        val minScale = 0.5f
        
        // At reference height - scale is 1.0
        val scaleAtRef = (1920f / referenceHeight).coerceAtLeast(minScale)
        assertEquals("At reference height = 1.0", 1.0f, scaleAtRef, 0.001f)
        
        // At double height - scale is 2.0
        val scaleAtDouble = (3840f / referenceHeight).coerceAtLeast(minScale)
        assertEquals("At double height = 2.0", 2.0f, scaleAtDouble, 0.001f)
        
        // At half height - scale is 0.5 (clamped to min)
        val scaleAtHalf = (960f / referenceHeight).coerceAtLeast(minScale)
        assertEquals("At half height = 0.5 (min)", minScale, scaleAtHalf, 0.001f)
        
        // At quarter height - still 0.5 (clamped)
        val scaleAtQuarter = (480f / referenceHeight).coerceAtLeast(minScale)
        assertEquals("At quarter height = 0.5 (clamped)", minScale, scaleAtQuarter, 0.001f)
    }
    
    @Test
    fun `event duration constants are reasonable`() {
        // New events added for seasonal system
        val squirrelDuration = 10000L
        val dragonflyDuration = 10000L
        val snowflakeDuration = 20000L
        
        assertTrue("Squirrel duration reasonable", squirrelDuration in 5000L..30000L)
        assertTrue("Dragonfly duration reasonable", dragonflyDuration in 5000L..20000L)
        assertTrue("Snowflake duration allows slow fall", snowflakeDuration in 10000L..30000L)
    }

    // ============== Direct Function Tests ==============

    @Test
    fun `getHeightScale at reference height returns 1`() {
        assertEquals(1.0f, info.olli.weatherlwp.rendering.RenderConstants.getHeightScale(1920f), 0.001f)
    }

    @Test
    fun `getHeightScale at double height returns 2`() {
        assertEquals(2.0f, info.olli.weatherlwp.rendering.RenderConstants.getHeightScale(3840f), 0.001f)
    }

    @Test
    fun `getHeightScale clamps at minimum`() {
        assertEquals(0.5f, info.olli.weatherlwp.rendering.RenderConstants.getHeightScale(100f), 0.001f)
    }

    @Test
    fun `getHeightScale at zero still returns minimum`() {
        assertEquals(0.5f, info.olli.weatherlwp.rendering.RenderConstants.getHeightScale(0f), 0.001f)
    }

    @Test
    fun `getExtendedWidth is wider than screen`() {
        val extended = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 1920f)
        assertTrue(extended > 1080f)
    }

    @Test
    fun `getExtendedWidth equals genWorldRange at reference height`() {
        // At reference height, heightScale = 1.0, so extendedWidth = genWorldRange
        val extended = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 1920f)
        val genRange = info.olli.weatherlwp.rendering.RenderConstants.getGenerationWorldRange(1080f)
        assertEquals(genRange, extended, 0.01f)
    }

    @Test
    fun `getExtendedWidth scales linearly with height`() {
        // extendedWidth = genWorldRange * heightScale
        // Doubling height doubles heightScale (if not clamped) → doubles extendedWidth
        val ext1 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 1920f)
        val ext2 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 3840f)
        assertEquals(ext1 * 2f, ext2, 1f)
    }

    @Test
    fun `getExtendedWidth scales with screen width`() {
        // Different screen widths give different genWorldRange → different extendedWidth
        val ext1 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 1920f)
        val ext2 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(2160f, 1920f)
        assertTrue(ext2 > ext1)
    }

    @Test
    fun `getExtendedWidth at min height uses MIN_HEIGHT_SCALE`() {
        // Heights below 960 (MIN_HEIGHT_SCALE * REFERENCE_HEIGHT) should clamp
        val extAt960 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 960f)
        val extAt400 = info.olli.weatherlwp.rendering.RenderConstants.getExtendedWidth(1080f, 400f)
        assertEquals(extAt960, extAt400, 0.01f)
    }

    @Test
    fun `getHorizontalDensityScale is stable across height changes`() {
        // Density must not depend on height — only on screen width
        val scale1 = info.olli.weatherlwp.rendering.RenderConstants.getHorizontalDensityScale(0f, 1080f, 1920f)
        val scale2 = info.olli.weatherlwp.rendering.RenderConstants.getHorizontalDensityScale(0f, 1080f, 800f)
        assertEquals(scale1, scale2, 0.001f)
    }

    @Test
    fun `getHorizontalDensityScale grows with screen width`() {
        val scaleSmall = info.olli.weatherlwp.rendering.RenderConstants.getHorizontalDensityScale(0f, 720f, 1920f)
        val scaleLarge = info.olli.weatherlwp.rendering.RenderConstants.getHorizontalDensityScale(0f, 1440f, 1920f)
        assertTrue(scaleLarge > scaleSmall)
    }

    @Test
    fun `getHorizontalDensityScale clamps at minimum`() {
        val scale = info.olli.weatherlwp.rendering.RenderConstants.getHorizontalDensityScale(10f)
        assertEquals(0.25f, scale, 0.001f)
    }

    @Test
    fun `getExtendedOffset centers content`() {
        val offset = info.olli.weatherlwp.rendering.RenderConstants.getExtendedOffset(1080f, 1900.8f)
        assertEquals((1900.8f - 1080f) / 2f, offset, 0.01f)
    }

    @Test
    fun `getExtendedOffset is zero when widths match`() {
        val offset = info.olli.weatherlwp.rendering.RenderConstants.getExtendedOffset(1000f, 1000f)
        assertEquals(0f, offset, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 0 returns HILLS_MIN`() {
        val depth = info.olli.weatherlwp.rendering.RenderConstants.getHillParallaxDepth(0f)
        assertEquals(0.50f, depth, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 1 returns HILLS_MAX`() {
        val depth = info.olli.weatherlwp.rendering.RenderConstants.getHillParallaxDepth(1f)
        assertEquals(0.80f, depth, 0.001f)
    }

    @Test
    fun `getHillParallaxDepth at 0-5 returns midpoint`() {
        val depth = info.olli.weatherlwp.rendering.RenderConstants.getHillParallaxDepth(0.5f)
        assertEquals(0.65f, depth, 0.001f)
    }

    @Test
    fun `calculateEventDuration basic case`() {
        val dur = info.olli.weatherlwp.rendering.RenderConstants.calculateEventDuration(1.3f, 0.0001f)
        assertEquals(13000L, dur)
    }

    @Test
    fun `calculateEventDuration clamps to minimum`() {
        val dur = info.olli.weatherlwp.rendering.RenderConstants.calculateEventDuration(0.001f, 1f)
        assertEquals(1000L, dur)
    }

    @Test
    fun `calculateEventDuration clamps to maximum`() {
        val dur = info.olli.weatherlwp.rendering.RenderConstants.calculateEventDuration(100f, 0.00001f)
        assertEquals(180000L, dur)
    }

    @Test
    fun `calculateEventDuration handles zero speed`() {
        val dur = info.olli.weatherlwp.rendering.RenderConstants.calculateEventDuration(1f, 0f)
        // Speed coerced to 0.00001, distance=1 -> 100000ms clamped to 180000
        assertEquals(100000L, dur)
    }

    @Test
    fun `calculateEventDuration custom min max`() {
        val dur = info.olli.weatherlwp.rendering.RenderConstants.calculateEventDuration(
            1f, 0.001f, minMs = 2000L, maxMs = 5000L
        )
        // raw: 1/0.001 = 1000 -> clamped to minMs=2000
        assertEquals(2000L, dur)
    }
}
