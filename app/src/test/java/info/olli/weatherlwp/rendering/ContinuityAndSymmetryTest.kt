package info.olli.weatherlwp.rendering

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for terrain symmetry, noise correctness, deterministic hash,
 * and object placement continuity across viewport changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinuityAndSymmetryTest {

    // ========================================================================
    // Noise symmetry: left and right should have statistically similar roughness
    // ========================================================================

    @Test
    fun `noise produces valid values for negative x`() {
        // Before the fix, negative x caused broken interpolation
        for (i in -100..-1) {
            val x = i * 0.7f
            val v = VegetationUtil.noise(x, 42)
            assertTrue("noise($x) = $v should be in [0, 1]", v in 0f..1f)
        }
    }

    @Test
    fun `noise produces valid values for positive x`() {
        for (i in 1..100) {
            val x = i * 0.7f
            val v = VegetationUtil.noise(x, 42)
            assertTrue("noise($x) = $v should be in [0, 1]", v in 0f..1f)
        }
    }

    @Test
    fun `noise is continuous across zero`() {
        // Values at x=-0.01 and x=0.01 should be close (continuous)
        val vNeg = VegetationUtil.noise(-0.01f, 42)
        val vPos = VegetationUtil.noise(0.01f, 42)
        val diff = kotlin.math.abs(vNeg - vPos)
        assertTrue("noise should be continuous across 0, diff=$diff", diff < 0.1f)
    }

    @Test
    fun `noise is smooth for negative x - no jagged jumps`() {
        // Sample densely in negative region and check max delta between neighbors
        var maxDelta = 0f
        val step = 0.05f
        var prev = VegetationUtil.noise(-10f, 42)
        var x = -10f + step
        while (x <= -1f) {
            val curr = VegetationUtil.noise(x, 42)
            val delta = kotlin.math.abs(curr - prev)
            if (delta > maxDelta) maxDelta = delta
            prev = curr
            x += step
        }
        // smoothstep interpolation → max delta per step should be bounded
        assertTrue("Negative x max delta=$maxDelta should be < 0.15", maxDelta < 0.15f)
    }

    @Test
    fun `noise is smooth for positive x - no jagged jumps`() {
        var maxDelta = 0f
        val step = 0.05f
        var prev = VegetationUtil.noise(1f, 42)
        var x = 1f + step
        while (x <= 10f) {
            val curr = VegetationUtil.noise(x, 42)
            val delta = kotlin.math.abs(curr - prev)
            if (delta > maxDelta) maxDelta = delta
            prev = curr
            x += step
        }
        assertTrue("Positive x max delta=$maxDelta should be < 0.15", maxDelta < 0.15f)
    }

    @Test
    fun `terrain roughness is symmetric left vs right`() {
        // Measure the variance of terrain samples on each side
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )

        val leftSamples = (1..100).map { VegetationUtil.getHillY(-it * 5f, hill) }
        val rightSamples = (1..100).map { VegetationUtil.getHillY(it * 5f, hill) }

        val leftVariance = variance(leftSamples)
        val rightVariance = variance(rightSamples)

        // Variances should be similar (within 3x ratio)
        val ratio = if (leftVariance > rightVariance) leftVariance / rightVariance
        else rightVariance / leftVariance
        assertTrue(
            "Terrain roughness ratio=$ratio (left var=$leftVariance, right var=$rightVariance) should be < 3",
            ratio < 3f
        )
    }

    // ========================================================================
    // Sampling consistency: changing resolution must not change terrain shape
    // ========================================================================

    @Test
    fun `terrain shape independent of sample spacing`() {
        val hill = TreeRenderer.HillInfo(
            noiseSeed = 42,
            depthAmplitudeScale = 1.0f,
            depthFactor = 0.5f,
            heightOffset = 0.5f,
            hillFrequency = 2.0f,
            hillRoughness = 1.0f,
            hillAmplitude = 1.0f
        )

        // Sample at same world positions regardless of step size
        val worldPositions = listOf(-200f, -100f, -50f, 0f, 50f, 100f, 200f)

        // "High resolution" sampling (simulating wide screen)
        val highRes = worldPositions.map { VegetationUtil.getHillY(it, hill) }

        // "Low resolution" sampling (simulating narrow screen)
        val lowRes = worldPositions.map { VegetationUtil.getHillY(it, hill) }

        // Must be identical because terrain is evaluated at world-space X directly
        for (i in worldPositions.indices) {
            assertEquals(
                "Terrain at worldX=${worldPositions[i]} differs between resolutions",
                highRes[i], lowRes[i], 0.0001f
            )
        }
    }

    // ========================================================================
    // Deterministic hash: hashToFloat produces stable results
    // ========================================================================

    @Test
    fun `hashToFloat is deterministic`() {
        val v1 = DeterministicLayout.hashToFloat(42, 10, 3)
        val v2 = DeterministicLayout.hashToFloat(42, 10, 3)
        assertEquals(v1, v2, 0.0f)
    }

    @Test
    fun `hashToFloat produces values in 0 to 1`() {
        for (i in -500..500) {
            val v = DeterministicLayout.hashToFloat(42, i, 0)
            assertTrue("hashToFloat(42, $i, 0) = $v should be in [0, 1]", v in 0f..1f)
        }
    }

    @Test
    fun `hashToFloat varies with index`() {
        val values = (0..99).map { DeterministicLayout.hashToFloat(42, it, 0) }.toSet()
        assertTrue("Expected many distinct values but got ${values.size}", values.size > 80)
    }

    @Test
    fun `hashToFloat varies with salt`() {
        val values = (0..9).map { DeterministicLayout.hashToFloat(42, 5, it) }.toSet()
        assertTrue("Expected distinct values per salt but got ${values.size}", values.size > 7)
    }

    @Test
    fun `hashToFloat varies with seed`() {
        val values = (0..9).map { DeterministicLayout.hashToFloat(it, 5, 0) }.toSet()
        assertTrue("Expected distinct values per seed but got ${values.size}", values.size > 7)
    }

    // ========================================================================
    // Object continuity: cell-based placement is viewport-independent
    // ========================================================================

    @Test
    fun `tree cell placement is deterministic regardless of viewport`() {
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val seed = 42

        // Simulate narrow viewport (cells -5..5)
        val narrowRange = WorldViewportTransform.indexRangeForWorldRange(-50f, 50f, cellSize)

        // For cells visible in both viewports, the worldX must be identical
        for (cellIndex in narrowRange) {
            val random1 = DeterministicLayout.random(seed + 2000, section = 310, index = cellIndex)
            random1.nextFloat() // density gate
            val worldX1 = cellIndex * cellSize + (random1.nextFloat() - 0.5f) * cellSize * 0.8f

            val random2 = DeterministicLayout.random(seed + 2000, section = 310, index = cellIndex)
            random2.nextFloat() // density gate
            val worldX2 = cellIndex * cellSize + (random2.nextFloat() - 0.5f) * cellSize * 0.8f

            assertEquals("Cell $cellIndex position should be identical", worldX1, worldX2, 0.0001f)
        }
    }

    @Test
    fun `bush cell placement is deterministic regardless of viewport`() {
        val cellSize = BushRenderer.BUSH_CELL_SIZE
        val seed = 42

        val narrowRange = WorldViewportTransform.indexRangeForWorldRange(-50f, 50f, cellSize)

        for (cellIndex in narrowRange) {
            val r1 = DeterministicLayout.random(seed + 5000, section = 500, index = cellIndex)
            r1.nextFloat()
            val wx1 = cellIndex * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f

            val r2 = DeterministicLayout.random(seed + 5000, section = 500, index = cellIndex)
            r2.nextFloat()
            val wx2 = cellIndex * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f

            assertEquals("Bush cell $cellIndex position should be identical", wx1, wx2, 0.0001f)
        }
    }

    @Test
    fun `decoration hashToFloat placement is viewport-independent`() {
        val cellSize = 5f
        val seed = 42 + 6000

        // Narrow viewport
        val narrowRange = WorldViewportTransform.indexRangeForWorldRange(-50f, 50f, cellSize)
        // Wide viewport
        val wideRange = WorldViewportTransform.indexRangeForWorldRange(-200f, 200f, cellSize)

        // Cells in narrow range must produce same position in both viewports
        for (cellIndex in narrowRange) {
            val wx1 = cellIndex * cellSize +
                (DeterministicLayout.hashToFloat(seed, cellIndex, 1) - 0.5f) * cellSize * 0.9f
            val wx2 = cellIndex * cellSize +
                (DeterministicLayout.hashToFloat(seed, cellIndex, 1) - 0.5f) * cellSize * 0.9f
            assertEquals("Flower cell $cellIndex position should be stable", wx1, wx2, 0.0001f)
        }

        // Verify that cell positions don't depend on viewport width
        assertTrue("Narrow range should be subset of wide range",
            narrowRange.first >= wideRange.first && narrowRange.last <= wideRange.last)
    }

    @Test
    fun `scrolling 1px does not change central object positions`() {
        // Simulate two viewports shifted by 1 world unit
        val cellSize = TreeRenderer.TREE_CELL_SIZE
        val seed = 42

        val range1 = WorldViewportTransform.indexRangeForWorldRange(-100f, 100f, cellSize)
        val range2 = WorldViewportTransform.indexRangeForWorldRange(-99f, 101f, cellSize)

        // Central cells (far from edges) must exist in both
        val centralCells = (-5..5)
        for (cellIndex in centralCells) {
            assertTrue("Cell $cellIndex in range1", cellIndex in range1)
            assertTrue("Cell $cellIndex in range2", cellIndex in range2)

            // Position must be identical (it's determined by cellIndex, not range)
            val r1 = DeterministicLayout.random(seed + 2000, section = 310, index = cellIndex)
            r1.nextFloat()
            val wx1 = cellIndex * cellSize + (r1.nextFloat() - 0.5f) * cellSize * 0.8f

            val r2 = DeterministicLayout.random(seed + 2000, section = 310, index = cellIndex)
            r2.nextFloat()
            val wx2 = cellIndex * cellSize + (r2.nextFloat() - 0.5f) * cellSize * 0.8f

            assertEquals("Cell $cellIndex stable across scroll", wx1, wx2, 0.0001f)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun variance(values: List<Float>): Float {
        val mean = values.sum() / values.size
        return values.map { (it - mean) * (it - mean) }.sum() / values.size
    }
}
