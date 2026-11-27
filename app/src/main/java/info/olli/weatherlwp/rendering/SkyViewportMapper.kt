package info.olli.weatherlwp.rendering

/**
 * Centralized mapping from normalized sky coordinates (0..1) to screen space.
 *
 * Two related projections live here:
 *  - Stars use an ISOTROPIC square field ([toScreenX]/[toScreenY]/[starFieldExtent]).
 *    A unit step in X covers exactly as many pixels as a unit step in Y, so the star
 *    pattern keeps uniform, round spacing in every orientation. The square field is
 *    centered on the screen, so changing the viewport shape reveals stars symmetrically
 *    in ALL directions instead of stretching them along the long axis.
 *  - Celestial bodies (sun/moon) and random events use the dimension-scaled field
 *    ([fieldWidth]/[fieldHeight]/[fieldOffset]/[fieldOffsetY]), which tracks the screen
 *    aspect so the sun/moon arc stays within the visible area.
 */
object SkyViewportMapper {

    /**
     * Overscan applied to the isotropic star field so it always extends past every screen
     * edge (in any orientation, even as parallax shifts it), preventing empty star borders.
     */
    private const val STAR_FIELD_OVERSCAN = 1.15f

    /**
     * Side length (in pixels) of the square, isotropic field used to place stars.
     * Identical for X and Y so stars are distributed uniformly in all directions.
     * Based on the larger screen dimension so the field always overflows every edge.
     */
    fun starFieldExtent(width: Float, height: Float): Float {
        return maxOf(width, height) * STAR_FIELD_OVERSCAN
    }

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
     * Map a normalized X (0..1) to screen pixels using the isotropic star field.
     * The square field is centered on the screen, so the midpoint (0.5) maps to the
     * horizontal center and stars spread symmetrically off both edges. A unit step in X
     * covers the same pixel distance as a unit step in Y, keeping the pattern undistorted.
     */
    fun toScreenX(normalizedX: Float, width: Float, height: Float, parallaxX: Float = 0f): Float {
        val extent = starFieldExtent(width, height)
        return width / 2f + (normalizedX - 0.5f) * extent + parallaxX
    }

    /**
     * Map a normalized Y (0..1) to screen pixels using the same isotropic star field as X.
     * The midpoint (0.5) maps to the vertical center and stars spread symmetrically off the
     * top and bottom edges, matching the horizontal behavior for a true zoom-from-center look.
     */
    fun toScreenY(normalizedY: Float, width: Float, height: Float, parallaxY: Float = 0f): Float {
        val extent = starFieldExtent(width, height)
        return height / 2f + (normalizedY - 0.5f) * extent + parallaxY
    }

    /**
     * Star radius scale factor. Uses height as the reference dimension so star
     * size scales uniformly with all other height-based elements.
     */
    fun starRadiusScale(@Suppress("UNUSED_PARAMETER") width: Float, height: Float): Float {
        return (height / RenderConstants.REFERENCE_HEIGHT).coerceAtLeast(0.5f)
    }
}
