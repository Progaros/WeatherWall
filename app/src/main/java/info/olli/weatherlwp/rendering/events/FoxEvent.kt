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
 * Fox with proper tail and ears using local coordinate system
 */
class FoxEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.82f + random.nextFloat() * 0.06f
    private var walkPhase = 0f
    private val size = 0.7f + random.nextFloat() * 0.3f
    private val path = Path()
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += direction * 0.00008f * deltaTimeMs
        walkPhase += deltaTimeMs * 0.015f
        return x > -0.15f && x < 1.15f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.10f)
        val py = parallaxCalculator.getOffsetYForDepth(-0.10f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width - extendedOffset
        val cy = groundY * height
        val unit = minOf(width, height)
        val s = size * unit * 0.020f
        val flip = if (direction > 0) 1f else -1f
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        
        // Tail (bushy, horizontal, low angle with offset white tip)
        path.reset()
        path.moveTo(-2.0f*s, -0.4f*s)
        path.cubicTo(-3.2f*s, -0.2f*s, -4.0f*s, -0.6f*s, -4.2f*s, -0.3f*s)
        path.cubicTo(-4.0f*s, 0.0f*s, -3.0f*s, 0.2f*s, -2.0f*s, 0.0f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        // Body
        canvas.drawOval(-2.4f*s, -1.2f*s, 2.0f*s, 0.2f*s, paint)
        
        // Head + snout
        canvas.drawOval(1.7f*s, -1.8f*s, 3.2f*s, -0.6f*s, paint)
        paint.color = Color.rgb(240, 200, 180)
        canvas.drawOval(2.7f*s, -1.4f*s, 3.7f*s, -0.9f*s, paint)
        
        // Legs
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        val step = sin(walkPhase) * 0.25f*s
        canvas.drawRoundRect(-1.8f*s, 0.1f*s, -1.4f*s, 1.0f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(-0.8f*s, 0.1f*s, -0.4f*s, 1.0f*s - step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(0.6f*s, 0.1f*s, 1.0f*s, 1.0f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(1.4f*s, 0.1f*s, 1.8f*s, 1.0f*s - step, 0.2f*s, 0.2f*s, paint)
        
        // Tail tip (white)
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(-4.0f*s, -0.3f*s, 0.35f*s, paint)
        
        // Ears
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        path.reset()
        path.moveTo(2.2f*s, -1.6f*s)
        path.lineTo(2.0f*s, -2.4f*s)
        path.lineTo(2.6f*s, -1.6f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(2.8f*s, -1.6f*s)
        path.lineTo(2.9f*s, -2.3f*s)
        path.lineTo(3.2f*s, -1.6f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
