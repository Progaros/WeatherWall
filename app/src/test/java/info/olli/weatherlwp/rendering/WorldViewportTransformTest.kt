package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldViewportTransformTest {

    /** Helper: default transform at reference device. worldHeight defaults to REFERENCE_HEIGHT. */
    private fun transform(w: Float = 1080f, h: Float = 1920f): WorldViewportTransform {
        return WorldViewportTransform(w, h)
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
    fun `distance from center scales with zoom`() {
        val w = 1080f
        val worldX = 200f
        // worldHeight = 1920 -> pixelsPerUnit = 1920/1920 = 1
        val t1 = WorldViewportTransform(w, 1920f, worldHeight = 1920f)
        // worldHeight = 960 -> pixelsPerUnit = 1920/960 = 2
        val t2 = WorldViewportTransform(w, 1920f, worldHeight = 960f)
        val dist1 = t1.worldToScreenX(worldX) - t1.centerX
        val dist2 = t2.worldToScreenX(worldX) - t2.centerX
        assertEquals(dist2 / dist1, 2f, 0.01f)
    }

    @Test
    fun `intra-layer spacing is preserved (no parallax compression)`() {
        val t = transform()
        val worldX1 = 100f
        val worldX2 = 300f
        val screenDist = t.worldToScreenX(worldX2) - t.worldToScreenX(worldX1)
        assertEquals((worldX2 - worldX1) * t.heightScale, screenDist, 0.01f)
    }

    // ===================== visibleWorldRangeX =====================

    @Test
    fun `visible range is symmetric around zero`() {
        val t = transform()
        val range = t.visibleWorldRangeX()
        assertEquals(-range.start, range.endInclusive, 0.01f)
    }

    @Test
    fun `visible range shrinks under zoom`() {
        val w = 1080f
        val zoomedOut = WorldViewportTransform(w, 1920f, worldHeight = 2400f)
        val zoomedIn = WorldViewportTransform(w, 1920f, worldHeight = 960f)
        val rangeOut = zoomedOut.visibleWorldRangeX()
        val rangeIn = zoomedIn.visibleWorldRangeX()
        assertTrue("zoomed in sees narrower world range",
            rangeIn.endInclusive < rangeOut.endInclusive)
    }

    @Test
    fun `visible range contracts when worldHeight decreases`() {
        val w = 1080f
        val ref = WorldViewportTransform(w, 1920f, worldHeight = 1920f)
        val zoomed = WorldViewportTransform(w, 1920f, worldHeight = 960f)
        assertTrue(ref.visibleWorldRangeX().endInclusive > zoomed.visibleWorldRangeX().endInclusive)
    }

    @Test
    fun `padding extends visible range symmetrically`() {
        val t = transform()
        val noPad = t.visibleWorldRangeX(paddingWorld = 0f)
        val pad = t.visibleWorldRangeX(paddingWorld = 100f)
        assertEquals(noPad.start - 100f, pad.start, 0.01f)
        assertEquals(noPad.endInclusive + 100f, pad.endInclusive, 0.01f)
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
        val seed = 42
        val index = 5
        val cellSize = 220f

        val rng1 = DeterministicLayout.random(seed, section = 100, index = index)
        val jitter1 = (rng1.nextFloat() - 0.5f) * cellSize * 0.6f
        val worldX1 = index * cellSize + jitter1

        val rng2 = DeterministicLayout.random(seed, section = 100, index = index)
        val jitter2 = (rng2.nextFloat() - 0.5f) * cellSize * 0.6f
        val worldX2 = index * cellSize + jitter2

        assertEquals("same seed+index -> same worldX", worldX1, worldX2, 0.0001f)
    }

    @Test
    fun `different index produces different object`() {
        val seed = 42
        val cellSize = 220f

        val rng1 = DeterministicLayout.random(seed, section = 100, index = 3)
        val worldX1 = 3 * cellSize + (rng1.nextFloat() - 0.5f) * cellSize * 0.6f

        val rng2 = DeterministicLayout.random(seed, section = 100, index = 4)
        val worldX2 = 4 * cellSize + (rng2.nextFloat() - 0.5f) * cellSize * 0.6f

        assertTrue("different index -> different worldX",
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

            assertEquals("center at h=$h", center, screenPositions[1], 0.01f)

            val distLeft = center - screenPositions[0]
            val distRight = screenPositions[2] - center
            assertEquals("symmetric at h=$h", distLeft, distRight, 0.01f)

            assertTrue("left object left of center at h=$h", distLeft > 0f)
        }
    }

    // ===================== visibleWorldRangeX: zoom scales visible range =====================

    @Test
    fun `visibleWorldRangeX uses worldHeight for zoom control`() {
        val w = 1080f
        // worldHeight = 1920 -> worldWidth = 1920 * (1080/1920) = 1080 -> half = 540
        val t1 = WorldViewportTransform(w, 1920f, worldHeight = 1920f)
        val range1 = t1.visibleWorldRangeX()
        assertEquals(w / 2f, range1.endInclusive, 0.01f)

        // worldHeight = 960 (zoomed in 2x) -> worldWidth = 960 * (1080/1920) = 540 -> half = 270
        val t2 = WorldViewportTransform(w, 1920f, worldHeight = 960f)
        val range2 = t2.visibleWorldRangeX()
        assertEquals(w / 4f, range2.endInclusive, 0.01f)

        // Confirm the ratio: range1 should be exactly 2x range2
        assertEquals(range1.endInclusive / range2.endInclusive, 2f, 0.01f)
    }

    // ===================== indexRangeForWorldRange: require positive cellSize =====================

    @Test(expected = IllegalArgumentException::class)
    fun `indexRangeForWorldRange throws on zero cellSize`() {
        WorldViewportTransform.indexRangeForWorldRange(-100f, 100f, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `indexRangeForWorldRange throws on negative cellSize`() {
        WorldViewportTransform.indexRangeForWorldRange(-100f, 100f, -10f)
    }

    @Test
    fun `indexRangeForWorldRange single cell boundary`() {
        val range = WorldViewportTransform.indexRangeForWorldRange(0f, 0.5f, 1f)
        assertEquals(0, range.first)
        assertEquals(1, range.last)
    }
}
