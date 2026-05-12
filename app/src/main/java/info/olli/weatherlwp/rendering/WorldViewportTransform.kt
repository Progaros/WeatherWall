package info.olli.weatherlwp.rendering

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Central world-to-screen coordinate transform backed by [Viewport].
 *
 * The canonical horizontal coordinate is **worldX** (world-space units):
 * - `worldX = 0` is the scene center (when [centerWorldX] is 0).
 * - Negative values are left of center, positive values right.
 *
 * Screen projection:
 * ```
 * screenX = (worldX - worldRect.left) / worldRect.width * screenWidth
 * ```
 *
 * The [Viewport] computes a world rectangle from [worldHeight] (the zoom
 * control) and screen aspect ratio:
 * - `worldWidth = worldHeight * aspect`
 * - Smaller [worldHeight] → smaller [worldWidth] → camera zoom-in
 * - Objects at fixed worldX project to different screenX when zoom changes
 *
 * Parallax is handled exclusively via canvas.translate() at the call site.
 *
 * @param screenWidth  Screen width in pixels.
 * @param screenHeight Screen height in pixels.
 * @param worldHeight  Visible world height (zoom control). Defaults to
 *                     [RenderConstants.REFERENCE_HEIGHT].
 * @param centerWorldX Camera center in world-space X (default 0).
 */
data class WorldViewportTransform(
    val screenWidth: Float,
    val screenHeight: Float,
    val worldHeight: Float = RenderConstants.REFERENCE_HEIGHT,
    val centerWorldX: Float = 0f
) {
    /** The underlying viewport that defines the visible world rectangle. */
    val viewport: Viewport = Viewport(
        screenWidthPx = screenWidth.toInt().coerceAtLeast(1),
        screenHeightPx = screenHeight.toInt().coerceAtLeast(1),
        centerWorldX = centerWorldX,
        worldHeight = worldHeight
    )

    /** Mapper that projects world coordinates to screen pixels. */
    private val mapper: WorldToScreenMapper = WorldToScreenMapper(viewport)

    /** Pixels per world unit (uniform for X and Y). */
    val heightScale: Float get() = viewport.pixelsPerUnit

    /** Screen-space center (pixels). Retained for call-site compatibility. */
    val centerX: Float get() = screenWidth / 2f

    /** Visible world width derived from worldHeight and screen aspect. */
    val worldWidth: Float get() = viewport.worldWidth

    /**
     * Convert a world-space x coordinate to screen pixels.
     *
     * Maps through the viewport world-rect:
     * `screenX = (worldX - rect.left) / rect.width * screenWidth`
     *
     * Parallax depth is NOT applied here — it is handled by
     * canvas.translate() at the call site.
     */
    fun worldToScreenX(worldX: Float): Float = mapper.x(worldX)

    /**
     * Convert a screen-pixel x coordinate back to world-space.
     */
    fun screenToWorldX(screenX: Float): Float = mapper.worldX(screenX)

    /**
     * Return the visible world-x range for the current viewport.
     *
     * Based on the viewport's world rectangle — shrinks when
     * [worldHeight] decreases (zoom-in).
     *
     * @param paddingWorld Extra world-space padding on each side.
     */
    fun visibleWorldRangeX(
        paddingWorld: Float = 0f
    ): ClosedFloatingPointRange<Float> {
        val rect = viewport.worldRect
        return (rect.left - paddingWorld)..(rect.right + paddingWorld)
    }

    /**
     * Return the visible world range expanded by [paddingWorld] on each side,
     * suitable for generation or culling decisions.
     */
    fun visibleWorldRange(paddingWorld: Float = 0f): ClosedFloatingPointRange<Float> =
        visibleWorldRangeX(paddingWorld)

    /**
     * Return the cell index range that overlaps the visible world range
     * (plus [paddingWorld] on each side).
     */
    fun visibleCellRange(cellSize: Float, paddingWorld: Float = 0f): IntRange {
        val range = visibleWorldRangeX(paddingWorld)
        return indexRangeForWorldRange(range.start, range.endInclusive, cellSize)
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
