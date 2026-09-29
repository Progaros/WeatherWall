package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Shared hip -> knee -> foot stroke with an oval foot, used by the walking ground animals. */
internal object JointedLeg {

    fun draw(
        canvas: Canvas,
        path: Path,
        strokePaint: Paint,
        fillPaint: Paint,
        hipX: Float, hipY: Float,
        kneeX: Float, kneeY: Float,
        footX: Float, footY: Float,
        legWidth: Float,
        legColor: Int,
        footColor: Int,
        footSize: Float,
        footBottomRatio: Float
    ) {
        strokePaint.color = legColor
        strokePaint.strokeWidth = legWidth
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(hipX, hipY)
        path.lineTo(kneeX, kneeY)
        path.lineTo(footX, footY)
        canvas.drawPath(path, strokePaint)
        fillPaint.color = footColor
        canvas.drawOval(footX - footSize, footY - footSize, footX + footSize, footY + footSize * footBottomRatio, fillPaint)
    }
}
