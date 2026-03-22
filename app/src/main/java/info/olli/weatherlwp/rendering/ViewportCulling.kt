package info.olli.weatherlwp.rendering

/**
 * Shared helpers for conservative horizontal culling.
 * Objects are still drawn when partially visible or close enough to enter via parallax.
 */
object ViewportCulling {
    fun screenWidth(extendedWidth: Float, extendedOffset: Float): Float {
        return (extendedWidth - 2f * extendedOffset).coerceAtLeast(0f)
    }

    fun maxParallaxShift(height: Float): Float {
        // Conservatively covers configured parallax range plus a small buffer.
        return height * 0.45f
    }

    fun isVisibleHorizontally(
        x: Float,
        objectHalfWidth: Float,
        screenWidth: Float,
        height: Float,
        extraMargin: Float = 0f
    ): Boolean {
        val margin = objectHalfWidth + maxParallaxShift(height) + extraMargin
        return x >= -margin && x <= screenWidth + margin
    }
}
