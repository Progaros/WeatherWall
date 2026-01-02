package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fireflies - Summer night, multiple flickering points.
 * Spawn in the foreground hill area (lower portion of screen).
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class FireflyEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND  // In front of hills, integrated with terrain
    
    override val durationMs = 8000L + random.nextLong(5000)  // 8-13 seconds
    private val flies = List(8 + random.nextInt(12)) { Firefly(random) }
    // Position in foreground hill area (lower 40% of screen, above bottom margin)
    private val baseX = 0.15f + random.nextFloat() * 0.7f
    private val baseY = 0.65f + random.nextFloat() * 0.25f  // 0.65 to 0.90 - on hills
    
    // Fireflies are in the front hills area (depth 0.70-0.80)
    override val depthFactor: Float = 0.70f + random.nextFloat() * 0.10f
    
    private class Firefly(random: Random) {
        val offsetX = (random.nextFloat() - 0.5f) * 0.15f
        val offsetY = (random.nextFloat() - 0.5f) * 0.12f
        val phase = random.nextFloat() * 6.28f
        val speed = 0.2f + random.nextFloat() * 0.5f  // Slowed 3x (was too fast)
        val driftX = (random.nextFloat() - 0.5f) * 0.00003f  // Slowed 3x
        val driftY = (random.nextFloat() - 0.5f) * 0.000025f // Slowed 3x
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Scale size to 0.1% of height - fireflies are tiny!
        val unit = height * 0.001f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Use foreground depth for parallax (matches front hills where fireflies float)
        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)
        
        for (fly in flies) {
            // Calculate drift from elapsed time
            val currentOffsetX = fly.offsetX + fly.driftX * elapsedMs
            val currentOffsetY = fly.offsetY + fly.driftY * elapsedMs
            
            val x = (baseX + currentOffsetX) * width - extendedOffset
            val y = (baseY + currentOffsetY) * height
            
            val flicker = sin(timeSeconds * fly.speed * 3f + fly.phase)
            if (flicker > 0.2f) {
                val alpha = ((flicker - 0.2f) / 0.8f * 200).toInt().coerceIn(0, 200)
                paint.color = Color.argb(alpha, 200, 255, 100)
                canvas.drawCircle(x, y, (2.5f + flicker) * unit, paint)
                
                paint.color = Color.argb(alpha / 3, 200, 255, 100)
                canvas.drawCircle(x, y, (5f + flicker * 2) * unit, paint)
            }
        }
        
        canvas.restore()
    }
}
