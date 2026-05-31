package info.olli.weatherlwp.rendering

/**
 * Centralized mapping from normalized sky coordinates (0..1) to screen space.
 *
 * Uses height-based uniform scaling so that elements spread equally in both
 * X and Y when the rendering height changes. The "field width" scales with
 * height (maintaining [RenderConstants.REFERENCE_ASPECT_RATIO]), with a floor
 * of [width] to guarantee full coverage in landscape orientations.
 */
object SkyViewportMapper {

    /**
     * Compute the virtual field width used for X mapping.
     * Scales with height (proportional to reference aspect), clamped to at least screen width.
     */
    fun fieldWidth(width: Float, height: Float): Float =
        maxOf(height * RenderConstants.REFERENCE_ASPECT_RATIO, width)

    /**
     * Horizontal offset to center the field on the screen.
     */
    fun fieldOffset(width: Float, height: Float): Float {
        val fw = fieldWidth(width, height)
        return (fw - width) / 2f
    }

    /**
     * Map a normalized X (0..1) to screen pixels using height-based field scaling.
     * On the reference device (1080×1920) this produces the same result as
     * `normalizedX * width`. On taller devices the field extends beyond the
     * screen edges, pushing some elements off-screen while maintaining uniform
     * density with Y.
     */
    fun toScreenX(normalizedX: Float, width: Float, height: Float, parallaxX: Float = 0f): Float {
        val fw = fieldWidth(width, height)
        val offset = (fw - width) / 2f
        return normalizedX * fw - offset + parallaxX
    }

    fun toScreenY(normalizedY: Float, height: Float, parallaxY: Float = 0f): Float {
        return normalizedY * height + parallaxY
    }

    /**
     * Star radius scale factor. Uses height as the reference dimension so star
     * size scales uniformly with all other height-based elements.
     */
    fun starRadiusScale(@Suppress("UNUSED_PARAMETER") width: Float, height: Float): Float {
        return (height / RenderConstants.REFERENCE_HEIGHT).coerceAtLeast(0.5f)
    }
}
