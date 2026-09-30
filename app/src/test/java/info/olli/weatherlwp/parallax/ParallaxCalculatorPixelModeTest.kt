package info.olli.weatherlwp.parallax

import info.olli.weatherlwp.testutil.rampTilt
import info.olli.weatherlwp.testutil.rampTiltFromTo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ParallaxCalculator in legacy pixel mode (maxOffsetPercent = 0).
 * Tests parallax physics: strength, max offset, delta-based movement, soft limit.
 *
 * Key: Tests must ramp tilt gradually (not set abruptly) because the delta
 * model computes per-frame changes, clamps large deltas, and discards sensor resets.
 */
class ParallaxCalculatorPixelModeTest {

    private lateinit var calculator: ParallaxCalculator

    @Before
    fun setUp() {
        calculator = ParallaxCalculator()
        calculator.setCanvasSize(1080f, 1920f)
        calculator.maxOffsetPercent = 0f  // Use legacy pixel mode for these tests
        calculator.smoothing = 0f         // No smoothing for deterministic tests
    }

    private fun rampTiltFromZero(toX: Float, toY: Float, steps: Int = -1) =
        rampTilt(calculator, toX, toY, steps)

    // P1: Test position follows tilt and respects maxOffset
    @Test
    fun testPositionFollowsTiltWithinMaxOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(1.0f, 0f)

        val offset = calculator.getOffsetX()
        assertTrue("X offset should be positive and bounded: $offset", offset > 20f && offset <= 40f)
    }

    // P2: Test max offset is respected with high strength
    @Test
    fun testMaxOffsetIsRespectedWithHighStrength() {
        calculator.strength = 2.0f
        calculator.maxOffsetPx = 50f

        rampTiltFromZero(1.0f, 1.0f)

        val offset = calculator.getOffset()
        val magnitude = kotlin.math.sqrt(offset.first * offset.first + offset.second * offset.second)
        assertTrue("Combined magnitude should be bounded by soft limit: $magnitude", magnitude <= 75f)
    }

    // P3: Test vertical movement works
    @Test
    fun testVerticalParallaxWorks() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(0f, 1.0f)

        assertTrue("Y offset should be positive", calculator.getOffsetY() > 10f)
        assertEquals("X offset should be minimal", 0f, calculator.getOffsetX(), 1f)
    }

    // P4: Test position stays when tilt is held constant (delta = 0)
    @Test
    fun testPositionStaysWithConstantTilt() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(0.5f, 0f)
        // Allow smoothing buffer to settle (3 frames of zero delta)
        repeat(3) { calculator.update(16) }
        val initialOffset = calculator.getOffsetX()

        // Hold same tilt (delta = 0 each frame, no return speed)
        repeat(50) { calculator.update(16) }
        val laterOffset = calculator.getOffsetX()

        assertEquals("Position should remain stable", initialOffset, laterOffset, 0.5f)
    }

    // P4b: Radial clamping consistency
    @Test
    fun testCircularClampingConsistency() {
        calculator.strength = 2.0f
        calculator.maxOffsetPx = 50f

        rampTiltFromZero(1.0f, 1.0f)
        val diagonalDist = kotlin.math.sqrt(
            calculator.getOffsetX().let { it * it } + calculator.getOffsetY().let { it * it }
        )

        calculator.reset()

        rampTiltFromZero(1.0f, 0f)
        val pureXOffset = kotlin.math.abs(calculator.getOffsetX())

        assertEquals("Diagonal distance ≈ cardinal distance (radial limit)",
            pureXOffset, diagonalDist, pureXOffset * 0.3f)
    }

    // P5: Test returning to center when tilt goes to zero
    @Test
    fun testReturnsToZeroWhenTiltRemoved() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        calculator.returnSpeed = 1.0f

        rampTiltFromZero(1.0f, 0f)
        assertTrue("Should have moved", calculator.getOffsetX() > 10f)

        // Ramp back to zero, then hold for recenter
        rampTiltFromTo(calculator, 1.0f, 0f, 0f, 0f, 20)
        repeat(200) { calculator.update(16) }

        assertTrue("Should return near center", kotlin.math.abs(calculator.getOffsetX()) < 5f)
    }

    @Test
    fun testResetClearsOffset() {
        rampTiltFromZero(1.0f, 1.0f, 20)
        assertTrue("Should have offset before reset", calculator.getOffsetX() > 0f)

        calculator.reset()
        assertEquals(0f, calculator.getOffsetX(), 0.001f)
        assertEquals(0f, calculator.getOffsetY(), 0.001f)
    }

    @Test
    fun testDepthBasedOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.depthSeparation = 1.0f
        calculator.perspective = 0f

        rampTiltFromZero(1.0f, 0f)

        val baseOffset = calculator.getOffsetX()
        val halfDepthOffset = calculator.getOffsetXForDepth(0.5f)
        val fullDepthOffset = calculator.getOffsetXForDepth(1.0f)

        assertEquals("Half depth", baseOffset * 0.5f, halfDepthOffset, 3f)
        assertEquals("Full depth", baseOffset, fullDepthOffset, 3f)
    }

    @Test
    fun testGetOffsetForDepthReturnsPair() {
        calculator.depthSeparation = 1.0f
        rampTiltFromZero(0.5f, 0.8f)

        val offset = calculator.getOffsetForDepth(0.5f)
        assertEquals(calculator.getOffsetX() * 0.5f, offset.first, 1f)
        assertEquals(calculator.getOffsetY() * 0.5f, offset.second, 1f)
    }

    @Test
    fun testStrengthAffectsOffset() {
        calculator.maxOffsetPx = 40f

        calculator.strength = 0.5f
        rampTiltFromZero(1.0f, 0f)
        val lowStrengthOffset = calculator.getOffsetX()

        calculator.reset()

        calculator.strength = 1.5f
        rampTiltFromZero(1.0f, 0f)
        val highStrengthOffset = calculator.getOffsetX()

        assertTrue("Higher strength → larger offset", highStrengthOffset > lowStrengthOffset)
    }

    @Test
    fun testAllCardinalDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        // Right
        rampTiltFromZero(1.0f, 0f)
        assertTrue("Right +X", calculator.getOffsetX() > 20f)
        assertEquals("Right Y≈0", 0f, calculator.getOffsetY(), 1f)

        calculator.reset()
        rampTiltFromZero(-1.0f, 0f)
        assertTrue("Left -X", calculator.getOffsetX() < -20f)

        calculator.reset()
        rampTiltFromZero(0f, 1.0f)
        assertTrue("Down +Y", calculator.getOffsetY() > 20f)

        calculator.reset()
        rampTiltFromZero(0f, -1.0f)
        assertTrue("Up -Y", calculator.getOffsetY() < -20f)
    }

    @Test
    fun testDiagonalDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(0.7f, -0.7f)
        assertTrue("Top-right +X", calculator.getOffsetX() > 10f)
        assertTrue("Top-right -Y", calculator.getOffsetY() < -10f)

        calculator.reset()

        rampTiltFromZero(-0.7f, 0.7f)
        assertTrue("Bottom-left -X", calculator.getOffsetX() < -10f)
        assertTrue("Bottom-left +Y", calculator.getOffsetY() > 10f)
    }

    @Test
    fun testSmoothTransitionBetweenDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(1.0f, 0f)
        val rightOffset = calculator.getOffsetX()
        assertTrue("Should be right", rightOffset > 20f)

        // Ramp from right to left
        rampTiltFromTo(calculator, 1.0f, 0f, -1.0f, 0f, 100)
        val leftOffset = calculator.getOffsetX()
        assertTrue("Should reach left side: $leftOffset", leftOffset < -20f)
    }

    @Test
    fun testZeroTiltReturnsToCenter() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        calculator.returnSpeed = 1.0f

        val testDirections = listOf(
            Pair(1.0f, 0f), Pair(-1.0f, 0f), Pair(0f, 1.0f), Pair(0f, -1.0f)
        )

        for (direction in testDirections) {
            rampTiltFromZero(direction.first, direction.second)
            val mag = kotlin.math.sqrt(
                calculator.getOffsetX().let { it * it } + calculator.getOffsetY().let { it * it }
            )
            assertTrue("Should have moved for $direction: $mag", mag > 5f)

            // Ramp back to zero then hold for recenter
            rampTiltFromTo(calculator, direction.first, direction.second, 0f, 0f, 20)
            repeat(200) { calculator.update(16) }

            assertEquals("X near center", 0f, calculator.getOffsetX(), 5f)
            assertEquals("Y near center", 0f, calculator.getOffsetY(), 5f)
        }
    }

    @Test
    fun testPartialTiltProducesProportionalOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f

        rampTiltFromZero(1.0f, 0f)
        val fullTiltOffset = calculator.getOffsetX()

        calculator.reset()

        rampTiltFromZero(0.5f, 0f)
        val halfTiltOffset = calculator.getOffsetX()

        assertTrue("Half tilt < full tilt", halfTiltOffset < fullTiltOffset)
        assertTrue("Half tilt > quarter of full", halfTiltOffset > fullTiltOffset * 0.25f)
    }

    // ============== Defaults ==============

    @Test
    fun testMaxOffsetPercentIs20() {
        assertEquals("Default maxOffsetPercent", 20f, ParallaxCalculator().maxOffsetPercent, 0.01f)
    }

    @Test
    fun testDefaultSmoothingIsNonZero() {
        assertEquals("Default smoothing", 0.134f, ParallaxCalculator().smoothing, 0.01f)
    }

    // ============== Delta-model specific tests ==============

    @Test
    fun testDirectModeSmallDeltaProducesProportionalMove() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f

        // First update sets baseline
        calculator.updateFromSensor(0f, 0f)
        calculator.update(16)

        // Single small delta
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)

        val offset = calculator.getOffsetX()
        // Expected: 0.1 * 100 * 1.0 = 10px (before soft limit, which barely changes it)
        assertTrue("Small delta should produce proportional offset: $offset", offset > 8f && offset < 12f)
    }

    @Test
    fun testSmoothingDelaysResponse() {
        // Smoothing field is accepted but no longer delays input response
        // (direct delta integration). Both paths should produce identical offset.
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.smoothing = 0f

        calculator.updateFromSensor(0f, 0f)
        calculator.update(16)
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)
        val directOffset = calculator.getOffsetX()

        calculator.reset()

        // With smoothing > 0: direct integration means same result
        calculator.smoothing = 0.5f
        calculator.updateFromSensor(0f, 0f)
        calculator.update(16)
        calculator.updateFromSensor(0.1f, 0f)
        calculator.update(16)
        val smoothedOffset = calculator.getOffsetX()

        assertTrue("Both paths should produce positive offset",
            directOffset > 0f && smoothedOffset > 0f)
        assertEquals("Direct integration ignores smoothing setting",
            directOffset, smoothedOffset, 0.1f)
    }
}
