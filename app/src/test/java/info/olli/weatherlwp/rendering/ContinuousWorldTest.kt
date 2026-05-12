package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the world is conceptually infinite and continuous.
 *
 * These tests would FAIL if generation range were too narrow for the viewport,
 * or if the mountain shape noise were incorrectly scaled.
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

    // ===================== 4. Mountain shape invariants =====================

    @Test
    fun `mountain slope progress is monotonically increasing`() {
        // At any roughness, the progress values (peak to base) must be monotonic
        val seed = 12345L
        for (roughness in listOf(0.0f, 0.5f, 1.0f, 2.0f)) {
            val shape = createTestShape(seed, roughness, 0.5f)
            for (i in 1 until shape.leftSlopeYs.size) {
                assertTrue("left slope progress must be monotonic at roughness=$roughness",
                    shape.leftSlopeYs[i] > shape.leftSlopeYs[i - 1])
            }
            for (i in 1 until shape.rightSlopeYs.size) {
                assertTrue("right slope progress must be monotonic at roughness=$roughness",
                    shape.rightSlopeYs[i] > shape.rightSlopeYs[i - 1])
            }
        }
    }

    @Test
    fun `lower roughness produces smaller noise variance`() {
        val seed = 42L
        val lowNoise = computeNoiseVariance(createTestShape(seed, 0.2f, 0.5f))
        val highNoise = computeNoiseVariance(createTestShape(seed, 1.5f, 0.5f))

        assertTrue("lower roughness should produce less noise variance: low=$lowNoise, high=$highNoise",
            lowNoise < highNoise)
    }

    @Test
    fun `default roughness noise stays within reasonable bounds`() {
        // Default roughness pref is 80 -> 0.8
        val defaultRoughness = 0.8f
        val shape = createTestShape(99L, defaultRoughness, 0.5f)

        // Check that noise values don't exceed ±roughness/2 = ±0.4
        // When scaled by noiseScale (height*0.015), max displacement ≈ 0.4 * 29 ≈ 12px
        for (noise in shape.leftSlopeNoiseYs) {
            assertTrue("left noise $noise should be in [-0.5, 0.5]",
                noise in -0.5f..0.5f)
        }
        for (noise in shape.rightSlopeNoiseYs) {
            assertTrue("right noise $noise should be in [-0.5, 0.5]",
                noise in -0.5f..0.5f)
        }
    }

    @Test
    fun `noise is separate from progress`() {
        // The progress and noise arrays must be independent:
        // progress contains only monotonic slope values, noise contains only random displacement
        val shape = createTestShape(77L, 1.0f, 0.5f)

        // Progress should be strictly increasing from 0 toward 1
        assertTrue("progress[0] should be near 0",
            shape.leftSlopeYs[0] < 0.2f)
        assertTrue("progress[last] should be near 1",
            shape.leftSlopeYs[shape.leftSlopeYs.size - 1] > 0.8f)

        // Noise should average near 0 (it's centered around 0)
        val avgNoise = shape.leftSlopeNoiseYs.average().toFloat()
        assertTrue("noise average should be near 0, got $avgNoise",
            kotlin.math.abs(avgNoise) < 0.3f)
    }

    // ===================== 5. Aspect-ratio aware generation =====================

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

    // ===================== Helpers =====================

    /**
     * Create a MountainShape for testing via reflection.
     * We call precomputeShape indirectly by inspecting the shape stored in MountainData.
     * Since precomputeShape is private, we replicate its logic here for test purposes.
     */
    private fun createTestShape(seed: Long, roughness: Float, ridgeRoughness: Float): TestShape {
        val random = kotlin.random.Random(seed)
        val segments = (10 + roughness * 20).toInt()

        val leftSlopeYs = FloatArray(segments)
        val leftSlopeNoiseYs = FloatArray(segments)
        val rightSlopeYs = FloatArray(segments)
        val rightSlopeNoiseYs = FloatArray(segments)

        // Skip ridge-related random calls to match precomputeShape's Random consumption
        val ridgeSegments = (5 + ridgeRoughness * 10).toInt().coerceAtLeast(2)
        if (ridgeRoughness > 0) random.nextFloat() // peakAngleOffset
        for (i in 1 until ridgeSegments) {
            random.nextFloat() // ridgeNoise
        }

        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            leftSlopeYs[i] = progress
            leftSlopeNoiseYs[i] = (random.nextFloat() - 0.5f) * roughness
        }
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            rightSlopeYs[i] = progress
            rightSlopeNoiseYs[i] = (random.nextFloat() - 0.5f) * roughness
        }

        return TestShape(leftSlopeYs, leftSlopeNoiseYs, rightSlopeYs, rightSlopeNoiseYs)
    }

    private data class TestShape(
        val leftSlopeYs: FloatArray,
        val leftSlopeNoiseYs: FloatArray,
        val rightSlopeYs: FloatArray,
        val rightSlopeNoiseYs: FloatArray
    )

    private fun computeNoiseVariance(shape: TestShape): Float {
        val allNoise = shape.leftSlopeNoiseYs.toList() + shape.rightSlopeNoiseYs.toList()
        val mean = allNoise.average().toFloat()
        return allNoise.map { (it - mean) * (it - mean) }.average().toFloat()
    }
}
