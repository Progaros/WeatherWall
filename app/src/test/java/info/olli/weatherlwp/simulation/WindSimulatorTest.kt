package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WindSimulator — extracted wind gust, smoothing, and direction logic.
 */
class WindSimulatorTest {

    private lateinit var sim: WindSimulator

    @Before
    fun setUp() {
        sim = WindSimulator()
    }

    // ============== Gust Tests ==============

    @Test
    fun `calm wind produces no gusts`() {
        // Wind below 0.15 → gust decays to 0
        repeat(50) { sim.calculateWindGust(0.05f, 16L) }
        val gust = sim.calculateWindGust(0.05f, 16L)
        assertEquals("Calm wind should have zero gust", 0f, gust, 0.01f)
    }

    @Test
    fun `windy conditions produce gusts in valid range`() {
        var maxGust = 0f
        repeat(500) {
            val g = sim.calculateWindGust(0.8f, 16L)
            assertTrue("Gust should be >= 0", g >= 0f)
            assertTrue("Gust should be <= 1", g <= 1f)
            if (g > maxGust) maxGust = g
        }
        assertTrue("Strong wind should eventually produce a gust > 0", maxGust > 0f)
    }

    @Test
    fun `gust output is always in 0-1 range`() {
        // Edge cases: very high wind, very large delta
        repeat(100) {
            val g = sim.calculateWindGust(1f, 100L)
            assertTrue("Gust in range: $g", g in 0f..1f)
        }
    }

    @Test
    fun `reset clears gust state`() {
        // Build up some gust
        repeat(200) { sim.calculateWindGust(0.9f, 16L) }
        sim.reset()
        // After reset, gust ramps from zero
        val g = sim.calculateWindGust(0.0f, 16L)
        assertEquals("After reset + calm, gust = 0", 0f, g, 0.01f)
    }

    // ============== Wind Strength Smoothing ==============

    @Test
    fun `smoothStrength converges toward target`() {
        val target = 0.8f
        var smoothed = 0f
        repeat(200) {
            smoothed = sim.smoothStrength(target, 0.016f)
        }
        assertEquals("Should converge to target", target, smoothed, 0.05f)
    }

    @Test
    fun `smoothStrength does not overshoot`() {
        val target = 0.6f
        var prev = 0f
        repeat(100) {
            val current = sim.smoothStrength(target, 0.016f)
            assertTrue("Should not overshoot target", current <= target + 0.01f)
            prev = current
        }
    }

    // ============== Wind Direction Smoothing ==============

    @Test
    fun `smoothDirection converges toward target`() {
        val target = 90f
        var dir = 0f
        repeat(2000) {
            dir = sim.smoothDirection(target, 0.016f)
        }
        assertEquals("Should converge to 90°", target, dir, 2f)
    }

    @Test
    fun `smoothDirection handles wraparound correctly`() {
        // Going from 350° to 10° should wrap through 0°, not spin 340°
        // Seed the smoother near 350
        repeat(500) { sim.smoothDirection(350f, 0.016f) }
        // Now target 10°
        repeat(500) { sim.smoothDirection(10f, 0.016f) }
        val dir = sim.smoothDirection(10f, 0.016f)
        assertTrue("Should be near 10° (actual=$dir)", dir in 0f..25f)
    }

    @Test
    fun `smoothDirection stays in 0-360 range`() {
        // Test various targets including edge cases
        val targets = listOf(0f, 90f, 180f, 270f, 359f, 1f)
        for (target in targets) {
            repeat(100) {
                val dir = sim.smoothDirection(target, 0.016f)
                assertTrue("Direction in range: $dir", dir in 0f..360f)
            }
        }
    }
}
