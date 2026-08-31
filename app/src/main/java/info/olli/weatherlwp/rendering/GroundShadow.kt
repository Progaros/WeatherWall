package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Single ground-shadow model shared by every upright object in the scene.
 *
 * Previously needle trees drew a height-scaled wedge, round trees drew a
 * crown-radius ellipse and bushes drew nothing at all, so neighbouring objects
 * disagreed about where the light was coming from.
 */
object GroundShadow {

    /**
     * Projects an upright object onto the ground as a flat streak running along
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

        val tipX = baseX + state.shadowDirX * objectHeight * state.shadowLengthFactor() + tipSwayX
        val centerX = (baseX + tipX) * 0.5f
        val halfLength = kotlin.math.abs(tipX - baseX) * 0.5f + objectWidth * 0.3f
        val halfThickness = (objectHeight * 0.022f).coerceAtLeast(1.2f)

        paint.color = Color.argb(alpha, 0, 0, 0)
        canvas.drawOval(
            centerX - halfLength, groundY - halfThickness,
            centerX + halfLength, groundY + halfThickness,
            paint
        )
    }
}
