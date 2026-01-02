package info.olli.weatherlwp.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ColorMath helper that runs on JVM (no Android dependencies).
 */
class ColorMathTest {

    // Helper to build ARGB int
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun alpha(c: Int) = (c ushr 24) and 0xFF
    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `shadeRgb with zero strength returns same color`() {
        val original = argb(255, 100, 150, 200)
        val result = ColorMath.shadeRgb(original, 0f)
        assertEquals(original, result)
    }

    @Test
    fun `shadeRgb with full strength darkens significantly`() {
        val original = argb(255, 200, 200, 200)
        val result = ColorMath.shadeRgb(original, 1f)

        // At strength=1, brightness should be ~35% of original
        // Plus slight blue lift
        assertTrue("Red should be darkened", red(result) < red(original))
        assertTrue("Green should be darkened", green(result) < green(original))
        // Blue gets darkened but also lifted, so may be close to original
        assertTrue("Red is darkened significantly", red(result) < 100)
        assertTrue("Green is darkened significantly", green(result) < 100)
    }

    @Test
    fun `shadeRgb preserves alpha`() {
        val original = argb(128, 200, 200, 200)
        val result = ColorMath.shadeRgb(original, 0.5f)
        assertEquals("Alpha should be preserved", 128, alpha(result))
    }

    @Test
    fun `shadeRgb with half strength gives moderate darkening`() {
        val original = argb(255, 200, 200, 200)
        val result = ColorMath.shadeRgb(original, 0.5f)

        // Should be darker than original but not as dark as full strength
        assertTrue("Red should be darkened", red(result) < red(original))
        assertTrue("Red not too dark", red(result) > 100)
    }

    @Test
    fun `shadeRgb adds slight blue tint`() {
        val original = argb(255, 100, 100, 100)
        val result = ColorMath.shadeRgb(original, 1f)

        // With full strength, blue gets +10 lift
        // Original blue darkened to ~35 + 10 = ~45
        val expectedBlueApprox = (100 * 0.35f).toInt() + 10
        assertTrue(
            "Blue should have lift effect",
            blue(result) >= expectedBlueApprox - 5 && blue(result) <= expectedBlueApprox + 5
        )
    }

    @Test
    fun `shadeRgb clamps to valid range`() {
        // Test with max values
        val bright = argb(255, 255, 255, 255)
        val darkened = ColorMath.shadeRgb(bright, 1f)

        assertTrue("Red in range", red(darkened) in 0..255)
        assertTrue("Green in range", green(darkened) in 0..255)
        assertTrue("Blue in range", blue(darkened) in 0..255)
    }

    @Test
    fun `shadeRgb with negative strength treated as zero`() {
        val original = argb(255, 100, 150, 200)
        val result = ColorMath.shadeRgb(original, -0.5f)
        assertEquals(original, result)
    }

    @Test
    fun `shadeRgb with strength over 1 clamped to 1`() {
        val original = argb(255, 200, 200, 200)
        val resultAt1 = ColorMath.shadeRgb(original, 1f)
        val resultAt2 = ColorMath.shadeRgb(original, 2f)
        assertEquals("Over 1 should be same as 1", resultAt1, resultAt2)
    }

    @Test
    fun `default night shade 35 percent gives noticeable darkening`() {
        // Default slider value is 35 = 0.35f strength
        val original = argb(255, 180, 180, 200)
        val result = ColorMath.shadeRgb(original, 0.35f)

        // Should be noticeably darker but not extreme
        assertTrue("Red darkened", red(result) < red(original))
        assertTrue("Still visible", red(result) > 80)
    }
}
