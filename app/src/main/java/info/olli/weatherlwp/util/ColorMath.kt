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
}
