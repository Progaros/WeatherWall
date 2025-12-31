package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ParallaxCalculator.
 * Tests position-based tilt following system.
 */
class ParallaxCalculatorTest {

    @Test
    fun testUpdateFromSensor() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        
        // Simulate tilt input (position-based system)
        calculator.updateFromSensor(0.5f, -0.5f)
        repeat(100) {
            calculator.update(16)
        }
        
        val (x, y) = calculator.getOffset()
        
        // Should have moved towards positive X, negative Y
        assert(x > 0) { "X should be positive: $x" }
        assert(y < 0) { "Y should be negative: $y" }
    }

    @Test
    fun testPositionFollowsTilt() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        
        // Set tilt and let it converge
        calculator.updateFromSensor(1.0f, 0.0f)
        repeat(100) {
            calculator.update(16)
        }
        
        val xAfterTilt = calculator.getOffsetX()
        assert(xAfterTilt > 50f) { "X should be near max after tilt: $xAfterTilt" }
        
        // Position should stay stable when tilt is maintained
        repeat(50) {
            calculator.update(16)
        }
        
        val xStable = calculator.getOffsetX()
        assertEquals(xAfterTilt, xStable, 1f)
    }
    
    @Test
    fun testDepthScaling() {
        val calculator = ParallaxCalculator()
        calculator.depthSeparation = 1.0f  // Max separation for clear test
        calculator.maxOffsetPx = 100f
        
        // Apply tilt to move position
        calculator.updateFromSensor(1.0f, 1.0f)
        repeat(100) {
            calculator.update(16)
        }
        
        val (baseX, baseY) = calculator.getOffset()
        val (depthX, depthY) = calculator.getOffsetForDepth(0.5f)
        
        // With depthSeparation=1.0, depthFactor=0.5 gives effectiveDepth=0.5
        assertEquals(baseX * 0.5f, depthX, 1f)
        assertEquals(baseY * 0.5f, depthY, 1f)
    }
}
