package info.olli.weatherlwp.rendering

/**
 * Centralized mapping from normalized sky coordinates (0..1) to screen space.
 * Using this helper avoids shape distortions when width != height.
 */
object SkyViewportMapper {
    fun toScreenX(normalizedX: Float, width: Float, parallaxX: Float = 0f): Float {
        return normalizedX.coerceIn(0f, 1f) * width + parallaxX
    }

    fun toScreenY(normalizedY: Float, height: Float, parallaxY: Float = 0f): Float {
        return normalizedY.coerceIn(0f, 1f) * height + parallaxY
    }

    fun starRadiusScale(width: Float, height: Float): Float {
        return (minOf(width, height) / 1080f).coerceAtLeast(0.5f)
    }
}
