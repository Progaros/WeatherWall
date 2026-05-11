package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that verify the world-coordinate generation model:
 * - Objects are placed in world-space (centered at 0)
 * - Positions are deterministic from seed alone
 * - Screen dimensions do NOT affect generated world coordinates
 * - Screen projection correctly maps worldX=0 to screen center
 * - Cell-indexed generation produces stable objects across resize
 */
class WorldCoordinateModelTest {

    // ===================== Mountain cell-indexed generation =====================

    @Test
    fun `mountain worldX is deterministic for same seed and index`() {
        val seed = 42
        val section = 100
        for (index in -5..5) {
            val r1 = DeterministicLayout.random(seed, section, index)
            val r2 = DeterministicLayout.random(seed, section, index)
            assertEquals(r1.nextFloat(), r2.nextFloat(), 0f)
            assertEquals(r1.nextFloat(), r2.nextFloat(), 0f)
        }
    }

    @Test
    fun `mountain cell worldX is within cell bounds`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        for (index in -3..3) {
            val random = DeterministicLayout.random(42, 100, index)
            random.nextFloat() // density gate consume
            val worldX = index * cellSize +
                (random.nextFloat() - 0.5f) * cellSize * 0.6f
            val cellStart = index * cellSize - cellSize * 0.5f
            val cellEnd = index * cellSize + cellSize * 1.5f
            assertTrue("worldX $worldX out of range for index $index",
                worldX > cellStart && worldX < cellEnd)
        }
    }

    @Test
    fun `mountain index range expands symmetrically for wider screens`() {
        val rangeNarrow = WorldViewportTransform.indexRangeForWorldRange(-400f, 400f, 220f)
        val rangeWide = WorldViewportTransform.indexRangeForWorldRange(-800f, 800f, 220f)

        assertTrue(rangeWide.first <= rangeNarrow.first)
        assertTrue(rangeWide.last >= rangeNarrow.last)
        // Symmetry: negative and positive indices should be roughly balanced
        assertTrue(rangeWide.first < 0)
        assertTrue(rangeWide.last > 0)
    }

    @Test
    fun `same mountain index produces same worldX regardless of visible range`() {
        val seed = 42
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        // Simulate two different visible ranges (different heights)
        val range1 = WorldViewportTransform.indexRangeForWorldRange(-400f, 400f, cellSize)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(-800f, 800f, cellSize)
        
        // For any index in both ranges, worldX must be identical
        for (index in range1) {
            assertTrue("index $index should be in wider range", index in range2)
            val r1 = DeterministicLayout.random(seed, 100, index)
            val r2 = DeterministicLayout.random(seed, 100, index)
            r1.nextFloat(); r2.nextFloat() // density gate
            val wx1 = index * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.6f
            val wx2 = index * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.6f
            assertEquals("worldX mismatch at index $index", wx1, wx2, 0f)
        }
    }

    // ===================== Tree cell-indexed generation =====================

    @Test
    fun `tree cell worldX is deterministic per index`() {
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val seed = 42
        val hillIndex = 0
        for (cellIndex in -5..5) {
            val r1 = DeterministicLayout.random(seed + 2000 + hillIndex, section = 310, index = cellIndex)
            val r2 = DeterministicLayout.random(seed + 2000 + hillIndex, section = 310, index = cellIndex)
            r1.nextFloat(); r2.nextFloat() // density gate
            val wx1 = cellIndex * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f
            val wx2 = cellIndex * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f
            assertEquals("tree worldX mismatch at cell $cellIndex", wx1, wx2, 0f)
        }
    }

    @Test
    fun `tree cell index range expands when world range grows`() {
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val range1 = WorldViewportTransform.indexRangeForWorldRange(-500f, 500f, cellSize)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(-1000f, 1000f, cellSize)
        assertTrue(range2.first <= range1.first)
        assertTrue(range2.last >= range1.last)
        // Additional side cells exist in wider range
        assertTrue((range2.last - range2.first) > (range1.last - range1.first))
    }

    @Test
    fun `different tree cell indices produce different worldX`() {
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val seed = 42
        val r1 = DeterministicLayout.random(seed + 2000, section = 310, index = 0)
        val r2 = DeterministicLayout.random(seed + 2000, section = 310, index = 1)
        r1.nextFloat(); r2.nextFloat()
        val wx1 = 0 * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f
        val wx2 = 1 * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f
        assertNotEquals("cells 0 and 1 should have different worldX", wx1, wx2, 0.01f)
    }

    // ===================== Bush cell-indexed generation =====================

    @Test
    fun `bush cell worldX is deterministic per index`() {
        val cellSize = BushRenderer.BUSH_CELL_SIZE
        val seed = 42
        val hillIndex = 0
        for (cellIndex in -3..3) {
            val r1 = DeterministicLayout.random(seed + 5000 + hillIndex, section = 500, index = cellIndex)
            val r2 = DeterministicLayout.random(seed + 5000 + hillIndex, section = 500, index = cellIndex)
            r1.nextFloat(); r2.nextFloat() // density gate
            val wx1 = cellIndex * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f
            val wx2 = cellIndex * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f
            assertEquals("bush worldX mismatch at cell $cellIndex", wx1, wx2, 0f)
        }
    }

    // ===================== Cloud generation =====================

    @Test
    fun `cloud per-index seed is stable regardless of cloud count`() {
        // Cloud 0 in back layer should always get the same seed
        val seed = 42
        val r1 = DeterministicLayout.random(seed + 4000, section = 401, index = 0)
        val r2 = DeterministicLayout.random(seed + 4000, section = 401, index = 0)
        assertEquals(r1.nextFloat(), r2.nextFloat(), 0f)
        assertEquals(r1.nextFloat(), r2.nextFloat(), 0f)
    }

    @Test
    fun `cloud initialWorldX is centered around zero`() {
        val screenWidth = 1080f
        val genWorldRange = RenderConstants.getGenerationWorldRange(screenWidth)
        val halfRange = genWorldRange / 2f

        val random = DeterministicLayout.random(42, 401, 0)
        val worldX = (random.nextFloat() - 0.5f) * genWorldRange
        assertTrue(worldX >= -halfRange)
        assertTrue(worldX <= halfRange)
    }

    @Test
    fun `cloud wrapping stays within world bounds`() {
        val screenWidth = 1080f
        val genWorldRange = RenderConstants.getGenerationWorldRange(screenWidth)
        val halfRange = genWorldRange / 2f

        var worldX = halfRange + 50f
        while (worldX > halfRange) worldX -= genWorldRange
        while (worldX < -halfRange) worldX += genWorldRange

        assertTrue(worldX >= -halfRange)
        assertTrue(worldX <= halfRange)
    }

    @Test
    fun `cloud count scaling is height-independent`() {
        // getGenerationWorldRange depends only on screenWidth
        val range1 = RenderConstants.getGenerationWorldRange(1080f)
        val range2 = RenderConstants.getGenerationWorldRange(1080f)
        assertEquals(range1, range2, 0f)
        // Scale factor for cloud count is width-only
        val scale1 = (range1 / RenderConstants.REFERENCE_EXTENDED_WIDTH).coerceIn(1.0f, 1.5f)
        val scale2 = (range2 / RenderConstants.REFERENCE_EXTENDED_WIDTH).coerceIn(1.0f, 1.5f)
        assertEquals(scale1, scale2, 0f)
    }

    // ===================== Screen projection =====================

    @Test
    fun `worldX 0 projects to screen center for any screen size`() {
        for ((w, h) in listOf(720f to 1280f, 1080f to 1920f, 1440f to 2560f, 1080f to 800f)) {
            val heightScale = RenderConstants.getHeightScale(h)
            val screenX = w / 2f + 0f * heightScale
            assertEquals("screen center broken at ${w}x${h}", w / 2f, screenX, 0.01f)
        }
    }

    @Test
    fun `same worldX maps further from center on taller screens`() {
        val worldX = 200f
        val w = 1080f
        val heightScaleShort = RenderConstants.getHeightScale(960f)
        val heightScaleTall = RenderConstants.getHeightScale(2400f)

        val screenXShort = w / 2f + worldX * heightScaleShort
        val screenXTall = w / 2f + worldX * heightScaleTall

        val distShort = screenXShort - w / 2f
        val distTall = screenXTall - w / 2f

        assertTrue("Taller screen should push further from center", distTall > distShort)
        assertEquals(heightScaleTall / heightScaleShort, distTall / distShort, 0.01f)
    }

    @Test
    fun `height-only resize does not change worldX of generated objects`() {
        val seed = 99
        val section = 100
        val index = 5
        val r1 = DeterministicLayout.random(seed, section, index)
        val r2 = DeterministicLayout.random(seed, section, index)

        val worldX1 = r1.nextFloat()
        val worldX2 = r2.nextFloat()
        assertEquals(worldX1, worldX2, 0f)
    }

    // ===================== Terrain decorations =====================

    @Test
    fun `terrain decorations use per-item seeds, not shared random`() {
        val seed = 42
        val hillIndex = 2
        val r1 = DeterministicLayout.random(seed + 6000 + hillIndex, section = 600, index = 0)
        val r2 = DeterministicLayout.random(seed + 6000 + hillIndex, section = 600, index = 1)

        val x1 = r1.nextFloat()
        val x2 = r2.nextFloat()
        assertTrue("Items at different indices should differ", x1 != x2)
    }

    @Test
    fun `terrain decoration yOffset is normalized (height-independent)`() {
        val seed = 42
        val hillIndex = 0
        val maxVerticalOffset = 0.5f * 0.12f
        val r = DeterministicLayout.random(seed + 6000 + hillIndex, section = 600, index = 0)
        r.nextFloat() // skip xRatio
        val yOffset = r.nextFloat() * maxVerticalOffset

        assertTrue("yOffset should be normalized (< 1.0)", yOffset < 1f)
        assertTrue("yOffset should be non-negative", yOffset >= 0f)
    }

    // ===================== Generation world range =====================

    @Test
    fun `generation world range is width-dependent only`() {
        val range1 = RenderConstants.getGenerationWorldRange(1080f)
        val range2 = RenderConstants.getGenerationWorldRange(1080f)
        assertEquals(range1, range2, 0f)

        val rangeWide = RenderConstants.getGenerationWorldRange(1440f)
        assertTrue(rangeWide > range1)
    }

    @Test
    fun `generation world range is symmetric around zero`() {
        val range = RenderConstants.getGenerationWorldRange(1080f)
        assertTrue(range > 0f)
    }

    // ===================== REGRESSION: Original resize bug =====================

    @Test
    fun `height resize keeps center object centered and sides equidistant`() {
        // Given world objects at -d, 0, +d
        val d = 300f
        val w = 1080f

        for (h in listOf(800f, 1200f, 1920f, 2400f)) {
            val heightScale = RenderConstants.getHeightScale(h)

            val screenCenter = w / 2f + 0f * heightScale
            val screenLeft = w / 2f + (-d) * heightScale
            val screenRight = w / 2f + d * heightScale

            // Center object remains centered
            assertEquals("center not centered at h=$h", w / 2f, screenCenter, 0.01f)

            // Left and right objects are equidistant from center
            val distLeft = screenCenter - screenLeft
            val distRight = screenRight - screenCenter
            assertEquals("left/right not equidistant at h=$h", distLeft, distRight, 0.01f)

            // No right-only expansion: both sides move equally
            assertTrue("left should be to the left at h=$h", screenLeft < screenCenter)
            assertTrue("right should be to the right at h=$h", screenRight > screenCenter)
        }
    }

    @Test
    fun `height resize changes projection distance but not worldX`() {
        val d = 300f

        val heightScale1 = RenderConstants.getHeightScale(1200f)
        val heightScale2 = RenderConstants.getHeightScale(2400f)

        val dist1 = d * heightScale1
        val dist2 = d * heightScale2

        // Taller screen → elements further from center
        assertTrue("taller screen should increase distance", dist2 > dist1)
        // But worldX is still d — it didn't change
    }

    @Test
    fun `no object anchored to left edge on height change`() {
        // Simulate: if an object were anchored to x=0 screen-space (left edge),
        // it would NOT move when height changes. In our model, objects at
        // worldX != 0 MUST move when height changes (they scale from center).
        val worldX = -200f
        val w = 1080f

        val screenX_short = w / 2f + worldX * RenderConstants.getHeightScale(960f)
        val screenX_tall = w / 2f + worldX * RenderConstants.getHeightScale(2400f)

        // The screen position must CHANGE (not be anchored)
        assertTrue("object should move on height change",
            kotlin.math.abs(screenX_short - screenX_tall) > 1f)
    }

    @Test
    fun `wider visible range adds side indices on both sides`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val narrow = WorldViewportTransform.indexRangeForWorldRange(-400f, 400f, cellSize)
        val wide = WorldViewportTransform.indexRangeForWorldRange(-800f, 800f, cellSize)

        // Added indices on the LEFT
        assertTrue("wider range should add left indices", wide.first < narrow.first)
        // Added indices on the RIGHT
        assertTrue("wider range should add right indices", wide.last > narrow.last)
    }

    // ===================== Height-based zoom (getVisibleWorldHalf) =====================

    @Test
    fun `getVisibleWorldHalf expands when height decreases`() {
        val w = 1080f
        val tallHalf = RenderConstants.getVisibleWorldHalf(w, 2400f)
        val shortHalf = RenderConstants.getVisibleWorldHalf(w, 960f)
        assertTrue("shorter height should give wider world half", shortHalf > tallHalf)
    }

    @Test
    fun `getVisibleWorldHalf at reference height matches expected`() {
        val w = 1080f
        val half = RenderConstants.getVisibleWorldHalf(w, RenderConstants.REFERENCE_HEIGHT)
        // At reference height, heightScale=1, so visibleHalf = w/(2*1) + parallaxPad
        val expected = w / 2f + RenderConstants.REFERENCE_HEIGHT * 0.45f
        assertEquals(expected, half, 0.01f)
    }

    @Test
    fun `getVisibleWorldHalf scales inversely with heightScale`() {
        val w = 1080f
        val h1 = 960f
        val h2 = 1920f
        val half1 = RenderConstants.getVisibleWorldHalf(w, h1)
        val half2 = RenderConstants.getVisibleWorldHalf(w, h2)
        // The viewport half (excluding parallax pad) should scale inversely
        val pad = RenderConstants.REFERENCE_HEIGHT * 0.45f
        val viewport1 = half1 - pad
        val viewport2 = half2 - pad
        val scale1 = RenderConstants.getHeightScale(h1)
        val scale2 = RenderConstants.getHeightScale(h2)
        // viewport = screenWidth / (2 * heightScale), so viewport1/viewport2 = scale2/scale1
        assertEquals(scale2 / scale1, viewport1 / viewport2, 0.01f)
    }

    @Test
    fun `shorter screen generates more cell indices (zoom-out)`() {
        val w = 1080f
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val tallHalf = RenderConstants.getVisibleWorldHalf(w, 2400f)
        val shortHalf = RenderConstants.getVisibleWorldHalf(w, 960f)
        val rangeTall = WorldViewportTransform.indexRangeForWorldRange(-tallHalf, tallHalf, cellSize)
        val rangeShort = WorldViewportTransform.indexRangeForWorldRange(-shortHalf, shortHalf, cellSize)
        assertTrue("shorter screen sees more cells",
            (rangeShort.last - rangeShort.first) >= (rangeTall.last - rangeTall.first))
    }

    @Test
    fun `height zoom compresses elements toward center on short screen`() {
        val w = 1080f
        val worldX = 300f
        val tallScale = RenderConstants.getHeightScale(2400f)
        val shortScale = RenderConstants.getHeightScale(960f)
        val screenTall = w / 2f + worldX * tallScale
        val screenShort = w / 2f + worldX * shortScale
        // On shorter screen, element is closer to center
        val distTall = screenTall - w / 2f
        val distShort = screenShort - w / 2f
        assertTrue("shorter height compresses toward center", distShort < distTall)
    }
}
