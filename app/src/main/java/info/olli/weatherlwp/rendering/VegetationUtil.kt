package info.olli.weatherlwp.rendering

import android.graphics.Color
import kotlin.math.floor

/**
 * Shared vegetation utility functions used by both TreeRenderer and BushRenderer.
 * Eliminates code duplication for common color and terrain calculations.
 *
 * Also contains the deterministic noise functions used by hill terrain
 * generation so that hills can be evaluated at any world-space X position
 * (infinite width).
 */
object VegetationUtil {

    // ============== Noise Functions ==============

    /** Integer hash → float in [0, 1). Used by value noise. */
    fun hash(n: Int): Float {
        var x = n
        x = (x shr 13) xor x
        x = (x * (x * x * 15731 + 789221) + 1376312589)
        return (x and 0x7fffffff) / 2147483647.0f
    }

    /** 1D value noise with smoothstep interpolation. Works at any x. */
    fun noise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val h1 = hash(i + seed)
        val h2 = hash(i + 1 + seed)
        val t = f * f * (3 - 2 * f)
        return h1 + t * (h2 - h1)
    }

    /**
     * Evaluate fractal Brownian motion (fBm) hill noise at a world-space X.
     *
     * The noise coordinate is derived from [worldX] normalised by
     * [RenderConstants.REFERENCE_EXTENDED_WIDTH] so that the visual
     * pattern matches the old pre-sampled control-point approach at
     * reference dimensions and extends seamlessly beyond.
     *
     * @return Raw noise value in approximately 0–1 range.
     */
    fun evaluateHillNoise(
        worldX: Float,
        noiseSeed: Int,
        hillFrequency: Float,
        hillRoughness: Float
    ): Float {
        val noiseX = (worldX / RenderConstants.REFERENCE_EXTENDED_WIDTH + 0.5f) * hillFrequency * 5.0f
        var noiseVal = noise(noiseX, noiseSeed) * 1.0f
        noiseVal += noise(noiseX * 2f, noiseSeed + 100) * 0.5f * hillRoughness
        noiseVal += noise(noiseX * 4f, noiseSeed + 200) * 0.25f * hillRoughness
        noiseVal += noise(noiseX * 8f, noiseSeed + 300) * 0.125f * hillRoughness
        val maxAmp = 1.0f + 0.5f * hillRoughness + 0.25f * hillRoughness + 0.125f * hillRoughness
        return noiseVal / maxAmp
    }

    /**
     * Get hill Y (as a ratio of screen height, 0 = top, 1 = bottom)
     * at an arbitrary world-space X position.
     *
     * This replaces the old control-point interpolation approach and allows
     * hills to extend infinitely in both directions.
     */
    fun getHillYFromNoise(
        worldX: Float,
        noiseSeed: Int,
        depthAmplitudeScale: Float,
        heightOffset: Float,
        hillFrequency: Float,
        hillRoughness: Float,
        hillAmplitude: Float
    ): Float {
        val noiseVal = evaluateHillNoise(worldX, noiseSeed, hillFrequency, hillRoughness)
        val effectiveAmplitude = hillAmplitude * depthAmplitudeScale
        val normalizedValue = (RenderConstants.HILL_BASE_HEIGHT +
            (noiseVal - 0.5f) * RenderConstants.HILL_HEIGHT_RANGE * effectiveAmplitude)
            .coerceIn(0.05f, 0.75f)
        return heightOffset + 0.12f * (1f - normalizedValue)
    }

    /**
     * Get hill Y at a world-space X using a [TreeRenderer.HillInfo].
     */
    fun getHillY(worldX: Float, hill: TreeRenderer.HillInfo): Float =
        getHillYFromNoise(
            worldX,
            hill.noiseSeed,
            hill.depthAmplitudeScale,
            hill.heightOffset,
            hill.hillFrequency,
            hill.hillRoughness,
            hill.hillAmplitude
        )

    /**
     * Apply autumn coloring to a base green color.
     * Each item gets a slightly different hue based on index.
     */
    fun autumnColor(baseColor: Int, weight: Float, itemIndex: Int): Int {
        if (weight <= 0f) return baseColor
        val hueShift = (itemIndex * 37) % 3
        val targetR: Int
        val targetG: Int
        val targetB: Int
        when (hueShift) {
            0 -> { targetR = 200; targetG = 90; targetB = 20 }    // Orange
            1 -> { targetR = 180; targetG = 140; targetB = 30 }   // Golden
            else -> { targetR = 190; targetG = 60; targetB = 25 } // Red-orange
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
