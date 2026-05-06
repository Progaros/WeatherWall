package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStabilityTest {
    @Test
    fun `deterministic layout mix is stable`() {
        val a = DeterministicLayout.mix(42, 100, 7)
        val b = DeterministicLayout.mix(42, 100, 7)

        assertEquals(a, b)
    }

    @Test
    fun `deterministic layout mix varies by index`() {
        val a = DeterministicLayout.mix(42, 100, 7)
        val b = DeterministicLayout.mix(42, 100, 8)

        assertNotEquals(a, b)
    }

    @Test
    fun `deterministic random stream is stable`() {
        val r1 = DeterministicLayout.random(99, 5, 3)
        val r2 = DeterministicLayout.random(99, 5, 3)

        assertEquals(r1.nextFloat(), r2.nextFloat(), 0.0000001f)
        assertEquals(r1.nextInt(), r2.nextInt())
    }

    @Test
    fun `viewport culling keeps border objects`() {
        val visible = ViewportCulling.isVisibleHorizontally(
            x = -20f,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )

        assertTrue(visible)
    }

    @Test
    fun `viewport culling rejects far objects`() {
        val farAway = ViewportCulling.isVisibleHorizontally(
            x = -3000f,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )

        assertFalse(farAway)
    }

    @Test
    fun `extended width scales proportionally with height for tall screens`() {
        val extendedTall = RenderConstants.getExtendedWidth(1080f, 2400f)
        val extendedRef = RenderConstants.getExtendedWidth(1080f, 1920f)

        // Taller screen should have wider extended width (proportional to height ratio)
        val expectedRatio = 2400f / 1920f  // 1.25
        assertEquals(expectedRatio, extendedTall / extendedRef, 0.01f)
    }

    @Test
    fun `extended width uses screen width for short screens`() {
        // When height is small, screen-based width takes over
        val extendedShort = RenderConstants.getExtendedWidth(1080f, 800f)
        val extendedRef = RenderConstants.getExtendedWidth(1080f, 1920f)

        // At 800px height: heightBased = 1900.8 * (800/1920) = 792 < screenBased = 1900.8
        // So screen-based wins, giving the same result as reference
        assertEquals(extendedRef, extendedShort, 0.01f)
    }

    @Test
    fun `horizontal density scale grows with width`() {
        val small = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(720f, 1600f))
        val large = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(1440f, 1600f))

        assertTrue(large > small)
    }
}
