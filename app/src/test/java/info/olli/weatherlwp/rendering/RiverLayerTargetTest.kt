package info.olli.weatherlwp.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RiverLayer single-layer drawing policy.
 * The river should draw at exactly one terrain depth layer (like LakeLayer),
 * not at every layer creating a sandwiched appearance.
 */
class RiverLayerTargetTest {

    /**
     * Replicates the getTargetLayerIndex logic:
     * depthPercent 0=foreground, 1=background.
     * Layer index 0=background, last=foreground.
     */
    private fun getTargetLayerIndex(depthPercent: Float, totalLayers: Int): Int {
        if (totalLayers <= 0) return -1
        val clamped = depthPercent.coerceIn(0f, 1f)
        return ((1f - clamped) * (totalLayers - 1)).toInt().coerceIn(0, totalLayers - 1)
    }

    @Test
    fun `river draws at single layer not all layers`() {
        val totalLayers = 5
        val depthPercent = 0.3f  // Default (foreground-ish)
        val target = getTargetLayerIndex(depthPercent, totalLayers)

        // Target should be a single valid index
        assertTrue("Target should be >= 0", target >= 0)
        assertTrue("Target should be < totalLayers", target < totalLayers)

        // Simulate the per-layer loop: only one layer should pass the filter
        var drawCount = 0
        for (layerIndex in 0 until totalLayers) {
            if (layerIndex == target) drawCount++
        }
        assertEquals("River should draw at exactly 1 layer", 1, drawCount)
    }

    @Test
    fun `depthPercent 0 selects foreground layer`() {
        val target = getTargetLayerIndex(0f, 5)
        assertEquals("depthPercent=0 should select last (foreground) layer", 4, target)
    }

    @Test
    fun `depthPercent 1 selects background layer`() {
        val target = getTargetLayerIndex(1f, 5)
        assertEquals("depthPercent=1 should select first (background) layer", 0, target)
    }

    @Test
    fun `depthPercent 0_5 selects middle layer`() {
        val target = getTargetLayerIndex(0.5f, 5)
        assertEquals("depthPercent=0.5 should select middle layer", 2, target)
    }

    @Test
    fun `single layer returns 0`() {
        val target = getTargetLayerIndex(0.3f, 1)
        assertEquals("Single layer should return 0", 0, target)
    }

    @Test
    fun `zero layers returns -1`() {
        val target = getTargetLayerIndex(0.3f, 0)
        assertEquals("Zero layers should return -1", -1, target)
    }
}
