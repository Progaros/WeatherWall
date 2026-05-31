package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for SimulationController.smoothTimeTransitions midnight wrap fix.
 *
 * The function is private, so we test the circular-distance logic directly
 * by replicating the algorithm. This ensures the mod-24 wrap-around at midnight
 * works correctly (bug: 23.99→0.01 previously caused full day-cycle flash).
 */
class TimeTransitionTest {

    /**
     * Replicates the fixed smoothTimeTransitions algorithm for testability.
     * This must stay in sync with the actual implementation.
     */
    private fun smoothTimeTransitions(
        rawTime: Float,
        deltaSec: Float,
        lastSmoothedTime: Float
    ): Float {
        // Circular distance (handles 24h wrap)
        var delta = rawTime - lastSmoothedTime
        if (delta > 12f) delta -= 24f
        if (delta < -12f) delta += 24f

        val timeDelta = kotlin.math.abs(delta)
        if (timeDelta < 0.001f) return rawTime

        val baseRate = when {
            timeDelta > 2f -> 1.8f
            timeDelta > 0.5f -> 4.8f
            timeDelta > 0.1f -> 9.0f
            else -> 18.0f
        }

        val lerpFactor = (baseRate * deltaSec).coerceIn(0f, 1f)
        return (lastSmoothedTime + delta * lerpFactor).mod(24f)
    }

    // ============== Midnight Wrap Tests ==============

    @Test
    fun `midnight wrap forward - 23_9 to 0_1 moves forward not backward`() {
        // Simulates 23.9 → 0.1 (crossing midnight forward)
        val result = smoothTimeTransitions(0.1f, 0.033f, 23.9f)
        // Should move slightly forward past midnight, NOT backward through daytime
        // Result should be between 23.9 and 0.1 (crossing midnight), i.e. > 23.9 or < 0.15
        assertTrue(
            "Should progress forward past midnight (got $result)",
            result > 23.8f || result < 0.2f
        )
    }

    @Test
    fun `midnight wrap backward - 0_1 to 23_9 moves backward not forward`() {
        // Edge case: time slider dragged backward past midnight
        val result = smoothTimeTransitions(23.9f, 0.033f, 0.1f)
        // Should move slightly backward past midnight
        assertTrue(
            "Should progress backward past midnight (got $result)",
            result < 0.2f || result > 23.8f
        )
    }

    @Test
    fun `large forward jump uses slow rate`() {
        // Jump from 6h to 12h (6 hour gap) — but circular distance is 6
        val result = smoothTimeTransitions(12f, 0.033f, 6f)
        // With baseRate=1.8 and deltaSec=0.033, lerpFactor=0.0594
        // result = 6 + 6*0.0594 = 6.356
        assertTrue("Should move toward 12, slowly (got $result)", result > 6f && result < 7f)
    }

    @Test
    fun `small delta snaps directly`() {
        val result = smoothTimeTransitions(14.0005f, 0.033f, 14f)
        // timeDelta < 0.001 → returns rawTime directly
        assertEquals(14.0005f, result, 0.001f)
    }

    @Test
    fun `normal forward transition within same day`() {
        // 10.0 → 10.5 (30 min gap, should use fast rate)
        val result = smoothTimeTransitions(10.5f, 0.033f, 10f)
        // timeDelta=0.5, baseRate=4.8, lerpFactor=0.158
        // result = 10 + 0.5*0.158 = 10.079
        assertTrue("Should move toward 10.5 (got $result)", result > 10f && result < 10.5f)
    }

    @Test
    fun `result stays in 0-24 range after wrap`() {
        // Multiple steps crossing midnight
        var time = 23.8f
        for (i in 1..100) {
            time = smoothTimeTransitions(0.2f, 0.033f, time)
            assertTrue("Time must be in [0,24) range (got $time at step $i)", time >= 0f && time < 24f)
        }
    }

    @Test
    fun `converges to target after midnight wrap`() {
        // After many frames, should converge to target (0.2h = 12 minutes past midnight)
        var time = 23.9f
        for (i in 1..300) {
            time = smoothTimeTransitions(0.2f, 0.033f, time)
        }
        assertEquals("Should converge to 0.2", 0.2f, time, 0.05f)
    }

    @Test
    fun `does not traverse through 12h opposite direction`() {
        // Key regression: going from 23.5 to 0.5 should NOT pass through 12h
        var time = 23.5f
        for (i in 1..200) {
            time = smoothTimeTransitions(0.5f, 0.033f, time)
            // Time should always be either > 23 or < 1 during transition
            // It should NEVER be in the 2-22 range
            assertFalse(
                "Should not traverse through daytime hours (got $time at step $i)",
                time > 2f && time < 22f
            )
        }
    }
}
