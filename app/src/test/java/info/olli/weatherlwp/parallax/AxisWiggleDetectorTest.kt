package info.olli.weatherlwp.parallax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AxisWiggleDetectorTest {

    /** Sensitivity-0.05 tuning that ParallaxSensorManager applies for the default preference. */
    private fun defaultDetector() = AxisWiggleDetector(rateThreshold = 2.9f, requiredReversals = 5, windowMs = 1800L)

    /** Feeds a 50 Hz trace (SENSOR_DELAY_GAME) and returns the trigger timestamps. */
    private fun run(
        detector: AxisWiggleDetector,
        durationMs: Long,
        rates: (tSec: Float) -> Triple<Float, Float, Float>
    ): List<Long> {
        val triggers = mutableListOf<Long>()
        var t = 0L
        while (t <= durationMs) {
            val (x, y, z) = rates(t / 1000f)
            if (detector.onSample(x, y, z, t)) triggers.add(t)
            t += 20L
        }
        return triggers
    }

    private fun wave(amplitude: Float, hz: Float, tSec: Float) =
        amplitude * sin(2f * PI.toFloat() * hz * tSec)

    @Test
    fun `quick wiggle around long axis triggers`() {
        val triggers = run(defaultDetector(), 2000L) { t -> Triple(0.3f, wave(6f, 2.5f, t), 0.3f) }
        assertTrue("expected a trigger, got none", triggers.isNotEmpty())
        assertTrue("should fire within ~1.2 s, fired at ${triggers.first()}", triggers.first() <= 1200L)
    }

    @Test
    fun `steering-wheel twist around Z does not trigger`() {
        val triggers = run(defaultDetector(), 3000L) { t -> Triple(0.3f, 0.5f, wave(6f, 2.5f, t)) }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `tilting forward and back around X does not trigger`() {
        val triggers = run(defaultDetector(), 3000L) { t -> Triple(wave(6f, 2.5f, t), 0.5f, 0.3f) }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `wiggle around a diagonal axis does not trigger`() {
        // Axis ~40 degrees off the long axis: X keeps up with Y, so Y never dominates.
        val triggers = run(defaultDetector(), 3000L) { t ->
            Triple(wave(5f, 2.5f, t), wave(6f, 2.5f, t), 0.3f)
        }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `gentle wiggle below rate threshold does not trigger`() {
        val triggers = run(defaultDetector(), 3000L) { t -> Triple(0f, wave(2.5f, 2.5f, t), 0f) }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `slow rocking spreads reversals beyond the window`() {
        val triggers = run(defaultDetector(), 6000L) { t -> Triple(0f, wave(6f, 0.5f, t), 0f) }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `single turn and back is not enough`() {
        // One full period at 2.5 Hz = two reversals, then the phone is held still.
        val triggers = run(defaultDetector(), 2000L) { t ->
            Triple(0f, if (t < 0.4f) wave(6f, 2.5f, t) else 0f, 0f)
        }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `sample-to-sample sign jitter is not counted`() {
        var flip = 1f
        val triggers = run(defaultDetector(), 2000L) { _ ->
            flip = -flip
            Triple(0f, 4f * flip, 0f)
        }
        assertEquals(emptyList<Long>(), triggers)
    }

    @Test
    fun `higher sensitivity accepts a gentler wiggle`() {
        val sensitive = AxisWiggleDetector(rateThreshold = 1.2f, requiredReversals = 3, windowMs = 1800L)
        val triggers = run(sensitive, 2000L) { t -> Triple(0f, wave(2.5f, 2.5f, t), 0f) }
        assertTrue("expected a trigger with high sensitivity", triggers.isNotEmpty())
    }

    @Test
    fun `continuous wiggling re-arms after each trigger`() {
        val triggers = run(defaultDetector(), 4000L) { t -> Triple(0f, wave(6f, 2.5f, t), 0f) }
        assertTrue("expected repeated triggers, got $triggers", triggers.size >= 2)
    }
}
