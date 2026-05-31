package info.olli.weatherlwp.rendering

/**
 * Centralized mapping from normalized sky coordinates (0..1) to screen space.
 *
 * Uses uniform scaling so that elements spread equally in both X and Y.
 * The "field" scales with the dominant screen dimension (maintaining
 * [RenderConstants.REFERENCE_ASPECT_RATIO]), pushing elements off-screen
 * symmetrically on all sides for a true zoom-from-center effect.
 */
object SkyViewportMapper {

    /**
     * Compute the virtual field width used for X mapping.
     * Ensures overflow (elements beyond screen edges) in ALL orientations,
     * including landscape where the old formula produced no overflow.
     *
     * For portrait (reference 9:16): identical to the previous formula.
     * For landscape: scales width by how much wider the aspect ratio is
     * compared to the reference, guaranteeing symmetric overflow.
     */
    fun fieldWidth(width: Float, height: Float): Float {
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height).coerceAtLeast(1f)
        val aspectRatio = maxDim / minDim
        val referenceAspectRatio = 1f / RenderConstants.REFERENCE_ASPECT_RATIO // 16/9
        val scaleFactor = (aspectRatio / referenceAspectRatio).coerceAtLeast(1f)
        return width * scaleFactor
    }

    /**
     * Compute the virtual field height used for Y mapping.
     * Ensures the same overflow ratio as X so the zoom is uniform on all sides.
     */
    fun fieldHeight(width: Float, height: Float): Float {
        val fw = fieldWidth(width, height)
        return height * fw / width
    }

    /**
     * Horizontal offset to center the field on the screen.
     */
    fun fieldOffset(width: Float, height: Float): Float {
        val fw = fieldWidth(width, height)
        return (fw - width) / 2f
    }

    /**
     * Vertical offset to center the field on the screen.
     */
    fun fieldOffsetY(width: Float, height: Float): Float {
        val fh = fieldHeight(width, height)
        return (fh - height) / 2f
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

    /**
     * Map a normalized Y (0..1) to screen pixels using the same uniform scaling as X.
     * On the reference device this equals `normalizedY * height`. On non-reference
     * aspect ratios the field extends above/below the screen symmetrically.
     */
    fun toScreenY(normalizedY: Float, width: Float, height: Float, parallaxY: Float = 0f): Float {
        val fh = fieldHeight(width, height)
        val offset = (fh - height) / 2f
        return normalizedY * fh - offset + parallaxY
    }

    /**
     * Star radius scale factor. Uses height as the reference dimension so star
     * size scales uniformly with all other height-based elements.
     */
    fun starRadiusScale(@Suppress("UNUSED_PARAMETER") width: Float, height: Float): Float {
        return (height / RenderConstants.REFERENCE_HEIGHT).coerceAtLeast(0.5f)
    }
}
