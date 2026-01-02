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
        calculator.setCanvasSize(1080f, 1920f)  // Reference height for scale=1.0
        
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
        calculator.setCanvasSize(1080f, 1920f)  // Reference height for scale=1.0
        
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
        calculator.setCanvasSize(1080f, 1920f)  // Reference height for scale=1.0
        
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
    
    @Test
    fun testCanvasSizeScaling() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        
        // Test with half-height canvas (like preview)
        calculator.setCanvasSize(540f, 960f)  // Half of reference
        calculator.updateFromSensor(1.0f, 0.0f)
        repeat(100) {
            calculator.update(16)
        }
        val offsetAtHalfHeight = calculator.getOffsetX()
        
        // Reset
        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPx = 100f
        
        // Test with full-height canvas
        calculator2.setCanvasSize(1080f, 1920f)  // Reference height
        calculator2.updateFromSensor(1.0f, 0.0f)
        repeat(100) {
            calculator2.update(16)
        }
        val offsetAtFullHeight = calculator2.getOffsetX()
        
        // Half-height should have half the offset (proportionally)
        assertEquals(offsetAtFullHeight * 0.5f, offsetAtHalfHeight, 5f)
    }
    
    @Test
    fun testInvertX() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.invertX = false
        
        // Without inversion
        calculator.updateFromSensor(1.0f, 0.0f)
        repeat(100) { calculator.update(16) }
        val xNormal = calculator.getOffsetX()
        
        // With inversion
        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.invertX = true
        
        calculator2.updateFromSensor(1.0f, 0.0f)
        repeat(100) { calculator2.update(16) }
        val xInverted = calculator2.getOffsetX()
        
        // Inverted should be opposite sign
        assert(xNormal > 0) { "Normal X should be positive: $xNormal" }
        assert(xInverted < 0) { "Inverted X should be negative: $xInverted" }
        assertEquals(xNormal, -xInverted, 1f)
    }
    
    @Test
    fun testInvertY() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.invertY = false
        
        // Without inversion
        calculator.updateFromSensor(0.0f, 1.0f)
        repeat(100) { calculator.update(16) }
        val yNormal = calculator.getOffsetY()
        
        // With inversion
        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.invertY = true
        
        calculator2.updateFromSensor(0.0f, 1.0f)
        repeat(100) { calculator2.update(16) }
        val yInverted = calculator2.getOffsetY()
        
        // Inverted should be opposite sign
        assert(yNormal > 0) { "Normal Y should be positive: $yNormal" }
        assert(yInverted < 0) { "Inverted Y should be negative: $yInverted" }
        assertEquals(yNormal, -yInverted, 1f)
    }
    
    @Test
    fun testAspectRatioCompensation() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.aspectRatioCompensation = 1.0f  // 1:1 ratio
        
        // With 1:1 aspect ratio
        calculator.updateFromSensor(1.0f, 1.0f)
        repeat(100) { calculator.update(16) }
        val xNormal = calculator.getOffsetX()
        val yNormal = calculator.getOffsetY()
        
        // X and Y should have similar magnitudes at 1:1
        assertEquals(kotlin.math.abs(xNormal), kotlin.math.abs(yNormal), 5f)
        
        // With 1.5x horizontal aspect ratio
        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.aspectRatioCompensation = 1.5f  // 50% more horizontal
        
        calculator2.updateFromSensor(1.0f, 1.0f)
        repeat(100) { calculator2.update(16) }
        val xScaled = calculator2.getOffsetX()
        val yScaled = calculator2.getOffsetY()
        
        // X should be ~1.5x of Y
        assertEquals(kotlin.math.abs(yScaled) * 1.5f, kotlin.math.abs(xScaled), 5f)
    }
}
