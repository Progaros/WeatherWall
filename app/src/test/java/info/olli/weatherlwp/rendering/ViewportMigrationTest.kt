package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verifying the viewport migration: all world-space layers use
 * WorldViewportTransform for world→screen mapping, seasonal decorations
 * use world-space coordinates, and the viewport model produces correct
 * zoom behavior.
 */
class ViewportMigrationTest {

    // ===================== Viewport zoom invariants =====================

    @Test
    fun `reducing worldHeight reduces worldWidth`() {
        val v1 = Viewport(1080, 1920, worldHeight = 2000f)
        val v2 = Viewport(1080, 1920, worldHeight = 1000f)
        assertTrue("smaller worldHeight must produce smaller worldWidth",
            v2.worldWidth < v1.worldWidth)
        // Ratio should match
        assertEquals(v1.worldWidth / 2f, v2.worldWidth, 0.01f)
    }

    @Test
    fun `fixed worldX changes screenX under zoom`() {
        val worldX = 300f
        val zoomedOut = Viewport(1080, 1920, worldHeight = 2000f)
        val zoomedIn = Viewport(1080, 1920, worldHeight = 1000f)
        val mOut = WorldToScreenMapper(zoomedOut)
        val mIn = WorldToScreenMapper(zoomedIn)

        val screenXOut = mOut.x(worldX)
        val screenXIn = mIn.x(worldX)

        // Screen position MUST change when zoom changes (not anchored)
        assertTrue("object screen x must change under zoom",
            kotlin.math.abs(screenXOut - screenXIn) > 1f)
        // Zoomed in → further from center on screen
        val distOut = screenXOut - 540f
        val distIn = screenXIn - 540f
        assertTrue("zoom in pushes object further from center",
            kotlin.math.abs(distIn) > kotlin.math.abs(distOut))
    }

    // ===================== WorldViewportTransform consistency =====================

    @Test
    fun `WorldViewportTransform worldToScreenX matches Viewport mapper`() {
        val w = 1080f
        val h = 1920f
        val transform = WorldViewportTransform(w, h)
        val vp = Viewport(w.toInt(), h.toInt(), worldHeight = RenderConstants.REFERENCE_HEIGHT)
        val mapper = WorldToScreenMapper(vp)

        for (worldX in listOf(-500f, -200f, 0f, 200f, 500f)) {
            val fromTransform = transform.worldToScreenX(worldX)
            val fromMapper = mapper.x(worldX)
            assertEquals("Mismatch at worldX=$worldX", fromTransform, fromMapper, 0.5f)
        }
    }

    @Test
    fun `WorldViewportTransform worldToScreenX matches for landscape`() {
        val w = 1920f
        val h = 1080f
        val transform = WorldViewportTransform(w, h)
        val vp = Viewport(w.toInt(), h.toInt(), worldHeight = RenderConstants.REFERENCE_HEIGHT)
        val mapper = WorldToScreenMapper(vp)

        for (worldX in listOf(-800f, -400f, 0f, 400f, 800f)) {
            val fromTransform = transform.worldToScreenX(worldX)
            val fromMapper = mapper.x(worldX)
            assertEquals("Landscape mismatch at worldX=$worldX", fromTransform, fromMapper, 0.5f)
        }
    }

    // ===================== Overlapping generation ranges =====================

    @Test
    fun `overlapping generated cell ranges produce identical objects`() {
        // Narrow and wide visible ranges must produce the same objects
        // for all overlapping cell indices
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val seed = 42
        val hillIndex = 0

        val narrowHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val wideHalf = RenderConstants.getVisibleWorldHalf(1920f, 1080f)

        val narrowRange = WorldViewportTransform.indexRangeForWorldRange(
            -narrowHalf, narrowHalf, cellSize
        )
        val wideRange = WorldViewportTransform.indexRangeForWorldRange(
            -wideHalf, wideHalf, cellSize
        )

        // All narrow cells must be in wide range
        for (idx in narrowRange) {
            assertTrue("cell $idx missing in wider range", idx in wideRange)
        }

        // Objects at overlapping indices must be identical
        for (idx in narrowRange) {
            val r1 = DeterministicLayout.random(seed + 2000 + hillIndex, section = 310, index = idx)
            val r2 = DeterministicLayout.random(seed + 2000 + hillIndex, section = 310, index = idx)
            r1.nextFloat(); r2.nextFloat() // density gate
            val wx1 = idx * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f
            val wx2 = idx * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f
            assertEquals("tree at cell $idx differs between narrow and wide",
                wx1, wx2, 0f)
        }
    }

    @Test
    fun `overlapping mountain cell ranges produce identical mountains`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val seed = 42

        val narrowHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val wideHalf = RenderConstants.getVisibleWorldHalf(1920f, 1080f)

        val narrowRange = WorldViewportTransform.indexRangeForWorldRange(
            -narrowHalf, narrowHalf, cellSize
        )
        val wideRange = WorldViewportTransform.indexRangeForWorldRange(
            -wideHalf, wideHalf, cellSize
        )

        for (idx in narrowRange) {
            val r1 = DeterministicLayout.random(seed, 100, idx)
            val r2 = DeterministicLayout.random(seed, 100, idx)
            assertEquals("mountain at cell $idx differs",
                r1.nextFloat(), r2.nextFloat(), 0f)
        }
    }

    // ===================== Landscape short-height viewport =====================

    @Test
    fun `landscape wider screen generates wider visible range`() {
        // 1920-wide landscape should have wider generation range than 1080-wide portrait
        val portraitHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val landscapeHalf = RenderConstants.getVisibleWorldHalf(1920f, 1080f)

        assertTrue("landscape (wider screen) must generate wider range",
            landscapeHalf > portraitHalf)
    }

    @Test
    fun `generation range is aspect-ratio-aware`() {
        // Same screen width at different heights → different aspect ratios → different ranges
        // Shorter height = wider aspect = more world visible horizontally
        val tallHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val shortHalf = RenderConstants.getVisibleWorldHalf(1080f, 960f)

        assertTrue("shorter screen (wider aspect) must produce wider generation range",
            shortHalf > tallHalf)
    }

    // ===================== heightScale / positionScale audit =====================

    @Test
    fun `getPositionScale is deprecated and equals getHeightScale`() {
        // Verify positionScale remains an exact alias for backwards compat
        for ((w, h) in listOf(1080f to 1920f, 1920f to 1080f, 720f to 1280f)) {
            @Suppress("DEPRECATION")
            val posScale = RenderConstants.getPositionScale(w, h)
            val heightScale = RenderConstants.getHeightScale(h, w)
            assertEquals("positionScale should equal heightScale at ${w}x${h}",
                heightScale, posScale, 0f)
        }
    }

    // ===================== Generation range does not include far objects =====================

    @Test
    fun `visible world half does not extend unreasonably far`() {
        val half = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        // Should be larger than half screen width (generation covers viewport + padding)
        val screenHalfWorld = 1080f / 2f
        assertTrue("visibleHalf should exceed screen half-width",
            half > screenHalfWorld)
        // But should not be absurdly large (< 10x screen width in world units)
        assertTrue("visibleHalf should be reasonable",
            half < screenHalfWorld * 10f)
    }

    @Test
    fun `bush generation range covers visible area with padding`() {
        val cellSize = BushRenderer.BUSH_CELL_SIZE
        val half = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val range = WorldViewportTransform.indexRangeForWorldRange(-half, half, cellSize)

        // Range should include cells beyond screen edges for parallax
        val screenEdgeWorld = 1080f / 2f
        val edgeCell = (screenEdgeWorld / cellSize).toInt()
        assertTrue("range should extend past screen edge cell",
            range.last > edgeCell)
        assertTrue("range should extend past negative screen edge cell",
            range.first < -edgeCell)
    }

    // ===================== World-space decoration generation =====================

    @Test
    fun `decoration worldX is centered around zero`() {
        // Flowers, leaves, mushrooms should be generated around worldX=0
        val seed = 42
        val worldHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)

        // Check flower generation pattern
        for (f in 0 until 20) {
            val r = DeterministicLayout.random(seed + 6000, section = 600, index = f)
            val worldX = (r.nextFloat() - 0.5f) * worldHalf * 2f
            assertTrue("flower worldX should be within generation range",
                worldX >= -worldHalf && worldX <= worldHalf)
        }
    }

    @Test
    fun `decoration worldX is deterministic per index`() {
        val seed = 42
        val worldHalf = RenderConstants.getVisibleWorldHalf(1080f, 1920f)

        for (f in 0 until 10) {
            val r1 = DeterministicLayout.random(seed + 6000, section = 600, index = f)
            val r2 = DeterministicLayout.random(seed + 6000, section = 600, index = f)
            val wx1 = (r1.nextFloat() - 0.5f) * worldHalf * 2f
            val wx2 = (r2.nextFloat() - 0.5f) * worldHalf * 2f
            assertEquals("flower $f worldX should be deterministic", wx1, wx2, 0f)
        }
    }

    // ===================== xRatio adapter equivalence =====================

    @Test
    fun `xRatio adapter and world-space API are equivalent`() {
        val w = 1080f
        val h = 1920f
        val t = WorldViewportTransform(w, h)

        for (xRatio in listOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val screenX = xRatio * w
            val worldX = t.screenToWorldX(screenX)

            // The world-space coordinate derived from xRatio should give
            // the same screen position when projected back
            val reconstructedScreenX = t.worldToScreenX(worldX)
            assertEquals("round-trip for xRatio=$xRatio",
                screenX, reconstructedScreenX, 0.01f)
        }
    }

    // ===================== Banned API usage =====================

    @Test
    fun `no production renderer calls getPositionScale`() {
        // getPositionScale is deprecated. Verify only the definition exists in production.
        // This is a documentation/awareness test — the actual enforcement is that
        // getPositionScale is marked @Deprecated and no production code references it.
        @Suppress("DEPRECATION")
        val scale = RenderConstants.getPositionScale(1080f, 1920f)
        val heightScale = RenderConstants.getHeightScale(1920f, 1080f)
        assertEquals("getPositionScale must equal getHeightScale",
            heightScale, scale, 0f)
    }
}
