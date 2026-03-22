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
    fun `horizontal density scale is height independent`() {
        val extendedA = RenderConstants.getExtendedWidth(1080f, 2400f)
        val extendedB = RenderConstants.getExtendedWidth(1080f, 1200f)

        val scaleA = RenderConstants.getHorizontalDensityScale(extendedA)
        val scaleB = RenderConstants.getHorizontalDensityScale(extendedB)

        assertEquals(scaleA, scaleB, 0.0001f)
    }

    @Test
    fun `horizontal density scale grows with width`() {
        val small = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(720f, 1600f))
        val large = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(1440f, 1600f))

        assertTrue(large > small)
    }
}
