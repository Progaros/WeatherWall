package info.olli.weatherlwp.rendering

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * Shared viewport math for full-screen or near-full-screen layers.
 *
 * This keeps layer coverage logic consistent across sky/weather/cloud overlays:
 * - Cover scale chooses the larger axis scale so source fully covers viewport.
 * - Parallax-safe rect adds enough overscan for worst-case layer offset.
 */
object LayerViewportMath {

    private const val MIN_DIMENSION = 1f

    /**
     * Compute a classic "cover" scale so a source rectangle fully covers the viewport.
     */
    fun computeCoverScale(
        viewportWidth: Float,
        viewportHeight: Float,
        sourceWidth: Float,
        sourceHeight: Float
    ): Float {
        val vw = viewportWidth.coerceAtLeast(MIN_DIMENSION)
        val vh = viewportHeight.coerceAtLeast(MIN_DIMENSION)
        val sw = sourceWidth.coerceAtLeast(MIN_DIMENSION)
        val sh = sourceHeight.coerceAtLeast(MIN_DIMENSION)
        return max(vw / sw, vh / sh)
    }

    /**
     * Compute a draw rect that guarantees viewport coverage under parallax offsets.
     *
     * The returned rect is expected to be used in a translated canvas space.
     * If the layer can be translated by [-maxOffsetX,+maxOffsetX] and
     * [-maxOffsetY,+maxOffsetY], this rect keeps the viewport fully covered.
     */
    fun computeParallaxSafeDrawRect(
        viewportWidth: Float,
        viewportHeight: Float,
        maxOffsetX: Float,
        maxOffsetY: Float,
        safetyPx: Float = 3f
    ): RectF {
        val padX = abs(maxOffsetX) + safetyPx.coerceAtLeast(0f)
        val padY = abs(maxOffsetY) + safetyPx.coerceAtLeast(0f)
        return RectF(-padX, -padY, viewportWidth + padX, viewportHeight + padY)
    }
}
