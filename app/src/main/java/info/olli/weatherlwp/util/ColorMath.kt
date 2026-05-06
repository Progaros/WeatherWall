package info.olli.weatherlwp.util

import kotlin.math.roundToInt

/**
 * Small color helpers that avoid android.graphics.Color so they can be unit-tested on the JVM.
 *
 * Colors are expected in packed ARGB format (0xAARRGGBB).
 */
object ColorMath {

    fun shadeRgb(argb: Int, shadeStrength: Float): Int {
        val strength = shadeStrength.coerceIn(0f, 1f)
        if (strength <= 0f) return argb

        // Darken towards black. At strength=1, keep ~35% of brightness.
        val darken = 1f - 0.65f * strength

        // Very slight blue lift to keep nights from turning muddy/gray.
        val blueLift = (10f * strength).roundToInt()

        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        val nr = (r * darken).roundToInt().coerceIn(0, 255)
        val ng = (g * darken).roundToInt().coerceIn(0, 255)
        val nb = ((b * darken).roundToInt() + blueLift).coerceIn(0, 255)

        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /**
     * Darken an RGB color by a multiplicative factor.
     * Alpha channel is preserved. Factor of 1.0 = no change, 0.0 = black.
     */
    fun darkenColor(color: Int, factor: Float = 0.65f): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * factor
        val g = ((color ushr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    /**
     * Quantize a color to 5-bit per channel precision (32 levels per channel).
     * Useful for reducing cache misses on gradient lookups.
     */
    fun quantizeColor(color: Int): Int {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xF8
        val g = (color ushr 8) and 0xF8
        val b = color and 0xF8
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Bucket a color into a compact 15-bit key (5 bits per RGB channel).
     * Used to detect when ambient lighting changes enough to invalidate cached gradients.
     */
    fun ambientColorBucket(color: Int): Int {
        val r = ((color ushr 16) and 0xFF) shr 3
        val g = ((color ushr 8) and 0xFF) shr 3
        val b = (color and 0xFF) shr 3
        return (r shl 10) or (g shl 5) or b
    }
}
