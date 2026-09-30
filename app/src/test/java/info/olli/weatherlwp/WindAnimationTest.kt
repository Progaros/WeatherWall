package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test

/**
 * Sanity bounds on the wind animation constants in RenderConstants.
 */
class WindAnimationTest {

    // ============== Tree Sway Tests ==============
    
    @Test
    fun `tree sway frequency is natural speed`() {
        val frequency = RenderConstants.TREE_SWAY_FREQUENCY
        
        assertTrue("Frequency is positive", frequency > 0f)
        assertTrue("Frequency is slow enough to look natural", frequency <= 1.0f)
        assertTrue("Frequency is fast enough to be visible", frequency >= 0.3f)
    }
    
    @Test
    fun `tree sway amplitude allows visible movement`() {
        val amplitude = RenderConstants.TREE_SWAY_AMPLITUDE
        
        assertTrue("Amplitude is positive", amplitude > 0f)
        assertTrue("Amplitude not too extreme", amplitude <= 0.5f)
        assertTrue("Amplitude visible", amplitude >= 0.1f)
    }
    
    // ============== Cloud Speed Tests ==============
    
    @Test
    fun `cloud speed range is reasonable`() {
        val minSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN
        val maxSpeed = RenderConstants.WIND_CLOUD_SPEED_MAX
        
        assertTrue("Min speed positive", minSpeed > 0f)
        assertTrue("Max speed > min", maxSpeed > minSpeed)
        assertTrue("Max speed not too fast", maxSpeed <= 15f)  // Allow up to 15 for storms
        
        // Ratio should be significant for dramatic effect
        val ratio = maxSpeed / minSpeed
        assertTrue("Speed range is significant", ratio >= 10f)
    }
}
