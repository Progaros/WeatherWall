package info.olli.weatherlwp.rendering

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TreeRenderer minimum spacing logic.
 */
class TreeSpacingTest {

    @Test
    fun `minimumTreeSpacingWorld returns positive value`() {
        val spacing = TreeRenderer.minimumTreeSpacingWorld(50, 0.3f)
        assertTrue(spacing > 0f)
    }

    @Test
    fun `minimumTreeSpacingWorld increases with sparsity`() {
        val lowSparsity = TreeRenderer.minimumTreeSpacingWorld(50, 0.1f)
        val highSparsity = TreeRenderer.minimumTreeSpacingWorld(50, 0.8f)
        assertTrue(highSparsity > lowSparsity)
    }

    @Test
    fun `minimumTreeSpacingWorld decreases with density`() {
        val lowDensity = TreeRenderer.minimumTreeSpacingWorld(10, 0.3f)
        val highDensity = TreeRenderer.minimumTreeSpacingWorld(200, 0.3f)
        assertTrue(lowDensity > highDensity)
    }

    @Test
    fun `minimumTreeSpacingWorld never goes below floor`() {
        // Even max density + zero sparsity shouldn't go below floor
        val spacing = TreeRenderer.minimumTreeSpacingWorld(200, 0.0f)
        val floor = TreeRenderer.TREE_CELL_SIZE * 0.3f
        assertTrue(spacing >= floor)
    }

    @Test
    fun `minimumTreeSpacingWorld with default values is reasonable`() {
        // With PreferenceDefaults: density=30, sparsity=38/100=0.38
        val spacing = TreeRenderer.minimumTreeSpacingWorld(30, 0.38f)
        // Should be less than cell size but meaningful
        assertTrue(spacing > TreeRenderer.TREE_CELL_SIZE * 0.3f)
        assertTrue(spacing < TreeRenderer.TREE_CELL_SIZE * 2f)
    }
}
