package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verifying mountain density scaling and depth distribution.
 */
class MountainDensityTest {

    // ===================== Cell size produces expected slot count =====================

    @Test
    fun `cell size 110 produces about 36 slots for reference screen`() {
        // genWorldRange for 1080px screen ≈ 3888
        val genWorldRange = RenderConstants.getGenerationWorldRange(1080f)
        val halfWorld = genWorldRange / 2f
        val indexRange = WorldViewportTransform.indexRangeForWorldRange(
            -halfWorld, halfWorld, MountainLayer.MOUNTAIN_CELL_SIZE
        )
        val slotCount = indexRange.count()
        // With halved cell size (110), expect ~35 slots for standard screen
        assertTrue("Expected ~35 slots, got $slotCount", slotCount in 30..45)
    }

    @Test
    fun `density 50 produces approximately half the slots`() {
        val genWorldRange = RenderConstants.getGenerationWorldRange(1080f)
        val halfWorld = genWorldRange / 2f
        val indexRange = WorldViewportTransform.indexRangeForWorldRange(
            -halfWorld, halfWorld, MountainLayer.MOUNTAIN_CELL_SIZE
        )
        val totalSlots = indexRange.count()
        val densityFraction = 50 / 100f

        // Count how many survive density gating (deterministic)
        var survivors = 0
        for (index in indexRange) {
            val r = DeterministicLayout.random(42, section = 100, index = index)
            if (r.nextFloat() <= densityFraction) survivors++
        }

        // With 50% density and ~35 slots, expect ~17 mountains (old behavior at density 100)
        assertTrue("Expected ~17 survivors at density 50, got $survivors", survivors in 12..25)
    }

    // ===================== Golden-ratio depth scatter =====================

    @Test
    fun `golden ratio scatter produces well-distributed depths`() {
        // Simulate depth assignment for 20 consecutive indices
        val depths = (-10..10).map { index ->
            val baseDepth = ((index.toFloat() * MountainLayer.PHI) % 1f)
                .let { if (it < 0f) it + 1f else it }
            baseDepth
        }

        // Check that depths span the full 0-1 range reasonably
        val sorted = depths.sorted()
        val minDepth = sorted.first()
        val maxDepth = sorted.last()
        assertTrue("Depths should span near 0, got min=$minDepth", minDepth < 0.15f)
        assertTrue("Depths should span near 1, got max=$maxDepth", maxDepth > 0.85f)

        // Check that max gap between consecutive sorted depths is small
        val maxGap = sorted.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue("Max gap should be < 0.15 for even spread, got $maxGap", maxGap < 0.15f)
    }

    @Test
    fun `golden ratio scatter is deterministic`() {
        val depths1 = (0..5).map { index ->
            ((index.toFloat() * MountainLayer.PHI) % 1f)
                .let { if (it < 0f) it + 1f else it }
        }
        val depths2 = (0..5).map { index ->
            ((index.toFloat() * MountainLayer.PHI) % 1f)
                .let { if (it < 0f) it + 1f else it }
        }
        assertEquals(depths1, depths2)
    }

    @Test
    fun `sparse subset still has depth variety`() {
        // Simulate density gating: only keep every 3rd index (like low density)
        val sparseIndices = (-10..10).filter { it % 3 == 0 }
        val depths = sparseIndices.map { index ->
            val baseDepth = ((index.toFloat() * MountainLayer.PHI) % 1f)
                .let { if (it < 0f) it + 1f else it }
            baseDepth
        }

        val sorted = depths.sorted()
        val range = sorted.last() - sorted.first()
        assertTrue("Sparse subset should still span >0.5 range, got $range", range > 0.5f)
    }

    // ===================== Default changed =====================

    @Test
    fun `mountain cell size is 110`() {
        assertEquals(110f, MountainLayer.MOUNTAIN_CELL_SIZE, 0.001f)
    }
}
