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
    fun `extended width is stable when only height changes`() {
        val extendedTall = RenderConstants.getExtendedWidth(1080f, 2400f)
        val extendedRef = RenderConstants.getExtendedWidth(1080f, 1920f)
        val extendedShort = RenderConstants.getExtendedWidth(1080f, 800f)

        // Width-only formula: height changes must NOT affect extendedWidth
        assertEquals(extendedRef, extendedTall, 0.01f)
        assertEquals(extendedRef, extendedShort, 0.01f)
    }

    @Test
    fun `horizontal density scale grows with width`() {
        val small = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(720f, 1600f))
        val large = RenderConstants.getHorizontalDensityScale(RenderConstants.getExtendedWidth(1440f, 1600f))

        assertTrue(large > small)
    }

    @Test
    fun `element at peakX half always maps to screen center`() {
        // Core centering property: peakX=0.5 must map to screenWidth/2
        // regardless of canvas dimensions.
        for ((w, h) in listOf(400f to 800f, 720f to 1920f, 1080f to 1920f, 1920f to 1080f)) {
            val ew = RenderConstants.getExtendedWidth(w, h)
            val eo = RenderConstants.getExtendedOffset(w, ew)
            val screenX = 0.5f * ew - eo
            assertEquals("center broken at ${w}x${h}", w / 2f, screenX, 0.01f)
        }
    }

    @Test
    fun `element screen position is stable across height changes`() {
        // When width stays constant, height-only changes must NOT shift element screen X.
        val w = 720f
        val ew = RenderConstants.getExtendedWidth(w, 1920f)
        val eo = RenderConstants.getExtendedOffset(w, ew)
        val posRef = 0.3f * ew - eo

        for (h in listOf(800f, 1200f, 1920f, 2400f)) {
            val ew2 = RenderConstants.getExtendedWidth(w, h)
            val eo2 = RenderConstants.getExtendedOffset(w, ew2)
            val pos = 0.3f * ew2 - eo2
            assertEquals("position shifted at height=$h", posRef, pos, 0.01f)
        }
    }
}
