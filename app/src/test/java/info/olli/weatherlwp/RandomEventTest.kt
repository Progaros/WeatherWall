package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity bounds for the random-event timing and placement constants in RenderConstants.
 */
class RandomEventTest {

    // ============== Event Duration Tests ==============

    @Test
    fun `airplane duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_AIRPLANE

        assertTrue("Airplane visible long enough", duration >= 20000L)
        assertTrue("Airplane not too long", duration <= 60000L)
    }

    @Test
    fun `bird flock duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_BIRD_FLOCK

        assertTrue("Birds visible long enough", duration >= 10000L)
        assertTrue("Birds not too long", duration <= 20000L)
    }

    @Test
    fun `shooting star duration is short`() {
        val duration = RenderConstants.EVENT_DURATION_SHOOTING_STAR

        // Shooting stars have a longer graceful arc with fade-out effect
        assertTrue("Shooting star is brief", duration <= 12000L)
        assertTrue("Shooting star visible", duration >= 5000L)
    }

    @Test
    fun `balloon duration is long`() {
        val duration = RenderConstants.EVENT_DURATION_BALLOON

        assertTrue("Balloon drifts slowly", duration >= 30000L)
        assertTrue("Balloon not too long", duration <= 60000L)
    }

    @Test
    fun `squirrel duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_SQUIRREL

        assertTrue("Squirrel visible long enough", duration >= 8000L)
        assertTrue("Squirrel not too long", duration <= 20000L)
    }

    @Test
    fun `dragonfly duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_DRAGONFLY

        assertTrue("Dragonfly visible long enough", duration >= 6000L)
        assertTrue("Dragonfly not too long", duration <= 15000L)
    }

    @Test
    fun `snowflake duration allows slow fall`() {
        val duration = RenderConstants.EVENT_DURATION_SNOWFLAKE

        assertTrue("Snowflake falls slowly", duration >= 12000L)
        assertTrue("Snowflake not too long", duration <= 30000L)
    }

    // ============== Event Positioning Tests ==============

    @Test
    fun `sky events spawn in upper portion`() {
        val yMin = RenderConstants.EVENT_SKY_Y_MIN
        val yMax = RenderConstants.EVENT_SKY_Y_MAX

        assertTrue("Sky min in upper half", yMin <= 0.5f)
        assertTrue("Sky max in upper half", yMax <= 0.5f)
        assertTrue("Range is valid", yMax > yMin)
    }

    @Test
    fun `ground events spawn at bottom`() {
        val yMin = RenderConstants.EVENT_GROUND_Y_MIN
        val yMax = RenderConstants.EVENT_GROUND_Y_MAX

        assertTrue("Ground events near bottom", yMin >= 0.7f)
        assertTrue("Ground events not off screen", yMax <= 1.0f)
    }

    @Test
    fun `low sky events in middle`() {
        val yMin = RenderConstants.EVENT_LOW_SKY_Y_MIN
        val yMax = RenderConstants.EVENT_LOW_SKY_Y_MAX

        assertTrue("Low sky above ground", yMax < RenderConstants.EVENT_GROUND_Y_MIN)
        assertTrue("Low sky below high sky", yMin > RenderConstants.EVENT_SKY_Y_MAX)
    }

    @Test
    fun `ground events use hill depth range`() {
        // Ground events use depthFactor in the hills range
        val hillsMin = RenderConstants.PARALLAX_HILLS_MIN
        val hillsMax = RenderConstants.PARALLAX_HILLS_MAX

        assertTrue("Hills min is background", hillsMin >= 0.4f)
        assertTrue("Hills max is foreground", hillsMax <= 0.9f)
        assertTrue("Range is valid", hillsMax > hillsMin)
    }
}
