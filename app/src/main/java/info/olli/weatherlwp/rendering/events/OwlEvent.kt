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
 * Owl silhouette - Night only, slow wing beats, gliding across
 */
class OwlEvent(random: Random) : RandomEvent {
    private var progress = -0.1f
    private val y = 0.18f + random.nextFloat() * 0.12f
    private val speed = 0.00008f + random.nextFloat() * 0.00004f
    private val path = Path()
    private val size = 14f + random.nextFloat() * 6f
    private val wingPhase = random.nextFloat() * 6.28f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        progress += speed * deltaTimeMs
        return progress < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.25f)
        val py = parallaxCalculator.getOffsetYForDepth(0.25f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = progress * 1.2f * width
        val currentY = y * height
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(35, 30, 25), ambientColor, 0.5f)
        
        val wingY = sin(timeSeconds * 1.2f + wingPhase) * size * 0.3f
        
        canvas.drawOval(currentX - size * 0.4f, currentY - size * 0.5f,
            currentX + size * 0.4f, currentY + size * 0.5f, paint)
        
        strokePaint.color = paint.color
        strokePaint.strokeWidth = size * 0.25f
        path.reset()
        path.moveTo(currentX - size * 0.3f, currentY)
        path.quadTo(currentX - size * 1.2f, currentY - wingY, currentX - size * 1.5f, currentY + size * 0.2f)
        canvas.drawPath(path, strokePaint)
        
        path.reset()
        path.moveTo(currentX + size * 0.3f, currentY)
        path.quadTo(currentX + size * 1.2f, currentY - wingY, currentX + size * 1.5f, currentY + size * 0.2f)
        canvas.drawPath(path, strokePaint)
        
        canvas.drawCircle(currentX, currentY - size * 0.4f, size * 0.35f, paint)
        
        paint.color = Color.argb(180, 255, 220, 100)
        canvas.drawCircle(currentX - size * 0.12f, currentY - size * 0.45f, size * 0.08f, paint)
        canvas.drawCircle(currentX + size * 0.12f, currentY - size * 0.45f, size * 0.08f, paint)
        
        canvas.restore()
    }
}
