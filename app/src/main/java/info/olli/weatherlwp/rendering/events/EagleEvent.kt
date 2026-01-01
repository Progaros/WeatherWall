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
 * Distance affects size, speed, and parallax for depth perception.
 */
class EagleEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.2-0.7 (eagles can be fairly close)
    private val distance = 0.2f + random.nextFloat() * 0.5f
    
    // Size scales inversely with distance
    private val sizeScale = 1.0f - distance * 0.5f  // 0.65-1.0 based on distance
    
    // Far eagles soar higher
    private val baseY = 0.15f + random.nextFloat() * 0.12f + distance * 0.08f
    
    // Speed scales with distance (far = slower)
    private val baseSpeed = 0.00004f - distance * 0.00002f
    private val speed = baseSpeed + random.nextFloat() * 0.00002f
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val path = Path()
    private val soarPhase = random.nextFloat() * 6.28f
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.2f + distance * 0.2f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long) {
        // Scale: 0.5-0.8% of height based on distance (was 1%)
        val unit = height * 0.006f * sizeScale
        val size = unit * 12f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
        // Less soaring movement for distant eagles
        val soarOffset = sin(timeSeconds * 0.8f + soarPhase) * 0.015f * sizeScale
        val currentY = (baseY + soarOffset) * height
        
        // Distant eagles are slightly faded
        val alpha = (1f - distance * 0.25f)
        strokePaint.color = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 40, 35, 30), ambientColor, 0.35f)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = (size * 0.15f).coerceAtLeast(1.5f)
        
        val span = size * 2f
        val camber = size * 0.4f
        
        path.reset()
        path.moveTo(currentX - span, currentY)
        path.quadTo(currentX - size * 0.9f, currentY - camber, currentX, currentY)
        path.quadTo(currentX + size * 0.9f, currentY - camber, currentX + span, currentY)
        canvas.drawPath(path, strokePaint)
        
        // Head (only visible for closer eagles)
        if (distance < 0.5f) {
            paint.color = strokePaint.color
            canvas.drawCircle(currentX + size * 0.2f, currentY, size * 0.15f, paint)
        }
        
        canvas.restore()
    }
}
