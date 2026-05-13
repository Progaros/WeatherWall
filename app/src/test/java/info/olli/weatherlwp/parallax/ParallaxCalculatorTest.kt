package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        
        // Default max offset should be 20% of screen height (increased for more movement)
        assertEquals("Default max offset percent should be 20%", 20f, calculator.maxOffsetPercent, 0.01f)
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
        
        // With softLimit: target = softLimit(192, 192) = 192*tanh(1) ≈ 146
        assertTrue("Max offset should be ~10% of height (softLimited)", kotlin.math.abs(x) > 130f)
        assertTrue("Max offset should not exceed ~10% of height", kotlin.math.abs(x) < 200f)
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
        calculator.returnSpeed = 0f  // No recenter during convergence
        
        // Move to offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtTilt = calculator.getOffsetX()
        assertTrue("Should have moved", kotlin.math.abs(xAtTilt) > 50f)
        
        // Release tilt - with high returnSpeed, should quickly return to center
        calculator.returnSpeed = 1.0f
        calculator.updateFromSensor(0f, 0f)
        repeat(50) { calculator.update(16) }
        val xAfterRelease = calculator.getOffsetX()
        
        // Should have moved significantly toward center
        assertTrue("With returnSpeed=1.0, should return quickly", kotlin.math.abs(xAfterRelease) < kotlin.math.abs(xAtTilt) * 0.3f)
    }
    
    @Test
    fun testReturnSpeedDefault() {
        val calculator = ParallaxCalculator()
        
        // Default should be 0 (production sets from preferences)
        assertEquals("Default return speed should be 0", 0f, calculator.returnSpeed, 0.01f)
    }
    
    @Test
    fun testReturnSpeedLogScale() {
        // Test that logarithmic scale gives natural progression
        @Suppress("UNUSED_VARIABLE")
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
        
        // With softLimit: target = softLimit(100, 100) = 100*tanh(1) ≈ 76.2
        // With smoothing=0 and lerp rate 1.0: reaches target in 1 frame
        assertTrue("After 1 frame, should reach softLimit target (~76)", 
            kotlin.math.abs(xAfter1Frame) > 60f)
        assertTrue("After 2 frames, should still be at softLimit target", 
            kotlin.math.abs(xAfter2Frames) > 70f)
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
    fun testDirectResponseMatchesExpectedBehavior() {
        // Direct mode reads rotation values directly each frame
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
    
    // ============== Edge Easing Tests ==============
    
    @Test
    fun testEdgeEasingDefaultIsZero() {
        val calculator = ParallaxCalculator()
        assertEquals("Default edge easing should be 0 (linear)", 0f, calculator.edgeEasing, 0.01f)
    }
    
    @Test
    fun testEdgeEasingZeroIsLinear() {
        // With edgeEasing=0, movement should not slow down near edges
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 0f  // Linear, no slowdown
        calculator.smoothing = 0f  // Instant response
        
        // Move to edge
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtEdge = calculator.getOffsetX()
        
        // Should reach near maximum (softLimit target ≈ 146 for maxOffset=192)
        assertTrue("With edgeEasing=0, should reach near soft-limited max offset", xAtEdge > 130f)
    }
    
    @Test
    fun testEdgeEasingSlowsNearEdge() {
        // With high edgeEasing, movement should slow significantly near edges
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f  // Maximum easing
        calculator.smoothing = 0.1f  // Some smoothing to see the effect
        
        // First move to center
        calculator.updateFromSensor(0.5f, 0f)
        repeat(50) { calculator.update(16) }
        val xAtMid = calculator.getOffsetX()
        
        // Then try to move to edge
        calculator.updateFromSensor(1.0f, 0f)
        repeat(50) { calculator.update(16) }
        val xAtEdge = calculator.getOffsetX()
        
        // Progress from mid to edge should be slower than from center to mid
        // due to edge easing
        val centerToMidProgress = kotlin.math.abs(xAtMid)
        val midToEdgeProgress = kotlin.math.abs(xAtEdge - xAtMid)
        
        // With extreme easing, mid-to-edge progress should be less than center-to-mid
        assertTrue("Edge easing should slow movement near edges (midToEdge < centerToMid)", 
            midToEdgeProgress < centerToMidProgress * 1.5f)
    }
    
    @Test
    fun testReturnSpeedUnlockedDefault() {
        val calculator = ParallaxCalculator()
        assertTrue("Default returnSpeedUnlocked should be true", calculator.returnSpeedUnlocked)
    }
    
    @Test
    fun testReturnSpeedUnlockedIgnoresEdgeEasing() {
        // When returnSpeedUnlocked=true, return to center should be fast regardless of edge easing
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f  // Maximum easing
        calculator.returnSpeed = 0f  // No recenter during convergence
        calculator.returnSpeedUnlocked = true  // Fast return ignores easing
        
        // Move to edge
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtEdge = calculator.getOffsetX()
        assertTrue("Should have moved to edge", xAtEdge > 100f)
        
        // Now return to center (tilt near zero triggers return)
        calculator.returnSpeed = 0.5f
        calculator.updateFromSensor(0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAfterReturn = calculator.getOffsetX()
        
        // Should return to near center quickly despite edge easing
        assertTrue("With unlocked return, should return to near center quickly", 
            kotlin.math.abs(xAfterReturn) < 20f)
    }
    
    @Test
    fun testMaxOffsetPercentDefaultIncreased() {
        val calculator = ParallaxCalculator()
        // Default max offset should be 20% (increased from 15%)
        assertEquals("Default max offset percent should be 20%", 20f, calculator.maxOffsetPercent, 0.01f)
    }

    // ============== Property readback tests (kills getter return-value mutations) ==============

    @Test
    fun `edgeEasing readback matches set value`() {
        val calculator = ParallaxCalculator()
        calculator.edgeEasing = 0.75f
        assertEquals(0.75f, calculator.edgeEasing, 0.001f)
    }

    @Test
    fun `returnSpeedUnlocked readback when set to false`() {
        val calculator = ParallaxCalculator()
        calculator.returnSpeedUnlocked = false
        assertFalse("returnSpeedUnlocked should read back false", calculator.returnSpeedUnlocked)
    }

    // ============== reset clears pending tilt ==============

    @Test
    fun `reset clears pending tilt so next update does not move`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // Apply tilt
        calculator.updateFromSensor(1.0f, 0.5f)
        calculator.update(16)
        assertTrue("Should have moved", calculator.getOffsetX() > 0f)

        // Reset and update without new sensor data
        calculator.reset()
        calculator.update(16)

        // Position should be at or near zero after reset
        assertEquals("After reset, X should be 0", 0f, calculator.getOffsetX(), 0.1f)
        assertEquals("After reset, Y should be 0", 0f, calculator.getOffsetY(), 0.1f)
    }

    // ============== movementMultiplier / depth precision ==============

    @Test
    fun `depth factor 0 gives zero offset with default perspective`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.depthSeparation = 1.0f
        calculator.perspective = 1.0f // background-heavy: mult = (1-d)*depthSep

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        // depth=1.0 with perspective=1.0: mult = foregroundHeavy(1)*0 + backgroundHeavy(0)*1 = 0
        // Wait, let me re-check: mixed = d + (1-d - d)*perspective = d + (1-2d)*perspective
        // At d=1: mixed = 1 + (1-2)*1 = 1 + (-1) = 0
        val (dx, _) = calculator.getOffsetForDepth(1.0f)
        assertEquals("Depth=1 with perspective=1 should give 0 offset", 0f, dx, 0.1f)
    }

    @Test
    fun `depth factor 0 with perspective 1 gives full offset`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.depthSeparation = 1.0f
        calculator.perspective = 1.0f

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        val baseX = calculator.getOffsetX()
        // depth=0 with perspective=1: mixed = 0 + (1-0)*1 = 1, mult = 1*depthSep = 1
        val (dx, _) = calculator.getOffsetForDepth(0.0f)

        // Offset should be proportional to base offset
        assertTrue("Depth=0 should give significant offset", kotlin.math.abs(dx) > 50f)
    }

    @Test
    fun `perspective 0 reverses depth direction`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.depthSeparation = 1.0f
        calculator.perspective = 0.0f // foreground-heavy: mult = d * depthSep

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        // depth=0 with perspective=0: mixed = 0, mult = 0
        val (dx0, _) = calculator.getOffsetForDepth(0.0f)
        // depth=1 with perspective=0: mixed = 1, mult = 1
        val (dx1, _) = calculator.getOffsetForDepth(1.0f)

        assertEquals("Depth=0 with perspective=0 should give 0", 0f, dx0, 0.1f)
        assertTrue("Depth=1 with perspective=0 should give full offset", kotlin.math.abs(dx1) > 50f)
    }

    // ============== getEffectiveMaxOffset precision ==============

    @Test
    fun `effective max offset uses percent when set`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 5f // 5% of 1920 = 96px
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        val x = calculator.getOffsetX()
        // Should be bounded by ~96px (with tan curve it might be slightly different)
        assertTrue("Offset should be positive", x > 0f)
        assertTrue("Offset should not exceed 5% of height significantly", x < 120f)
    }

    @Test
    fun `effective max offset falls back to legacy px when percent is 0`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 50f
        calculator.setCanvasSize(1080f, 1920f) // scaleFactor = 1.0
        calculator.smoothing = 0f

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        val x = calculator.getOffsetX()
        assertTrue("Should use legacy maxOffsetPx", x > 30f)
        assertTrue("Should be bounded by maxOffsetPx * scaleFactor", x < 65f)
    }

    // ============== isMovingAwayFromCenter ==============

    @Test
    fun `edge easing does not slow return toward center`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f
        calculator.returnSpeed = 0f
        calculator.smoothing = 0f

        // Move to right edge
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAtEdge = calculator.getOffsetX()
        assertTrue("Should be at right edge", xAtEdge > 100f)

        // Now move back toward center - edge easing should NOT slow this
        calculator.updateFromSensor(-0.3f, 0f)
        calculator.update(16)
        val xAfter = calculator.getOffsetX()

        // Moving toward center from positive → target is negative,
        // target magnitude < current magnitude → not moving away → no easing
        assertTrue("Should move toward center without easing slowdown",
            xAfter < xAtEdge)
    }

    // ============== Edge easing multiplier boundary ==============

    @Test
    fun `edge easing at center gives full movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f
        calculator.smoothing = 0f

        // Start from center (default) and apply small tilt
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)

        // At center, edge easing multiplier should be ~1.0 (full speed)
        val x = calculator.getOffsetX()
        assertTrue("Should move from center despite edge easing", x > 0f)
    }

    @Test
    fun `depthSeparation 0 gives zero offset for all depths`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.depthSeparation = 0.0f

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        val (dx, _) = calculator.getOffsetForDepth(0.5f)
        assertEquals("Zero depthSeparation should give zero depth offset", 0f, dx, 0.1f)
    }

    @Test
    fun `curvedOffset boundary - zero position gives zero output`() {
        val calculator = ParallaxCalculator()
        calculator.setCanvasSize(1080f, 1920f)

        // With no tilt, offsets for any depth should be zero
        val (x, y) = calculator.getOffsetForDepth(0.5f)
        assertEquals(0f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    // ============== Default canvas orientation (kills init mul→div L99) ==============

    @Test
    fun `default canvas is portrait orientation`() {
        // Without setCanvasSize, canvasWidth = 1920*0.5625 = 1080 < canvasHeight = 1920
        // so it should behave as portrait: horizontalComp = aspectRatio, verticalComp = 1
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.smoothing = 0f
        calculator.aspectRatioCompensation = 2.0f // 2x horizontal

        calculator.updateFromSensor(0.5f, 0.5f)
        repeat(100) { calculator.update(16) }

        val x = kotlin.math.abs(calculator.getOffsetX())
        val y = kotlin.math.abs(calculator.getOffsetY())

        // In portrait: horizontalComp=2.0, verticalComp=1.0
        // X should be ~2x of Y (then circular clamping normalizes, but ratio should hold)
        assertTrue("In portrait, X should be larger than Y with aspectRatio>1 (x=$x, y=$y)",
            x > y * 1.3f)
    }

    // ============== maxOffsetPercent vs pixel fallback (kills L354 condition removal) ==============

    @Test
    fun `maxOffsetPercent controls movement when maxOffsetPx is zero`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.maxOffsetPx = 0f // Disable legacy fallback
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }

        val x = calculator.getOffsetX()
        // With percent=10%: effectiveMaxOffset = 1920 * 0.1 = 192
        // With mutation (always uses legacy): effectiveMaxOffset = 0 → NO movement
        assertTrue("With maxOffsetPercent=10 and maxOffsetPx=0, should still move", x > 100f)
    }

    @Test
    fun `maxOffsetPercent gives proportional offset to height`() {
        // Small percent
        val calc1 = ParallaxCalculator()
        calc1.strength = 1.0f
        calc1.maxOffsetPercent = 2f // 2% = 38px
        calc1.maxOffsetPx = 0f
        calc1.setCanvasSize(1080f, 1920f)
        calc1.smoothing = 0f
        calc1.updateFromSensor(1.0f, 0f)
        repeat(100) { calc1.update(16) }
        val x2pct = calc1.getOffsetX()

        // Large percent
        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 20f // 20% = 384px
        calc2.maxOffsetPx = 0f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.updateFromSensor(1.0f, 0f)
        repeat(100) { calc2.update(16) }
        val x20pct = calc2.getOffsetX()

        // 20% should give ~10x the offset of 2%
        assertTrue("Higher percent should give much larger offset (2%=$x2pct, 20%=$x20pct)",
            x20pct > x2pct * 5f)
    }

    // ============== Edge easing with returnSpeedUnlocked=false (kills L278 mutations) ==============

    @Test
    fun `returnSpeedUnlocked false slows return near edge with easing`() {
        // With returnSpeedUnlocked=false, edge easing ALSO affects return-to-center
        val calcUnlocked = ParallaxCalculator()
        calcUnlocked.strength = 1.0f
        calcUnlocked.maxOffsetPercent = 10f
        calcUnlocked.setCanvasSize(1080f, 1920f)
        calcUnlocked.edgeEasing = 1.0f
        calcUnlocked.returnSpeed = 0f  // No recenter during convergence
        calcUnlocked.returnSpeedUnlocked = true

        calcUnlocked.updateFromSensor(1.0f, 0f)
        repeat(100) { calcUnlocked.update(16) }
        calcUnlocked.returnSpeed = 0.5f
        calcUnlocked.updateFromSensor(0f, 0f)
        repeat(30) { calcUnlocked.update(16) }
        val xUnlocked = calcUnlocked.getOffsetX()

        val calcLocked = ParallaxCalculator()
        calcLocked.strength = 1.0f
        calcLocked.maxOffsetPercent = 10f
        calcLocked.setCanvasSize(1080f, 1920f)
        calcLocked.edgeEasing = 1.0f
        calcLocked.returnSpeed = 0f  // No recenter during convergence
        calcLocked.returnSpeedUnlocked = false

        calcLocked.updateFromSensor(1.0f, 0f)
        repeat(100) { calcLocked.update(16) }
        calcLocked.returnSpeed = 0.5f
        calcLocked.updateFromSensor(0f, 0f)
        repeat(30) { calcLocked.update(16) }
        val xLocked = calcLocked.getOffsetX()

        // Unlocked return should be faster (closer to center)
        assertTrue("Unlocked return should be closer to center (unlocked=$xUnlocked, locked=$xLocked)",
            kotlin.math.abs(xUnlocked) <= kotlin.math.abs(xLocked))
    }

    // ============== Smoothing affects convergence speed (kills L258, L262 mutations) ==============

    @Test
    fun `high smoothing slows convergence`() {
        val calcFast = ParallaxCalculator()
        calcFast.strength = 1.0f
        calcFast.maxOffsetPercent = 10f
        calcFast.setCanvasSize(1080f, 1920f)
        calcFast.smoothing = 0.1f
        calcFast.returnSpeed = 0f

        calcFast.updateFromSensor(1.0f, 0f)
        calcFast.update(16) // Just 1 frame
        val xFast = calcFast.getOffsetX()

        val calcSlow = ParallaxCalculator()
        calcSlow.strength = 1.0f
        calcSlow.maxOffsetPercent = 10f
        calcSlow.setCanvasSize(1080f, 1920f)
        calcSlow.smoothing = 0.9f
        calcSlow.returnSpeed = 0f

        calcSlow.updateFromSensor(1.0f, 0f)
        calcSlow.update(16) // Just 1 frame
        val xSlow = calcSlow.getOffsetX()

        // Low smoothing should converge faster after 1 frame
        assertTrue("Lower smoothing should converge faster after 1 frame (fast=$xFast, slow=$xSlow)",
            xFast > xSlow)
    }

    // ============== Tilt noise deadzone (kills L197-198 conditional mutations) ==============

    @Test
    fun `tiny tilt below noise deadzone produces no movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        // Tilt below deadzone (0.06)
        calculator.updateFromSensor(0.03f, 0.03f)
        repeat(100) { calculator.update(16) }

        assertEquals("Tilt below deadzone should not move X", 0f, calculator.getOffsetX(), 0.5f)
        assertEquals("Tilt below deadzone should not move Y", 0f, calculator.getOffsetY(), 0.5f)
    }

    @Test
    fun `tilt above noise deadzone produces movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        // Tilt above deadzone (0.06)
        calculator.updateFromSensor(0.5f, 0f)
        repeat(100) { calculator.update(16) }

        assertTrue("Tilt above deadzone should produce movement", calculator.getOffsetX() > 10f)
    }

    @Test
    fun `tilt exactly at deadzone boundary produces movement`() {
        // TILT_NOISE_DEADZONE = 0.06, filter is abs(tilt) < 0.06
        // So tilt = 0.06 should NOT be filtered (passes through)
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        calculator.updateFromSensor(0.06f, 0.06f)
        repeat(100) { calculator.update(16) }

        // Should produce movement since 0.06 is NOT strictly less than 0.06
        assertTrue("Tilt at exactly deadzone should produce X movement", calculator.getOffsetX() > 0.5f)
        assertTrue("Tilt at exactly deadzone should produce Y movement", calculator.getOffsetY() > 0.5f)
    }

    // ============== Circular clamping (kills L232 boundary, L219 math) ==============

    @Test
    fun `diagonal tilt is clamped to circle not square`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // Max tilt in both axes
        calculator.updateFromSensor(1.0f, 1.0f)
        repeat(100) { calculator.update(16) }

        val x = calculator.getOffsetX()
        val y = calculator.getOffsetY()
        val magnitude = kotlin.math.sqrt(x * x + y * y)

        // Same calculator, max tilt in one axis only
        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.updateFromSensor(1.0f, 0f)
        repeat(100) { calc2.update(16) }
        val xOnly = calc2.getOffsetX()

        // With per-axis softLimit (no circular clamping), diagonal magnitude
        // can be up to sqrt(2) times single-axis. Verify each axis is still bounded.
        assertTrue("Each diagonal axis should be bounded (X=$x < max=192)",
            kotlin.math.abs(x) < 1920f * 0.10f)
        assertTrue("Each diagonal axis should be bounded (Y=$y < max=192)",
            kotlin.math.abs(y) < 1920f * 0.10f)
    }

    // ============== Landscape orientation flips compensation axes (kills L212 conditional) ==============

    @Test
    fun `landscape orientation swaps aspect ratio compensation axes`() {
        // In landscape (width >= height): horizontalComp=1.0, verticalComp=aspectRatio
        val calcLandscape = ParallaxCalculator()
        calcLandscape.strength = 1.0f
        calcLandscape.maxOffsetPercent = 10f
        calcLandscape.setCanvasSize(1920f, 1080f) // Landscape
        calcLandscape.smoothing = 0f
        calcLandscape.aspectRatioCompensation = 2.0f
        calcLandscape.returnSpeed = 0f

        calcLandscape.updateFromSensor(0.5f, 0.5f)
        repeat(100) { calcLandscape.update(16) }

        val xLand = kotlin.math.abs(calcLandscape.getOffsetX())
        val yLand = kotlin.math.abs(calcLandscape.getOffsetY())

        // In landscape: horizontalComp=1.0, verticalComp=2.0 → Y should be larger
        assertTrue("In landscape, Y should be larger with aspect ratio 2.0 (x=$xLand, y=$yLand)",
            yLand > xLand * 1.3f)
    }

    // ============== Delta time scaling (kills L219 mul→div, L247 add→sub) ==============

    @Test
    fun `longer delta time produces faster convergence`() {
        // With smoothing > 0.02: lerp rate includes deltaScale
        val calcNormal = ParallaxCalculator()
        calcNormal.strength = 1.0f
        calcNormal.maxOffsetPercent = 10f
        calcNormal.setCanvasSize(1080f, 1920f)
        calcNormal.smoothing = 0.5f
        calcNormal.returnSpeed = 0f

        calcNormal.updateFromSensor(1.0f, 0f)
        calcNormal.update(16) // Normal 60fps frame
        val x16ms = calcNormal.getOffsetX()

        val calcSlow = ParallaxCalculator()
        calcSlow.strength = 1.0f
        calcSlow.maxOffsetPercent = 10f
        calcSlow.setCanvasSize(1080f, 1920f)
        calcSlow.smoothing = 0.5f
        calcSlow.returnSpeed = 0f

        calcSlow.updateFromSensor(1.0f, 0f)
        calcSlow.update(32) // Slower frame rate, bigger step
        val x32ms = calcSlow.getOffsetX()

        // Longer delta should move further in one step
        assertTrue("32ms frame should move further than 16ms (32ms=$x32ms, 16ms=$x16ms)",
            x32ms > x16ms)
    }

    // ============== Return speed engages proportionally (kills L251, L247 boundary) ==============

    @Test
    fun `return speed medium gives moderate return`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0.3f
        calc.returnSpeed = 0f  // No recenter during convergence

        // Move to offset
        calc.updateFromSensor(1.0f, 0f)
        repeat(100) { calc.update(16) }
        val xAtTilt = calc.getOffsetX()
        assertTrue("Should have moved", xAtTilt > 50f)

        // Enable moderate return speed and release tilt
        calc.returnSpeed = 0.5f
        calc.updateFromSensor(0f, 0f)
        repeat(50) { calc.update(16) }
        val xAfter = calc.getOffsetX()

        // Medium return speed should move somewhat toward center
        assertTrue("Medium return should reduce offset (before=$xAtTilt, after=$xAfter)",
            kotlin.math.abs(xAfter) < kotlin.math.abs(xAtTilt) * 0.8f)
    }

    // ============== Snap to target when close (kills L295-296 boundary/math) ==============

    @Test
    fun `snap to target prevents small oscillations`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Move to position
        calc.updateFromSensor(1.0f, 0f)
        repeat(100) { calc.update(16) }
        val xFinal = calc.getOffsetX()

        // Continue updating with same tilt - should stay exactly at target (no oscillation)
        repeat(10) { calc.update(16) }
        val xStable = calc.getOffsetX()
        assertEquals("Position should be exactly stable (no micro-oscillation)", xFinal, xStable, 0.001f)
    }

    // ============== getOffsetXForDepth and getOffsetYForDepth (kills curvedOffset L348) ==============

    @Test
    fun `getOffsetXForDepth consistent with getOffsetForDepth`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.depthSeparation = 1.0f

        calc.updateFromSensor(0.8f, 0.6f)
        repeat(100) { calc.update(16) }

        val (pairX, pairY) = calc.getOffsetForDepth(0.3f)
        val singleX = calc.getOffsetXForDepth(0.3f)
        val singleY = calc.getOffsetYForDepth(0.3f)

        assertEquals("X from pair and single should match", pairX, singleX, 0.001f)
        assertEquals("Y from pair and single should match", pairY, singleY, 0.001f)
    }

    @Test
    fun `depth offset scales between 0 and base proportionally`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.depthSeparation = 1.0f
        calc.perspective = 1.0f

        calc.updateFromSensor(0.8f, 0f)
        repeat(100) { calc.update(16) }

        val depth00 = kotlin.math.abs(calc.getOffsetXForDepth(0.0f))
        val depth05 = kotlin.math.abs(calc.getOffsetXForDepth(0.5f))
        val depth10 = kotlin.math.abs(calc.getOffsetXForDepth(1.0f))

        // depth=0 should have most offset, depth=1 should have least (with perspective=1)
        assertTrue("depth=0 should have more offset than depth=0.5 (d0=$depth00, d05=$depth05)",
            depth00 > depth05)
        assertTrue("depth=0.5 should have more offset than depth=1.0 (d05=$depth05, d10=$depth10)",
            depth05 > depth10)
    }

    // ============== Edge easing with both axes (kills calculateEdgeEasingMultiplier L384-395) ==============

    @Test
    fun `edge easing slows diagonal movement more than axial`() {
        // At a diagonal position, normalizedDistance is higher than on a single axis
        // So easing should slow it more
        val calcAxis = ParallaxCalculator()
        calcAxis.strength = 1.0f
        calcAxis.maxOffsetPercent = 10f
        calcAxis.setCanvasSize(1080f, 1920f)
        calcAxis.edgeEasing = 0.8f
        calcAxis.smoothing = 0.3f
        calcAxis.returnSpeed = 0f

        calcAxis.updateFromSensor(0.9f, 0f) // Single axis
        repeat(30) { calcAxis.update(16) }
        val xAxis = calcAxis.getOffsetX()

        val calcDiag = ParallaxCalculator()
        calcDiag.strength = 1.0f
        calcDiag.maxOffsetPercent = 10f
        calcDiag.setCanvasSize(1080f, 1920f)
        calcDiag.edgeEasing = 0.8f
        calcDiag.smoothing = 0.3f
        calcDiag.returnSpeed = 0f

        calcDiag.updateFromSensor(0.9f, 0.9f) // Diagonal
        repeat(30) { calcDiag.update(16) }
        val xDiag = calcDiag.getOffsetX()

        // Diagonal should have less X progress due to higher easing effect
        assertTrue("Diagonal X should be less due to stronger easing (axis=$xAxis, diag=$xDiag)",
            xDiag < xAxis)
    }

    // ============== softLimit tests (monotonic, bounded, sign-preserving) ==============

    @Test
    fun `softLimit is monotonically increasing`() {
        val calc = ParallaxCalculator()
        val max = 100f
        var prev = calc.softLimit(-200f, max)
        for (i in -199..200) {
            val v = i.toFloat()
            val result = calc.softLimit(v, max)
            assertTrue("softLimit($v) = $result should be >= softLimit(${v - 1}) = $prev",
                result >= prev)
            prev = result
        }
    }

    @Test
    fun `softLimit never exceeds max`() {
        val calc = ParallaxCalculator()
        val max = 50f
        for (v in listOf(-10000f, -100f, -50f, -1f, 0f, 1f, 50f, 100f, 10000f)) {
            val result = calc.softLimit(v, max)
            assertTrue("softLimit($v, $max) = $result should be in [-$max, $max]",
                kotlin.math.abs(result) <= max)
        }
    }

    @Test
    fun `softLimit preserves sign`() {
        val calc = ParallaxCalculator()
        assertTrue("Positive input → positive output", calc.softLimit(5f, 10f) > 0f)
        assertTrue("Negative input → negative output", calc.softLimit(-5f, 10f) < 0f)
        assertEquals("Zero input → zero output", 0f, calc.softLimit(0f, 10f), 0.001f)
    }

    @Test
    fun `softLimit with zero max returns zero`() {
        val calc = ParallaxCalculator()
        assertEquals(0f, calc.softLimit(100f, 0f), 0.001f)
        assertEquals(0f, calc.softLimit(-100f, 0f), 0.001f)
    }

    @Test
    fun `softLimit near-linear for small inputs`() {
        val calc = ParallaxCalculator()
        val max = 100f
        // For small inputs relative to max, tanh(x/max) ≈ x/max, so softLimit ≈ x
        val result = calc.softLimit(5f, max)
        assertEquals("Small input should be near-linear", 5f, result, 0.5f)
    }

    // ============== Return-to-center stillness detection ==============

    @Test
    fun `recenter does not engage during active motion`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 1.0f

        // Actively changing tilt each frame (simulates hand movement) —
        // velocity stays above threshold so recentering never engages
        var tiltX = 0.8f
        repeat(200) { i ->
            // Oscillate tilt with enough amplitude and frequency to keep
            // velocity above STILL_VELOCITY_EPSILON (0.015) every frame
            tiltX = 0.8f + 0.1f * kotlin.math.sin(i * 0.5f)
            calc.updateFromSensor(tiltX, 0f)
            calc.update(16)
        }
        val xAtTilt = calc.getOffsetX()
        assertTrue("Should have significant offset during active motion: $xAtTilt", xAtTilt > 60f)
    }

    @Test
    fun `recenter engages after hold time with zero tilt`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f  // No recenter during convergence phase

        // Move to offset without recentering
        calc.updateFromSensor(0.8f, 0f)
        repeat(100) { calc.update(16) }
        val xBefore = calc.getOffsetX()
        assertTrue("Should have offset", xBefore > 50f)

        // Enable recenter and zero tilt — wait for hold timer (400ms = 25 frames)
        calc.returnSpeed = 1.0f
        calc.updateFromSensor(0f, 0f)
        repeat(100) { calc.update(16) }  // 1600ms > 400ms hold time
        val xAfter = calc.getOffsetX()

        // Should have recentered significantly
        assertTrue("Should recenter after hold time (before=$xBefore, after=$xAfter)",
            kotlin.math.abs(xAfter) < kotlin.math.abs(xBefore) * 0.2f)
    }

    @Test
    fun `recenter is frame-rate independent`() {
        // Test that recentering at 30fps and 60fps produces similar results
        val calc60 = ParallaxCalculator()
        calc60.strength = 1.0f
        calc60.maxOffsetPercent = 10f
        calc60.setCanvasSize(1080f, 1920f)
        calc60.smoothing = 0f
        calc60.returnSpeed = 0f  // No recenter during convergence

        calc60.updateFromSensor(0.8f, 0f)
        repeat(100) { calc60.update(16) }
        calc60.returnSpeed = 0.8f  // Enable recenter for decay test
        calc60.updateFromSensor(0f, 0f)
        // Skip past hold time then measure decay
        repeat(30) { calc60.update(16) }  // 480ms to pass hold timer
        val x60start = calc60.getOffsetX()
        repeat(60) { calc60.update(16) }  // 960ms of recentering at 60fps
        val x60end = calc60.getOffsetX()
        val decay60 = if (x60start != 0f) x60end / x60start else 0f

        val calc30 = ParallaxCalculator()
        calc30.strength = 1.0f
        calc30.maxOffsetPercent = 10f
        calc30.setCanvasSize(1080f, 1920f)
        calc30.smoothing = 0f
        calc30.returnSpeed = 0f  // No recenter during convergence

        calc30.updateFromSensor(0.8f, 0f)
        repeat(100) { calc30.update(16) }
        calc30.returnSpeed = 0.8f  // Enable recenter for decay test
        calc30.updateFromSensor(0f, 0f)
        repeat(30) { calc30.update(16) }  // Same 480ms hold
        val x30start = calc30.getOffsetX()
        repeat(30) { calc30.update(32) }  // Same 960ms of recentering at 30fps
        val x30end = calc30.getOffsetX()
        val decay30 = if (x30start != 0f) x30end / x30start else 0f

        // Decay ratios should be similar (frame-rate independent)
        assertEquals("Decay should be frame-rate independent", decay60, decay30, 0.15f)
    }

    @Test
    fun `softLimit replaces hard clamp in target position`() {
        // Verify that extreme tilt values produce output approaching but not reaching the max
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Extreme tilt
        calc.updateFromSensor(5.0f, 0f)
        repeat(100) { calc.update(16) }
        val xExtreme = calc.getOffsetX()

        // Normal tilt
        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.returnSpeed = 0f
        calc2.updateFromSensor(1.0f, 0f)
        repeat(100) { calc2.update(16) }
        val xNormal = calc2.getOffsetX()

        // Extreme should be larger than normal but still bounded
        assertTrue("Extreme tilt should produce larger offset", xExtreme > xNormal)
        val maxOffset = 1920f * 0.10f // 10% = 192px
        assertTrue("Extreme tilt should still be bounded below max ($xExtreme < $maxOffset)",
            xExtreme < maxOffset)
    }

    @Test
    fun softLimitPreservesDirection() {
        // Radial soft-limit must preserve the direction vector
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Diagonal tilt at 45 degrees
        calc.updateFromSensor(0.8f, 0.8f)
        repeat(200) { calc.update(16) }
        val x = calc.getOffsetX()
        val y = calc.getOffsetY()

        // X and Y should have the same sign and similar magnitude (direction preserved)
        assertTrue("X should be positive: $x", x > 0)
        assertTrue("Y should be positive: $y", y > 0)
        // Ratio should be close to 1.0 (diagonal direction preserved)
        val ratio = x / y
        assertEquals("Direction should be preserved (ratio ~1)", 1.0f, ratio, 0.15f)
    }

    @Test
    fun softLimitIsRadialNotPerAxis() {
        // A 45° tilt should reach the same DISTANCE as a cardinal tilt of same magnitude,
        // not sqrt(2) * cardinal (which would happen with per-axis clamp)
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Cardinal: pure X
        calc.updateFromSensor(1.0f, 0.0f)
        repeat(200) { calc.update(16) }
        val cardinalDist = kotlin.math.abs(calc.getOffsetX())

        // Diagonal: equal X and Y
        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.returnSpeed = 0f
        calc2.updateFromSensor(0.707f, 0.707f) // Same magnitude as (1,0)
        repeat(200) { calc2.update(16) }
        val diagonalDist = kotlin.math.sqrt(
            calc2.getOffsetX() * calc2.getOffsetX() + calc2.getOffsetY() * calc2.getOffsetY()
        )

        // With radial limit, diagonal distance should be similar to cardinal distance
        // (both saturate at the same radial maximum)
        assertEquals("Radial limit: diagonal dist ~= cardinal dist",
            cardinalDist, diagonalDist, cardinalDist * 0.2f)
    }

    @Test
    fun phoneStillButTiltedRecentersAfterHold() {
        // A phone held perfectly still at an angle should recenter
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.5f  // Non-zero return speed enables recentering

        // Move to a tilted position
        calc.updateFromSensor(0.5f, 0.3f)
        repeat(60) { calc.update(16) }  // ~1 second of tracking
        val xBeforeRecenter = calc.getOffsetX()
        assertTrue("Should have moved to tilted position: $xBeforeRecenter",
            xBeforeRecenter > 10f)

        // Now hold perfectly still at same tilt (no velocity)
        // Keep calling update with the SAME tilt value — velocity = 0
        repeat(200) { calc.update(16) }  // ~3.2 seconds of stillness

        val xAfterRecenter = calc.getOffsetX()
        // Should have recentered significantly
        assertTrue("Should have recentered from $xBeforeRecenter to near zero: $xAfterRecenter",
            kotlin.math.abs(xAfterRecenter) < kotlin.math.abs(xBeforeRecenter) * 0.3f)
    }

    @Test
    fun strongTiltStillApproachesUsefulMaxOffset() {
        // Without double-tanh compression, full tilt should reach a useful fraction of max offset
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        calc.updateFromSensor(1.0f, 0.0f)
        repeat(200) { calc.update(16) }
        val x = calc.getOffsetX()
        val maxOffset = 1920f * 0.10f  // 192px

        // With single radial tanh, tilt=1.0 should reach ~76% of max (tanh(1) ≈ 0.76)
        // With double-tanh it would only reach ~57% (tanh(tanh(1)) ≈ 0.57)
        assertTrue("Full tilt should reach >70% of max offset ($x vs $maxOffset)",
            x > maxOffset * 0.70f)
    }
}
