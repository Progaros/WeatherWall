package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity bounds for the tree sway and tree scale constants in RenderConstants.
 */
class TerrainSwayTest {

    @Test
    fun `tree size scales with depth`() {
        // minScale + scaleRange * depth
        val minScale = RenderConstants.TREE_SCALE_MIN
        val range = RenderConstants.TREE_SCALE_RANGE

        val backScale = minScale + range * 0f
        val frontScale = minScale + range * 1f

        assertEquals("Back trees smaller", minScale, backScale, 0.01f)
        assertEquals("Front trees larger", minScale + range, frontScale, 0.01f)
        assertTrue("Front > back", frontScale > backScale)
    }

    @Test
    fun `sway period with default frequency`() {
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY

        // Full sway cycle = 2*PI radians / frequency
        val period = (2 * kotlin.math.PI / frequency).toFloat()

        assertTrue("Period > 5 seconds (slow natural sway)", period >= 5f)
        assertTrue("Period < 20 seconds (visible movement)", period <= 20f)
    }
}
