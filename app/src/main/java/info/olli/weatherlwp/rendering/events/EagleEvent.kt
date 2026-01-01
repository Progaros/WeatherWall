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
 * Eagle with majestic soaring silhouette.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class EagleEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains, between clouds and terrain
    
    private val baseY = 0.2f + random.nextFloat() * 0.15f
    // Reduced speed: was 0.0001-0.00018, now 0.00003-0.00006 (~3x slower)
    private val speed = 0.00003f + random.nextFloat() * 0.00003f
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val path = Path()
    private val size = 18f + random.nextFloat() * 8f
    private val soarPhase = random.nextFloat() * 6.28f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(0.3f)
        val py = parallaxCalculator.getOffsetYForDepth(0.3f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
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
