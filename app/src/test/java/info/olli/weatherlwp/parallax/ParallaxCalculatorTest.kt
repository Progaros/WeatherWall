package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Test

class ParallaxCalculatorTest {

    @Test
    fun testUpdateFromSensor() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        
        // Simulate continuous sensor input (velocity-based system)
        repeat(20) {
            calculator.updateFromSensor(0.5f, -0.5f)
            calculator.update(16)
        }
        
        val (x, y) = calculator.getOffset()
        
        // Should have moved from 0 towards positive X, negative Y
        assert(x > 0) { "X should be positive: $x" }
        assert(y < 0) { "Y should be negative: $y" }
    }

    @Test
    fun testCenterDrift() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 150f  // Set higher max for this test
        
        // Continuous velocity input to drive position positive
        repeat(50) {
            calculator.updateFromSensor(1.0f, 0.0f)
            calculator.update(16)
        }
        
        val xAfterMove = calculator.getOffsetX()
        assert(xAfterMove > 10f) { "X should be positive after moving: $xAfterMove" }
        
        // Wait for drift (simulate time passing without sensor input)
        // After IDLE_FRAMES_BEFORE_DRIFT (30 frames), position should start drifting to center
        // Run 100 more frames without input to test drift
        repeat(100) {
            calculator.update(16)
        }
        
        val xAfterDrift = calculator.getOffsetX()
        assert(xAfterDrift < xAfterMove) { "X should have drifted toward center: $xAfterMove -> $xAfterDrift" }
    }
    
    @Test
    fun testDepthScaling() {
        val calculator = ParallaxCalculator()
        calculator.depthSeparation = 1.0f  // Max separation for clear test
        
        // Apply velocity to move position
        repeat(20) {
            calculator.updateFromSensor(1.0f, 1.0f)
            calculator.update(16)
        }
        
        val (baseX, baseY) = calculator.getOffset()
        val (depthX, depthY) = calculator.getOffsetForDepth(0.5f)
        
        // With depthSeparation=1.0, minScale=0, so depthFactor=0.5 gives effectiveDepth=0.5
        assertEquals(baseX * 0.5f, depthX, 1f)
        assertEquals(baseY * 0.5f, depthY, 1f)
    }
}
