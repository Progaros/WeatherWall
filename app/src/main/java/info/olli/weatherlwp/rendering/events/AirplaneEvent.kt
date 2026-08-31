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

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = (elapsedMs * speed).coerceIn(-0.1f, 1.4f)
        return -0.1f + 1.4f * progress
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return y
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        // Scale: 0.4-0.8% of height based on distance (was 1%)
        val unit = height * 0.004f * sizeScale
        val size = unit * 8f  // Base airplane size
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val progress = (traversalElapsed * speed).coerceIn(-0.1f, 1.4f)
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
        val currentY = EventTraversal.mapFieldY(y, height, extendedOffsetY)
        
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
        
        // Plane body - improved sleek silhouette
        val bodyColor = RenderConstants.blendWithAmbient(Color.rgb(220, 220, 230), ambientColor, 0.3f)
        val shadowColor = RenderConstants.blendWithAmbient(Color.rgb(100, 100, 110), ambientColor, 0.3f)
        paint.color = bodyColor
        
        // Fuselage - sleek tapered shape
        path.reset()
        path.moveTo(currentX, currentY)  // Nose point
        path.lineTo(currentX - size * 0.8f, currentY - size * 0.25f)  // Top curve start
        path.lineTo(currentX - size * 2.5f, currentY - size * 0.2f)  // Top of fuselage
        path.lineTo(currentX - size * 3f, currentY)  // Tail
        path.lineTo(currentX - size * 2.5f, currentY + size * 0.2f)  // Bottom of fuselage
        path.lineTo(currentX - size * 0.8f, currentY + size * 0.25f)  // Bottom curve
        path.close()
        canvas.drawPath(path, paint)
        
        // Wings (swept back, tapered) - realistic jet wing shape
        // Leading edge sweeps back, trailing edge nearly straight
        // Top wing
        path.reset()
        path.moveTo(currentX - size * 1.0f, currentY - size * 0.08f)  // Wing root leading edge
        path.lineTo(currentX - size * 1.8f, currentY - size * 0.7f)  // Wing tip (swept BACK)
        path.lineTo(currentX - size * 2.2f, currentY - size * 0.55f) // Trailing edge tip
        path.lineTo(currentX - size * 1.8f, currentY)  // Trailing edge root
        path.close()
        canvas.drawPath(path, paint)
        
        // Bottom wing (perspective - slightly smaller/angled)
        path.reset()
        path.moveTo(currentX - size * 1.0f, currentY + size * 0.08f)  // Wing root
        path.lineTo(currentX - size * 1.7f, currentY + size * 0.6f)  // Wing tip (swept back)
        path.lineTo(currentX - size * 2.1f, currentY + size * 0.45f) // Trailing edge tip
        path.lineTo(currentX - size * 1.8f, currentY)  // Trailing edge root
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail fin (vertical stabilizer)
        paint.color = shadowColor
        path.reset()
        path.moveTo(currentX - size * 2.5f, currentY - size * 0.2f)
        path.lineTo(currentX - size * 2.8f, currentY - size * 0.6f)  // Fin top
        path.lineTo(currentX - size * 3.2f, currentY - size * 0.5f)
        path.lineTo(currentX - size * 3f, currentY)
        path.close()
        canvas.drawPath(path, paint)
        
        // Horizontal tail
        paint.color = bodyColor
        path.reset()
        path.moveTo(currentX - size * 2.6f, currentY)
        path.lineTo(currentX - size * 2.5f, currentY - size * 0.35f)
        path.lineTo(currentX - size * 3.1f, currentY - size * 0.25f)
        path.lineTo(currentX - size * 3f, currentY)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(currentX - size * 2.6f, currentY)
        path.lineTo(currentX - size * 2.5f, currentY + size * 0.3f)
        path.lineTo(currentX - size * 3.1f, currentY + size * 0.2f)
        path.lineTo(currentX - size * 3f, currentY)
        path.close()
        canvas.drawPath(path, paint)
        
        // Engine pods (under wings)
        paint.color = shadowColor
        val engineSize = size * 0.15f
        canvas.drawOval(
            currentX - size * 0.9f - engineSize * 2f, currentY + size * 0.35f - engineSize,
            currentX - size * 0.9f, currentY + size * 0.35f + engineSize,
            paint
        )
        
        canvas.restore()
    }
}
