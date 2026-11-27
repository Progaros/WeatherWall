package info.olli.weatherlwp.rendering

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for LayerViewportMath - shared viewport coverage math.
 * Note: computeParallaxSafeDrawRect returns android.graphics.RectF which is stubbed
 * in unit tests, so we test computeCoverScale (pure float math) directly.
 */
class LayerViewportMathTest {

    @Test
    fun `computeCoverScale returns correct scale for wider viewport`() {
        // Viewport 1920x1080, source 1000x1000 -> need scale 1.92 (width governs)
        val scale = LayerViewportMath.computeCoverScale(1920f, 1080f, 1000f, 1000f)
        assertEquals(1.92f, scale, 0.01f)
    }

    @Test
    fun `computeCoverScale returns correct scale for taller viewport`() {
        // Viewport 1080x1920, source 1000x1000 -> need scale 1.92 (height governs)
        val scale = LayerViewportMath.computeCoverScale(1080f, 1920f, 1000f, 1000f)
        assertEquals(1.92f, scale, 0.01f)
    }

    @Test
    fun `computeCoverScale handles minimum dimensions`() {
        val scale = LayerViewportMath.computeCoverScale(0f, 0f, 100f, 100f)
        // Both viewport dims clamped to 1, source 100 -> scale = 1/100 = 0.01
        assertEquals(0.01f, scale, 0.001f)
    }

    @Test
    fun `computeCoverScale with exact fit returns 1`() {
        val scale = LayerViewportMath.computeCoverScale(500f, 500f, 500f, 500f)
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `computeCoverScale with non-square source picks larger axis`() {
        // Viewport 1080x1920, source 2000x1000
        // Width scale = 1080/2000 = 0.54, height scale = 1920/1000 = 1.92
        // Cover picks max = 1.92
        val scale = LayerViewportMath.computeCoverScale(1080f, 1920f, 2000f, 1000f)
        assertEquals(1.92f, scale, 0.01f)
    }

    @Test
    fun `computeCoverScale with tall narrow source`() {
        // Viewport 1080x1920, source 100x2000
        // Width scale = 1080/100 = 10.8, height scale = 1920/2000 = 0.96
        // Cover picks max = 10.8
        val scale = LayerViewportMath.computeCoverScale(1080f, 1920f, 100f, 2000f)
        assertEquals(10.8f, scale, 0.01f)
    }
}
