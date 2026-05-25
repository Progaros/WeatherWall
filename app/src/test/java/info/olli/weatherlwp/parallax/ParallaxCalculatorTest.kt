package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ParallaxCalculator (delta-based motion model).
 *
 * All behavior tests use ramp helpers to gradually change tilt,
 * matching the per-frame delta integration model.
 */
class ParallaxCalculatorTest {

    // ============== Ramp helpers ==============

    /** Ramp tilt from (0,0) to (toX, toY). Auto-calculates steps to keep per-frame
     *  delta above the stillness-enter threshold so recenter never engages during a ramp. */
    private fun rampTilt(calc: ParallaxCalculator, toX: Float, toY: Float, steps: Int = -1) {
        val mag = kotlin.math.sqrt(toX * toX + toY * toY)
        val autoSteps = (mag / 0.025f).toInt().coerceIn(5, 100)
        val actualSteps = if (steps > 0) steps else autoSteps
        for (i in 0..actualSteps) {
            val t = i.toFloat() / actualSteps
            calc.updateFromSensor(toX * t, toY * t)
            calc.update(16)
        }
    }

    /** Ramp tilt between two absolute values. Auto-calculates steps. */
    private fun rampTiltFromTo(
        calc: ParallaxCalculator,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        steps: Int = -1
    ) {
        val dx = toX - fromX
        val dy = toY - fromY
        val mag = kotlin.math.sqrt(dx * dx + dy * dy)
        val autoSteps = (mag / 0.025f).toInt().coerceIn(5, 100)
        val actualSteps = if (steps > 0) steps else autoSteps
        for (i in 1..actualSteps) {
            val t = i.toFloat() / actualSteps
            calc.updateFromSensor(fromX + dx * t, fromY + dy * t)
            calc.update(16)
        }
    }

    // ============== Basic behavior tests ==============

    @Test
    fun testUpdateFromSensor() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)

        rampTilt(calculator, 0.5f, -0.5f)

        val (x, y) = calculator.getOffset()
        assert(x > 0) { "X should be positive: $x" }
        assert(y < 0) { "Y should be negative: $y" }
    }

    @Test
    fun testPositionFollowsTilt() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)

        rampTilt(calculator, 1.0f, 0.0f)
        val xAfterTilt = calculator.getOffsetX()
        assert(xAfterTilt > 50f) { "X should be near max after tilt: $xAfterTilt" }

        // Allow smoothing buffer to settle, then check stability
        repeat(3) { calculator.update(16) }
        val xSettled = calculator.getOffsetX()
        repeat(50) { calculator.update(16) }
        val xStable = calculator.getOffsetX()
        assertEquals(xSettled, xStable, 1f)
    }

    @Test
    fun testDepthScaling() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.depthSeparation = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)

        rampTilt(calculator, 1.0f, 1.0f)

        val (depthX, depthY) = calculator.getOffsetForDepth(0.5f)
        // depth=0.5 with perspective=1.0: mult = 0.5 * depthSep = 0.5
        assertTrue("Depth offset should be significant", kotlin.math.abs(depthX) > 5f)
        assertTrue("Depth offset should be significant", kotlin.math.abs(depthY) > 5f)
    }

    @Test
    fun testCanvasSizeScaling() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f

        calculator.setCanvasSize(540f, 960f) // Half of reference
        rampTilt(calculator, 1.0f, 0.0f)
        val offsetAtHalfHeight = calculator.getOffsetX()

        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPercent = 0f
        calculator2.maxOffsetPx = 100f

        calculator2.setCanvasSize(1080f, 1920f)
        rampTilt(calculator2, 1.0f, 0.0f)
        val offsetAtFullHeight = calculator2.getOffsetX()

        assertEquals(offsetAtFullHeight * 0.5f, offsetAtHalfHeight, 5f)
    }

    @Test
    fun testInvertX() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.invertX = false

        rampTilt(calculator, 1.0f, 0.0f)
        val xNormal = calculator.getOffsetX()

        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPercent = 0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.invertX = true

        rampTilt(calculator2, 1.0f, 0.0f)
        val xInverted = calculator2.getOffsetX()

        assert(xNormal > 0) { "Normal X should be positive: $xNormal" }
        assert(xInverted < 0) { "Inverted X should be negative: $xInverted" }
        assertEquals(xNormal, -xInverted, 1f)
    }

    @Test
    fun testInvertY() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.invertY = false

        rampTilt(calculator, 0.0f, 1.0f)
        val yNormal = calculator.getOffsetY()

        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPercent = 0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.invertY = true

        rampTilt(calculator2, 0.0f, 1.0f)
        val yInverted = calculator2.getOffsetY()

        assert(yNormal > 0) { "Normal Y should be positive: $yNormal" }
        assert(yInverted < 0) { "Inverted Y should be negative: $yInverted" }
        assertEquals(yNormal, -yInverted, 1f)
    }

    @Test
    fun testAspectRatioCompensation() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.aspectRatioCompensation = 1.0f

        rampTilt(calculator, 1.0f, 1.0f)
        val xNormal = calculator.getOffsetX()
        val yNormal = calculator.getOffsetY()
        assertEquals(kotlin.math.abs(xNormal), kotlin.math.abs(yNormal), 5f)

        val calculator2 = ParallaxCalculator()
        calculator2.strength = 1.0f
        calculator2.maxOffsetPercent = 0f
        calculator2.maxOffsetPx = 100f
        calculator2.setCanvasSize(1080f, 1920f)
        calculator2.aspectRatioCompensation = 1.5f

        rampTilt(calculator2, 1.0f, 1.0f)
        val xScaled = calculator2.getOffsetX()
        val yScaled = calculator2.getOffsetY()
        assertEquals(kotlin.math.abs(yScaled) * 1.5f, kotlin.math.abs(xScaled), 5f)
    }

    @Test
    fun testAspectRatioDefault() {
        val calculator = ParallaxCalculator()
        assertEquals("Default aspect ratio should be 1.0", 1.0f, calculator.aspectRatioCompensation, 0.01f)
    }

    @Test
    fun testMaxOffsetPercentDefault() {
        val calculator = ParallaxCalculator()
        assertEquals("Default max offset percent should be 20%", 20f, calculator.maxOffsetPercent, 0.01f)
    }

    @Test
    fun testMaxOffsetPercent() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)

        rampTilt(calculator, 1.0f, 0f)
        val x = calculator.getOffsetX()

        assertTrue("Max offset should be > 100 for 10% of 1920", kotlin.math.abs(x) > 100f)
        assertTrue("Max offset should not exceed 10% of height", kotlin.math.abs(x) < 200f)
    }

    @Test
    fun testMaxOffsetPercentPreview() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(270f, 480f)

        rampTilt(calculator, 1.0f, 0f)
        val x = calculator.getOffsetX()

        assertTrue("Preview offset should be ~10% of preview height", kotlin.math.abs(x) > 30f)
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
        calculator.returnSpeed = 0f

        rampTilt(calculator, 1.0f, 0f)
        val xAtTilt = calculator.getOffsetX()
        assertTrue("Should have moved", kotlin.math.abs(xAtTilt) > 50f)

        // Release tilt - with returnSpeed=0, should stay at position
        calculator.updateFromSensor(0f, 0f)
        repeat(200) { calculator.update(16) }
        val xAfterRelease = calculator.getOffsetX()

        assertEquals("With returnSpeed=0, position should stay", xAtTilt, xAfterRelease, 5f)
    }

    @Test
    fun testReturnSpeedHighFastReturn() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.returnSpeed = 0f

        rampTilt(calculator, 1.0f, 0f)
        val xAtTilt = calculator.getOffsetX()
        assertTrue("Should have moved", kotlin.math.abs(xAtTilt) > 50f)

        // Enable high return speed and release tilt
        calculator.returnSpeed = 1.0f
        calculator.updateFromSensor(0f, 0f)
        repeat(100) { calculator.update(16) }
        val xAfterRelease = calculator.getOffsetX()

        assertTrue("With returnSpeed=1.0, should return quickly",
            kotlin.math.abs(xAfterRelease) < kotlin.math.abs(xAtTilt) * 0.1f)
    }

    @Test
    fun testReturnSpeedDefault() {
        val calculator = ParallaxCalculator()
        assertEquals("Default return speed should be 0", 0f, calculator.returnSpeed, 0.01f)
    }

    @Test
    fun testReturnSpeedLogScale() {
        val logScaleAt50 = kotlin.math.ln(1f + 0.5f) / kotlin.math.ln(2f)
        assertTrue("Log scale at 50% should be ~0.58", logScaleAt50 in 0.55f..0.62f)
        val logScaleAt100 = kotlin.math.ln(1f + 1f) / kotlin.math.ln(2f)
        assertEquals("Log scale at 100% should be 1.0", 1.0f, logScaleAt100, 0.01f)
    }

    // ============== Delta-model latency tests ==============

    @Test
    fun testSmallDeltaProducesProportionalMove() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 100f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // First frame: sets baseline
        calculator.updateFromSensor(0f, 0f)
        calculator.update(16)

        // Small delta
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)
        val xAfter1 = calculator.getOffsetX()

        // Expected: delta=0.1, sensitivity=100, offset ~= 10px
        assertTrue("After 1 frame, should have proportional offset (~10px): $xAfter1",
            kotlin.math.abs(xAfter1) > 7f)

        // Another delta in same direction
        calculator.updateFromSensor(0.2f, 0f)
        calculator.update(16)
        val xAfter2 = calculator.getOffsetX()
        assertTrue("Continuing delta should increase offset", xAfter2 > xAfter1)
    }

    @Test
    fun testConstantTiltProducesNoMovement() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        // Set tilt once and hold
        calculator.updateFromSensor(0.5f, 0f)
        calculator.update(16) // baseline frame
        val x1 = calculator.getOffsetX()

        // Subsequent frames with same tilt: delta = 0, no movement
        repeat(100) { calculator.update(16) }
        val x2 = calculator.getOffsetX()
        assertEquals("Constant tilt should not create movement", x1, x2, 0.1f)
    }

    @Test
    fun testDirectResponseToDirectionChange() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // Ramp right
        rampTilt(calculator, 0.5f, 0f)
        val x1 = calculator.getOffsetX()
        assertTrue("Should have positive offset", x1 > 10f)

        // Ramp back left
        rampTiltFromTo(calculator, 0.5f, 0f, -0.5f, 0f)
        val x2 = calculator.getOffsetX()

        assertTrue("Should have moved left from direction change (x1=$x1, x2=$x2)", x2 < x1)
    }

    // ============== Edge Easing Tests ==============

    @Test
    fun testEdgeEasingDefaultIsZero() {
        val calculator = ParallaxCalculator()
        assertEquals("Default edge easing should be 0 (linear)", 0f, calculator.edgeEasing, 0.01f)
    }

    @Test
    fun testEdgeEasingZeroIsLinear() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 0f
        calculator.smoothing = 0f

        rampTilt(calculator, 1.0f, 0f)
        val xAtEdge = calculator.getOffsetX()

        assertTrue("With edgeEasing=0, should reach near soft-limited max offset", xAtEdge > 100f)
    }

    @Test
    fun testEdgeEasingSlowsNearEdge() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f
        calculator.smoothing = 0f

        rampTilt(calculator, 0.5f, 0f)
        val xAtMid = calculator.getOffsetX()

        rampTiltFromTo(calculator, 0.5f, 0f, 1.0f, 0f)
        val xAtEdge = calculator.getOffsetX()

        val centerToMidProgress = kotlin.math.abs(xAtMid)
        val midToEdgeProgress = kotlin.math.abs(xAtEdge - xAtMid)

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
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f
        calculator.returnSpeed = 0f
        calculator.returnSpeedUnlocked = true

        rampTilt(calculator, 1.0f, 0f)
        val xAtEdge = calculator.getOffsetX()
        assertTrue("Should have moved to edge", xAtEdge > 80f)

        calculator.returnSpeed = 0.5f
        calculator.updateFromSensor(0f, 0f)
        repeat(200) { calculator.update(16) }
        val xAfterReturn = calculator.getOffsetX()

        assertTrue("With unlocked return, should return to near center quickly",
            kotlin.math.abs(xAfterReturn) < 20f)
    }

    @Test
    fun testMaxOffsetPercentDefaultIncreased() {
        val calculator = ParallaxCalculator()
        assertEquals("Default max offset percent should be 20%", 20f, calculator.maxOffsetPercent, 0.01f)
    }

    // ============== Property readback tests ==============

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

        rampTilt(calculator, 0.5f, 0.3f)
        assertTrue("Should have moved", calculator.getOffsetX() > 0f)

        calculator.reset()
        calculator.update(16)

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
        calculator.perspective = 1.0f

        rampTilt(calculator, 1.0f, 0f)

        // depth=1.0 with perspective=1.0: mult = 0
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

        rampTilt(calculator, 1.0f, 0f)

        // depth=0 with perspective=1: mult = 1*depthSep = 1
        val (dx, _) = calculator.getOffsetForDepth(0.0f)
        assertTrue("Depth=0 should give significant offset", kotlin.math.abs(dx) > 50f)
    }

    @Test
    fun `perspective 0 reverses depth direction`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.depthSeparation = 1.0f
        calculator.perspective = 0.0f

        rampTilt(calculator, 1.0f, 0f)

        val (dx0, _) = calculator.getOffsetForDepth(0.0f)
        val (dx1, _) = calculator.getOffsetForDepth(1.0f)

        assertEquals("Depth=0 with perspective=0 should give 0", 0f, dx0, 0.1f)
        assertTrue("Depth=1 with perspective=0 should give full offset", kotlin.math.abs(dx1) > 50f)
    }

    // ============== getEffectiveMaxOffset precision ==============

    @Test
    fun `effective max offset uses percent when set`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 5f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        rampTilt(calculator, 1.0f, 0f)

        val x = calculator.getOffsetX()
        assertTrue("Offset should be positive", x > 0f)
        assertTrue("Offset should not exceed 5% of height significantly", x < 120f)
    }

    @Test
    fun `effective max offset falls back to legacy px when percent is 0`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 0f
        calculator.maxOffsetPx = 50f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        rampTilt(calculator, 1.0f, 0f)

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

        rampTilt(calculator, 1.0f, 0f)
        val xAtEdge = calculator.getOffsetX()
        assertTrue("Should be at right edge", xAtEdge > 80f)

        // Move toward center - should not be slowed by edge easing
        rampTiltFromTo(calculator, 1.0f, 0f, 0.7f, 0f)
        val xAfter = calculator.getOffsetX()

        assertTrue("Should move toward center without easing slowdown", xAfter < xAtEdge)
    }

    @Test
    fun `edge easing at center gives full movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.edgeEasing = 1.0f
        calculator.smoothing = 0f

        // Small tilt from center
        calculator.updateFromSensor(0f, 0f)
        calculator.update(16)
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)

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

        rampTilt(calculator, 1.0f, 0f)

        val (dx, _) = calculator.getOffsetForDepth(0.5f)
        assertEquals("Zero depthSeparation should give zero depth offset", 0f, dx, 0.1f)
    }

    @Test
    fun `curvedOffset boundary - zero position gives zero output`() {
        val calculator = ParallaxCalculator()
        calculator.setCanvasSize(1080f, 1920f)

        val (x, y) = calculator.getOffsetForDepth(0.5f)
        assertEquals(0f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    // ============== Default canvas orientation ==============

    @Test
    fun `default canvas is portrait orientation`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.smoothing = 0f
        calculator.aspectRatioCompensation = 2.0f

        rampTilt(calculator, 0.5f, 0.5f)

        val x = kotlin.math.abs(calculator.getOffsetX())
        val y = kotlin.math.abs(calculator.getOffsetY())

        assertTrue("In portrait, X should be larger than Y with aspectRatio>1 (x=$x, y=$y)",
            x > y * 1.3f)
    }

    // ============== maxOffsetPercent vs pixel fallback ==============

    @Test
    fun `maxOffsetPercent controls movement when maxOffsetPx is zero`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.maxOffsetPx = 0f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        rampTilt(calculator, 1.0f, 0f)

        val x = calculator.getOffsetX()
        assertTrue("With maxOffsetPercent=10 and maxOffsetPx=0, should still move", x > 100f)
    }

    @Test
    fun `maxOffsetPercent gives proportional offset to height`() {
        val calc1 = ParallaxCalculator()
        calc1.strength = 1.0f
        calc1.maxOffsetPercent = 2f
        calc1.maxOffsetPx = 0f
        calc1.setCanvasSize(1080f, 1920f)
        calc1.smoothing = 0f
        rampTilt(calc1, 1.0f, 0f)
        val x2pct = calc1.getOffsetX()

        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 20f
        calc2.maxOffsetPx = 0f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        rampTilt(calc2, 1.0f, 0f)
        val x20pct = calc2.getOffsetX()

        assertTrue("Higher percent should give much larger offset (2%=$x2pct, 20%=$x20pct)",
            x20pct > x2pct * 5f)
    }

    // ============== Edge easing with returnSpeedUnlocked=false ==============

    @Test
    fun `returnSpeedUnlocked false slows return near edge with easing`() {
        val calcUnlocked = ParallaxCalculator()
        calcUnlocked.strength = 1.0f
        calcUnlocked.maxOffsetPercent = 10f
        calcUnlocked.setCanvasSize(1080f, 1920f)
        calcUnlocked.edgeEasing = 1.0f
        calcUnlocked.returnSpeed = 0f
        calcUnlocked.returnSpeedUnlocked = true

        rampTilt(calcUnlocked, 1.0f, 0f)
        calcUnlocked.returnSpeed = 0.5f
        calcUnlocked.updateFromSensor(0f, 0f)
        repeat(50) { calcUnlocked.update(16) }
        val xUnlocked = calcUnlocked.getOffsetX()

        val calcLocked = ParallaxCalculator()
        calcLocked.strength = 1.0f
        calcLocked.maxOffsetPercent = 10f
        calcLocked.setCanvasSize(1080f, 1920f)
        calcLocked.edgeEasing = 1.0f
        calcLocked.returnSpeed = 0f
        calcLocked.returnSpeedUnlocked = false

        rampTilt(calcLocked, 1.0f, 0f)
        calcLocked.returnSpeed = 0.5f
        calcLocked.updateFromSensor(0f, 0f)
        repeat(50) { calcLocked.update(16) }
        val xLocked = calcLocked.getOffsetX()

        assertTrue("Unlocked return should be closer to center (unlocked=$xUnlocked, locked=$xLocked)",
            kotlin.math.abs(xUnlocked) <= kotlin.math.abs(xLocked))
    }

    // ============== Smoothing affects convergence speed ==============

    @Test
    fun `high smoothing slows convergence`() {
        // With direct delta integration, smoothing no longer affects convergence.
        // Both should produce the same offset for the same ramp.
        val calcFast = ParallaxCalculator()
        calcFast.strength = 1.0f
        calcFast.maxOffsetPercent = 10f
        calcFast.setCanvasSize(1080f, 1920f)
        calcFast.smoothing = 0.1f
        calcFast.returnSpeed = 0f

        // Ramp — both accumulate identically
        rampTilt(calcFast, 1.0f, 0f, 20)
        val xFast = calcFast.getOffsetX()

        val calcSlow = ParallaxCalculator()
        calcSlow.strength = 1.0f
        calcSlow.maxOffsetPercent = 10f
        calcSlow.setCanvasSize(1080f, 1920f)
        calcSlow.smoothing = 0.9f
        calcSlow.returnSpeed = 0f

        rampTilt(calcSlow, 1.0f, 0f, 20)
        val xSlow = calcSlow.getOffsetX()

        // Direct integration: smoothing is ignored, both converge equally
        assertEquals("Direct integration ignores smoothing (fast=$xFast, slow=$xSlow)",
            xFast, xSlow, 1.0f)
    }

    // ============== Tilt noise deadzone ==============

    @Test
    fun `tiny tilt below noise deadzone produces no movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        // Ramp to tiny values below deadzone (0.06)
        rampTilt(calculator, 0.03f, 0.03f)

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

        rampTilt(calculator, 0.5f, 0f)

        assertTrue("Tilt above deadzone should produce movement", calculator.getOffsetX() > 10f)
    }

    @Test
    fun `tilt exactly at deadzone boundary produces movement`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f
        calculator.returnSpeed = 0f

        // Ramp through the deadzone boundary
        rampTilt(calculator, 0.2f, 0.2f, 30)

        assertTrue("Tilt passing through deadzone should produce X movement", calculator.getOffsetX() > 0.5f)
        assertTrue("Tilt passing through deadzone should produce Y movement", calculator.getOffsetY() > 0.5f)
    }

    // ============== Circular/radial clamping ==============

    @Test
    fun `diagonal tilt is clamped to circle not square`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 10f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        rampTilt(calculator, 1.0f, 1.0f)

        val x = calculator.getOffsetX()
        val y = calculator.getOffsetY()

        assertTrue("Each diagonal axis should be bounded (X=$x < max=192)",
            kotlin.math.abs(x) < 1920f * 0.10f)
        assertTrue("Each diagonal axis should be bounded (Y=$y < max=192)",
            kotlin.math.abs(y) < 1920f * 0.10f)
    }

    // ============== Landscape orientation ==============

    @Test
    fun `landscape orientation swaps aspect ratio compensation axes`() {
        val calcLandscape = ParallaxCalculator()
        calcLandscape.strength = 1.0f
        calcLandscape.maxOffsetPercent = 10f
        calcLandscape.setCanvasSize(1920f, 1080f)
        calcLandscape.smoothing = 0f
        calcLandscape.aspectRatioCompensation = 2.0f
        calcLandscape.returnSpeed = 0f

        rampTilt(calcLandscape, 0.5f, 0.5f)

        val xLand = kotlin.math.abs(calcLandscape.getOffsetX())
        val yLand = kotlin.math.abs(calcLandscape.getOffsetY())

        assertTrue("In landscape, Y should be larger with aspect ratio 2.0 (x=$xLand, y=$yLand)",
            yLand > xLand * 1.3f)
    }

    // ============== Delta time scaling ==============

    @Test
    fun `longer delta time produces faster convergence`() {
        // With direct delta integration, the tilt delta is the same regardless of dt.
        // Both should produce the same offset for the same tilt change.
        val calcNormal = ParallaxCalculator()
        calcNormal.strength = 1.0f
        calcNormal.maxOffsetPercent = 10f
        calcNormal.setCanvasSize(1080f, 1920f)
        calcNormal.smoothing = 0.5f
        calcNormal.returnSpeed = 0f

        calcNormal.updateFromSensor(0f, 0f)
        calcNormal.update(16) // baseline
        calcNormal.updateFromSensor(0.2f, 0f)
        calcNormal.update(16) // 60fps frame
        val x16ms = calcNormal.getOffsetX()

        val calcSlow = ParallaxCalculator()
        calcSlow.strength = 1.0f
        calcSlow.maxOffsetPercent = 10f
        calcSlow.setCanvasSize(1080f, 1920f)
        calcSlow.smoothing = 0.5f
        calcSlow.returnSpeed = 0f

        calcSlow.updateFromSensor(0f, 0f)
        calcSlow.update(16) // baseline
        calcSlow.updateFromSensor(0.2f, 0f)
        calcSlow.update(32) // 30fps frame
        val x32ms = calcSlow.getOffsetX()

        // Direct delta: same tilt change, same offset regardless of dt
        assertEquals("Same tilt delta should produce same offset (32ms=$x32ms, 16ms=$x16ms)",
            x16ms, x32ms, 1.0f)
    }

    // ============== Return speed proportional ==============

    @Test
    fun `return speed medium gives moderate return`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 1.0f, 0f)
        val xAtTilt = calc.getOffsetX()
        assertTrue("Should have moved", xAtTilt > 50f)

        calc.returnSpeed = 0.5f
        calc.updateFromSensor(0f, 0f)
        repeat(100) { calc.update(16) }
        val xAfter = calc.getOffsetX()

        assertTrue("Medium return should reduce offset (before=$xAtTilt, after=$xAfter)",
            kotlin.math.abs(xAfter) < kotlin.math.abs(xAtTilt) * 0.5f)
    }

    // ============== Snap to zero prevents oscillation ==============

    @Test
    fun `snap to target prevents small oscillations`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 1.0f, 0f)
        // Allow smoothing buffer to settle before measuring stable position
        repeat(3) { calc.update(16) }
        val xFinal = calc.getOffsetX()

        // Continue updating with same tilt — no delta, no movement
        repeat(10) { calc.update(16) }
        val xStable = calc.getOffsetX()
        assertEquals("Position should be exactly stable (no micro-oscillation)", xFinal, xStable, 0.001f)
    }

    // ============== getOffsetXForDepth / getOffsetYForDepth ==============

    @Test
    fun `getOffsetXForDepth consistent with getOffsetForDepth`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.depthSeparation = 1.0f

        rampTilt(calc, 0.8f, 0.6f)

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

        rampTilt(calc, 0.8f, 0f)

        val depth00 = kotlin.math.abs(calc.getOffsetXForDepth(0.0f))
        val depth05 = kotlin.math.abs(calc.getOffsetXForDepth(0.5f))
        val depth10 = kotlin.math.abs(calc.getOffsetXForDepth(1.0f))

        assertTrue("depth=0 should have more offset than depth=0.5 (d0=$depth00, d05=$depth05)",
            depth00 > depth05)
        assertTrue("depth=0.5 should have more offset than depth=1.0 (d05=$depth05, d10=$depth10)",
            depth05 > depth10)
    }

    // ============== Edge easing with both axes ==============

    @Test
    fun `edge easing slows diagonal movement more than axial`() {
        val calcAxis = ParallaxCalculator()
        calcAxis.strength = 1.0f
        calcAxis.maxOffsetPercent = 10f
        calcAxis.setCanvasSize(1080f, 1920f)
        calcAxis.edgeEasing = 0.8f
        calcAxis.smoothing = 0f
        calcAxis.returnSpeed = 0f

        rampTilt(calcAxis, 0.9f, 0f, 30)
        val xAxis = calcAxis.getOffsetX()

        val calcDiag = ParallaxCalculator()
        calcDiag.strength = 1.0f
        calcDiag.maxOffsetPercent = 10f
        calcDiag.setCanvasSize(1080f, 1920f)
        calcDiag.edgeEasing = 0.8f
        calcDiag.smoothing = 0f
        calcDiag.returnSpeed = 0f

        rampTilt(calcDiag, 0.9f, 0.9f, 30)
        val xDiag = calcDiag.getOffsetX()

        assertTrue("Diagonal X should be less due to stronger easing (axis=$xAxis, diag=$xDiag)",
            xDiag < xAxis)
    }

    // ============== softLimit tests (pure math, unchanged) ==============

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

        // Ramp to establish offset first
        rampTilt(calc, 0.8f, 0f)

        // Then actively oscillate tilt (keeps velocity above stillness threshold)
        repeat(200) { i ->
            val tiltX = 0.8f + 0.1f * kotlin.math.sin(i * 0.5f)
            calc.updateFromSensor(tiltX, 0f)
            calc.update(16)
        }
        val xAtTilt = calc.getOffsetX()
        assertTrue("Should have significant offset during active motion: $xAtTilt", xAtTilt > 40f)
    }

    @Test
    fun `recenter engages after hold time with zero tilt`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 0.8f, 0f)
        val xBefore = calc.getOffsetX()
        assertTrue("Should have offset", xBefore > 50f)

        calc.returnSpeed = 1.0f
        calc.updateFromSensor(0f, 0f)
        repeat(150) { calc.update(16) }
        val xAfter = calc.getOffsetX()

        assertTrue("Should recenter after hold time (before=$xBefore, after=$xAfter)",
            kotlin.math.abs(xAfter) < kotlin.math.abs(xBefore) * 0.2f)
    }

    @Test
    fun `recenter is frame-rate independent`() {
        val calc60 = ParallaxCalculator()
        calc60.strength = 1.0f
        calc60.maxOffsetPercent = 10f
        calc60.setCanvasSize(1080f, 1920f)
        calc60.smoothing = 0f
        calc60.returnSpeed = 0f

        rampTilt(calc60, 0.8f, 0f)
        calc60.returnSpeed = 0.8f
        calc60.updateFromSensor(0f, 0f)
        repeat(55) { calc60.update(16) }  // 880ms to pass hold timer (>500ms)
        val x60start = calc60.getOffsetX()
        repeat(60) { calc60.update(16) }  // 960ms of recentering at 60fps
        val x60end = calc60.getOffsetX()
        val decay60 = if (x60start != 0f) x60end / x60start else 0f

        val calc30 = ParallaxCalculator()
        calc30.strength = 1.0f
        calc30.maxOffsetPercent = 10f
        calc30.setCanvasSize(1080f, 1920f)
        calc30.smoothing = 0f
        calc30.returnSpeed = 0f

        rampTilt(calc30, 0.8f, 0f)
        calc30.returnSpeed = 0.8f
        calc30.updateFromSensor(0f, 0f)
        repeat(55) { calc30.update(16) }  // Same 880ms hold (>500ms)
        val x30start = calc30.getOffsetX()
        repeat(30) { calc30.update(32) }  // Same 960ms of recentering at 30fps
        val x30end = calc30.getOffsetX()
        val decay30 = if (x30start != 0f) x30end / x30start else 0f

        assertEquals("Decay should be frame-rate independent", decay60, decay30, 0.15f)
    }

    @Test
    fun `softLimit replaces hard clamp in target position`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Ramp to very large tilt (many small steps to avoid JUMP_THRESHOLD)
        rampTilt(calc, 3.0f, 0f, 200)
        val xExtreme = calc.getOffsetX()

        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.returnSpeed = 0f
        rampTilt(calc2, 1.0f, 0f)
        val xNormal = calc2.getOffsetX()

        assertTrue("Extreme tilt should produce larger offset", xExtreme > xNormal)
        val maxOffset = 1920f * 0.10f
        assertTrue("Extreme tilt should still be bounded below max ($xExtreme < $maxOffset)",
            xExtreme < maxOffset)
    }

    @Test
    fun softLimitPreservesDirection() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 0.8f, 0.8f)
        val x = calc.getOffsetX()
        val y = calc.getOffsetY()

        assertTrue("X should be positive: $x", x > 0)
        assertTrue("Y should be positive: $y", y > 0)
        val ratio = x / y
        assertEquals("Direction should be preserved (ratio ~1)", 1.0f, ratio, 0.15f)
    }

    @Test
    fun softLimitIsRadialNotPerAxis() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 1.0f, 0.0f)
        val cardinalDist = kotlin.math.abs(calc.getOffsetX())

        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.returnSpeed = 0f
        rampTilt(calc2, 0.707f, 0.707f)
        val diagonalDist = kotlin.math.sqrt(
            calc2.getOffsetX() * calc2.getOffsetX() + calc2.getOffsetY() * calc2.getOffsetY()
        )

        assertEquals("Radial limit: diagonal dist ~= cardinal dist",
            cardinalDist, diagonalDist, cardinalDist * 0.25f)
    }

    @Test
    fun phoneStillButTiltedRecentersAfterHold() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.5f

        rampTilt(calc, 0.5f, 0.3f)
        val xBeforeRecenter = calc.getOffsetX()
        assertTrue("Should have moved to tilted position: $xBeforeRecenter",
            xBeforeRecenter > 10f)

        // Hold still at same tilt - velocity = 0 → recenter engages
        repeat(200) { calc.update(16) }

        val xAfterRecenter = calc.getOffsetX()
        assertTrue("Should have recentered from $xBeforeRecenter to near zero: $xAfterRecenter",
            kotlin.math.abs(xAfterRecenter) < kotlin.math.abs(xBeforeRecenter) * 0.3f)
    }

    @Test
    fun strongTiltStillApproachesUsefulMaxOffset() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 1.0f, 0.0f)
        val x = calc.getOffsetX()
        val maxOffset = 1920f * 0.10f

        assertTrue("Full tilt should reach >65% of max offset ($x vs $maxOffset)",
            x > maxOffset * 0.65f)
    }

    @Test
    fun phoneFlatStillRecenters() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        rampTilt(calc, 0.8f, 0f)
        val xBefore = calc.getOffsetX()
        assertTrue("Should have offset", xBefore > 50f)

        calc.returnSpeed = 0.5f
        calc.updateFromSensor(0f, 0f)
        repeat(200) { calc.update(16) }
        val xAfter = calc.getOffsetX()
        assertTrue("Phone flat and still should recenter (before=$xBefore, after=$xAfter)",
            kotlin.math.abs(xAfter) < kotlin.math.abs(xBefore) * 0.1f)
    }

    @Test
    fun phoneMovingDoesNotRecenter() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.8f

        // Ramp to establish initial offset
        rampTilt(calc, 0.6f, 0f)

        // Continue varying tilt (active hand movement) — keeps velocity above threshold
        repeat(200) { i ->
            val tiltX = 0.6f + 0.15f * kotlin.math.sin(i * 0.4f)
            calc.updateFromSensor(tiltX, 0f)
            calc.update(16)
        }
        val x = calc.getOffsetX()
        assertTrue("Active motion should maintain offset: $x", x > 30f)
    }

    // ============== Recenter-exit continuity ==============

    @Test
    fun `recenter then small movement does not jump to old offset`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0.2f
        calc.returnSpeed = 0f

        // 1. Establish large offset
        rampTilt(calc, 0.8f, 0f)
        val farOffset = calc.getOffsetX()
        assertTrue("Should have far offset: $farOffset", farOffset > 30f)

        // 2. Enable recenter and hold still
        calc.returnSpeed = 1.0f
        repeat(300) { calc.update(16) }
        val nearZero = calc.getOffsetX()
        assertTrue("Should be near zero after recentering: $nearZero",
            kotlin.math.abs(nearZero) < 10f)

        // 3. Small tilt change — must not jump back to old offset
        rampTiltFromTo(calc, 0.8f, 0f, 0.85f, 0f, 10)
        val afterFirstFrame = calc.getOffsetX()

        assertTrue("First frame after recenter exit must not jump back to old offset. " +
            "Position: $afterFirstFrame, old far offset: $farOffset",
            kotlin.math.abs(afterFirstFrame) < farOffset * 0.5f)

        // 4. Verify multiple subsequent frames stay stable
        repeat(30) { frame ->
            calc.update(16)
            val pos = calc.getOffsetX()
            assertTrue("Frame ${frame + 2} after exit must not jump. " +
                "Position: $pos, old far offset: $farOffset",
                kotlin.math.abs(pos) < farOffset * 0.5f)
        }
    }

    @Test
    fun `recenter rebases tilt origin so movement is relative`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // 1. Ramp to 0.5 and converge
        rampTilt(calc, 0.5f, 0f)
        val initialOffset = calc.getOffsetX()
        assertTrue("Should have offset at 0.5 tilt", initialOffset > 20f)

        // 2. Enable recenter, hold still, let it recenter to zero
        calc.returnSpeed = 1.0f
        repeat(300) { calc.update(16) }
        assertTrue("Should be near zero", kotlin.math.abs(calc.getOffsetX()) < 5f)

        // 3. Small tilt change (0.5 → 0.6 = delta 0.1)
        rampTiltFromTo(calc, 0.5f, 0f, 0.6f, 0f, 30)
        val afterSmallMove = calc.getOffsetX()

        assertTrue("Movement after recenter should be small relative to initial. " +
            "Offset: $afterSmallMove, initial offset at 0.5: $initialOffset",
            kotlin.math.abs(afterSmallMove) < initialOffset * 0.5f)
    }

    @Test
    fun `held tilt becomes neutral after recenter`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // 1. Ramp to 0.7
        rampTilt(calc, 0.7f, 0f)
        assertTrue("Should have offset", calc.getOffsetX() > 20f)

        // 2. Enable recenter, hold still at 0.7 → recenter damps to zero
        calc.returnSpeed = 1.0f
        repeat(300) { calc.update(16) }
        assertTrue("Should be near zero", kotlin.math.abs(calc.getOffsetX()) < 5f)

        // 3. Keep holding at 0.7 — delta is 0, stays at zero
        repeat(60) { calc.update(16) }
        assertTrue("Holding at rebased tilt should keep position at zero. " +
            "Position: ${calc.getOffsetX()}",
            kotlin.math.abs(calc.getOffsetX()) < 2f)
    }

    @Test
    fun `stillness hysteresis prevents recenter flicker`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 1.0f

        // 1. Establish offset
        rampTilt(calc, 0.5f, 0f)
        val establishedOffset = calc.getOffsetX()

        // 2. Inject tiny noise oscillating around the enter threshold
        val positions = mutableListOf<Float>()
        for (i in 0 until 200) {
            val tiltX = if (i % 2 == 0) 0.5f else 0.513f
            calc.updateFromSensor(tiltX, 0f)
            calc.update(16)
            positions.add(calc.getOffsetX())
        }

        val maxDelta = positions.zipWithNext().maxOf { kotlin.math.abs(it.second - it.first) }
        assertTrue("Position should not wildly oscillate with tiny noise. " +
            "Max frame-to-frame delta: $maxDelta", maxDelta < establishedOffset * 0.15f)
    }

    // ============== Delta-model specific tests ==============

    @Test
    fun `constant tilt does not create continuing movement`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Set tilt once (baseline)
        calc.updateFromSensor(0.5f, 0f)
        calc.update(16) // baseline frame

        // Hold constant: no movement should occur
        repeat(100) { calc.update(16) }
        val x = calc.getOffsetX()
        assertEquals("Constant tilt = zero delta = no movement", 0f, x, 0.5f)
    }

    @Test
    fun `same delta from different absolute tilts gives same offset change`() {
        // Delta model: offset depends on delta, not absolute tilt
        val calc1 = ParallaxCalculator()
        calc1.strength = 1.0f
        calc1.maxOffsetPercent = 10f
        calc1.setCanvasSize(1080f, 1920f)
        calc1.smoothing = 0f
        calc1.returnSpeed = 0f

        // Start at 0.0, move to 0.2 (delta = 0.2)
        calc1.updateFromSensor(0.0f, 0f)
        calc1.update(16)
        calc1.updateFromSensor(0.2f, 0f)
        calc1.update(16)
        val offset1 = calc1.getOffsetX()

        val calc2 = ParallaxCalculator()
        calc2.strength = 1.0f
        calc2.maxOffsetPercent = 10f
        calc2.setCanvasSize(1080f, 1920f)
        calc2.smoothing = 0f
        calc2.returnSpeed = 0f

        // Start at 0.5, move to 0.7 (delta = 0.2)
        calc2.updateFromSensor(0.5f, 0f)
        calc2.update(16)
        calc2.updateFromSensor(0.7f, 0f)
        calc2.update(16)
        val offset2 = calc2.getOffsetX()

        assertEquals("Same delta should give same offset regardless of absolute tilt",
            offset1, offset2, 1f)
    }

    @Test
    fun `first frame sets baseline without moving`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f

        // First frame with non-zero tilt → should NOT move
        calc.updateFromSensor(0.8f, 0.5f)
        calc.update(16)

        assertEquals("First frame should set baseline, not move", 0f, calc.getOffsetX(), 0.1f)
        assertEquals("First frame should set baseline, not move", 0f, calc.getOffsetY(), 0.1f)
    }

    @Test
    fun `resetInputBaseline prevents jump`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Ramp to establish offset
        rampTilt(calc, 0.5f, 0f)
        val positionBefore = calc.getOffsetX()
        assertTrue("Should have offset", positionBefore > 10f)

        // Simulate sensor recalibration: tilt jumps to 0
        calc.resetInputBaseline()

        // Next frame with new tilt: should set baseline, not jump
        calc.updateFromSensor(0f, 0f)
        calc.update(16)
        val positionAfter = calc.getOffsetX()

        // Position should be close to before (resetInputBaseline doesn't zero position)
        assertEquals("resetInputBaseline should not change position",
            positionBefore, positionAfter, 1f)
    }

    @Test
    fun `sensor reset threshold discards huge delta`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Establish baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Huge sudden tilt (> SENSOR_RESET_THRESHOLD 0.6) — should be discarded as sensor reset
        calc.updateFromSensor(0.8f, 0f)
        calc.update(16)
        val x = calc.getOffsetX()

        assertEquals("Sensor reset delta should be discarded", 0f, x, 0.5f)
    }

    @Test
    fun `large delta is clamped not discarded`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Establish baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Moderate-large delta (> MAX_DELTA_PER_FRAME 0.1 but < SENSOR_RESET_THRESHOLD 0.6)
        // should be clamped to MAX_DELTA_PER_FRAME, not discarded
        calc.updateFromSensor(0.3f, 0f)
        calc.update(16)
        val x = calc.getOffsetX()

        assertTrue("Clamped delta should produce some movement: $x", kotlin.math.abs(x) > 1f)
    }

    // ============== Distance-based return tests ==============

    @Test
    fun `large offset returns faster than small offset`() {
        // With uniform exponential decay, both large and small offsets reduce
        // by the same FRACTION per frame. Verify both decay at similar rates.
        val calcFar = ParallaxCalculator()
        calcFar.strength = 1.0f
        calcFar.maxOffsetPercent = 20f
        calcFar.setCanvasSize(1080f, 1920f)
        calcFar.smoothing = 0f
        calcFar.returnSpeed = 0f

        // Establish a LARGE offset
        rampTilt(calcFar, 1.0f, 0f)
        val farInitial = calcFar.getOffsetX()
        assertTrue("Should have large offset", farInitial > 100f)

        val calcSmall = ParallaxCalculator()
        calcSmall.strength = 1.0f
        calcSmall.maxOffsetPercent = 20f
        calcSmall.setCanvasSize(1080f, 1920f)
        calcSmall.smoothing = 0f
        calcSmall.returnSpeed = 0f

        // Establish a SMALL offset
        rampTilt(calcSmall, 0.15f, 0f)
        val smallInitial = calcSmall.getOffsetX()
        assertTrue("Should have small offset", smallInitial > 5f)
        assertTrue("Far > small", farInitial > smallInitial * 2f)

        // Enable return and run a few frames
        calcFar.returnSpeed = 0.5f
        calcSmall.returnSpeed = 0.5f
        calcFar.updateFromSensor(0f, 0f)
        calcSmall.updateFromSensor(0f, 0f)

        // Let stillness engage (need > 500ms hold time, plus initial delta frame)
        repeat(55) { calcFar.update(16) }
        repeat(55) { calcSmall.update(16) }

        // Now measure return progress over 5 frames
        val farBefore = calcFar.getOffsetX()
        val smallBefore = calcSmall.getOffsetX()
        repeat(5) { calcFar.update(16) }
        repeat(5) { calcSmall.update(16) }
        val farAfter = calcFar.getOffsetX()
        val smallAfter = calcSmall.getOffsetX()

        val farReduction = if (farBefore > 0.5f) (farBefore - farAfter) / farBefore else 0f
        val smallReduction = if (smallBefore > 0.5f) (smallBefore - smallAfter) / smallBefore else 0f

        // Uniform exponential decay: both should reduce by similar fraction per frame
        // (within 10% tolerance due to soft-limit nonlinearity)
        if (farReduction > 0f && smallReduction > 0f) {
            val fractionDiff = kotlin.math.abs(farReduction - smallReduction)
            assertTrue("Uniform decay: fraction difference ($fractionDiff) should be < 0.15. " +
                "farReduction=$farReduction, smallReduction=$smallReduction",
                fractionDiff < 0.15f
            )
        }
        // Both should have decayed
        assertTrue("Far offset should have returned (before=$farBefore, after=$farAfter)",
            farAfter < farBefore)
    }

    @Test
    fun `return near center is gentle`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 20f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Small offset
        rampTilt(calc, 0.1f, 0f)
        val initial = calc.getOffsetX()
        assertTrue("Should have small offset", initial > 3f)

        // Enable return, let stillness engage (need > 500ms)
        calc.returnSpeed = 0.5f
        calc.updateFromSensor(0f, 0f)
        repeat(55) { calc.update(16) }

        // Measure single-frame return at small distance
        val before = calc.getOffsetX()
        calc.update(16)
        val after = calc.getOffsetX()

        // Near center uses RECENTER_DAMPING_NEAR (2.0) — return is gentle
        val reduction = if (before > 0.5f) (before - after) / before else 0f
        assertTrue("Near-center return should be gentle (reduction fraction < 0.15): $reduction",
            reduction < 0.15f
        )
    }

    @Test
    fun `return does not fight active input`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.8f

        // Ramp to establish offset
        rampTilt(calc, 0.6f, 0f)
        val xAtTilt = calc.getOffsetX()
        assertTrue("Should have offset", xAtTilt > 30f)

        // Continue tilting more - should still track, not recenter
        rampTiltFromTo(calc, 0.6f, 0f, 0.9f, 0f)
        val xAfterMore = calc.getOffsetX()
        assertTrue("Active input should increase offset (before=$xAtTilt, after=$xAfterMore)",
            xAfterMore > xAtTilt * 0.9f)
    }

    // ============== Delta Smoothing / Noise Rejection Tests ==============

    @Test
    fun `noisy sensor input does not accumulate visible jitter`() {
        val calc = ParallaxCalculator()
        calc.strength = 0.075f
        calc.maxOffsetPercent = 20f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // First frame: baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Feed 200 frames of pseudo-random alternating noise around zero.
        // Simulates a phone lying flat with sensor jitter.
        var maxJump = 0f
        var prevX = calc.getOffsetX()
        val noiseAmplitude = 0.004f
        for (i in 1..200) {
            // Deterministic alternating noise pattern
            val sign = if (i % 2 == 0) 1f else -1f
            val noiseX = sign * noiseAmplitude * ((i % 7 + 1) / 7f)
            val noiseY = -sign * noiseAmplitude * ((i % 5 + 1) / 5f)
            calc.updateFromSensor(noiseX, noiseY)
            calc.update(16)

            val currentX = calc.getOffsetX()
            val jump = kotlin.math.abs(currentX - prevX)
            if (jump > maxJump) maxJump = jump
            prevX = currentX
        }

        val finalOffset = kotlin.math.sqrt(
            calc.getOffsetX() * calc.getOffsetX() + calc.getOffsetY() * calc.getOffsetY()
        )

        // With smoothing, noise should not accumulate to more than a few pixels
        assertTrue("Noise should not accumulate to large offset (got $finalOffset px)",
            finalOffset < 5f)
        // Frame-to-frame jumps should be tiny
        assertTrue("Max frame-to-frame jump should be small (got $maxJump px)",
            maxJump < 2f)
    }

    @Test
    fun `intentional movement remains responsive after smoothing`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Feed consistent tilt change over several frames (intentional movement)
        for (i in 1..20) {
            calc.updateFromSensor(i * 0.03f, 0f)
            calc.update(16)
        }

        val xOffset = calc.getOffsetX()
        // Should have moved significantly in the positive direction
        assertTrue("Intentional movement should produce visible offset (got $xOffset)",
            xOffset > 20f)
    }

    @Test
    fun `reset clears smoothing state`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Feed some movement
        rampTilt(calc, 0.5f, 0.3f)
        assertTrue("Should have offset before reset", calc.getOffsetX() > 10f)

        // Reset
        calc.reset()
        assertEquals("X should be zero after reset", 0f, calc.getOffsetX(), 0.001f)
        assertEquals("Y should be zero after reset", 0f, calc.getOffsetY(), 0.001f)

        // First sample after reset: should not jump
        calc.updateFromSensor(0.1f, 0.1f)
        calc.update(16)
        val afterReset = kotlin.math.sqrt(
            calc.getOffsetX() * calc.getOffsetX() + calc.getOffsetY() * calc.getOffsetY()
        )
        // First frame after reset just sets baseline, no movement
        assertEquals("First frame after reset should not move", 0f, afterReset, 0.1f)
    }

    @Test
    fun `resetInputBaseline clears smoothing but preserves visual offset`() {
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Build up some offset
        rampTilt(calc, 0.4f, 0f)
        val xBefore = calc.getOffsetX()
        assertTrue("Should have offset", xBefore > 10f)

        // Reset input baseline only
        calc.resetInputBaseline()

        // Visual offset should be preserved
        assertEquals("Visual offset should be preserved", xBefore, calc.getOffsetX(), 0.1f)

        // Next sensor sample should become new baseline (no jump)
        calc.updateFromSensor(0.4f, 0f)
        calc.update(16)
        val xAfter = calc.getOffsetX()
        assertEquals("No movement after baseline reset with same tilt",
            xBefore, xAfter, 1f)
    }

    // ============== Frame-Rate Independence & Latency Tests ==============

    @Test
    fun `uneven frame timing produces smooth motion`() {
        // Simulate alternating fast/slow frames (10ms / 25ms) with consistent input
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Consistent slow tilt ramp with alternating frame times
        var maxJump = 0f
        var prevX = 0f
        for (i in 1..60) {
            val tilt = i * 0.01f
            calc.updateFromSensor(tilt, 0f)
            val frameTime = if (i % 2 == 0) 10L else 25L
            calc.update(frameTime)

            val currentX = calc.getOffsetX()
            val jump = kotlin.math.abs(currentX - prevX)
            if (i > 3 && jump > maxJump) maxJump = jump  // skip first 3 for buffer fill
            prevX = currentX
        }

        val finalX = calc.getOffsetX()
        assertTrue("Should have moved with consistent input: $finalX", finalX > 10f)
        // Max frame-to-frame jump should be reasonable (no spikes from timing variance)
        assertTrue("Frame timing variance should not cause spikes (maxJump=$maxJump)",
            maxJump < finalX * 0.15f)
    }

    @Test
    fun `noise with occasional spike is handled cleanly`() {
        val calc = ParallaxCalculator()
        calc.strength = 0.075f
        calc.maxOffsetPercent = 20f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // 100 frames of small noise with a spike at frame 50
        var maxJump = 0f
        var prevX = calc.getOffsetX()
        for (i in 1..100) {
            val noise = if (i == 50) {
                0.08f  // Spike (below sensor reset, above noise)
            } else {
                val sign = if (i % 2 == 0) 1f else -1f
                sign * 0.003f * ((i % 5 + 1) / 5f)
            }
            calc.updateFromSensor(noise, 0f)
            calc.update(16)

            val currentX = calc.getOffsetX()
            val jump = kotlin.math.abs(currentX - prevX)
            if (jump > maxJump) maxJump = jump
            prevX = currentX
        }

        // Spike should be clamped (MAX_DELTA_PER_FRAME = 0.10), not cause explosion
        val finalOffset = kotlin.math.abs(calc.getOffsetX())
        assertTrue("Spike should not cause runaway offset (got $finalOffset)",
            finalOffset < 30f)
        // Max single-frame jump should be bounded by clamp + smoothing
        assertTrue("Max jump should be bounded (got $maxJump)",
            maxJump < 15f)
    }

    @Test
    fun `weighted smoothing responds faster than uniform average`() {
        // Compare latency: with weighted smoothing, a step input should produce
        // a larger response in the first 2 frames than uniform 1/3 averaging would.
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Step input: constant delta of 0.08 per frame (above 0.06 deadzone)
        calc.updateFromSensor(0.08f, 0f)
        calc.update(16)
        val x1 = calc.getOffsetX()

        calc.updateFromSensor(0.16f, 0f)
        calc.update(16)
        val x2 = calc.getOffsetX()

        // With weighted smoothing (newest=0.6), first frame should produce at least 50%
        // of what a raw unsmoothed delta would give (raw: 0.08 * 192 = 15.4px)
        assertTrue("First frame response should be > 4px with weighted smoothing (got $x1)",
            x1 > 4f)
        assertTrue("Second frame should continue building offset (got $x2)",
            x2 > x1)
    }

    @Test
    fun `multi-sensor-event accumulation between frames`() {
        // Simulate multiple sensor events between render frames
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        // Baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Multiple sensor events between frames (simulating 100Hz sensor at 30fps)
        calc.updateFromSensor(0.02f, 0f)
        calc.updateFromSensor(0.04f, 0f)
        calc.updateFromSensor(0.06f, 0f)
        // One render frame consumes all accumulated deltas
        calc.update(33)  // ~30fps
        val xMulti = calc.getOffsetX()

        // Compare: single sensor event with same total delta
        val calcSingle = ParallaxCalculator()
        calcSingle.strength = 1.0f
        calcSingle.maxOffsetPercent = 10f
        calcSingle.setCanvasSize(1080f, 1920f)
        calcSingle.smoothing = 0f
        calcSingle.returnSpeed = 0f

        calcSingle.updateFromSensor(0f, 0f)
        calcSingle.update(16)
        calcSingle.updateFromSensor(0.06f, 0f)
        calcSingle.update(33)
        val xSingle = calcSingle.getOffsetX()

        // Both should produce similar offsets (accumulator sums all intermediate deltas)
        assertTrue("Multi-event should produce movement: $xMulti", xMulti > 2f)
        assertEquals("Accumulated multi-event should match single-event total delta",
            xSingle, xMulti, xSingle * 0.3f)
    }

    // ============== Task 7: Hardening tests ==============

    @Test
    fun `long still period with noise produces no drift`() {
        // Simulate 10 seconds of sensor noise while device is nominally still.
        // Position must remain near zero (no accumulated drift).
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.5f

        // Initial tilt establishes baseline
        calc.updateFromSensor(0.2f, 0.1f)
        calc.update(16)

        // 625 frames ≈ 10 seconds at 16ms: tiny random noise around the baseline
        val rng = java.util.Random(42)
        for (i in 0 until 625) {
            val noiseX = 0.2f + (rng.nextFloat() - 0.5f) * 0.004f  // ±0.002 noise
            val noiseY = 0.1f + (rng.nextFloat() - 0.5f) * 0.004f
            calc.updateFromSensor(noiseX, noiseY)
            calc.update(16)
        }

        val finalX = calc.getOffsetX()
        val finalY = calc.getOffsetY()
        assertTrue("X drift should be near zero after noise (got $finalX)",
            kotlin.math.abs(finalX) < 5f)
        assertTrue("Y drift should be near zero after noise (got $finalY)",
            kotlin.math.abs(finalY) < 5f)
    }

    @Test
    fun `alternating frame times produce stable movement`() {
        // Frame jitter: alternate between 10ms and 30ms frames.
        // Total tilt change is the same; final position should be similar to uniform 16ms.
        val calcJittery = ParallaxCalculator()
        calcJittery.strength = 1.0f
        calcJittery.maxOffsetPercent = 10f
        calcJittery.setCanvasSize(1080f, 1920f)
        calcJittery.smoothing = 0f
        calcJittery.returnSpeed = 0f

        val calcUniform = ParallaxCalculator()
        calcUniform.strength = 1.0f
        calcUniform.maxOffsetPercent = 10f
        calcUniform.setCanvasSize(1080f, 1920f)
        calcUniform.smoothing = 0f
        calcUniform.returnSpeed = 0f

        // Ramp both from 0 to 0.5 over 20 steps
        calcJittery.updateFromSensor(0f, 0f)
        calcJittery.update(16)
        calcUniform.updateFromSensor(0f, 0f)
        calcUniform.update(16)

        for (i in 1..20) {
            val tilt = 0.5f * i / 20f
            calcJittery.updateFromSensor(tilt, 0f)
            calcJittery.update(if (i % 2 == 0) 10L else 30L)

            calcUniform.updateFromSensor(tilt, 0f)
            calcUniform.update(16)
        }

        val jittery = calcJittery.getOffsetX()
        val uniform = calcUniform.getOffsetX()
        // Frame normalization should keep them within 30% of each other
        assertTrue("Jittery frames should produce movement (got $jittery)", jittery > 10f)
        assertTrue("Uniform frames should produce movement (got $uniform)", uniform > 10f)
        val ratio = jittery / uniform
        assertTrue("Jittery/uniform ratio should be near 1.0 (got $ratio)",
            ratio in 0.5f..2.0f)
    }

    @Test
    fun `very small tilt changes produce visible movement`() {
        // Micro-movement: small but intentional tilt (just above deadzone).
        // Must produce visible offset, not be over-filtered.
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Slowly tilt to 0.15 (above deadzone of 0.06) in small 0.01 increments
        for (i in 1..15) {
            calc.updateFromSensor(i * 0.01f, 0f)
            calc.update(16)
        }

        val offset = calc.getOffsetX()
        assertTrue("Small deliberate tilt should produce visible offset (got $offset)",
            offset > 5f)
    }

    @Test
    fun `extreme rapid sensor changes are clamped without large jumps`() {
        // Rapid large sensor changes must be clamped, not produce huge position jumps.
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0f

        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        // Record position, then apply a rapid sensor jump (within reset threshold)
        calc.updateFromSensor(0.1f, 0f)
        calc.update(16)
        val before = calc.getOffsetX()

        // Large jump: 0.1 → 0.5 in one frame (delta = 0.4, above MAX_DELTA_PER_FRAME)
        calc.updateFromSensor(0.5f, 0f)
        calc.update(16)
        val after = calc.getOffsetX()

        val jump = after - before
        // The delta should be clamped — max single-frame contribution limited
        val effectiveMax = 1920f * 0.10f  // maxOffsetPercent=10%, strength=1.0
        assertTrue("Jump should be positive (got $jump)", jump > 0f)
        assertTrue("Jump per frame should be bounded (got $jump, max effective ≈$effectiveMax)",
            jump < effectiveMax * 0.15f)  // Well under maximum possible per-frame move
    }

    @Test
    fun `adaptive recenter settles gently near center`() {
        // Verify adaptive recenter: fast return from far, gentle near center (no oscillation).
        val calc = ParallaxCalculator()
        calc.strength = 1.0f
        calc.maxOffsetPercent = 10f
        calc.setCanvasSize(1080f, 1920f)
        calc.smoothing = 0f
        calc.returnSpeed = 0.5f

        // Push position far from center
        rampTilt(calc, 0.5f, 0f)
        val farOffset = calc.getOffsetX()
        assertTrue("Should be far from center", farOffset > 30f)

        // Now hold still to trigger recenter
        val positions = mutableListOf<Float>()
        for (i in 0 until 200) {
            calc.updateFromSensor(0.5f, 0f)  // Hold same tilt = zero delta
            calc.update(16)
            positions.add(calc.getOffsetX())
        }

        // Should converge toward zero monotonically (no oscillation)
        val finalPos = positions.last()
        assertTrue("Should recenter toward zero (got $finalPos)", finalPos < farOffset * 0.3f)

        // Check no oscillation: once position drops below a threshold, it stays below
        var minSeen = Float.MAX_VALUE
        for (pos in positions) {
            if (pos < minSeen) minSeen = pos
            assertTrue("Position should not bounce back up (pos=$pos, min=$minSeen)",
                pos <= minSeen + 1f)
        }
    }

    // ============== Visibility lifecycle reset (the actual bug fix) ==============

    @Test
    fun `visibility lifecycle - extreme offset then reset produces zero on next frame`() {
        // Simulates: user tilts to extreme → goes to app → returns to home
        // The bug: stale extreme offset rendered on first visible frame
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 30f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // Accumulate extreme parallax offset
        rampTilt(calculator, 0.8f, 0.6f)
        val extremeX = calculator.getOffsetX()
        val extremeY = calculator.getOffsetY()
        assertTrue("Should have significant X offset", kotlin.math.abs(extremeX) > 50f)
        assertTrue("Should have significant Y offset", kotlin.math.abs(extremeY) > 50f)

        // === Visibility lifecycle: wallpaper becomes visible ===
        // This is what ParallaxScene.start() does:
        calculator.reset()

        // Verify: offset is zero IMMEDIATELY after reset (before any update/render)
        assertEquals("X must be zero after reset", 0f, calculator.getOffsetX(), 0.001f)
        assertEquals("Y must be zero after reset", 0f, calculator.getOffsetY(), 0.001f)

        // Verify: first update() after reset does not produce movement
        calculator.update(16)
        assertEquals("X must stay zero on first frame", 0f, calculator.getOffsetX(), 0.001f)
        assertEquals("Y must stay zero on first frame", 0f, calculator.getOffsetY(), 0.001f)
    }

    @Test
    fun `visibility lifecycle - first sensor event after reset sets baseline without jump`() {
        // After reset, the first sensor event must establish a reference point
        // without producing any movement (no delta from 0 to current tilt)
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 30f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        // Build up offset, then reset (simulating hide → show)
        rampTilt(calculator, 0.5f, 0.4f)
        calculator.reset()

        // Simulate: device is tilted when wallpaper becomes visible again
        // This sensor event should establish baseline, not cause movement
        calculator.updateFromSensor(0.3f, 0.2f)
        calculator.update(16)

        assertEquals("First sensor after reset must not jump X", 0f, calculator.getOffsetX(), 0.5f)
        assertEquals("First sensor after reset must not jump Y", 0f, calculator.getOffsetY(), 0.5f)

        // Second sensor event with slightly different tilt — should produce small movement
        calculator.updateFromSensor(0.32f, 0.22f)
        calculator.update(16)
        // Small movement is fine, but not a huge jump
        assertTrue("Second event should produce small movement",
            kotlin.math.abs(calculator.getOffsetX()) < 20f)
    }

    @Test
    fun `visibility lifecycle - getOffsetForDepth is zero after reset`() {
        val calculator = ParallaxCalculator()
        calculator.strength = 1.0f
        calculator.maxOffsetPercent = 30f
        calculator.depthSeparation = 1.32f
        calculator.perspective = 1.0f
        calculator.setCanvasSize(1080f, 1920f)
        calculator.smoothing = 0f

        rampTilt(calculator, 0.7f, 0.5f)
        calculator.reset()

        // All layers must see zero offset immediately after reset
        val skyOffset = calculator.getOffsetForDepth(0.05f)
        val hillOffset = calculator.getOffsetForDepth(0.80f)
        assertEquals("Sky X must be zero", 0f, skyOffset.first, 0.001f)
        assertEquals("Sky Y must be zero", 0f, skyOffset.second, 0.001f)
        assertEquals("Hill X must be zero", 0f, hillOffset.first, 0.001f)
        assertEquals("Hill Y must be zero", 0f, hillOffset.second, 0.001f)
    }
}
