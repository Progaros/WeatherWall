package info.olli.weatherlwp.util

import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Shared water color and state utilities used by [RiverLayer] and [LakeLayer].
 *
 * Centralizes the time-of-day water color selection and frozen-state check
 * so both water features stay consistent and changes only need to be made once.
 */
object WaterColorUtil {

    /**
     * A set of water colors for different times of day, plus ice/frozen state.
     */
    data class WaterPalette(
        val day: Int,
        val dawn: Int,
        val dusk: Int,
        val night: Int,
        val deep: Int,
        val ice: Int,
        val frozenThreshold: Float = 0.05f
    )

    /**
     * Select the appropriate water color for the current time-of-day and frozen state.
     * Uses smooth time-based interpolation with actual sunrise/sunset times.
     */
    fun getWaterColor(state: SimulationState, isFrozen: Boolean, palette: WaterPalette): Int {
        if (isFrozen) return palette.ice

        val hour = state.timeOfDayHours
        val sunrise = state.sunriseHours
        val sunset = state.sunsetHours
        val dawnEnd = sunrise + 0.5f
        val duskStart = sunset - 0.5f
        val duskEnd = sunset + 1.0f
        val dawnStart = sunrise - 1.0f

        return when {
            // Pre-dawn: Night → Dawn
            hour >= dawnStart && hour < sunrise -> {
                val t = ((hour - dawnStart) / (sunrise - dawnStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(palette.night, palette.dawn, t)
            }
            // Dawn → Day
            hour >= sunrise && hour < dawnEnd -> {
                val t = ((hour - sunrise) / (dawnEnd - sunrise)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(palette.dawn, palette.day, t)
            }
            // Day
            hour >= dawnEnd && hour < duskStart -> palette.day
            // Day → Dusk
            hour >= duskStart && hour < sunset -> {
                val t = ((hour - duskStart) / (sunset - duskStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(palette.day, palette.dusk, t)
            }
            // Dusk → Night
            hour >= sunset && hour < duskEnd -> {
                val t = ((hour - sunset) / (duskEnd - sunset)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(palette.dusk, palette.night, t)
            }
            // Night
            else -> palette.night
        }
    }

    /**
     * Determine whether the water surface should be frozen.
     * Requires both snow coverage above threshold AND sub-zero temperature.
     */
    fun isFrozen(state: SimulationState, threshold: Float): Boolean {
        return state.snowLevel > threshold && state.temperatureCelsius.let {
            !it.isNaN() && it < 0f
        }
    }
}
