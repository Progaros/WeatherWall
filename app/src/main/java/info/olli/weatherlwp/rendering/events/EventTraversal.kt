package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import info.olli.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shared helpers for events that traverse horizontally across the whole screen.
 * Centralizing these calculations avoids per-event drift in spawn/expiry behavior.
 */
object EventTraversal {
    data class HorizontalPath(
        val startX: Float,
        val endX: Float,
        val direction: Float
    ) {
        val distance: Float = abs(endX - startX)
    }

    fun randomHorizontalPath(random: Random, margin: Float): HorizontalPath {
        require(margin > 0f && margin < 0.5f) { "margin must be between 0 and 0.5" }
        val direction = if (random.nextBoolean()) 1f else -1f
        val startX = if (direction > 0f) -margin else 1f + margin
        val endX = if (direction > 0f) 1f + margin else -margin
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
}

/**
 * Draw with parallax offset applied for a given depth layer.
 * Saves canvas state, translates by the parallax offset, runs the draw block,
 * then restores canvas state.
 */
inline fun Canvas.withParallax(
    parallaxCalculator: ParallaxCalculator,
    depthFactor: Float,
    block: Canvas.() -> Unit
) {
    save()
    translate(
        parallaxCalculator.getOffsetXForDepth(depthFactor),
        parallaxCalculator.getOffsetYForDepth(depthFactor)
    )
    block()
    restore()
}
