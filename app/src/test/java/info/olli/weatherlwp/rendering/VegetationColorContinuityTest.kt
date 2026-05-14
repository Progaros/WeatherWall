package info.olli.weatherlwp.rendering

import android.graphics.Color
import info.olli.weatherlwp.simulation.SimulationState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Regression tests for vegetation and scene color continuity across time-of-day transitions.
 *
 * These tests verify that tree, bush, terrain, water, and ambient colors
 * do not flip abruptly at sunrise, sunset, dawn-end, or dusk-start boundaries.
 * All quantities must change smoothly between adjacent minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegetationColorContinuityTest {

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
            nightShadeStrength = 0.35f,
            mountainShadowsEnabled = true,
            treeShadowsEnabled = true,
            shadowStrength = 1f
        )
    }

    private fun colorDelta(c1: Int, c2: Int): Int {
        val dr = abs(Color.red(c1) - Color.red(c2))
        val dg = abs(Color.green(c1) - Color.green(c2))
        val db = abs(Color.blue(c1) - Color.blue(c2))
        return maxOf(dr, dg, db)
    }

    /** Compute the timeMultiplier the same way TerrainLayer/BushRenderer now do. */
    private fun continuousTimeMultiplier(state: SimulationState): Float {
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return 1.0f - dayFactor * 0.85f
    }

    /** Compute tree blend amount (same formula as TerrainLayer.drawTreeInPlace). */
    private fun treeBlendAmount(state: SimulationState, yBasedDepth: Float = 0.5f): Float {
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        val baseColor = RenderConstants.TREE_BASE_COLOR
        val tintAmount = RenderConstants.applyAmbientTint(baseColor, ambientColor, yBasedDepth)
        return tintAmount * continuousTimeMultiplier(state)
    }

    // ============== timeMultiplier Continuity ==============

    @Test
    fun `timeMultiplier continuous across full 24h cycle`() {
        // Step through every minute; no adjacent pair should jump more than 0.05
        var prev = continuousTimeMultiplier(stateAt(0f))
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val cur = continuousTimeMultiplier(stateAt(hour))
            val delta = abs(cur - prev)
            assertTrue(
                "timeMultiplier jumped $delta at hour=${"%.2f".format(hour)} (prev=$prev, cur=$cur)",
                delta < 0.05f
            )
            prev = cur
        }
    }

    @Test
    fun `timeMultiplier is 0_15 during day and 1_0 at night`() {
        // Well within daytime: multiplier should be near 0.15
        val midday = continuousTimeMultiplier(stateAt(12f))
        assertEquals("Midday timeMultiplier", 0.15f, midday, 0.01f)

        // Deep night: multiplier should be near 1.0
        val midnight = continuousTimeMultiplier(stateAt(1f))
        assertEquals("Midnight timeMultiplier", 1.0f, midnight, 0.01f)
    }

    @Test
    fun `timeMultiplier no jump at old isDawn boundary`() {
        // isDawn turns off at sunrise+1.5h. Old code jumped here.
        val sunrise = 6f
        val boundary = sunrise + 1.5f
        val before = continuousTimeMultiplier(stateAt(boundary - 0.02f))
        val after = continuousTimeMultiplier(stateAt(boundary + 0.02f))
        val delta = abs(after - before)
        assertTrue(
            "timeMultiplier jumped $delta at dawn-end boundary ($before -> $after)",
            delta < 0.02f
        )
    }

    @Test
    fun `timeMultiplier no jump at old isDusk boundary`() {
        // isDusk turns on at sunset-3h. Old code jumped here.
        val sunset = 20f
        val boundary = sunset - 3f
        val before = continuousTimeMultiplier(stateAt(boundary - 0.02f))
        val after = continuousTimeMultiplier(stateAt(boundary + 0.02f))
        val delta = abs(after - before)
        assertTrue(
            "timeMultiplier jumped $delta at dusk-start boundary ($before -> $after)",
            delta < 0.02f
        )
    }

    // ============== Ambient Color Continuity ==============

    @Test
    fun `ambient color continuous across full 24h cycle`() {
        var prevColor = RenderConstants.getAmbientColor(
            0f, true, false, false, 0.35f, 6f, 20f)
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val state = stateAt(hour)
            val color = RenderConstants.getAmbientColor(
                hour, state.isNight, state.isDawn, state.isDusk,
                0.35f, 6f, 20f)
            val delta = colorDelta(prevColor, color)
            assertTrue(
                "Ambient color channel jumped $delta at hour=${"%.2f".format(hour)}",
                delta < 12
            )
            prevColor = color
        }
    }

    @Test
    fun `ambient color with nightShade continuous at sunrise`() {
        // The old code applied nightShade only when isNight was true.
        // This caused a step at sunrise when isNight flipped off.
        val sunrise = 6f
        var prevColor = RenderConstants.getAmbientColor(
            sunrise - 0.5f, true, false, false, 0.35f, 6f, 20f)
        for (minute in 1..60) {
            val hour = sunrise - 0.5f + minute / 60f
            val state = stateAt(hour)
            val color = RenderConstants.getAmbientColor(
                hour, state.isNight, state.isDawn, state.isDusk,
                0.35f, 6f, 20f)
            val delta = colorDelta(prevColor, color)
            assertTrue(
                "Ambient+nightShade color jumped $delta at hour=${"%.2f".format(hour)}",
                delta < 12
            )
            prevColor = color
        }
    }

    @Test
    fun `ambient color with nightShade continuous at sunset`() {
        val sunset = 20f
        var prevColor = RenderConstants.getAmbientColor(
            sunset - 0.5f, false, false, true, 0.35f, 6f, 20f)
        for (minute in 1..60) {
            val hour = sunset - 0.5f + minute / 60f
            val state = stateAt(hour)
            val color = RenderConstants.getAmbientColor(
                hour, state.isNight, state.isDawn, state.isDusk,
                0.35f, 6f, 20f)
            val delta = colorDelta(prevColor, color)
            assertTrue(
                "Ambient+nightShade color jumped $delta at hour=${"%.2f".format(hour)}",
                delta < 12
            )
            prevColor = color
        }
    }

    // ============== Tree Blend Amount Continuity ==============

    @Test
    fun `tree blend amount continuous across full 24h`() {
        var prev = treeBlendAmount(stateAt(0f))
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val cur = treeBlendAmount(stateAt(hour))
            val delta = abs(cur - prev)
            assertTrue(
                "Tree blend amount jumped $delta at hour=${"%.2f".format(hour)} (prev=$prev, cur=$cur)",
                delta < 0.03f
            )
            prev = cur
        }
    }

    @Test
    fun `tree color continuous across sunrise transition`() {
        val sunrise = 6f
        val baseColor = RenderConstants.TREE_BASE_COLOR
        val depth = 0.5f

        var prevColor = computeTreeColor(stateAt(sunrise - 1f), baseColor, depth)
        // Step through sunrise-1h to sunrise+2h in 1-minute increments
        for (minute in 1..180) {
            val hour = sunrise - 1f + minute / 60f
            val color = computeTreeColor(stateAt(hour), baseColor, depth)
            val delta = colorDelta(prevColor, color)
            assertTrue(
                "Tree color channel jumped $delta at hour=${"%.2f".format(hour)}",
                delta < 12
            )
            prevColor = color
        }
    }

    @Test
    fun `tree color continuous across sunset transition`() {
        val sunset = 20f
        val baseColor = RenderConstants.TREE_BASE_COLOR
        val depth = 0.5f

        var prevColor = computeTreeColor(stateAt(sunset - 2f), baseColor, depth)
        for (minute in 1..180) {
            val hour = sunset - 2f + minute / 60f
            val color = computeTreeColor(stateAt(hour), baseColor, depth)
            val delta = colorDelta(prevColor, color)
            assertTrue(
                "Tree color channel jumped $delta at hour=${"%.2f".format(hour)}",
                delta < 12
            )
            prevColor = color
        }
    }

    // ============== Night Shade Factor Continuity ==============

    @Test
    fun `nightShade factor fades continuously at sunrise`() {
        // In getAmbientColor, nightShade is now applied with continuous nightFactor.
        // Verify no step at the isNight boundary.
        val sunrise = 6f
        val noShade = RenderConstants.getAmbientColor(
            sunrise + 0.5f, false, true, false, 0f, 6f, 20f)
        val withShade = RenderConstants.getAmbientColor(
            sunrise + 0.5f, false, true, false, 0.35f, 6f, 20f)
        // At sunrise+0.5h, nightFactor should be small (not fully night), so shade should be minimal
        val delta = colorDelta(noShade, withShade)
        assertTrue(
            "Night shade still applied significantly after sunrise ($delta channels)",
            delta < 10
        )
    }

    // ============== Sparkle Intensity Continuity ==============

    @Test
    fun `sparkle intensity formula continuous across full 24h`() {
        var prev = sparkleIntensity(stateAt(0f))
        for (minute in 1 until 1440) {
            val hour = minute / 60f
            val cur = sparkleIntensity(stateAt(hour))
            val delta = abs(cur - prev)
            assertTrue(
                "Sparkle intensity jumped $delta at hour=${"%.2f".format(hour)} (prev=$prev, cur=$cur)",
                delta < 0.05f
            )
            prev = cur
        }
    }

    // ============== SkyLayer Night Shade Continuity ==============

    @Test
    fun `sky nightShadeStrength continuous across sunrise`() {
        val sunrise = 6f
        var prev = skyNightShade(stateAt(sunrise - 1f))
        for (minute in 1..120) {
            val hour = sunrise - 1f + minute / 60f
            val cur = skyNightShade(stateAt(hour))
            val delta = abs(cur - prev)
            assertTrue(
                "Sky nightShade jumped $delta at hour=${"%.2f".format(hour)} (prev=$prev, cur=$cur)",
                delta < 0.03f
            )
            prev = cur
        }
    }

    // ============== Private helpers that mirror production code ==============

    private fun computeTreeColor(state: SimulationState, baseColor: Int, yBasedDepth: Float): Int {
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        val tintAmount = RenderConstants.applyAmbientTint(baseColor, ambientColor, yBasedDepth)
        val timeMultiplier = continuousTimeMultiplier(state)
        val blendAmount = tintAmount * timeMultiplier
        return RenderConstants.blendWithAmbient(baseColor, ambientColor, blendAmount)
    }

    private fun sparkleIntensity(state: SimulationState): Float {
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return dayFactor * lightLevel * 0.8f + (1f - dayFactor) * state.moonIllumination * 0.5f
    }

    private fun skyNightShade(state: SimulationState): Float {
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return state.nightShadeStrength * (1f - dayFactor)
    }
}
