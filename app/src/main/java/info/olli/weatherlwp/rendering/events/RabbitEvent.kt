package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rabbit with bunny silhouette using local coordinate system
 */
class RabbitEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.05f else 1.05f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.85f + random.nextFloat() * 0.05f
    private var hopPhase = 0f
    private var hopTimer = 0L
    private val size = 0.5f + random.nextFloat() * 0.2f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        hopTimer += deltaTimeMs
        hopPhase = (hopTimer * 0.01f) % (2f * 3.14159f)
        if (sin(hopPhase) > 0.5f) {
            x += direction * 0.0002f * deltaTimeMs
        }
        return x > -0.1f && x < 1.1f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.12f)
        val py = parallaxCalculator.getOffsetYForDepth(-0.12f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width - extendedOffset
        val unit = minOf(width, height)
        val hopHeight = (sin(hopPhase).coerceAtLeast(0f)) * unit * 0.02f * size
        val cy = groundY * height - hopHeight
        val s = size * unit * 0.015f
        val flip = if (direction > 0) 1f else -1f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        // Body + haunch
        canvas.drawOval(-1.8f*s, -0.9f*s, 1.6f*s, 0.8f*s, paint)
        canvas.drawOval(-2.3f*s, -0.6f*s, -1.0f*s, 0.6f*s, paint)
        
        // Head
        canvas.drawOval(1.0f*s, -1.2f*s, 2.4f*s, -0.1f*s, paint)
        
        // Ears
        canvas.drawRoundRect(1.4f*s, -3.0f*s, 1.8f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        canvas.drawRoundRect(1.9f*s, -2.8f*s, 2.3f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        
        // Tail (fluffy white)
        paint.color = Color.rgb(200, 190, 180)
        canvas.drawCircle(-2.2f*s, 0.0f, 0.45f*s, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
