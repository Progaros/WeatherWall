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

    /** Compute tree blend amount (same formula as TerrainLayer.draw band haze). */
    private fun treeBlendAmount(state: SimulationState, nearness: Float = 0.5f): Float =
        RenderConstants.bandHaze(nearness, state.getLightLevel())

    // ============== Band Haze ==============

    @Test
    fun `band haze continuous across full 24h for near and far bands`() {
        for (nearness in floatArrayOf(0f, 1f)) {
            var prev = treeBlendAmount(stateAt(0f), nearness)
            for (minute in 1 until 1440) {
                val hour = minute / 60f
                val cur = treeBlendAmount(stateAt(hour), nearness)
                val delta = abs(cur - prev)
                assertTrue(
                    "Band haze (nearness=$nearness) jumped $delta at hour=${"%.2f".format(hour)}",
                    delta < 0.03f
                )
                prev = cur
            }
        }
    }

    @Test
    fun `far bands are hazier than near bands by day`() {
        val midday = stateAt(12f)
        val far = treeBlendAmount(midday, 0f)
        val mid = treeBlendAmount(midday, 0.5f)
        val near = treeBlendAmount(midday, 1f)
        assertTrue("far ($far) > mid ($mid) > near ($near)", far > mid && mid > near)
        assertTrue("Near band keeps its own colour by day ($near)", near < 0.1f)
    }

    @Test
    fun `night tints even the nearest band`() {
        val midnight = treeBlendAmount(stateAt(1f), 1f)
        val midday = treeBlendAmount(stateAt(12f), 1f)
        assertTrue("Night haze ($midnight) well above day haze ($midday)", midnight > midday + 0.2f)
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

    private fun computeTreeColor(state: SimulationState, baseColor: Int, nearness: Float): Int {
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        return RenderConstants.blendWithAmbient(baseColor, ambientColor, treeBlendAmount(state, nearness))
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
