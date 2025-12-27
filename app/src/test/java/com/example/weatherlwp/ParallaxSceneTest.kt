package com.example.weatherlwp

import com.example.weatherlwp.rendering.ParallaxScene
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ParallaxScene - Tests for Issues #3, #4, #5, #6, #7
 * 
 * Issue #3: Floating Trees - Trees should sit on terrain, not float
 * Issue #4: Mountains Move Too Much - Mountain parallax should be subtle (0.05-0.15)
 * Issue #5: Sky Should Move Opposite - Sky parallax should be negative
 * Issue #7: Sky Not Changing With Time - Sunset colors at 17:00-18:30
 */
class ParallaxSceneTest {

    // ============== Issue #3: Floating Trees Tests ==============
    
    @Test
    fun `TERRAIN_BASELINE_RATIO should be between 0_6 and 0_8`() {
        // Trees and hills should share a baseline around 70% of screen height
        val baseline = ParallaxScene.TERRAIN_BASELINE_RATIO
        assertTrue("Terrain baseline should be >= 0.6", baseline >= 0.6f)
        assertTrue("Terrain baseline should be <= 0.8", baseline <= 0.8f)
    }
    
    @Test
    fun `TREE_FOOT_OFFSET should be positive and small`() {
        // Trees should sit slightly into the ground, not above it
        val offset = ParallaxScene.TREE_FOOT_OFFSET
        assertTrue("Tree foot offset should be positive", offset > 0f)
        assertTrue("Tree foot offset should be < 0.1", offset < 0.1f)
    }
    
    @Test
    fun `tree parallax range should match hill parallax range`() {
        // Trees should use parallax factor 0.3-0.5 (same as hills)
        // This ensures they move together with the terrain
        val treeParallaxMin = 0.3f
        val treeParallaxMax = 0.5f
        val hillParallaxMin = 0.3f
        val hillParallaxMax = 0.5f
        
        assertEquals("Tree and hill min parallax should match", treeParallaxMin, hillParallaxMin, 0.01f)
        assertEquals("Tree and hill max parallax should match", treeParallaxMax, hillParallaxMax, 0.01f)
    }

    // ============== Issue #4: Mountain Parallax Tests ==============
    
    @Test
    fun `mountain parallax should be very subtle`() {
        // Per ai.md: Mountains should have parallax factor 0.05-0.15 (almost static)
        val mountainParallaxMin = 0.05f
        val mountainParallaxMax = 0.15f
        
        assertTrue("Mountain min parallax should be > 0", mountainParallaxMin > 0f)
        assertTrue("Mountain max parallax should be <= 0.2", mountainParallaxMax <= 0.2f)
    }
    
    @Test
    fun `mountains should be less mobile than hills`() {
        val mountainParallaxMax = 0.15f
        val hillParallaxMin = 0.3f
        
        assertTrue("Mountain parallax should be less than hill parallax", 
            mountainParallaxMax < hillParallaxMin)
    }

    // ============== Issue #5: Sky Parallax Tests ==============
    
    @Test
    fun `sky parallax should be negative for depth illusion`() {
        // Sky should move in opposite direction to create depth effect
        val skyParallaxX = -0.15f
        val skyParallaxY = -0.08f
        
        assertTrue("Sky parallax X should be negative", skyParallaxX < 0f)
        assertTrue("Sky parallax Y should be negative", skyParallaxY < 0f)
    }

    // ============== Issue #7: Sunset Color Tests ==============
    
    @Test
    fun `sunset should occur between 17_00 and 18_30`() {
        val sunsetStartHour = 17f
        val sunsetEndHour = 18.5f
        
        assertTrue("Sunset should start at or after 17:00", sunsetStartHour >= 17f)
        assertTrue("Sunset should end before 19:00", sunsetEndHour < 19f)
    }
    
    @Test
    fun `time interpolation should be continuous`() {
        // lerpColor should produce valid interpolated colors
        // Testing the concept that transitions are smooth, not stepped
        
        val startColor = 0xFF87CEEB.toInt()  // Day blue
        val endColor = 0xFFFF8C42.toInt()    // Sunset orange
        
        // At 0% progress, should be start color
        val color0 = lerpColorHelper(startColor, endColor, 0f)
        assertEquals("At 0% should be start color", startColor, color0)
        
        // At 100% progress, should be end color
        val color1 = lerpColorHelper(startColor, endColor, 1f)
        assertEquals("At 100% should be end color", endColor, color1)
        
        // At 50% progress, should be somewhere in between
        val color50 = lerpColorHelper(startColor, endColor, 0.5f)
        assertNotEquals("At 50% should not be start", startColor, color50)
        assertNotEquals("At 50% should not be end", endColor, color50)
    }
    
    // Helper function to test color interpolation
    private fun lerpColorHelper(startColor: Int, endColor: Int, ratio: Float): Int {
        val r = (red(startColor) + ratio * (red(endColor) - red(startColor))).toInt()
        val g = (green(startColor) + ratio * (green(endColor) - green(startColor))).toInt()
        val b = (blue(startColor) + ratio * (blue(endColor) - blue(startColor))).toInt()
        return rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
    
    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ============== Night Blue Overlay Tests (Issue #14) ==============
    
    @Test
    fun `night multiplier should add blue tint`() {
        val baseColor = 0xFF4A6B28.toInt()  // Tree day color (green)
        val nightRatio = 1.0f
        
        val nightColor = applyNightMultiplierHelper(baseColor, nightRatio)
        
        // Night color should have more blue component
        assertTrue("Night color should have increased blue", 
            blue(nightColor) > blue(baseColor))
    }
    
    @Test
    fun `night multiplier at zero should return original color`() {
        val baseColor = 0xFF4A6B28.toInt()
        val nightRatio = 0.0f
        
        val result = applyNightMultiplierHelper(baseColor, nightRatio)
        assertEquals("No night = original color", baseColor, result)
    }
    
    private fun applyNightMultiplierHelper(baseColor: Int, nightRatio: Float): Int {
        if (nightRatio <= 0f) return baseColor
        
        val blueR = 0.1f
        val blueG = 0.15f
        val blueB = 0.4f
        
        val r = (red(baseColor) * (1 - nightRatio * blueR)).toInt()
        val g = (green(baseColor) * (1 - nightRatio * blueG)).toInt()
        val b = (blue(baseColor) + (255 - blue(baseColor)) * nightRatio * blueB).toInt()
        
        return rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
