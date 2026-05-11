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
    fun `extended width scales linearly with height`() {
        val extendedTall = RenderConstants.getExtendedWidth(1080f, 2400f)
        val extendedRef = RenderConstants.getExtendedWidth(1080f, 1920f)
        val extendedShort = RenderConstants.getExtendedWidth(1080f, 960f)

        // extendedWidth = genWorldRange * heightScale
        // Taller canvas → larger heightScale → wider extendedWidth
        assertTrue(extendedTall > extendedRef)
        // At 1080×960 (landscape): heightScale = max(1080,960)/1920 = 0.5625
        val expectedScale = RenderConstants.getHeightScale(960f, 1080f)
        val refScale = RenderConstants.getHeightScale(1920f, 1080f)
        assertEquals(extendedRef * (expectedScale / refScale), extendedShort, 1f)
    }

    @Test
    fun `height change produces symmetric zoom from center`() {
        // Core zoom property: when only height changes, elements at
        // symmetric ratios (e.g. 0.3 and 0.7) must move equally far
        // from screen center.
        val w = 720f
        for (h in listOf(800f, 1200f, 1920f, 2400f)) {
            val ew = RenderConstants.getExtendedWidth(w, h)
            val eo = RenderConstants.getExtendedOffset(w, ew)
            val pos03 = 0.3f * ew - eo
            val pos07 = 0.7f * ew - eo
            val center = w / 2f
            val distLeft = center - pos03
            val distRight = pos07 - center
            assertEquals("zoom asymmetric at h=$h", distLeft, distRight, 0.01f)
        }
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
    fun `element screen position scales proportionally with height`() {
        // When height changes, positions scale from center (zoom effect).
        // Distance from center at peakX=0.3 is proportional to heightScale.
        val w = 720f
        val ewRef = RenderConstants.getExtendedWidth(w, 1920f)
        val eoRef = RenderConstants.getExtendedOffset(w, ewRef)
        val distRef = w / 2f - (0.3f * ewRef - eoRef)

        val ewTall = RenderConstants.getExtendedWidth(w, 2400f)
        val eoTall = RenderConstants.getExtendedOffset(w, ewTall)
        val distTall = w / 2f - (0.3f * ewTall - eoTall)

        // Distance from center scales with heightScale ratio
        val scaleRef = RenderConstants.getHeightScale(1920f)
        val scaleTall = RenderConstants.getHeightScale(2400f)
        assertEquals(scaleTall / scaleRef, distTall / distRef, 0.01f)
    }

    @Test
    fun `density scale is stable across height changes`() {
        // Same screen width must give same density regardless of height
        val scale1 = RenderConstants.getHorizontalDensityScale(
            RenderConstants.getExtendedWidth(1080f, 1920f), 1080f, 1920f)
        val scale2 = RenderConstants.getHorizontalDensityScale(
            RenderConstants.getExtendedWidth(1080f, 800f), 1080f, 800f)
        assertEquals(scale1, scale2, 0.01f)
    }

    // ===================== DeterministicLayout.mix golden-value tests =====================
    // These pin the exact hash output so that bit-level mutations
    // (shift direction, xor→and, mul→div, add→sub) are caught.

    @Test
    fun `deterministic layout mix produces known golden value`() {
        // Pin a known output — any mutation to the hash arithmetic will change this
        val result = DeterministicLayout.mix(42, 100, 7)
        assertEquals("golden value for (42,100,7)", -8149335812245885045L, result)
    }

    @Test
    fun `deterministic layout mix golden value for different section`() {
        val result = DeterministicLayout.mix(42, 200, 7)
        // Different section must produce different output
        assertNotEquals(DeterministicLayout.mix(42, 100, 7), result)
        // Pin exact value
        assertEquals("golden value for (42,200,7)", -2762775937761742965L, result)
    }

    @Test
    fun `deterministic layout mix varies by baseSeed`() {
        val a = DeterministicLayout.mix(1, 100, 7)
        val b = DeterministicLayout.mix(2, 100, 7)
        assertNotEquals("different baseSeed → different hash", a, b)
    }

    // ===================== ViewportCulling boundary-precise tests =====================

    @Test
    fun `maxParallaxShift returns non-zero positive value`() {
        val shift = ViewportCulling.maxParallaxShift(1920f)
        assertTrue("maxParallaxShift should be positive", shift > 0f)
        assertEquals("maxParallaxShift = 0.45 * height", 1920f * 0.45f, shift, 0.01f)
    }

    @Test
    fun `viewport culling boundary - object exactly at left margin edge`() {
        // margin = objectHalfWidth + maxParallaxShift + extraMargin
        // = 30 + 1920*0.45 + 10 = 30 + 864 + 10 = 904
        // Object at x = -904 should be exactly on the boundary (visible)
        val margin = 30f + 1920f * 0.45f + 10f
        val exactlyOnEdge = ViewportCulling.isVisibleHorizontally(
            x = -margin,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )
        assertTrue("object at exact left margin should be visible", exactlyOnEdge)

        // Object 1px beyond should be invisible
        val justBeyond = ViewportCulling.isVisibleHorizontally(
            x = -margin - 1f,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )
        assertFalse("object just past left margin should be culled", justBeyond)
    }

    @Test
    fun `viewport culling boundary - object exactly at right margin edge`() {
        val margin = 30f + 1920f * 0.45f + 10f
        val rightEdge = 1080f + margin
        val exactlyOnEdge = ViewportCulling.isVisibleHorizontally(
            x = rightEdge,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )
        assertTrue("object at exact right margin should be visible", exactlyOnEdge)

        val justBeyond = ViewportCulling.isVisibleHorizontally(
            x = rightEdge + 1f,
            objectHalfWidth = 30f,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 10f
        )
        assertFalse("object just past right margin should be culled", justBeyond)
    }

    @Test
    fun `viewport culling - extraMargin 0 still includes parallax shift`() {
        // With extraMargin=0, margin = objectHalfWidth + maxParallaxShift
        val shift = ViewportCulling.maxParallaxShift(1920f)
        val halfW = 10f
        val margin = halfW + shift
        // Object at x = -margin should be visible
        assertTrue(ViewportCulling.isVisibleHorizontally(
            x = -margin,
            objectHalfWidth = halfW,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 0f
        ))
        // Object further should not be
        assertFalse(ViewportCulling.isVisibleHorizontally(
            x = -margin - 1f,
            objectHalfWidth = halfW,
            screenWidth = 1080f,
            height = 1920f,
            extraMargin = 0f
        ))
    }
}
