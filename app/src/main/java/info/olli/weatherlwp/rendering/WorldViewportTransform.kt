package info.olli.weatherlwp.rendering

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Central world-to-screen coordinate transform.
 *
 * The canonical horizontal coordinate is **worldX** (world-space units):
 * - `worldX = 0` is the scene center.
 * - Negative values are left of center, positive values right.
 *
 * Screen projection at draw time:
 * ```
 * screenX = screenWidth / 2 + worldX * heightScale * parallax
 * ```
 *
 * [heightScale] controls the zoom level: taller screens spread objects
 * farther from center; shorter screens compress them.
 */
data class WorldViewportTransform(
    val screenWidth: Float,
    val screenHeight: Float,
    val heightScale: Float
) {
    /** Screen-space center (pixels). */
    val centerX: Float get() = screenWidth / 2f

    /**
     * Convert a world-space x coordinate to screen pixels.
     *
     * @param worldX Position in world-space (0 = center).
     * @param parallax Parallax depth multiplier (higher = more movement).
     */
    fun worldToScreenX(worldX: Float, parallax: Float = 1f): Float {
        return centerX + worldX * heightScale * parallax
    }

    /**
     * Return the visible world-x range for the given parallax depth.
     *
     * Objects whose worldX falls inside this range (plus padding) are
     * potentially visible on screen.
     *
     * @param parallax Parallax depth multiplier.
     * @param paddingWorld Extra world-space padding on each side.
     */
    fun visibleWorldRangeX(
        parallax: Float = 1f,
        paddingWorld: Float = 0f
    ): ClosedFloatingPointRange<Float> {
        val effectiveScale = (heightScale * parallax).coerceAtLeast(0.001f)
        val half = screenWidth / (2f * effectiveScale)
        return (-half - paddingWorld)..(half + paddingWorld)
    }

    companion object {
        /**
         * Compute the integer index range whose cells overlap [left]..[right].
         *
         * Cell *i* occupies `[i * cellSize, (i+1) * cellSize)`.
         *
         * @param left  Left edge of the visible world range.
         * @param right Right edge of the visible world range.
         * @param cellSize Width of one cell in world units (must be > 0).
         */
        fun indexRangeForWorldRange(
            left: Float,
            right: Float,
            cellSize: Float
        ): IntRange {
            require(cellSize > 0f) { "cellSize must be positive" }
            val startIndex = floor(left / cellSize.toDouble()).toInt()
            val endIndex = ceil(right / cellSize.toDouble()).toInt()
            return startIndex..endIndex
        }
    }
}
