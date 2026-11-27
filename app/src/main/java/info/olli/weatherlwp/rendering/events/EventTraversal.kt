package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shared helpers for events that traverse horizontally across the whole screen.
 * Centralizing these calculations avoids per-event drift in spawn/expiry behavior.
 */
object EventTraversal {

    /** Minimum movement scale — prevents events from appearing frozen on tiny widgets. */
    const val MIN_HEIGHT_SCALE = 0.25f
    /** Maximum movement scale — prevents events from being too fast on large screens. */
    const val MAX_HEIGHT_SCALE = 4.0f

    /**
     * Movement scale factor for screen-overlay events.
     *
     * Events are authored at [RenderConstants.REFERENCE_HEIGHT]. When the screen
     * height is smaller (landscape, widget, zoomed-in), their traversal speed is
     * reduced proportionally so they don't look too fast relative to the visible
     * world slice.
     *
     * - At REFERENCE_HEIGHT (1920): returns 1.0 (unchanged speed)
     * - At smaller heights: returns < 1.0 (slower — viewport sees less world)
     * - At larger heights: returns > 1.0 (faster — viewport sees more world)
     */
    fun heightScale(screenHeight: Float): Float =
        (screenHeight / RenderConstants.REFERENCE_HEIGHT).coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)

    /**
     * Scale elapsed time for horizontal traversal position.
     *
     * Use this in event draw() to scale only the traversal component of
     * elapsed time. Animation timing (wing flaps, wobble) should NOT use this.
     */
    fun scaledElapsed(elapsedMs: Long, screenHeight: Float): Long =
        (elapsedMs * heightScale(screenHeight)).toLong()

    data class HorizontalPath(
        val startX: Float,
        val endX: Float,
        val direction: Float
    ) {
        val distance: Float = abs(endX - startX)
    }

    fun randomHorizontalPath(random: Random, margin: Float, viewportRatio: Float = 1.0f): HorizontalPath {
        require(margin > 0f && margin < 0.5f) { "margin must be between 0 and 0.5" }
        val vr = viewportRatio.coerceIn(0.1f, 1.0f)
        // Viewport edges in extended-width ratio space (centered)
        val viewLeft = (1f - vr) / 2f
        val viewRight = (1f + vr) / 2f
        val direction = if (random.nextBoolean()) 1f else -1f
        val startX = if (direction > 0f) viewLeft - margin else viewRight + margin
        val endX = if (direction > 0f) viewRight + margin else viewLeft - margin
        return HorizontalPath(startX = startX, endX = endX, direction = direction)
    }

    fun durationMsForPath(
        path: HorizontalPath,
        speed: Float,
        pauseIntervalMs: Long = 0L,
        pauseDurationMs: Long = 0L,
        minDurationMs: Long = 1000L
    ): Long {
        val safeSpeed = speed.coerceAtLeast(1e-7f)
        val movingMs = path.distance / safeSpeed
        val withPauses = if (pauseIntervalMs > 0L && pauseDurationMs > 0L) {
            val pauseMultiplier = 1f + (pauseDurationMs.toFloat() / pauseIntervalMs.toFloat())
            movingMs * pauseMultiplier + pauseDurationMs
        } else {
            movingMs
        }
        return withPauses.toLong().coerceAtLeast(minDurationMs)
    }

    fun xAtProgress(path: HorizontalPath, progress: Float): Float {
        return path.startX + (path.endX - path.startX) * progress
    }

    /**
     * Map a Y ratio (0..1) to screen pixels using the same uniform field scaling
     * as [SkyViewportMapper]. Keeps sizing based on [height] (screen height) while
     * applying the vertical field overflow from [extendedOffsetY].
     *
     * Formula: `yRatio * (height + 2*extendedOffsetY) - extendedOffsetY`
     * which equals `yRatio * fieldHeight - offsetY`.
     */
    fun mapFieldY(yRatio: Float, height: Float, extendedOffsetY: Float): Float =
        yRatio * (height + 2f * extendedOffsetY) - extendedOffsetY
}
