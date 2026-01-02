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
 * Rabbit with bunny silhouette using local coordinate system.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Depth is based on groundY - animals lower on screen are in front.
 * Now follows hill contour using hillHeightProvider.
 */
class RabbitEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND
    
    private val startX = if (random.nextBoolean()) -0.05f else 1.05f
    private val direction = if (startX < 0.5f) 1f else -1f
    // Base ground Y when no hill data available
    private val fallbackGroundY = 0.85f + random.nextFloat() * 0.05f
    private val size = 0.5f + random.nextFloat() * 0.2f
    // Random vertical offset from hill surface (-2% to +1% of screen height)
    private val groundOffset = -0.02f + random.nextFloat() * 0.03f
    
    // Depth based on initial groundY: lower on screen = more foreground (higher depthFactor)
    // Using normalized range matching hills: PARALLAX_HILLS_MIN (0.50) to PARALLAX_HILLS_MAX (0.80)
    override val depthFactor: Float = 0.55f + (fallbackGroundY - 0.85f) / 0.05f * 0.20f
    
    // Reduced speed: was 0.0001, now 0.00003 (~3x slower)
    private val avgSpeed = 0.00003f
    override val durationMs: Long = (1.15f / avgSpeed).toLong()
    
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
        
        // Body + haunch
        canvas.drawOval(-1.8f*s, -0.9f*s, 1.6f*s, 0.8f*s, paint)
        canvas.drawOval(-2.3f*s, -0.6f*s, -1.0f*s, 0.6f*s, paint)
        
        // Head
        canvas.drawOval(1.0f*s, -1.2f*s, 2.4f*s, -0.1f*s, paint)
        
        // Ears
        canvas.drawRoundRect(1.4f*s, -3.0f*s, 1.8f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        canvas.drawRoundRect(1.9f*s, -2.8f*s, 2.3f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        
        // Eye (dark dot on head)
        paint.color = Color.rgb(20, 15, 10)
        canvas.drawCircle(2.0f*s, -0.75f*s, 0.12f*s, paint)
        
        // Nose (small pink dot)
        paint.color = Color.rgb(180, 120, 120)
        canvas.drawCircle(2.35f*s, -0.5f*s, 0.08f*s, paint)
        
        // Tail (fluffy white)
        paint.color = Color.rgb(200, 190, 180)
        canvas.drawCircle(-2.2f*s, 0.0f, 0.45f*s, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
