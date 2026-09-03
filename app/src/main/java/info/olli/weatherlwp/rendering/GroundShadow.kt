package info.olli.weatherlwp.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Single ground-shadow model shared by every upright object in the scene.
 *
 * Previously needle trees drew a height-scaled wedge, round trees drew a
 * crown-radius ellipse and bushes drew nothing at all, so neighbouring objects
 * disagreed about where the light was coming from.
 *
 * Shadows are stamped from a single soft, alpha-only gradient bitmap (feathered
 * edge) instead of a hard-edged oval, so they read as soft contact shadows
 * rather than solid dark blobs.
 */
object GroundShadow {

    private const val MASK_SIZE = 64
    private var mask: Bitmap? = null

    // Reused across calls to avoid a per-frame RectF allocation.
    private val destRect = RectF()

    /** Lazily builds the shared soft round mask: opaque core fading to transparent at the rim. */
    private fun softMask(): Bitmap {
        mask?.let { return it }
        val size = MASK_SIZE
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(bmp)
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                radius, radius, radius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        maskCanvas.drawCircle(radius, radius, radius, gradientPaint)
        mask = bmp
        return bmp
    }

    /**
     * Projects an upright object onto the ground as a soft streak running along
     * [SimulationState.shadowDirX], with length driven by the light elevation.
     *
     * @param tipSwayX horizontal displacement of the far end, so a swaying plant's shadow sways too.
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        state: SimulationState,
        baseX: Float,
        groundY: Float,
        objectHeight: Float,
        objectWidth: Float,
        tipSwayX: Float = 0f
    ) {
        val alpha = state.groundShadowAlpha()
        if (alpha <= 0) return
        drawRaw(
            canvas, paint, baseX, groundY, objectHeight, objectWidth,
            state.shadowDirX, state.shadowLengthFactor(), alpha, tipSwayX
        )
    }

    /**
     * Same projection as [draw] but takes the light direction/length/alpha directly instead
     * of a [SimulationState], for callers (e.g. baked decoration bitmaps) that only have a
     * handful of cached shadow scalars rather than a full state snapshot.
     */
    fun drawRaw(
        canvas: Canvas,
        paint: Paint,
        baseX: Float,
        groundY: Float,
        objectHeight: Float,
        objectWidth: Float,
        shadowDirX: Float,
        shadowLengthFactor: Float,
        alpha: Int,
        tipSwayX: Float = 0f
    ) {
        if (alpha <= 0) return

        val tipX = baseX + shadowDirX * objectHeight * shadowLengthFactor + tipSwayX
        val centerX = (baseX + tipX) * 0.5f
        val halfLength = kotlin.math.abs(tipX - baseX) * 0.5f + objectWidth * 0.3f
        val halfThickness = (objectHeight * 0.03f).coerceAtLeast(1.6f)

        // ALPHA_8 mask: RGB comes from the paint color, shape/softness from the bitmap alpha.
        paint.color = Color.BLACK
        paint.alpha = alpha
        paint.isFilterBitmap = true
        destRect.set(centerX - halfLength, groundY - halfThickness, centerX + halfLength, groundY + halfThickness)
        canvas.drawBitmap(softMask(), null, destRect, paint)
    }
}
