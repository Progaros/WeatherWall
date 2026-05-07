package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldViewportTransformTest {

    private fun transform(w: Float = 1080f, h: Float = 1920f): WorldViewportTransform {
        return WorldViewportTransform(w, h, RenderConstants.getHeightScale(h))
    }

    // ===================== worldToScreenX =====================

    @Test
    fun `worldX 0 maps to screen center for multiple heights`() {
        for (h in listOf(800f, 1200f, 1920f, 2400f)) {
            val t = transform(1080f, h)
            assertEquals("h=$h", 1080f / 2f, t.worldToScreenX(0f), 0.01f)
        }
    }

    @Test
    fun `worldX negative and positive are symmetric around center`() {
        val t = transform(1080f, 1920f)
        val a = 100f
        val left = t.worldToScreenX(-a)
        val right = t.worldToScreenX(a)
        val center = t.centerX
        assertEquals(center - left, right - center, 0.01f)
    }

    @Test
    fun `distance from center scales with heightScale`() {
        val w = 1080f
        val worldX = 200f
        val t1 = WorldViewportTransform(w, 1920f, 1f)
        val t2 = WorldViewportTransform(w, 1920f, 2f)
        val dist1 = t1.worldToScreenX(worldX) - t1.centerX
        val dist2 = t2.worldToScreenX(worldX) - t2.centerX
        assertEquals(dist2 / dist1, 2f, 0.01f)
    }

    @Test
    fun `parallax multiplies distance from center`() {
        val t = transform()
        val worldX = 150f
        val dist1 = t.worldToScreenX(worldX, parallax = 1f) - t.centerX
        val dist2 = t.worldToScreenX(worldX, parallax = 0.5f) - t.centerX
        assertEquals(dist1 * 0.5f, dist2, 0.01f)
    }

    // ===================== visibleWorldRangeX =====================

    @Test
    fun `visible range is symmetric around zero`() {
        val t = transform()
        val range = t.visibleWorldRangeX()
        assertEquals(-range.start, range.endInclusive, 0.01f)
    }

    @Test
    fun `visible range expands when heightScale decreases`() {
        val w = 1080f
        val tall = WorldViewportTransform(w, 2400f, RenderConstants.getHeightScale(2400f))
        val short = WorldViewportTransform(w, 960f, RenderConstants.getHeightScale(960f))
        val rangeTall = tall.visibleWorldRangeX()
        val rangeShort = short.visibleWorldRangeX()
        assertTrue("shorter screen sees wider world range",
            rangeShort.endInclusive > rangeTall.endInclusive)
    }

    @Test
    fun `visible range contracts when heightScale increases`() {
        val w = 1080f
        val ref = WorldViewportTransform(w, 1920f, 1f)
        val big = WorldViewportTransform(w, 1920f, 2f)
        assertTrue(ref.visibleWorldRangeX().endInclusive > big.visibleWorldRangeX().endInclusive)
    }

    @Test
    fun `padding extends visible range symmetrically`() {
        val t = transform()
        val noPad = t.visibleWorldRangeX(paddingWorld = 0f)
        val pad = t.visibleWorldRangeX(paddingWorld = 100f)
        assertEquals(noPad.start - 100f, pad.start, 0.01f)
        assertEquals(noPad.endInclusive + 100f, pad.endInclusive, 0.01f)
    }

    @Test
    fun `parallax preserves symmetry in visible range`() {
        val t = transform()
        val range = t.visibleWorldRangeX(parallax = 0.3f)
        assertEquals(-range.start, range.endInclusive, 0.01f)
    }

    // ===================== indexRangeForWorldRange =====================

    @Test
    fun `same visible range gives same index range`() {
        val r1 = WorldViewportTransform.indexRangeForWorldRange(-500f, 500f, 100f)
        val r2 = WorldViewportTransform.indexRangeForWorldRange(-500f, 500f, 100f)
        assertEquals(r1, r2)
    }

    @Test
    fun `larger visible range includes more indices`() {
        val small = WorldViewportTransform.indexRangeForWorldRange(-200f, 200f, 100f)
        val large = WorldViewportTransform.indexRangeForWorldRange(-500f, 500f, 100f)
        assertTrue(large.first <= small.first)
        assertTrue(large.last >= small.last)
    }

    @Test
    fun `range around center is symmetric for symmetric input`() {
        val range = WorldViewportTransform.indexRangeForWorldRange(-300f, 300f, 100f)
        assertEquals(-range.first, range.last)
    }

    @Test
    fun `index range covers exact cell boundaries`() {
        val range = WorldViewportTransform.indexRangeForWorldRange(0f, 300f, 100f)
        assertEquals(0, range.first)
        assertEquals(3, range.last)
    }

    @Test
    fun `index range covers fractional boundaries`() {
        val range = WorldViewportTransform.indexRangeForWorldRange(-50f, 250f, 100f)
        assertEquals(-1, range.first)
        assertEquals(3, range.last)
    }

    // ===================== Deterministic generation stability =====================

    @Test
    fun `same seed and index produce same object across heights`() {
        // Simulates: generate a mountain at index 5 at two different screen heights
        // The worldX should be identical
        val seed = 42
        val index = 5
        val cellSize = 220f

        val rng1 = DeterministicLayout.random(seed, section = 100, index = index)
        val jitter1 = (rng1.nextFloat() - 0.5f) * cellSize * 0.6f
        val worldX1 = index * cellSize + jitter1

        val rng2 = DeterministicLayout.random(seed, section = 100, index = index)
        val jitter2 = (rng2.nextFloat() - 0.5f) * cellSize * 0.6f
        val worldX2 = index * cellSize + jitter2

        assertEquals("same seed+index → same worldX", worldX1, worldX2, 0.0001f)
    }

    @Test
    fun `different index produces different object`() {
        val seed = 42
        val cellSize = 220f

        val rng1 = DeterministicLayout.random(seed, section = 100, index = 3)
        val worldX1 = 3 * cellSize + (rng1.nextFloat() - 0.5f) * cellSize * 0.6f

        val rng2 = DeterministicLayout.random(seed, section = 100, index = 4)
        val worldX2 = 4 * cellSize + (rng2.nextFloat() - 0.5f) * cellSize * 0.6f

        assertTrue("different index → different worldX",
            kotlin.math.abs(worldX1 - worldX2) > 1f)
    }

    // ===================== Height-only resize regression =====================

    @Test
    fun `height change keeps center object centered and sides equidistant`() {
        val w = 1080f
        val worldPositions = listOf(-300f, 0f, 300f)

        for (h in listOf(800f, 1200f, 1920f, 2400f)) {
            val t = transform(w, h)
            val screenPositions = worldPositions.map { t.worldToScreenX(it) }
            val center = t.centerX

            // Center object at screen center
            assertEquals("center at h=$h", center, screenPositions[1], 0.01f)

            // Left and right equidistant from center
            val distLeft = center - screenPositions[0]
            val distRight = screenPositions[2] - center
            assertEquals("symmetric at h=$h", distLeft, distRight, 0.01f)

            // No right-only expansion: left distance should be positive
            assertTrue("left object left of center at h=$h", distLeft > 0f)
        }
    }

    @Test
    fun `visible index range expands symmetrically on height decrease`() {
        val w = 1080f
        val cellSize = 100f

        val tall = transform(w, 2400f)
        val short = transform(w, 960f)

        val rangeTall = tall.visibleWorldRangeX()
        val rangeShort = short.visibleWorldRangeX()

        val idxTall = WorldViewportTransform.indexRangeForWorldRange(
            rangeTall.start, rangeTall.endInclusive, cellSize)
        val idxShort = WorldViewportTransform.indexRangeForWorldRange(
            rangeShort.start, rangeShort.endInclusive, cellSize)

        // Shorter screen should have wider index range (more world visible)
        assertTrue("more indices visible on shorter screen",
            idxShort.last - idxShort.first >= idxTall.last - idxTall.first)

        // Index range should be symmetric around 0
        assertEquals("tall symmetric", -idxTall.first, idxTall.last)
        assertEquals("short symmetric", -idxShort.first, idxShort.last)
    }
}
