package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fireflies - Summer night, multiple flickering points
 */
class FireflyEvent(random: Random) : RandomEvent {
    private var lifetime = 0L
    private val maxLifetime = 12000L + random.nextLong(8000)
    private val flies = List(8 + random.nextInt(12)) { Firefly(random) }
    private val baseX = 0.2f + random.nextFloat() * 0.6f
    private val baseY = 0.55f + random.nextFloat() * 0.2f
    
    private class Firefly(random: Random) {
        val offsetX = (random.nextFloat() - 0.5f) * 0.15f
        val offsetY = (random.nextFloat() - 0.5f) * 0.12f
        val phase = random.nextFloat() * 6.28f
        val speed = 0.5f + random.nextFloat() * 1.5f
        val driftX = (random.nextFloat() - 0.5f) * 0.0001f
        val driftY = (random.nextFloat() - 0.5f) * 0.00008f
        var currentOffsetX = offsetX
        var currentOffsetY = offsetY
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        lifetime += deltaTimeMs
        for (fly in flies) {
            fly.currentOffsetX += fly.driftX * deltaTimeMs
            fly.currentOffsetY += fly.driftY * deltaTimeMs
        }
        return lifetime < maxLifetime
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(-0.05f)
        canvas.save()
        canvas.translate(px, py)
        
        for (fly in flies) {
            val x = (baseX + fly.currentOffsetX) * width
            val y = (baseY + fly.currentOffsetY) * height
            
            val flicker = sin(timeSeconds * fly.speed * 3f + fly.phase)
            if (flicker > 0.2f) {
                val alpha = ((flicker - 0.2f) / 0.8f * 200).toInt().coerceIn(0, 200)
                paint.color = Color.argb(alpha, 200, 255, 100)
                canvas.drawCircle(x, y, 2.5f + flicker, paint)
                
                paint.color = Color.argb(alpha / 3, 200, 255, 100)
                canvas.drawCircle(x, y, 5f + flicker * 2, paint)
            }
        }
        
        canvas.restore()
    }
}
