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
 * screenX = screenWidth / 2 + worldX * heightScale
 * ```
 *
 * [heightScale] controls the zoom level (pixels per world unit): taller
 * screens spread objects farther from center; shorter screens compress them.
 *
 * Parallax is handled exclusively via canvas.translate() at the call site,
 * which shifts the effective camera center per depth layer. This preserves
 * intra-layer spacing: two objects in the same layer always have screen
 * distance = (worldX2 - worldX1) * heightScale, regardless of depth.
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
     * Parallax depth is NOT applied here — it is handled by
     * canvas.translate() at the call site (camera-center model).
     *
     * @param worldX Position in world-space (0 = center).
     */
    fun worldToScreenX(worldX: Float): Float {
        return centerX + worldX * heightScale
    }

    /**
     * Convert a screen-pixel x coordinate back to world-space.
     *
     * Inverse of [worldToScreenX]:
     * `worldX = (screenX - centerX) / heightScale`
     */
    fun screenToWorldX(screenX: Float): Float {
        return (screenX - centerX) / heightScale
    }

    /**
     * Return the visible world-x range for the current viewport.
     *
     * Objects whose worldX falls inside this range (plus padding) are
     * potentially visible on screen.
     *
     * @param paddingWorld Extra world-space padding on each side.
     */
    fun visibleWorldRangeX(
        paddingWorld: Float = 0f
    ): ClosedFloatingPointRange<Float> {
        val half = screenWidth / (2f * heightScale.coerceAtLeast(0.001f))
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
