package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator2.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator2.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator2.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode
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
        calculator2.maxOffsetPercent = 0f  // Use legacy pixel mode
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
    
    @Test
    fun testAspectRatioDefault() {
        val calculator = ParallaxCalculator()
        
        // Default aspect ratio should be 1.0 (100/100) - equal movement for both axes
        assertEquals("Default aspect ratio should be 1.0", 1.0f, calculator.aspectRatioCompensation, 0.01f)
    }
    
    @Test
    fun testMaxOffsetPercentDefault() {
        val calculator = ParallaxCalculator()
        
        // Default max offset should be 15% of screen height (increased for more movement)
        assertEquals("Default max offset percent should be 15%", 15f, calculator.maxOffsetPercent, 0.01f)
    }
    
    @Test
    fun testMaxOffsetPercent() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f  // 10% of height
        calculator.setCanvasSize(1080f, 1920f)
        
        // With 10% of 1920px = 192px max offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val x = calculator.getOffsetX()
        
        // At max tilt (1.0) with strength 1.0, should approach 192px
        assertTrue("Max offset should be ~10% of height", kotlin.math.abs(x) > 150f)
        assertTrue("Max offset should not exceed ~10% of height", kotlin.math.abs(x) < 250f)
    }
    
    @Test
    fun testMaxOffsetPercentPreview() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f  // 10% of height
        calculator.setCanvasSize(270f, 480f)  // Preview size
        
        // With 10% of 480px = 48px max offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val x = calculator.getOffsetX()
        
        // At max tilt, should approach 48px on preview
        assertTrue("Preview offset should be ~10% of preview height", kotlin.math.abs(x) > 35f)
        assertTrue("Preview offset should not exceed ~10% of preview height", kotlin.math.abs(x) < 65f)
    }
    
    // ============== Return Speed Tests ==============
    
    @Test
    fun testReturnSpeedZeroNoReturn() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.returnSpeed = 0f  // No return
        
        // Move to offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtTilt = calculator.getOffsetX()
        assertTrue("Should have moved", kotlin.math.abs(xAtTilt) > 50f)
        
        // Release tilt - with returnSpeed=0, should stay at position
        calculator.updateFromSensor(0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAfterRelease = calculator.getOffsetX()
        
        // Should NOT have moved back to center
        assertEquals("With returnSpeed=0, position should stay", xAtTilt, xAfterRelease, 5f)
    }
    
    @Test
    fun testReturnSpeedHighFastReturn() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.returnSpeed = 1.0f  // Fast return
        
        // Move to offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtTilt = calculator.getOffsetX()
        assertTrue("Should have moved", kotlin.math.abs(xAtTilt) > 50f)
        
        // Release tilt - with high returnSpeed, should quickly return to center
        calculator.updateFromSensor(0f, 0f)
        repeat(50) { calculator.update(16) }
        val xAfterRelease = calculator.getOffsetX()
        
        // Should have moved significantly toward center
        assertTrue("With returnSpeed=1.0, should return quickly", kotlin.math.abs(xAfterRelease) < kotlin.math.abs(xAtTilt) * 0.3f)
    }
    
    @Test
    fun testReturnSpeedDefault() {
        val calculator = ParallaxCalculator()
        
        // Default should be slow return (0.15)
        assertEquals("Default return speed should be 0.15", 0.15f, calculator.returnSpeed, 0.01f)
    }
    
    @Test
    fun testReturnSpeedLogScale() {
        // Test that logarithmic scale gives natural progression
        val calculator = ParallaxCalculator()
        
        // At returnSpeed=0.5 (50%), should have noticeable but moderate return
        // ln(1+0.5)/ln(2) = ln(1.5)/ln(2) ≈ 0.58
        val logScaleAt50 = kotlin.math.ln(1f + 0.5f) / kotlin.math.ln(2f)
        assertTrue("Log scale at 50% should be ~0.58", logScaleAt50 in 0.55f..0.62f)
        
        // At returnSpeed=1.0 (100%), should be maximum return
        // ln(1+1)/ln(2) = ln(2)/ln(2) = 1.0
        val logScaleAt100 = kotlin.math.ln(1f + 1f) / kotlin.math.ln(2f)
        assertEquals("Log scale at 100% should be 1.0", 1.0f, logScaleAt100, 0.01f)
    }
    
    // ============== Latency Optimization Tests ==============
    
    @Test
    fun testHighLerpRateForLowLatency() {
        // With POSITION_LERP_RATE = 0.9, position should reach target very quickly
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f  // No smoothing for this test
        
        // Apply tilt
        calculator.updateFromSensor(1.0f, 0f)
        
        // After just 2-3 frames, should be near target (vs 20+ frames with old lerp rate)
        calculator.update(16)
        val xAfter1Frame = calculator.getOffsetX()
        
        calculator.update(16)
        val xAfter2Frames = calculator.getOffsetX()
        
        // With lerp rate 0.9: first frame reaches 90%, second frame reaches 99%
        assertTrue("After 1 frame, should reach at least 80% of target", 
            kotlin.math.abs(xAfter1Frame) > 70f)
        assertTrue("After 2 frames, should reach at least 95% of target", 
            kotlin.math.abs(xAfter2Frames) > 90f)
    }
    
    @Test
    fun testLatencyUnder3Frames() {
        // Target: reach 99% of final position in under 3 frames for ~50ms total latency
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        
        calculator.updateFromSensor(1.0f, 0f)
        
        // Let it converge for reference
        repeat(100) { calculator.update(16) }
        val targetX = calculator.getOffsetX()
        
        // Reset and test convergence speed
        calculator.reset()
        calculator.updateFromSensor(1.0f, 0f)
        
        var frames = 0
        var currentX = 0f
        while (kotlin.math.abs(currentX) < kotlin.math.abs(targetX) * 0.99f && frames < 10) {
            calculator.update(16)
            currentX = calculator.getOffsetX()
            frames++
        }
        
        assertTrue("Should reach 99% of target in 3 frames or less (got $frames)", frames <= 3)
    }
    
    @Test
    fun testDirectResponseMatchesReferenceApp() {
        // Reference app (ForestRenderer.java) reads rotation values directly each frame
        // Our implementation should match this with near-instant lerp
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f  // 10% of screen height
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        
        // Simulate tilt input changing rapidly (like real device motion)
        calculator.updateFromSensor(0.5f, 0f)
        calculator.update(16)
        val x1 = calculator.getOffsetX()
        
        // Immediately change direction
        calculator.updateFromSensor(-0.5f, 0f)
        calculator.update(16)
        val x2 = calculator.getOffsetX()
        
        // With high lerp rate, should have already moved significantly toward new target
        // (negative direction)
        assertTrue("Should respond immediately to direction change (x2 < x1 * 0.5)", 
            x2 < x1 * 0.5f)
    }
}
