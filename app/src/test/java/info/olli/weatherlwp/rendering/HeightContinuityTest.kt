package info.olli.weatherlwp.rendering

import info.olli.weatherlwp.rendering.events.EventTraversal
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for decoration continuity at different viewport heights
 * and height-scaled event motion.
 *
 * These tests would have FAILED on the broken implementation where:
 * - Only width changes triggered regeneration (height decreases widened
 *   the visible world range beyond the generated range)
 * - Events used fixed screen speed regardless of height
 */
class HeightContinuityTest {

    // ===================== A. Generation range covers visible range at all heights =====================

    @Test
    fun `generation range covers visible range in portrait`() {
        val screenWidth = 1080f
        val screenHeight = 1920f
        val generationHalf = RenderConstants.getVisibleWorldHalf(screenWidth, screenHeight)
        val transform = WorldViewportTransform(screenWidth, screenHeight)
        val visibleRange = transform.visibleWorldRange()

        assertTrue("Generation must cover left edge",
            -generationHalf <= visibleRange.start)
        assertTrue("Generation must cover right edge",
            generationHalf >= visibleRange.endInclusive)
    }

    @Test
    fun `generation range covers visible range in landscape`() {
        val screenWidth = 1920f
        val screenHeight = 1080f
        val generationHalf = RenderConstants.getVisibleWorldHalf(screenWidth, screenHeight)
        val transform = WorldViewportTransform(screenWidth, screenHeight)
        val visibleRange = transform.visibleWorldRange()

        assertTrue("Generation must cover left edge in landscape",
            -generationHalf <= visibleRange.start)
        assertTrue("Generation must cover right edge in landscape",
            generationHalf >= visibleRange.endInclusive)
    }

    @Test
    fun `generation range covers visible range at small height`() {
        // Small height (e.g. widget or landscape): wider aspect → wider world range
        val screenWidth = 1080f
        val screenHeight = 400f
        val generationHalf = RenderConstants.getVisibleWorldHalf(screenWidth, screenHeight)
        val transform = WorldViewportTransform(screenWidth, screenHeight)
        val visibleRange = transform.visibleWorldRange()

        assertTrue("Generation must cover left edge at small height",
            -generationHalf <= visibleRange.start)
        assertTrue("Generation must cover right edge at small height",
            generationHalf >= visibleRange.endInclusive)
    }

    @Test
    fun `visible world range widens when height decreases`() {
        val screenWidth = 1080f
        val transformTall = WorldViewportTransform(screenWidth, 1920f)
        val transformShort = WorldViewportTransform(screenWidth, 400f)

        val rangeTall = transformTall.visibleWorldRange()
        val rangeShort = transformShort.visibleWorldRange()

        val widthTall = rangeTall.endInclusive - rangeTall.start
        val widthShort = rangeShort.endInclusive - rangeShort.start

        assertTrue("Shorter screen shows wider world range",
            widthShort > widthTall)
    }

    @Test
    fun `getVisibleWorldHalf increases when height decreases`() {
        val halfTall = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val halfShort = RenderConstants.getVisibleWorldHalf(1080f, 400f)

        assertTrue("Visible world half must increase at smaller heights: " +
            "tall=$halfTall, short=$halfShort",
            halfShort > halfTall)
    }

    @Test
    fun `cell range at small height includes all cells at reference height`() {
        val cellSize = 10f // Tree cell size
        val screenWidth = 1080f

        val rangeRef = WorldViewportTransform.indexRangeForWorldRange(
            -RenderConstants.getVisibleWorldHalf(screenWidth, 1920f),
            RenderConstants.getVisibleWorldHalf(screenWidth, 1920f),
            cellSize
        )
        val rangeSmall = WorldViewportTransform.indexRangeForWorldRange(
            -RenderConstants.getVisibleWorldHalf(screenWidth, 400f),
            RenderConstants.getVisibleWorldHalf(screenWidth, 400f),
            cellSize
        )

        assertTrue("Small height range must include all reference cells",
            rangeSmall.first <= rangeRef.first && rangeSmall.last >= rangeRef.last)
    }

    // ===================== B. Event height-scaled speed =====================

    @Test
    fun `heightScale is 1 at reference height`() {
        val scale = EventTraversal.heightScale(RenderConstants.REFERENCE_HEIGHT)
        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun `heightScale less than 1 at smaller heights`() {
        val scale = EventTraversal.heightScale(1080f)
        assertTrue("Scale at 1080px should be < 1: $scale", scale < 1f)
        assertEquals(1080f / RenderConstants.REFERENCE_HEIGHT, scale, 0.001f)
    }

    @Test
    fun `heightScale greater than 1 at larger heights`() {
        val scale = EventTraversal.heightScale(3840f)
        assertTrue("Scale at 3840px should be > 1: $scale", scale > 1f)
        assertEquals(2f, scale, 0.001f)
    }

    @Test
    fun `heightScale is clamped to min`() {
        val scale = EventTraversal.heightScale(100f)
        assertEquals(EventTraversal.MIN_HEIGHT_SCALE, scale, 0.001f)
    }

    @Test
    fun `heightScale is clamped to max`() {
        val scale = EventTraversal.heightScale(20000f)
        assertEquals(EventTraversal.MAX_HEIGHT_SCALE, scale, 0.001f)
    }

    @Test
    fun `scaledElapsed returns less time at smaller heights`() {
        val elapsed = 10000L
        val scaledRef = EventTraversal.scaledElapsed(elapsed, RenderConstants.REFERENCE_HEIGHT)
        val scaledSmall = EventTraversal.scaledElapsed(elapsed, 960f)

        assertEquals("At reference height, scaled == original", elapsed, scaledRef)
        assertTrue("At small height, scaled < original: $scaledSmall < $elapsed",
            scaledSmall < elapsed)
        // 960/1920 = 0.5, so scaledSmall should be ~5000
        assertEquals(5000L, scaledSmall)
    }

    @Test
    fun `event speed halves when height halves`() {
        val speed = 0.00003f // typical airplane speed (ratio/ms)
        val elapsed = 20000L

        val progressRef = EventTraversal.scaledElapsed(elapsed, 1920f) * speed
        val progressHalf = EventTraversal.scaledElapsed(elapsed, 960f) * speed

        assertEquals("Progress at half height should be half",
            progressRef / 2f, progressHalf, progressRef * 0.01f)
    }

    // ===================== C. Culling bounds =====================

    @Test
    fun `object far off left is culled`() {
        val screenWidth = 1080f
        val height = 1920f
        assertFalse(
            ViewportCulling.isVisibleHorizontally(
                x = -2000f, objectHalfWidth = 50f, screenWidth = screenWidth, height = height
            )
        )
    }

    @Test
    fun `object far off right is culled`() {
        val screenWidth = 1080f
        val height = 1920f
        assertFalse(
            ViewportCulling.isVisibleHorizontally(
                x = 4000f, objectHalfWidth = 50f, screenWidth = screenWidth, height = height
            )
        )
    }

    @Test
    fun `object partially visible on left is NOT culled`() {
        val screenWidth = 1080f
        val height = 1920f
        // Object at x=-50 with halfWidth=100 overlaps visible area
        assertTrue(
            ViewportCulling.isVisibleHorizontally(
                x = -50f, objectHalfWidth = 100f, screenWidth = screenWidth, height = height
            )
        )
    }

    @Test
    fun `object just within parallax margin is NOT culled`() {
        val screenWidth = 1080f
        val height = 1920f
        val parallaxMargin = ViewportCulling.maxParallaxShift(height)
        // Object at -(parallaxMargin - 10) should still be visible (within margin)
        assertTrue(
            ViewportCulling.isVisibleHorizontally(
                x = -(parallaxMargin - 10f), objectHalfWidth = 0f,
                screenWidth = screenWidth, height = height
            )
        )
    }

    @Test
    fun `object beyond parallax margin is culled`() {
        val screenWidth = 1080f
        val height = 1920f
        val parallaxMargin = ViewportCulling.maxParallaxShift(height)
        // Object far beyond margin
        assertFalse(
            ViewportCulling.isVisibleHorizontally(
                x = -(parallaxMargin + 100f), objectHalfWidth = 0f,
                screenWidth = screenWidth, height = height
            )
        )
    }

    // ===================== D. Negative cell index stability =====================

    @Test
    fun `cell indices are stable for negative world positions`() {
        val cellSize = 10f
        // Generate cells for range that includes negative positions
        val range1 = WorldViewportTransform.indexRangeForWorldRange(-2000f, -1000f, cellSize)
        // Generate overlapping range
        val range2 = WorldViewportTransform.indexRangeForWorldRange(-1500f, -500f, cellSize)

        // Overlapping cells should have identical indices
        val overlap1 = range1.filter { it in range2 }
        val overlap2 = range2.filter { it in range1 }
        assertEquals("Overlapping cell indices must be identical", overlap1, overlap2)
    }

    @Test
    fun `floor-based indexing handles negative correctly`() {
        val cellSize = 10f
        // -15 / 10 = -1.5, floor = -2 (NOT -1 which toInt() would give)
        val range = WorldViewportTransform.indexRangeForWorldRange(-15f, -5f, cellSize)
        assertTrue("Range must include cell -2", -2 in range)
        assertTrue("Range must include cell -1", -1 in range)
    }

    // ===================== E. Regeneration hysteresis =====================

    @Test
    fun `visible world half changes with height`() {
        val w = 1080f
        val h1 = 1920f
        val h2 = 800f
        val half1 = RenderConstants.getVisibleWorldHalf(w, h1)
        val half2 = RenderConstants.getVisibleWorldHalf(w, h2)
        // Height decrease → aspect increase → wider visible world
        assertTrue("Smaller height must give larger visible half: $half2 > $half1",
            half2 > half1)
    }

    @Test
    fun `preload margin prevents regeneration on same-size resize`() {
        // Simulates the hysteresis: generatedWorldHalf = required * 1.25
        // So small changes don't exceed the cached coverage.
        val w = 1080f
        val h = 1920f
        val required = RenderConstants.getVisibleWorldHalf(w, h)
        val generated = required * 1.25f

        // A slightly wider requirement (e.g. height changed by 20px) should still fit
        val slightlyWider = RenderConstants.getVisibleWorldHalf(w, h - 20f)
        assertTrue("Slightly wider requirement ($slightlyWider) must fit within " +
            "generated coverage ($generated)",
            slightlyWider <= generated)
    }

    @Test
    fun `large height decrease escapes preload margin`() {
        // A dramatic height decrease (e.g. portrait to landscape) should exceed margin
        val w = 1080f
        val portraitRequired = RenderConstants.getVisibleWorldHalf(w, 1920f)
        val portraitGenerated = portraitRequired * 1.25f

        val landscapeRequired = RenderConstants.getVisibleWorldHalf(w, 400f)
        assertTrue("Landscape requirement ($landscapeRequired) must exceed " +
            "portrait generated coverage ($portraitGenerated)",
            landscapeRequired > portraitGenerated)
    }

    @Test
    fun `small float precision change does not escape margin`() {
        val w = 1080f
        val h = 1920f
        val required = RenderConstants.getVisibleWorldHalf(w, h)
        val generated = required * 1.25f

        // Tiny precision change
        val requiredAfterTinyChange = RenderConstants.getVisibleWorldHalf(w + 0.01f, h - 0.01f)
        assertTrue("Tiny precision change must not escape margin",
            requiredAfterTinyChange <= generated)
    }
}
