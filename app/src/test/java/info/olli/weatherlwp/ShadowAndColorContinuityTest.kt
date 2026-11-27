package info.olli.weatherlwp

import info.olli.weatherlwp.simulation.SimulationState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for shadow angle/alpha continuity and color palette transitions.
 *
 * These tests verify that no visual "flips" occur at sunrise, sunset, or other
 * time-of-day boundaries. All quantities must change smoothly between adjacent minutes.
 */
class ShadowAndColorContinuityTest {

    // ---------- helpers ----------

    private fun stateAt(hour: Float, sunrise: Float = 6f, sunset: Float = 20f): SimulationState {
        val isNight = hour < sunrise || hour >= sunset
        val isDawn = hour >= sunrise && hour < sunrise + 1.5f
        val isDusk = hour >= (sunset - 3f) && hour < sunset
        return SimulationState.default().copy(
            timeOfDayHours = hour,
            smoothedTimeOfDayHours = hour,
            sunriseHours = sunrise,
            sunsetHours = sunset,
            isNight = isNight,
            isDawn = isDawn,
            isDusk = isDusk,
            moonIllumination = 0.5f,
            mountainShadowsEnabled = true,
            treeShadowsEnabled = true,
            shadowStrength = 1f
        )
    }

    // ============== Shadow Angle Continuity ==============

    @Test
    fun `shadow angle continuous across sunset`() {
        val sunset = 20f
        // Check 30 minutes before to 30 minutes after sunset in 1-minute steps
        var prevAngle = stateAt(sunset - 0.5f).getLightSourceAngle()
        for (minute in 1..60) {
            val hour = sunset - 0.5f + minute / 60f
            val angle = stateAt(hour).getLightSourceAngle()
            val delta = abs(angle - prevAngle)
            assertTrue(
                "Shadow angle jumped $delta rad at hour=$hour (prev=$prevAngle, cur=$angle)",
                delta < 0.15f // max ~8.6 degrees per minute
            )
            prevAngle = angle
        }
    }

    @Test
    fun `shadow angle continuous across sunrise`() {
        val sunrise = 6f
        var prevAngle = stateAt(sunrise - 0.5f).getLightSourceAngle()
        for (minute in 1..60) {
            val hour = sunrise - 0.5f + minute / 60f
            val angle = stateAt(hour).getLightSourceAngle()
            val delta = abs(angle - prevAngle)
            assertTrue(
                "Shadow angle jumped $delta rad at hour=$hour",
                delta < 0.15f
            )
            prevAngle = angle
        }
    }

    @Test
    fun `shadow angle continuous across midnight wrap`() {
        // From 23:30 to 00:30 in 1-minute steps
        var prevAngle = stateAt(23.5f).getLightSourceAngle()
        for (minute in 1..60) {
            val hour = (23.5f + minute / 60f) % 24f
            val angle = stateAt(hour).getLightSourceAngle()
            val delta = abs(angle - prevAngle)
            assertTrue(
                "Shadow angle jumped $delta rad at hour=$hour",
                delta < 0.15f
            )
            prevAngle = angle
        }
    }

    @Test
    fun `shadow angle continuous full 24h cycle`() {
        // Every minute of a 24-hour cycle
        var prevAngle = stateAt(0f).getLightSourceAngle()
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val angle = stateAt(hour).getLightSourceAngle()
            val delta = abs(angle - prevAngle)
            assertTrue(
                "Shadow angle discontinuity at minute $minute (hour=$hour): delta=$delta",
                delta < 0.15f
            )
            prevAngle = angle
        }
    }

    /** Horizontal ground-shadow length per unit object height, as GroundShadow projects it. */
    private fun shadowDx(state: SimulationState) = state.shadowDirX * state.shadowLengthFactor()

    @Test
    fun `shadow offset opposite light direction`() {
        // At noon (light from above, angle=PI/2), shadow should be minimal horizontal offset
        val dx = shadowDx(stateAt(12f))
        assertTrue("Noon shadow dx should be near zero, was $dx", abs(dx) < 0.5f)

        // At sunrise (light from left, angle=PI), shadow should point right (dx > 0)
        val dxSunrise = shadowDx(stateAt(6.1f))
        assertTrue("Sunrise shadow should point right, dx=$dxSunrise", dxSunrise > 0)

        // At sunset (light from right, angle≈0), shadow should point left (dx < 0)
        val dxSunset = shadowDx(stateAt(19.9f))
        assertTrue("Sunset shadow should point left, dx=$dxSunset", dxSunset < 0)
    }

    @Test
    fun `shadow angle uses actual sun times`() {
        // With sunrise at 7.5, sunset at 15.5 (winter)
        val winterNoon = stateAt(11.5f, sunrise = 7.5f, sunset = 15.5f)
        val angle = winterNoon.getLightSourceAngle()
        // At solar noon (midpoint 11.5h), angle should be PI/2
        assertEquals("Winter noon angle should be PI/2", Math.PI.toFloat() / 2f, angle, 0.01f)
    }

    // ============== Shadow Strength Continuity ==============

    @Test
    fun `shadow strength no discontinuity near horizon`() {
        var prevStrength = stateAt(0f).getEffectiveShadowStrength()
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val strength = stateAt(hour).getEffectiveShadowStrength()
            val delta = abs(strength - prevStrength)
            assertTrue(
                "Shadow strength jumped $delta at minute $minute (hour=$hour)",
                delta < 0.05f // max 5% change per minute
            )
            prevStrength = strength
        }
    }

    @Test
    fun `shadow alpha fades smoothly instead of flipping`() {
        // Check dusk transition: strength should decrease smoothly, not jump
        val sunset = 20f
        val values = (0..60).map { m ->
            val hour = sunset + m / 60f
            stateAt(hour).getEffectiveShadowStrength()
        }
        // Should be monotonically decreasing (allowing small noise)
        for (i in 1 until values.size) {
            val increase = values[i] - values[i - 1]
            assertTrue(
                "Shadow strength should not increase during dusk (minute $i: ${values[i-1]} -> ${values[i]})",
                increase < 0.01f
            )
        }
    }

    @Test
    fun `shadow length no discontinuity near horizon`() {
        var prevDx = shadowDx(stateAt(0f))
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val dx = shadowDx(stateAt(hour))
            val delta = abs(dx - prevDx)
            assertTrue(
                "Shadow dx jumped $delta at minute $minute (hour=$hour)",
                delta < 0.4f
            )
            prevDx = dx
        }
    }

    @Test
    fun `tree shadow visible for daylight hours`() {
        // Mid-day: shadow should have nonzero strength
        val midday = stateAt(12f)
        assertTrue(
            "Midday shadow strength should be > 0",
            midday.getEffectiveShadowStrength() > 0.5f
        )
    }

    @Test
    fun `overcast shadow alpha lower but nonzero during day`() {
        val state = stateAt(12f).copy(
            cloudCover = 0.9f,
            shadowStrength = 0.3f
        )
        val strength = state.getEffectiveShadowStrength()
        assertTrue("Overcast shadow should be nonzero", strength > 0f)
        assertTrue("Overcast shadow should be reduced", strength < 0.5f)
    }

    // ============== Light Level Continuity ==============

    @Test
    fun `light level continuous full day cycle`() {
        var prev = stateAt(0f).getLightLevel()
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val level = stateAt(hour).getLightLevel()
            val delta = abs(level - prev)
            assertTrue(
                "Light level jumped $delta at minute $minute (hour=$hour)",
                delta < 0.04f
            )
            prev = level
        }
    }

    // ============== Color Continuity (Ambient Color) ==============
    // Note: Full color channel tests require Android Color class (not available in pure unit tests).
    // The RenderConstants.getAmbientColor() is tested indirectly through RenderConstantsTest.
    // Here we test the time-of-day mapping structure for continuity.

    @Test
    fun `light level provides continuous day factor for color blending`() {
        // Verify getLightLevel() provides a smooth curve that color blending can use
        val sunrise = 6f
        val sunset = 20f
        var prev = stateAt(0f, sunrise, sunset).getLightLevel()
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val level = stateAt(hour, sunrise, sunset).getLightLevel()
            val delta = abs(level - prev)
            assertTrue(
                "Light level (color blend driver) jumped $delta at minute $minute (hour=$hour)",
                delta < 0.04f
            )
            prev = level
        }
    }

    @Test
    fun `shadow strength provides continuous tint factor across sunset`() {
        // Shadow strength drives shadow tinting — must be smooth
        val sunset = 20f
        var prev = stateAt(sunset - 0.5f).getEffectiveShadowStrength()
        for (minute in 1..60) {
            val hour = sunset - 0.5f + minute / 60f
            val strength = stateAt(hour).getEffectiveShadowStrength()
            val delta = abs(strength - prev)
            assertTrue(
                "Shadow strength jumped $delta at hour=$hour",
                delta < 0.05f
            )
            prev = strength
        }
    }
}
