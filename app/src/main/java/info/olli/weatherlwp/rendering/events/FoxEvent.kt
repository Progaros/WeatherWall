package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fox with proper tail and ears using local coordinate system.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Depth is based on groundY - animals lower on screen are in front.
 * Now follows hill contour using hillHeightProvider.
 * 
 * DEPTH SYSTEM: Uses discrete foreground hill depths (last 60% of hill range) to ensure
 * the fox is drawn at the correct layer relative to trees. The depthFactor is chosen from
 * values that will match actual hill depthFactors when getHillYAtPosition snaps to closest hill.
 */
class FoxEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : RandomEvent {
    override val layer = EventLayer.GROUND
    
    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.04f, viewportRatio = viewportRatio)
    private val startX = traversal.startX
    private val direction = traversal.direction
    private val size = 0.7f + random.nextFloat() * 0.3f
    private val path = Path()
    // Random vertical offset from hill surface (-2% to +1% of screen height)
    private val groundOffset = -0.02f + random.nextFloat() * 0.03f
    
    // Choose a foreground depth (last 60% of hill range: 0.62 to 0.80)
    // This ensures the fox walks on visible foreground hills, not hidden in the background
    // The exact depthFactor will be matched to the closest hill by hillHeightProvider
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.6f  // 0.4 to 1.0
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    
    // Fallback ground Y based on depth (used when no hill data available)
    private val fallbackGroundY = 0.78f + normalizedDepth * 0.10f  // 0.78 to 0.88
    
    // Reduced speed: was 0.00008, now 0.000025 (~3x slower)
    private val speed = 0.000025f
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        minDurationMs = 5000L
    )
    
    /**
     * Get current Y position for Y-based draw ordering with trees.
     */
    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val x = startX + direction * speed * elapsedMs
        val hillY = hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY
        return hillY + groundOffset
    }

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        return startX + direction * speed * elapsedMs
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val x = startX + direction * speed * traversalElapsed
        val walkPhase = elapsedMs * 0.008f  // Slower walk animation
        
        // Use depthFactor for parallax so fox moves with terrain at correct depth
        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width - extendedOffset
        
        // Get ground Y from hill height provider if available, otherwise use fallback
        val groundY = hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY
        val cy = (groundY + groundOffset) * height
        
        val unit = height
        val s = size * unit * 0.009f
        val flip = if (direction > 0) 1f else -1f
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        
        // Tail (bushy but slimmer than the torso, tapering from the rump to the tip)
        path.reset()
        path.moveTo(-1.9f*s, -0.85f*s)
        path.cubicTo(-3.0f*s, -1.15f*s, -3.9f*s, -0.95f*s, -4.1f*s, -0.35f*s)
        path.cubicTo(-3.8f*s, 0.05f*s, -3.0f*s, 0.15f*s, -1.9f*s, 0.05f*s)
        path.close()
        canvas.drawPath(path, paint)

        // Back legs (behind torso). Tops reach into the torso because the body oval curves
        // away towards its ends, which would otherwise leave the outer leg in the air.
        val step = sin(walkPhase) * 0.25f * s
        val swing = sin(walkPhase) * 0.35f * s
        canvas.drawRoundRect(-1.8f*s + swing, -0.3f*s, -1.4f*s + swing, 1.0f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(-0.8f*s - swing, 0.1f*s, -0.4f*s - swing, 1.0f*s - step, 0.2f*s, 0.2f*s, paint)
        
        // Body
        canvas.drawOval(-2.4f*s, -1.2f*s, 2.0f*s, 0.2f*s, paint)

        // Shoulder bridge keeps head connected to torso silhouette.
        canvas.drawOval(1.2f*s, -1.25f*s, 2.1f*s, -0.35f*s, paint)
        
        // Belly (lighter underside)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(230, 190, 140), ambientColor, 0.3f)
        canvas.drawOval(-1.8f*s, -0.5f*s, 1.5f*s, 0.15f*s, paint)
        
        // Head + snout
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        canvas.drawOval(1.7f*s, -1.8f*s, 3.2f*s, -0.6f*s, paint)
        paint.color = Color.rgb(240, 200, 180)
        canvas.drawOval(2.7f*s, -1.4f*s, 3.7f*s, -0.9f*s, paint)
        
        // Front legs (in front of torso); same reason for the raised outer leg.
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        canvas.drawRoundRect(0.6f*s - swing, 0.1f*s, 1.0f*s - swing, 1.0f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(1.4f*s + swing, -0.35f*s, 1.8f*s + swing, 1.0f*s - step, 0.2f*s, 0.2f*s, paint)
        
        // Tail tip (white), seated inside the brush end
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(-3.8f*s, -0.35f*s, 0.3f*s, paint)
        
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
        
        // Inner ear (pink)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 130, 120), ambientColor, 0.3f)
        path.reset()
        path.moveTo(2.25f*s, -1.65f*s)
        path.lineTo(2.15f*s, -2.15f*s)
        path.lineTo(2.5f*s, -1.65f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(2.85f*s, -1.65f*s)
        path.lineTo(2.95f*s, -2.05f*s)
        path.lineTo(3.15f*s, -1.65f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        // Eye (small dark dot on head)
        paint.color = Color.rgb(20, 15, 10)
        canvas.drawCircle(2.6f*s, -1.35f*s, 0.12f*s, paint)
        
        // Nose (black dot on snout)
        canvas.drawCircle(3.5f*s, -1.15f*s, 0.1f*s, paint)
        
        // Whiskers
        strokePaint.color = Color.argb(80, 60, 40, 30)
        strokePaint.strokeWidth = 0.04f * s
        strokePaint.style = Paint.Style.STROKE
        canvas.drawLine(3.4f*s, -1.1f*s, 4.1f*s, -1.35f*s, strokePaint)
        canvas.drawLine(3.4f*s, -1.1f*s, 4.2f*s, -1.1f*s, strokePaint)
        canvas.drawLine(3.4f*s, -1.05f*s, 4.1f*s, -0.85f*s, strokePaint)
        
        canvas.restore()
        canvas.restore()
    }
}
