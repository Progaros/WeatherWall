package info.olli.weatherlwp.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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

    /**
     * Lazily builds the shared soft mask. The core is a feathered round blob (vertical
     * softness) multiplied by a horizontal ramp that runs from opaque at x=0 to fully
     * transparent at x=MASK_SIZE, so the stamp is dense where it touches the object's
     * base and dissolves toward the far tip - a contact shadow rather than a flat streak.
     */
    private fun softMask(): Bitmap {
        mask?.let { return it }
        val size = MASK_SIZE
        val sizeF = size.toFloat()
        val radius = size / 2f
        val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(src)
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                radius, radius, radius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        maskCanvas.drawCircle(radius, radius, radius, gradientPaint)

        gradientPaint.shader = LinearGradient(
            0f, 0f, sizeF, 0f,
            intArrayOf(Color.WHITE, Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        gradientPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        maskCanvas.drawRect(0f, 0f, sizeF, sizeF, gradientPaint)

        val alphaOnly = src.extractAlpha()
        src.recycle()
        mask = alphaOnly
        return alphaOnly
    }

    /**
     * Projects an upright object onto the ground as a soft streak running along
     * [SimulationState.shadowDirX], with length driven by the light elevation.
     *
     * @param tipSwayX horizontal displacement of the far end, so a swaying plant's shadow sways too.
     * @param clipPath ground silhouette the shadow must stay inside, in the caller's current
     *   canvas space. Without it a shadow cast from a hilltop would spill over the ridge
     *   into the sky.
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        state: SimulationState,
        baseX: Float,
        groundY: Float,
        objectHeight: Float,
        objectWidth: Float,
        tipSwayX: Float = 0f,
        clipPath: Path? = null
    ) {
        val alpha = state.groundShadowAlpha()
        if (alpha <= 0) return
        drawRaw(
            canvas, paint, baseX, groundY, objectHeight, objectWidth,
            state.shadowDirX, state.shadowLengthFactor(), alpha, tipSwayX, clipPath
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
        tipSwayX: Float = 0f,
        clipPath: Path? = null
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

        val needsRestore = clipPath != null || tipX < baseX
        if (needsRestore) canvas.save()
        if (clipPath != null) canvas.clipPath(clipPath)
        // The mask fades toward +X, so mirror it when the light throws the shadow leftward.
        // destRect is symmetric about centerX, so the mirrored stamp lands on the same rect.
        if (tipX < baseX) canvas.scale(-1f, 1f, centerX, groundY)
        canvas.drawBitmap(softMask(), null, destRect, paint)
        if (needsRestore) canvas.restore()
    }
}
