package info.olli.weatherlwp.parallax

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression tests for parallax large-movement handling, edge softness,
 * and recenter interaction.
 */
class ParallaxEdgeAndMovementTest {

    // ---- Ramp helpers (from ParallaxCalculatorTest) ----

    private fun rampTilt(calc: ParallaxCalculator, toX: Float, toY: Float, steps: Int = -1) {
        val mag = sqrt(toX * toX + toY * toY)
        val autoSteps = (mag / 0.025f).toInt().coerceIn(5, 100)
        val actualSteps = if (steps > 0) steps else autoSteps
        for (i in 0..actualSteps) {
            val t = i.toFloat() / actualSteps
            calc.updateFromSensor(toX * t, toY * t)
            calc.update(16)
        }
    }

    private fun rampTiltFromTo(
        calc: ParallaxCalculator,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        steps: Int = -1
    ) {
        val dx = toX - fromX
        val dy = toY - fromY
        val mag = sqrt(dx * dx + dy * dy)
        val autoSteps = (mag / 0.025f).toInt().coerceIn(5, 100)
        val actualSteps = if (steps > 0) steps else autoSteps
        for (i in 1..actualSteps) {
            val t = i.toFloat() / actualSteps
            calc.updateFromSensor(fromX + dx * t, fromY + dy * t)
            calc.update(16)
        }
    }

    private fun createCalculator(): ParallaxCalculator {
        return ParallaxCalculator().apply {
            strength = 1.0f
            maxOffsetPercent = 20f
            smoothing = 0.05f
            returnSpeed = 0.5f
            setCanvasSize(1080f, 1920f)
        }
    }

    // ============== Large Movement During Return Tests ==============

    @Test
    fun `large movement during return does not stutter or discard input`() {
        val calc = createCalculator()

        // Move to one side
        rampTilt(calc, 0.8f, 0f)
        val posAfterTilt = calc.getOffsetX()
        assertTrue("Should have moved right", posAfterTilt > 50f)

        // Hold still to trigger recenter
        repeat(60) {
            calc.updateFromSensor(0.8f, 0f)
            calc.update(16)
        }
        val posAfterStill = calc.getOffsetX()

        // Now apply a LARGE movement in the opposite direction during recenter
        // This should NOT be discarded - it should move the position
        val positions = mutableListOf<Float>()
        rampTiltFromTo(calc, 0.8f, 0f, -0.5f, 0f, steps = 20)
        positions.add(calc.getOffsetX())

        // Continue moving
        rampTiltFromTo(calc, -0.5f, 0f, -0.8f, 0f, steps = 10)
        positions.add(calc.getOffsetX())

        // The position should have moved significantly left (not stayed stuck)
        val finalPos = calc.getOffsetX()
        assertTrue(
            "Position should have moved left during large input (was $posAfterStill, now $finalPos)",
            finalPos < posAfterStill - 20f
        )
    }

    @Test
    fun `large delta is clamped not dropped`() {
        val calc = createCalculator()
        calc.smoothing = 0.01f

        // Initialize baseline
        calc.updateFromSensor(0f, 0f)
        calc.update(16)
        calc.updateFromSensor(0f, 0f)
        calc.update(16)

        val posBeforeJump = calc.getOffsetX()

        // Apply a large but sub-reset delta (between MAX_DELTA_PER_FRAME and SENSOR_RESET_THRESHOLD)
        calc.updateFromSensor(0.25f, 0f)
        calc.update(16)

        val posAfterJump = calc.getOffsetX()
        // Should have moved (not zero delta from discarding)
        assertTrue(
            "Position should move after large delta (before=$posBeforeJump, after=$posAfterJump)",
            abs(posAfterJump - posBeforeJump) > 1f
        )
    }

    @Test
    fun `sensor reset delta resets baseline without visible jump`() {
        val calc = createCalculator()

        // Move to a position
        rampTilt(calc, 0.5f, 0f)
        val posBefore = calc.getOffsetX()

        // Simulate sensor reset (huge delta > SENSOR_RESET_THRESHOLD)
        calc.updateFromSensor(5.0f, 0f)
        calc.update(16)

        val posAfter = calc.getOffsetX()
        // Position should not have jumped dramatically
        assertTrue(
            "Sensor reset should not cause visible jump (before=$posBefore, after=$posAfter)",
            abs(posAfter - posBefore) < 20f
        )
    }

    @Test
    fun `movement during recenter cancels recenter immediately`() {
        val calc = createCalculator()

        // Move right
        rampTilt(calc, 0.8f, 0f)
        val peakPos = calc.getOffsetX()

        // Start recentering (hold still, need > 500ms ≈ 32 frames at 16ms, using 55 for margin)
        repeat(55) {
            calc.updateFromSensor(0.8f, 0f)
            calc.update(16)
        }
        val recenteredPos = calc.getOffsetX()
        assertTrue("Should be recentering", recenteredPos < peakPos)

        // Record position just before movement resumes
        val posBeforeResume = calc.getOffsetX()

        // Now make a significant movement in the original direction
        // This should cancel recenter and add to position
        rampTiltFromTo(calc, 0.8f, 0f, 1.5f, 0f, steps = 30)
        val posAfterResume = calc.getOffsetX()

        // Position should have moved right (not continued recentering toward 0)
        // Even if recenter brought it down, the new movement should push it back up
        assertTrue(
            "Movement should push position away from center (before=$posBeforeResume, after=$posAfterResume)",
            posAfterResume > posBeforeResume * 0.5f || posAfterResume > recenteredPos
        )
    }

    // ============== Edge Resistance Tests ==============

    @Test
    fun `edge resistance reduces movement near limit`() {
        val calc = createCalculator()
        calc.edgeEasing = 0.5f

        // Move near edge
        rampTilt(calc, 0.95f, 0f)
        val nearEdgePos = calc.getOffsetX()

        // Try to push further
        rampTiltFromTo(calc, 0.95f, 0f, 1.0f, 0f, steps = 20)
        val atEdgePos = calc.getOffsetX()

        // Movement should be reduced near the edge
        val maxOffset = 1920f * 0.2f  // maxOffsetPercent = 20
        assertTrue("Should be near max offset", nearEdgePos > maxOffset * 0.5f)
        // The additional push should produce diminishing returns
        val additionalMovement = abs(atEdgePos - nearEdgePos)
        assertTrue(
            "Edge movement should be reduced (additional=$additionalMovement)",
            additionalMovement < maxOffset * 0.2f
        )
    }

    @Test
    fun `edge does not hard clamp position delta`() {
        val calc = createCalculator()

        // Ramp all the way to edge
        rampTilt(calc, 1.0f, 0f)
        val edgePos = calc.getOffsetX()

        // Apply small additional input
        rampTiltFromTo(calc, 1.0f, 0f, 1.05f, 0f, steps = 5)
        val pastEdgePos = calc.getOffsetX()

        // Should still register some movement (soft limit, not hard clamp)
        // The tanh soft limit allows going slightly past, just with diminishing returns
        assertTrue(
            "Should have some movement past edge (edgePos=$edgePos, pastEdgePos=$pastEdgePos)",
            pastEdgePos >= edgePos - 1f // Allow tiny rounding, but shouldn't go backwards
        )
    }

    @Test
    fun `offset returns smoothly from edge`() {
        val calc = createCalculator()
        calc.returnSpeed = 0.8f

        // Push to edge
        rampTilt(calc, 1.0f, 0f)
        val edgePos = calc.getOffsetX()
        assertTrue("Should be near edge", edgePos > 100f)

        // Hold still and let recenter engage
        val returnPositions = mutableListOf<Float>()
        for (frame in 0 until 120) {
            calc.updateFromSensor(1.0f, 0f)
            calc.update(16)
            returnPositions.add(calc.getOffsetX())
        }

        // Verify smooth return: no jumps > 10% of remaining distance
        for (i in 1 until returnPositions.size) {
            val prev = returnPositions[i - 1]
            val curr = returnPositions[i]
            if (abs(prev) > 1f) { // Only check meaningful positions
                val jumpRatio = abs(curr - prev) / abs(prev)
                assertTrue(
                    "Return should be smooth (frame $i: $prev -> $curr, ratio=$jumpRatio)",
                    jumpRatio < 0.15f
                )
            }
        }

        // Should have recentered significantly
        val finalPos = returnPositions.last()
        assertTrue("Should have returned toward center", finalPos < edgePos * 0.5f)
    }

    // ============== Bounded Output ==============

    @Test
    fun `output is bounded even with extreme input`() {
        val calc = createCalculator()
        val maxOffset = 1920f * 0.2f

        // Extreme input
        rampTilt(calc, 5.0f, 5.0f, steps = 200)

        val x = calc.getOffsetX()
        val y = calc.getOffsetY()
        val dist = sqrt(x * x + y * y)

        assertTrue(
            "Output should be bounded (dist=$dist, max=$maxOffset)",
            dist <= maxOffset * 1.5f // Soft limit allows slight overshoot
        )
    }

    @Test
    fun `no position discontinuity during tracking`() {
        val calc = createCalculator()

        val positions = mutableListOf<Float>()

        // Generate a complex movement pattern
        for (i in 0 until 200) {
            val phase = i * 0.05f
            val tiltX = 0.5f * kotlin.math.sin(phase.toDouble()).toFloat()
            val tiltY = 0.3f * kotlin.math.cos(phase.toDouble() * 0.7).toFloat()
            calc.updateFromSensor(tiltX, tiltY)
            calc.update(16)
            positions.add(calc.getOffsetX())
        }

        // Check for discontinuities (sudden jumps)
        for (i in 1 until positions.size) {
            val jump = abs(positions[i] - positions[i - 1])
            assertTrue(
                "Position jumped $jump at frame $i",
                jump < 50f // reasonable per-frame max
            )
        }
    }
}
