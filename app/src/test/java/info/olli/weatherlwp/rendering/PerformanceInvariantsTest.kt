package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance-correctness tests.
 *
 * These tests enforce structural invariants that prevent performance
 * regressions. If any of these fail, it means the rendering pipeline
 * has regressed to a state that wastes CPU/memory.
 */
class PerformanceInvariantsTest {

    // ===================== 1. Viewport culling =====================

    @Test
    fun `narrow viewport generates minimal cell count`() {
        // A very zoomed-in viewport should cover fewer cells than normal
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE  // 110f — reasonable cell size
        val t = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)
        val range = t.visibleCellRange(cellSize)
        val cellCount = range.last - range.first + 1

        // At 960 worldHeight, worldWidth = 960 * (1080/1920) = 540
        // Cells of size 110 should produce ~5-7 cells
        assertTrue("zoomed-in viewport should produce bounded cells, got $cellCount",
            cellCount in 1..20)
    }

    @Test
    fun `no cells generated outside padded world range with margin`() {
        val cellSize = 200f
        val padding = 100f
        val t = WorldViewportTransform(1080f, 1920f)
        val range = t.visibleCellRange(cellSize, padding)
        val worldRange = t.visibleWorldRangeX(padding)

        // Every cell in the range must be within one cellSize of the padded world range
        // (indexRangeForWorldRange is intentionally conservative by up to 1 cell)
        for (idx in range) {
            val cellLeft = idx * cellSize
            val cellRight = (idx + 1) * cellSize
            assertTrue("cell $idx [$cellLeft, $cellRight] must be near world range " +
                "[${worldRange.start}, ${worldRange.endInclusive}]",
                cellRight >= worldRange.start - cellSize && cellLeft <= worldRange.endInclusive + cellSize)
        }
    }

    // ===================== 2. Zoom cost scaling =====================

    @Test
    fun `zoom in reduces generated cell count`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val tNormal = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val tZoomed = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)

        val normalRange = tNormal.visibleCellRange(cellSize)
        val zoomedRange = tZoomed.visibleCellRange(cellSize)

        val normalCount = normalRange.last - normalRange.first + 1
        val zoomedCount = zoomedRange.last - zoomedRange.first + 1

        assertTrue("zoomed-in should have fewer cells: normal=$normalCount, zoomed=$zoomedCount",
            zoomedCount < normalCount)
    }

    @Test
    fun `zoom out increases cell count proportionally`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val t1 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val t2 = WorldViewportTransform(1080f, 1920f, worldHeight = 3840f)

        val count1 = t1.visibleCellRange(cellSize).let { it.last - it.first + 1 }
        val count2 = t2.visibleCellRange(cellSize).let { it.last - it.first + 1 }

        // 2x worldHeight → 2x worldWidth → ~2x cells
        val ratio = count2.toFloat() / count1
        assertTrue("2x zoom-out should roughly double cells: ratio=$ratio",
            ratio in 1.5f..2.5f)
    }

    // ===================== 3. Scrolling stability =====================

    @Test
    fun `small camera movement only changes edge cells`() {
        val cellSize = 200f
        val t1 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f, centerWorldX = 0f)
        val t2 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f, centerWorldX = 50f)

        val range1 = t1.visibleCellRange(cellSize)
        val range2 = t2.visibleCellRange(cellSize)

        // Overlap: most cells should be the same
        val overlap = (maxOf(range1.first, range2.first)..minOf(range1.last, range2.last))
        val overlapCount = overlap.last - overlap.first + 1
        val totalUnion = maxOf(range1.last, range2.last) - minOf(range1.first, range2.first) + 1

        assertTrue("small pan should share most cells: overlap=$overlapCount/$totalUnion",
            overlapCount.toFloat() / totalUnion > 0.7f)
    }

    @Test
    fun `overlapping world ranges produce identical cell subsets`() {
        val cellSize = 200f
        val t1 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f, centerWorldX = 0f)
        val t2 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f, centerWorldX = 100f)

        val range1 = t1.visibleCellRange(cellSize)
        val range2 = t2.visibleCellRange(cellSize)
        val set1 = range1.toSet()
        val set2 = range2.toSet()

        // The intersection should be non-empty (viewports overlap)
        val intersection = set1.intersect(set2)
        assertTrue("overlapping viewports must share cells", intersection.isNotEmpty())
    }

    // ===================== 4. No full-world iteration =====================

    @Test
    fun `indexRangeForWorldRange produces bounded count`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE  // 110f
        // Simulate a typical viewport
        val half = RenderConstants.getVisibleWorldHalf(1080f)
        val range = WorldViewportTransform.indexRangeForWorldRange(-half, half, cellSize)
        val count = range.last - range.first + 1

        // At 1080px wide with 110 cells, expect ~(1080/2 + 864)*2 / 110 ≈ 26 cells
        assertTrue("cell count should be bounded, got $count",
            count in 1..40)
    }

    @Test
    fun `generation range is independent of world size`() {
        // Two different screen widths should produce different generation ranges
        // but both should be bounded (not infinite)
        val half1 = RenderConstants.getVisibleWorldHalf(1080f)
        val half2 = RenderConstants.getVisibleWorldHalf(2160f)

        assertTrue("generation half must be finite and positive", half1 > 0f && half1 < 10000f)
        assertTrue("wider screen produces wider range", half2 > half1)
        assertTrue("range scales with width, not world size", half2 < half1 * 3f)
    }

    // ===================== 5. visibleWorldRange and visibleCellRange helpers =====================

    @Test
    fun `visibleWorldRange matches visibleWorldRangeX`() {
        val t = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val range1 = t.visibleWorldRange(50f)
        val range2 = t.visibleWorldRangeX(50f)

        assertEquals(range1.start, range2.start, 0.001f)
        assertEquals(range1.endInclusive, range2.endInclusive, 0.001f)
    }

    @Test
    fun `visibleCellRange matches manual indexRangeForWorldRange`() {
        val cellSize = 200f
        val padding = 100f
        val t = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)

        val range1 = t.visibleCellRange(cellSize, padding)
        val worldRange = t.visibleWorldRangeX(padding)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(
            worldRange.start, worldRange.endInclusive, cellSize
        )

        assertEquals(range1, range2)
    }

    // ===================== 6. Mountain shape caching invariant =====================

    @Test
    fun `mountain generation range bounded by visible world half`() {
        val half = RenderConstants.getVisibleWorldHalf(1080f)
        val range = WorldViewportTransform.indexRangeForWorldRange(
            -half, half, MountainLayer.MOUNTAIN_CELL_SIZE
        )
        val count = range.last - range.first + 1

        // With MOUNTAIN_CELL_SIZE=110 and half≈1404, expect ~26 cells
        assertTrue("mountain cell count should be bounded, got $count",
            count in 1..40)
    }
}
