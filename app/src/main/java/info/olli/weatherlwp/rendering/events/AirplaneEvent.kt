package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.random.Random

/**
 * Airplane with contrail - distant jet crossing the sky.
 * Uses distance-based scaling for realistic appearance.
 * Distance 0.0=near, 1.0=far affects size and speed.
 */
class AirplaneEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.6-1.0 (always fairly far, planes are high up)
    private val distance = 0.6f + random.nextFloat() * 0.4f
    
    // Size scales inversely with distance (far planes are smaller)
    // Base size scaled by 0.3-0.7 depending on distance
    private val sizeScale = 0.7f - distance * 0.4f
    
    // Speed scales inversely with distance (far planes appear slower)
    private val baseSpeed = 0.00004f - distance * 0.00002f  // Slower at distance
    private val speed = baseSpeed + random.nextFloat() * 0.00001f
    
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val y = 0.06f + random.nextFloat() * 0.12f + distance * 0.05f  // Higher when farther
    private val path = Path()
    private val contrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.05f + distance * 0.15f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long) {
        // Scale: 0.4-0.8% of height based on distance (was 1%)
        val unit = height * 0.004f * sizeScale
        val size = unit * 8f  // Base airplane size
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = (elapsedMs * speed).coerceIn(-0.1f, 1.4f)
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
        val currentY = y * height
        
        // Contrail width based on distance
        contrailPaint.strokeWidth = (4f - distance * 2f).coerceAtLeast(1.5f)
        
        // Contrail with gradient fade
        val contrailLength = width * (0.25f + (1f - distance) * 0.15f)
        val contrailStartX = (currentX - contrailLength).coerceAtLeast(-extendedOffset)
        val contrailEndX = currentX - size * 2.5f
        if (contrailEndX > contrailStartX) {
            contrailPaint.shader = android.graphics.LinearGradient(
                contrailStartX, currentY,
                contrailEndX, currentY,
                Color.argb(0, 255, 255, 255),
                Color.argb((60 + (1f - distance) * 40).toInt(), 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawLine(contrailStartX, currentY, contrailEndX, currentY, contrailPaint)
            contrailPaint.shader = null
        }
        
        // Plane body - simplified silhouette (more realistic for distance)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(80, 80, 90), ambientColor, 0.4f)
        
        // Fuselage (elongated ellipse)
        val fusW = size * 2.5f
        val fusH = size * 0.35f
        canvas.drawOval(
            currentX - fusW, currentY - fusH,
            currentX, currentY + fusH,
            paint
        )
        
        // Wings (simple swept triangle)
        path.reset()
        path.moveTo(currentX - size * 1.2f, currentY)
        path.lineTo(currentX - size * 0.6f, currentY - size * 0.6f)
        path.lineTo(currentX - size * 0.3f, currentY)
        path.lineTo(currentX - size * 0.6f, currentY + size * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail fin (small triangle)
        path.reset()
        path.moveTo(currentX - fusW, currentY)
        path.lineTo(currentX - fusW - size * 0.3f, currentY - size * 0.3f)
        path.lineTo(currentX - fusW - size * 0.1f, currentY)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}
