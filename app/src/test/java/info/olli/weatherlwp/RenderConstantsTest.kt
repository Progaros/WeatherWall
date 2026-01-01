package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for RenderConstants and scene layout calculations.
 * 
 * Tests:
 * - Extended canvas calculations for parallax
 * - Parallax depth factors
 * - Color constants
 * - Time thresholds
 */
class RenderConstantsTest {

    // ============== Extended Canvas Tests ==============
    
    @Test
    fun `extended width uses height multiplier`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        
        val extended = RenderConstants.getExtendedWidth(screenWidth, screenHeight)
        val expected = screenHeight * RenderConstants.EXTENDED_WIDTH_MULTIPLIER
        
        assertEquals("Extended width = height * multiplier", expected, extended, 0.01f)
    }
    
    @Test
    fun `extended width at least screen width`() {
        val screenWidth = 3000f  // Very wide screen
        val screenHeight = 1000f
        
        val extended = RenderConstants.getExtendedWidth(screenWidth, screenHeight)
        
        assertTrue("Extended at least screen width", extended >= screenWidth)
    }
    
    @Test
    fun `extended offset centers content`() {
        val screenWidth = 1080f
        val extendedWidth = 3840f
        
        val offset = RenderConstants.getExtendedOffset(screenWidth, extendedWidth)
        val expected = (extendedWidth - screenWidth) / 2f
        
        assertEquals("Offset centers extended canvas", expected, offset, 0.01f)
    }
    
    @Test
    fun `extended multiplier is reasonable`() {
        val multiplier = RenderConstants.EXTENDED_WIDTH_MULTIPLIER
        
        assertTrue("Multiplier > 1 for parallax content", multiplier > 1f)
        assertTrue("Multiplier not too large for performance", multiplier <= 4f)
    }
    
    // ============== Parallax Depth Factor Tests ==============
    
    @Test
    fun `parallax factors are ordered by depth`() {
        val sky = RenderConstants.PARALLAX_SKY
        val stars = RenderConstants.PARALLAX_STARS
        val clouds = RenderConstants.PARALLAX_CLOUDS
        val mountainMin = RenderConstants.PARALLAX_MOUNTAIN_MIN
        val mountainMax = RenderConstants.PARALLAX_MOUNTAIN_MAX
        val hillsMin = RenderConstants.PARALLAX_HILLS_MIN
        val hillsMax = RenderConstants.PARALLAX_HILLS_MAX
        
        assertTrue("Sky is farthest", sky < stars)
        assertTrue("Stars behind clouds", stars < clouds)
        assertTrue("Clouds behind mountains", clouds < mountainMin)
        assertTrue("Mountains behind hills", mountainMax < hillsMin)
    }
    
    @Test
    fun `parallax factors in valid range`() {
        val factors = listOf(
            RenderConstants.PARALLAX_SKY,
            RenderConstants.PARALLAX_STARS,
            RenderConstants.PARALLAX_CLOUDS,
            RenderConstants.PARALLAX_MOUNTAIN_MIN,
            RenderConstants.PARALLAX_MOUNTAIN_MAX,
            RenderConstants.PARALLAX_HILLS_MIN,
            RenderConstants.PARALLAX_HILLS_MAX
        )
        
        for (factor in factors) {
            assertTrue("Factor >= 0", factor >= 0f)
            assertTrue("Factor <= 1", factor <= 1f)
        }
    }
    
    @Test
    fun `hills have most parallax movement`() {
        val hillsMax = RenderConstants.PARALLAX_HILLS_MAX
        val allOthers = listOf(
            RenderConstants.PARALLAX_SKY,
            RenderConstants.PARALLAX_STARS,
            RenderConstants.PARALLAX_CLOUDS,
            RenderConstants.PARALLAX_MOUNTAIN_MAX
        )
        
        for (other in allOthers) {
            assertTrue("Hills move more than $other", hillsMax > other)
        }
    }
    
    // ============== Time Threshold Tests ==============
    
    @Test
    fun `time thresholds are in order`() {
        val nightEnd = RenderConstants.TIME_NIGHT_END
        val dawnStart = RenderConstants.TIME_DAWN_START
        val dawnEnd = RenderConstants.TIME_DAWN_END
        val dayEnd = RenderConstants.TIME_DAY_END
        val duskEnd = RenderConstants.TIME_DUSK_END
        
        assertEquals("Night end = dawn start", nightEnd, dawnStart, 0.01f)
        assertTrue("Dawn ends before day ends", dawnEnd < dayEnd)
        assertTrue("Day ends before dusk ends", dayEnd < duskEnd)
        assertTrue("All within 24 hours", duskEnd <= 24f)
    }
    
    @Test
    fun `dawn is in morning`() {
        val dawnStart = RenderConstants.TIME_DAWN_START
        val dawnEnd = RenderConstants.TIME_DAWN_END
        
        assertTrue("Dawn starts early", dawnStart >= 4f && dawnStart <= 7f)
        assertTrue("Dawn ends in morning", dawnEnd >= 6f && dawnEnd <= 9f)
    }
    
    @Test
    fun `dusk is in evening`() {
        val dayEnd = RenderConstants.TIME_DAY_END
        val duskEnd = RenderConstants.TIME_DUSK_END
        
        assertTrue("Day ends in late afternoon", dayEnd >= 16f && dayEnd <= 19f)
        assertTrue("Dusk ends in evening", duskEnd >= 19f && duskEnd <= 22f)
    }
    
    // ============== Terrain Generation Tests ==============
    
    @Test
    fun `hill height parameters are valid`() {
        val base = RenderConstants.HILL_BASE_HEIGHT
        val range = RenderConstants.HILL_HEIGHT_RANGE
        
        assertTrue("Base in lower half", base >= 0.3f && base <= 0.5f)
        assertTrue("Range provides variation", range >= 0.2f && range <= 0.5f)
    }
    
    @Test
    fun `tree scale parameters are valid`() {
        val minScale = RenderConstants.TREE_SCALE_MIN
        val range = RenderConstants.TREE_SCALE_RANGE
        val maxScale = minScale + range
        
        assertTrue("Min scale reasonable", minScale >= 0.5f)
        assertTrue("Max scale not too large", maxScale <= 1.5f)
    }
    
    @Test
    fun `mountain parameters create visible peaks`() {
        val heightMin = RenderConstants.MOUNTAIN_HEIGHT_MIN
        val heightRange = RenderConstants.MOUNTAIN_HEIGHT_RANGE
        val widthMin = RenderConstants.MOUNTAIN_WIDTH_MIN
        
        assertTrue("Mountains tall enough", heightMin >= 0.2f)
        assertTrue("Mountain height varies", heightRange >= 0.2f)
        assertTrue("Mountains wide enough", widthMin >= 0.5f)
    }
    
    // ============== Cloud Generation Tests ==============
    
    @Test
    fun `cloud Y position in upper screen`() {
        val yMin = RenderConstants.CLOUD_Y_MIN
        val yRange = RenderConstants.CLOUD_Y_RANGE
        val yMax = yMin + yRange
        
        assertTrue("Clouds start near top", yMin <= 0.1f)
        assertTrue("Clouds don't go below middle", yMax <= 0.5f)
    }
    
    @Test
    fun `cloud size is visible but not huge`() {
        val sizeMin = RenderConstants.CLOUD_SIZE_MIN
        val sizeRange = RenderConstants.CLOUD_SIZE_RANGE
        val sizeMax = sizeMin + sizeRange
        
        assertTrue("Clouds visible", sizeMin >= 0.05f)
        assertTrue("Clouds not too large", sizeMax <= 0.5f)
    }
    
    @Test
    fun `cloud speed parameters allow movement`() {
        val speedBase = RenderConstants.CLOUD_SPEED_BASE
        val speedRange = RenderConstants.CLOUD_SPEED_RANGE
        
        assertTrue("Base speed positive", speedBase > 0f)
        assertTrue("Speed varies", speedRange > 0f)
    }
    
    // ============== Fog Color Tests ==============
    
    @Test
    fun `fog color is light gray`() {
        val r = RenderConstants.FOG_R
        val g = RenderConstants.FOG_G
        val b = RenderConstants.FOG_B
        
        assertTrue("Fog R is light", r >= 180 && r <= 240)
        assertTrue("Fog G is light", g >= 180 && g <= 240)
        assertTrue("Fog B is light", b >= 180 && b <= 240)
    }
    
    // ============== Haze Tests ==============
    
    @Test
    fun `haze starts in lower half`() {
        val startY = RenderConstants.HAZE_START_Y
        val endY = RenderConstants.HAZE_END_Y
        
        assertTrue("Haze starts in middle", startY >= 0.4f && startY <= 0.7f)
        assertTrue("Haze ends near bottom", endY >= 0.7f && endY <= 1.0f)
        assertTrue("End > start", endY > startY)
    }
    
    @Test
    fun `haze alpha is subtle`() {
        val alpha = RenderConstants.HAZE_ALPHA
        
        assertTrue("Haze visible", alpha >= 10)
        assertTrue("Haze not too opaque", alpha <= 50)
    }
}
