package info.olli.weatherlwp.rendering

/**
 * Axis-aligned rectangle in world coordinates.
 */
data class WorldRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    /**
     * Expand the rectangle by [padX] on each horizontal side
     * and [padY] on each vertical side.
     */
    fun expand(padX: Float, padY: Float): WorldRect = WorldRect(
        left - padX, top - padY,
        right + padX, bottom + padY
    )
}

/**
 * Defines the visible world rectangle and its mapping to screen pixels.
 *
 * [worldHeight] is the primary zoom control.
 * [worldWidth] is derived from worldHeight, screen aspect, and an optional
 * horizontal zoom factor so that both axes shrink/grow together.
 *
 * For parallax layers, build a per-layer viewport by adjusting [centerWorldX]
 * based on parallaxFactor.
 *
 * @param screenWidthPx   Screen width in pixels.
 * @param screenHeightPx  Screen height in pixels.
 * @param centerWorldX    Center of the camera in world-space X.
 * @param centerWorldY    Center of the camera in world-space Y.
 * @param worldHeight     Visible world height (zoom control).
 * @param horizontalZoomFactor Extra horizontal stretch (1 = normal).
 */
data class Viewport(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val centerWorldX: Float = 0f,
    val centerWorldY: Float = 0.5f,
    val worldHeight: Float = 1f,
    val horizontalZoomFactor: Float = 1f
) {
    val aspect: Float
        get() = if (screenHeightPx > 0) screenWidthPx.toFloat() / screenHeightPx else 1f

    val worldWidth: Float
        get() = worldHeight * aspect * horizontalZoomFactor

    val worldRect: WorldRect
        get() = WorldRect(
            left = centerWorldX - worldWidth * 0.5f,
            top = centerWorldY - worldHeight * 0.5f,
            right = centerWorldX + worldWidth * 0.5f,
            bottom = centerWorldY + worldHeight * 0.5f
        )

    /** Pixels per world unit (uniform for X and Y). */
    val pixelsPerUnit: Float
        get() = if (worldHeight > 0f) screenHeightPx / worldHeight else 1f
}

/**
 * Maps world coordinates to screen pixels through a [Viewport].
 *
 * Reusable per frame: create once with the current viewport, use for all
 * drawing calls, then discard (no per-frame allocations beyond this object).
 */
class WorldToScreenMapper(val viewport: Viewport) {

    // Cache rect to avoid recomputing each call
    private val rect = viewport.worldRect
    private val invWidth = if (rect.width > 0f) 1f / rect.width else 1f
    private val invHeight = if (rect.height > 0f) 1f / rect.height else 1f

    /** Map a world-space X to screen pixels. */
    fun x(worldX: Float): Float =
        (worldX - rect.left) * invWidth * viewport.screenWidthPx

    /** Map a world-space Y to screen pixels. */
    fun y(worldY: Float): Float =
        (worldY - rect.top) * invHeight * viewport.screenHeightPx

    /** Convert a world-space length (e.g. radius, height) to screen pixels. */
    fun length(worldLength: Float): Float =
        worldLength * viewport.pixelsPerUnit

    /** Convert a screen-pixel X back to world X. */
    fun worldX(screenX: Float): Float =
        screenX / viewport.screenWidthPx * rect.width + rect.left

    /** Convert a screen-pixel Y back to world Y. */
    fun worldY(screenY: Float): Float =
        screenY / viewport.screenHeightPx * rect.height + rect.top
}
