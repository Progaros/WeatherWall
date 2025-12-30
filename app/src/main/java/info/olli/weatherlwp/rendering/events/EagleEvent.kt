package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Eagle with majestic soaring silhouette
 */
class EagleEvent(random: Random) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains, between clouds and terrain
    
    private var progress = -0.1f
    private val baseY = 0.2f + random.nextFloat() * 0.15f
    private val speed = 0.0001f + random.nextFloat() * 0.00008f
    private val path = Path()
    private val size = 18f + random.nextFloat() * 8f
    private val soarPhase = random.nextFloat() * 6.28f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        progress += speed * deltaTimeMs
        return progress < 1.3f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.3f)
        val py = parallaxCalculator.getOffsetYForDepth(0.3f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress
        val soarOffset = sin(timeSeconds * 0.8f + soarPhase) * 0.02f
        val currentY = (baseY + soarOffset) * height
        
        strokePaint.color = RenderConstants.blendWithAmbient(Color.rgb(40, 35, 30), ambientColor, 0.35f)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = (size * 0.18f).coerceAtLeast(2f)
        
        val span = size * 2.2f
        val camber = size * 0.45f
        
        path.reset()
        path.moveTo(currentX - span, currentY)
        path.quadTo(currentX - size, currentY - camber, currentX, currentY)
        path.quadTo(currentX + size, currentY - camber, currentX + span, currentY)
        canvas.drawPath(path, strokePaint)
        
        paint.color = strokePaint.color
        canvas.drawCircle(currentX + size * 0.25f, currentY, size * 0.18f, paint)
        
        canvas.restore()
    }
}
