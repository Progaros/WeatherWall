package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rabbit with bunny silhouette using local coordinate system.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Depth is based on groundY - animals lower on screen are in front.
 * Now follows hill contour using hillHeightProvider.
 * 
 * DEPTH SYSTEM: Uses discrete foreground hill depths (last 60% of hill range) to ensure
 * the rabbit is drawn at the correct layer relative to trees. The depthFactor is chosen from
 * values that will match actual hill depthFactors when getHillYAtPosition snaps to closest hill.
 */
class RabbitEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND
    
    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.05f)
    private val startX = traversal.startX
    private val direction = traversal.direction
    private val size = 0.5f + random.nextFloat() * 0.2f
    // Random vertical offset from hill surface (-2% to +1% of screen height)
    private val groundOffset = -0.02f + random.nextFloat() * 0.03f
    
    // Choose a foreground depth (last 60% of hill range: 0.62 to 0.80)
    // This ensures the rabbit walks on visible foreground hills, not hidden in the background
    // The exact depthFactor will be matched to the closest hill by hillHeightProvider
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.6f  // 0.4 to 1.0
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    
    // Fallback ground Y based on depth (used when no hill data available)
    private val fallbackGroundY = 0.82f + normalizedDepth * 0.08f  // 0.82 to 0.90
    
    // Reduced speed: was 0.0001, now 0.00003 (~3x slower)
    private val avgSpeed = 0.00003f
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = avgSpeed,
        minDurationMs = 5000L
    )
    
    /**
     * Get current Y position for Y-based draw ordering with trees.
     */
    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val x = startX + direction * avgSpeed * elapsedMs
        val hillY = hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY
        return hillY + groundOffset
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        val hopPhase = (elapsedMs * 0.006f) % (2f * 3.14159f)  // Slower hop animation
        
        // Calculate X position based on average speed
        val x = startX + direction * avgSpeed * elapsedMs
        
        // Use depthFactor for parallax so rabbit moves with terrain at correct depth
        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width - extendedOffset
        val unit = minOf(width, height)
        val hopHeight = (sin(hopPhase).coerceAtLeast(0f)) * unit * 0.02f * size
        
        // Get ground Y from hill height provider if available, otherwise use fallback
        val groundY = hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY
        val cy = (groundY + groundOffset) * height - hopHeight
        
        val s = size * unit * 0.015f
        val flip = if (direction > 0) 1f else -1f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)

        val hopLeg = sin(hopPhase) * 0.2f * s

        // Back legs (behind body)
        canvas.drawRoundRect(-1.6f*s, 0.2f*s, -1.2f*s, 1.0f*s - hopLeg, 0.15f*s, 0.15f*s, paint)
        canvas.drawRoundRect(-2.0f*s, 0.2f*s, -1.6f*s, 1.0f*s + hopLeg, 0.15f*s, 0.15f*s, paint)
        
        // Body + haunch
        canvas.drawOval(-1.8f*s, -0.9f*s, 1.6f*s, 0.8f*s, paint)
        canvas.drawOval(-2.3f*s, -0.6f*s, -1.0f*s, 0.6f*s, paint)

        // Shoulder/neck bridge to avoid detached head look.
        canvas.drawOval(0.7f*s, -0.95f*s, 1.5f*s, -0.2f*s, paint)
        
        // Head
        canvas.drawOval(1.0f*s, -1.2f*s, 2.4f*s, -0.1f*s, paint)
        
        // Ears (outer)
        canvas.drawRoundRect(1.4f*s, -3.0f*s, 1.8f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        canvas.drawRoundRect(1.9f*s, -2.8f*s, 2.3f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        
        // Inner ear (pink)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 150, 150), ambientColor, 0.3f)
        canvas.drawRoundRect(1.5f*s, -2.7f*s, 1.7f*s, -1.3f*s, 0.3f*s, 0.3f*s, paint)
        canvas.drawRoundRect(2.0f*s, -2.5f*s, 2.2f*s, -1.3f*s, 0.3f*s, 0.3f*s, paint)
        
        // Eye (dark dot on head)
        paint.color = Color.rgb(20, 15, 10)
        canvas.drawCircle(2.0f*s, -0.75f*s, 0.12f*s, paint)
        
        // Nose (small pink dot)
        paint.color = Color.rgb(180, 120, 120)
        canvas.drawCircle(2.35f*s, -0.5f*s, 0.08f*s, paint)

        // Front legs (in front of torso)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        canvas.drawRoundRect(0.6f*s, 0.3f*s, 0.95f*s, 1.1f*s + hopLeg, 0.15f*s, 0.15f*s, paint)
        canvas.drawRoundRect(0.2f*s, 0.3f*s, 0.55f*s, 1.1f*s - hopLeg, 0.15f*s, 0.15f*s, paint)
        
        // Whiskers
        strokePaint.color = Color.argb(100, 80, 70, 60)
        strokePaint.strokeWidth = 0.04f * s
        canvas.drawLine(2.3f*s, -0.45f*s, 3.0f*s, -0.7f*s, strokePaint)
        canvas.drawLine(2.3f*s, -0.5f*s, 3.0f*s, -0.5f*s, strokePaint)
        canvas.drawLine(2.3f*s, -0.45f*s, 3.0f*s, -0.25f*s, strokePaint)
        
        // Tail (fluffy white)
        paint.color = Color.rgb(200, 190, 180)
        canvas.drawCircle(-2.2f*s, 0.0f, 0.45f*s, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
