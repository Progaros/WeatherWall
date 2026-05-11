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
     */
    fun getWaterColor(state: SimulationState, isFrozen: Boolean, palette: WaterPalette): Int {
        if (isFrozen) return palette.ice
        return when {
            state.isNight -> palette.night
            state.isDawn -> RenderConstants.lerpColor(palette.night, palette.dawn, 0.5f)
            state.isDusk -> RenderConstants.lerpColor(palette.day, palette.dusk, 0.5f)
            else -> palette.day
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
