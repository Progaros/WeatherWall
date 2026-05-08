package info.olli.weatherlwp.rendering

import android.graphics.Color

/**
 * Shared vegetation utility functions used by both TreeRenderer and BushRenderer.
 * Eliminates code duplication for common color and terrain calculations.
 */
object VegetationUtil {

    /**
     * Apply autumn coloring to a base green color.
     * Each item gets a slightly different hue based on index.
     */
    fun autumnColor(baseColor: Int, weight: Float, itemIndex: Int): Int {
        if (weight <= 0f) return baseColor
        val hueShift = (itemIndex * 37) % 3
        val (targetR, targetG, targetB) = when (hueShift) {
            0 -> Triple(200, 90, 20)    // Orange
            1 -> Triple(180, 140, 30)   // Golden
            else -> Triple(190, 60, 25) // Red-orange
        }
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        return Color.rgb(
            (r + (targetR - r) * weight).toInt().coerceIn(0, 255),
            (g + (targetG - g) * weight).toInt().coerceIn(0, 255),
            (b + (targetB - b) * weight).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Interpolate hill Y at a given xRatio (0-1).
     * Uses linear interpolation between hill control points.
     */
    fun getHillY(xRatio: Float, controlPoints: List<Float>, heightOffset: Float): Float {
        if (controlPoints.size < 2) return heightOffset

        val indexFloat = xRatio * (controlPoints.size - 1)
        val index = indexFloat.toInt().coerceIn(0, controlPoints.size - 2)
        val fraction = indexFloat - index

        val p1 = controlPoints[index]
        val p2 = controlPoints[index + 1]
        val interpolated = p1 + fraction * (p2 - p1)

        return heightOffset + 0.12f * (1f - interpolated)
    }

    /**
     * Interpolate hill Y at a given xRatio (0-1) using HillInfo.
     * Convenience overload that delegates to the raw version.
     */
    fun getHillY(xRatio: Float, hill: TreeRenderer.HillInfo): Float =
        getHillY(xRatio, hill.controlPoints, hill.heightOffset)

    /**
     * Calculate the blending weight for a given season during transitions.
     * Shared by TerrainLayer, TreeRenderer, and BushRenderer.
     */
    fun getSeasonWeight(
        season: String,
        currentSeason: String,
        previousSeason: String,
        blend: Float
    ): Float {
        return when {
            currentSeason == season && previousSeason == season -> 1f
            currentSeason == season -> blend
            previousSeason == season -> 1f - blend
            else -> 0f
        }
    }
}
