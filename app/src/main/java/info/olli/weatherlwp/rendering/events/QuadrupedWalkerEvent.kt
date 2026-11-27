package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants

/**
 * Shared boilerplate for ground animals walking a horizontal path along the hill contour.
 *
 * Consumes no random values itself: subclasses keep drawing their own randoms in their
 * original order and expose the results through the abstract properties, which the base
 * only reads after construction.
 */
abstract class QuadrupedWalkerEvent : RandomEvent {
    override val layer = EventLayer.GROUND

    protected abstract val traversal: EventTraversal.HorizontalPath
    protected abstract val speed: Float
    /** Ground Y ratio used when no terrain data is available. */
    protected abstract val fallbackGroundY: Float

    protected val startX: Float get() = traversal.startX
    protected val direction: Float get() = traversal.direction

    protected val path = Path()

    /** Maps raw elapsed time to time spent moving; override for animals that pause. */
    protected open fun movingElapsedMs(elapsedMs: Long): Long = elapsedMs

    protected fun xAt(movingMs: Long): Float = startX + direction * speed * movingMs

    /**
     * X ratio at [screenHeight]; draw() and the sort/preview anchors all go through this.
     * Default scales elapsed time first, then applies [movingElapsedMs].
     */
    protected open fun traversalX(absoluteTimeMs: Long, screenHeight: Float): Float =
        xAt(movingElapsedMs(EventTraversal.scaledElapsed(absoluteTimeMs - spawnTimeMs, screenHeight)))

    protected fun currentX(absoluteTimeMs: Long): Float =
        traversalX(absoluteTimeMs, RenderConstants.REFERENCE_HEIGHT)

    protected fun groundY(x: Float, hillHeightProvider: EventTerrainProvider?): Float =
        hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY

    override fun getCurrentYRatio(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?,
        screenHeight: Float
    ): Float = groundY(traversalX(absoluteTimeMs, screenHeight), hillHeightProvider)

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float = currentX(absoluteTimeMs)

    /** Saves the canvas and applies the parallax offset for [depthFactor]; pair with restore(). */
    protected fun saveParallax(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)
    }

    /** Saves the canvas, moves the origin to ([x], [y]) and mirrors when walking left; pair with restore(). */
    protected fun saveFacing(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        if (direction < 0f) canvas.scale(-1f, 1f)
    }
}
