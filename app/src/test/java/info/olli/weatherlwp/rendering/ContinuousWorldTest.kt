package info.olli.weatherlwp.rendering

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the world is conceptually infinite and continuous.
 *
 * These tests would FAIL if generation range were too narrow for the viewport.
 */
class ContinuousWorldTest {

    // ===================== 1. Landscape fill =====================

    @Test
    fun `landscape generation covers viewport with padding`() {
        // Landscape phone: 1920x1080
        val screenW = 1920f
        val screenH = 1080f
        val genHalf = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        // Viewport visible world half at default zoom (worldHeight = REFERENCE_HEIGHT)
        val aspect = screenW / screenH
        val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f

        assertTrue("generation half ($genHalf) must exceed viewport half ($viewportHalf)",
            genHalf > viewportHalf)
        // Must include at least some parallax padding
        assertTrue("generation padding must be >= 100 world units",
            genHalf - viewportHalf >= 100f)
    }

    @Test
    fun `wide tablet landscape generation covers viewport with padding`() {
        // Wide tablet: 2560x1080
        val screenW = 2560f
        val screenH = 1080f
        val genHalf = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        val aspect = screenW / screenH
        val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f

        assertTrue("generation half ($genHalf) must exceed viewport half ($viewportHalf)",
            genHalf > viewportHalf)
        assertTrue("generation padding must be >= 100 world units",
            genHalf - viewportHalf >= 100f)
    }

    @Test
    fun `foldable landscape generation covers viewport with padding`() {
        // Foldable unfolded: 2208x1840
        val screenW = 2208f
        val screenH = 1840f
        val genHalf = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        val aspect = screenW / screenH
        val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f

        assertTrue("generation half ($genHalf) must exceed viewport half ($viewportHalf)",
            genHalf > viewportHalf)
    }

    @Test
    fun `landscape cell range fills viewport for mountains`() {
        val screenW = 1920f
        val screenH = 1080f
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val genHalf = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        val cellRange = WorldViewportTransform.indexRangeForWorldRange(-genHalf, genHalf, cellSize)

        // The generated cell range must span the viewport
        val aspect = screenW / screenH
        val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f
        val generatedMinWorldX = cellRange.first * cellSize
        val generatedMaxWorldX = (cellRange.last + 1) * cellSize

        assertTrue("generated cells must reach left viewport edge",
            generatedMinWorldX <= -viewportHalf)
        assertTrue("generated cells must reach right viewport edge",
            generatedMaxWorldX >= viewportHalf)
    }

    @Test
    fun `landscape cell range fills viewport for trees`() {
        val screenW = 1920f
        val screenH = 1080f
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val genHalf = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        val cellRange = WorldViewportTransform.indexRangeForWorldRange(-genHalf, genHalf, cellSize)

        val aspect = screenW / screenH
        val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f
        val generatedMinWorldX = cellRange.first * cellSize
        val generatedMaxWorldX = (cellRange.last + 1) * cellSize

        assertTrue("generated tree cells must reach left viewport edge",
            generatedMinWorldX <= -viewportHalf)
        assertTrue("generated tree cells must reach right viewport edge",
            generatedMaxWorldX >= viewportHalf)
    }

    // ===================== 2. Continuous generation =====================

    @Test
    fun `adjacent viewports share overlapping cells`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        // Two viewports shifted by half a screen width
        val screenW = 1080f
        val screenH = 1920f
        val half = RenderConstants.getVisibleWorldHalf(screenW, screenH)
        val shift = screenW / (2f * (screenH / RenderConstants.REFERENCE_HEIGHT)) // half viewport in world units

        val range1 = WorldViewportTransform.indexRangeForWorldRange(-half, half, cellSize)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(-half + shift, half + shift, cellSize)

        val set1 = range1.toSet()
        val set2 = range2.toSet()
        val overlap = set1.intersect(set2)

        assertTrue("adjacent viewports must share cells, overlap=${overlap.size}",
            overlap.size > set1.size / 2)
    }

    @Test
    fun `new objects only appear at edges when panning`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val half = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        val smallShift = cellSize * 0.5f // shift by half a cell

        val range1 = WorldViewportTransform.indexRangeForWorldRange(-half, half, cellSize)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(
            -half + smallShift, half + smallShift, cellSize)

        val set1 = range1.toSet()
        val set2 = range2.toSet()
        val newCells = set2 - set1
        val lostCells = set1 - set2

        // Small pan should only add/remove at most 1-2 cells at the edges
        assertTrue("small pan should add <= 2 cells, added ${newCells.size}",
            newCells.size <= 2)
        assertTrue("small pan should lose <= 2 cells, lost ${lostCells.size}",
            lostCells.size <= 2)
    }

    // ===================== 3. Zoom-out expands generation =====================

    @Test
    fun `zoom out generates more cells`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val t1 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val t2 = WorldViewportTransform(1080f, 1920f, worldHeight = 3840f)

        val count1 = t1.visibleCellRange(cellSize).let { it.last - it.first + 1 }
        val count2 = t2.visibleCellRange(cellSize).let { it.last - it.first + 1 }

        assertTrue("zoom-out should generate more cells: $count1 < $count2",
            count2 > count1)
    }

    @Test
    fun `zoom in generates fewer cells`() {
        val cellSize = MountainLayer.MOUNTAIN_CELL_SIZE
        val t1 = WorldViewportTransform(1080f, 1920f, worldHeight = 1920f)
        val t2 = WorldViewportTransform(1080f, 1920f, worldHeight = 960f)

        val count1 = t1.visibleCellRange(cellSize).let { it.last - it.first + 1 }
        val count2 = t2.visibleCellRange(cellSize).let { it.last - it.first + 1 }

        assertTrue("zoom-in should generate fewer cells: $count2 < $count1",
            count2 < count1)
    }

    // ===================== 4. Aspect-ratio aware generation =====================

    @Test
    fun `portrait and landscape have correct relative generation ranges`() {
        // Portrait (1080x1920): aspect=0.5625, worldHalf=540, genHalf=540+864=1404
        val portrait = RenderConstants.getVisibleWorldHalf(1080f, 1920f)
        // Landscape (1920x1080): aspect=1.778, worldHalf=1707, genHalf=1707+864=2571
        val landscape = RenderConstants.getVisibleWorldHalf(1920f, 1080f)

        // Landscape generation range should be roughly proportional to aspect ratio change
        val ratio = landscape / portrait
        assertTrue("landscape/portrait ratio ($ratio) should be > 1.5",
            ratio > 1.5f)
    }

    @Test
    fun `generation range always includes parallax padding`() {
        for ((w, h) in listOf(
            720f to 1280f,
            1080f to 1920f,
            1920f to 1080f,
            2560f to 1080f,
            2208f to 1840f
        )) {
            val genHalf = RenderConstants.getVisibleWorldHalf(w, h)
            val aspect = w / h
            val viewportHalf = RenderConstants.REFERENCE_HEIGHT * aspect / 2f
            val padding = genHalf - viewportHalf

            assertTrue("padding ($padding) must be > 0 for ${w}x${h}",
                padding > 0f)
            // Parallax padding should be at least REFERENCE_HEIGHT * 0.3
            assertTrue("padding ($padding) must be >= ${RenderConstants.REFERENCE_HEIGHT * 0.3f} for ${w}x${h}",
                padding >= RenderConstants.REFERENCE_HEIGHT * 0.3f)
        }
    }
}
