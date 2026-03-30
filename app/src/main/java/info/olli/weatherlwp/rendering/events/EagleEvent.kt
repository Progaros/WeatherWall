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

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        return -0.1f + 1.4f * progress
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedSec = (absoluteTimeMs - spawnTimeMs) / 1000f
        val soarOffset = sin(elapsedSec * 0.8f + soarPhase) * 0.015f * sizeScale
        return baseY + soarOffset
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Scale: ~1-1.5% of height based on distance
        val unit = height * 0.005f * sizeScale
        val size = unit * 5f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
        val soarOffset = sin(timeSeconds * 0.8f + soarPhase) * 0.015f * sizeScale
        val currentY = (baseY + soarOffset) * height
        
        // Alpha based on distance
        val alpha = (1f - distance * 0.25f)
        val birdColor = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 35, 30, 25), ambientColor, 0.35f)
        paint.color = birdColor
        
        // Soaring eagle silhouette - wide outstretched wings
        val wingspan = size * 2.2f
        val bodyLen = size * 0.8f
        
        // Body - elongated oval (thicker for visible silhouette)
        path.reset()
        path.addOval(currentX - bodyLen * 0.5f, currentY - size * 0.18f,
                     currentX + bodyLen * 0.5f, currentY + size * 0.18f, Path.Direction.CW)
        canvas.drawPath(path, paint)
        
        // Left wing - broad with slight upward curve (soaring position)
        // Wings are thicker with more depth
        path.reset()
        path.moveTo(currentX - size * 0.15f, currentY - size * 0.05f)
        path.quadTo(currentX - wingspan * 0.5f, currentY - size * 0.45f,
                    currentX - wingspan, currentY - size * 0.15f)
        // Wing tip - splayed primary feathers
        path.lineTo(currentX - wingspan * 0.95f, currentY + size * 0.05f)
        path.lineTo(currentX - wingspan * 0.88f, currentY + size * 0.12f)
        path.quadTo(currentX - wingspan * 0.5f, currentY - size * 0.05f,
                    currentX - size * 0.15f, currentY + size * 0.12f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Right wing - mirror
        path.reset()
        path.moveTo(currentX + size * 0.15f, currentY - size * 0.05f)
        path.quadTo(currentX + wingspan * 0.5f, currentY - size * 0.45f,
                    currentX + wingspan, currentY - size * 0.15f)
        // Wing tip - splayed primary feathers
        path.lineTo(currentX + wingspan * 0.95f, currentY + size * 0.05f)
        path.lineTo(currentX + wingspan * 0.88f, currentY + size * 0.12f)
        path.quadTo(currentX + wingspan * 0.5f, currentY - size * 0.05f,
                    currentX + size * 0.15f, currentY + size * 0.12f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail - fan shape (wider, more prominent)
        path.reset()
        path.moveTo(currentX - bodyLen * 0.4f, currentY)
        path.lineTo(currentX - bodyLen * 0.7f, currentY - size * 0.22f)
        path.lineTo(currentX - bodyLen * 1.0f, currentY)
        path.lineTo(currentX - bodyLen * 0.7f, currentY + size * 0.22f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Head (slightly larger, more visible)
        canvas.drawCircle(currentX + bodyLen * 0.45f, currentY - size * 0.02f, size * 0.16f, paint)
        
        // Beak (hooked, characteristic of raptors)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((220 * alpha).toInt(), 200, 180, 60), ambientColor, 0.3f)
        path.reset()
        path.moveTo(currentX + bodyLen * 0.6f, currentY - size * 0.04f)
        path.lineTo(currentX + bodyLen * 0.75f, currentY + size * 0.02f)
        path.lineTo(currentX + bodyLen * 0.6f, currentY + size * 0.06f)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}
