package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shared three-segment leg with an oval foot, used by the walking ground animals.
 * Hind legs: hip -> stifle (forward) -> hock (backward) -> paw.
 * Fore legs: shoulder -> elbow (backward) -> wrist/carpus -> paw.
 */
internal object JointedLeg {

    fun draw(
        canvas: Canvas,
        path: Path,
        strokePaint: Paint,
        fillPaint: Paint,
        hipX: Float, hipY: Float,
        j1X: Float, j1Y: Float,
        j2X: Float, j2Y: Float,
        footX: Float, footY: Float,
        legWidth: Float,
        legColor: Int,
        footColor: Int,
        footSize: Float,
        footBottomRatio: Float,
        upperWidth: Float = legWidth
    ) {
        strokePaint.color = legColor
        strokePaint.strokeWidth = legWidth
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(hipX, hipY)
        path.lineTo(j1X, j1Y)
        path.lineTo(j2X, j2Y)
        path.lineTo(footX, footY)
        canvas.drawPath(path, strokePaint)
        // Overdrawn thigh; leg colours are opaque, so the overlap does not darken.
        if (upperWidth > legWidth) {
            strokePaint.strokeWidth = upperWidth
            canvas.drawLine(hipX, hipY, j1X, j1Y, strokePaint)
        }
        fillPaint.color = footColor
        canvas.drawOval(footX - footSize, footY - footSize, footX + footSize, footY + footSize * footBottomRatio, fillPaint)
    }

    /**
     * [draw] with the upper joint solved by two-bone IK between the hip and the lower
     * joint (j2), so segment lengths stay constant through the gait. [bendForward] puts
     * the joint in front of the hip->j2 line (stifle) or behind it (elbow).
     */
    fun drawSolved(
        canvas: Canvas,
        path: Path,
        strokePaint: Paint,
        fillPaint: Paint,
        hipX: Float, hipY: Float,
        upperLen: Float, midLen: Float, bendForward: Boolean,
        j2X: Float, j2Y: Float,
        footX: Float, footY: Float,
        legWidth: Float,
        legColor: Int,
        footColor: Int,
        footSize: Float,
        footBottomRatio: Float,
        upperWidth: Float = legWidth
    ) {
        val dx = j2X - hipX
        val dy = j2Y - hipY
        val dist = sqrt(dx * dx + dy * dy)
        val ux = if (dist > 1e-4f) dx / dist else 0f
        val uy = if (dist > 1e-4f) dy / dist else 1f
        // Out of reach: the joint lands on the hip->j2 line and the leg reads as straight.
        val reach = dist.coerceIn(abs(upperLen - midLen), upperLen + midLen).coerceAtLeast(1e-4f)
        val along = (upperLen * upperLen - midLen * midLen + reach * reach) / (2f * reach)
        val out = sqrt((upperLen * upperLen - along * along).coerceAtLeast(0f))
        val side = if (bendForward) out else -out
        val j1X = hipX + ux * along + uy * side
        val j1Y = hipY + uy * along - ux * side
        draw(canvas, path, strokePaint, fillPaint, hipX, hipY, j1X, j1Y, j2X, j2Y, footX, footY,
            legWidth, legColor, footColor, footSize, footBottomRatio, upperWidth)
    }
}
