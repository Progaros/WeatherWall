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
        
        // Tail (bushy but clearly slimmer than the torso, swept down and back so the
        // silhouette does not just continue the body line)
        path.reset()
        path.moveTo(-2.0f*s, -0.9f*s)
        path.cubicTo(-3.0f*s, -0.95f*s, -3.7f*s, -0.6f*s, -3.85f*s, 0.1f*s)
        path.cubicTo(-3.55f*s, 0.3f*s, -2.85f*s, 0.05f*s, -2.0f*s, -0.15f*s)
        path.close()
        canvas.drawPath(path, paint)

        // Jointed legs (hip -> knee -> hoof) so the gait reads as a walk instead of blocky
        // stumps bobbing. Mirrors the deer renderer: the stride swings the hoof forward and
        // the lift only happens on the forward half of the step, so the hoof stays planted on
        // the recovery swing. A darker far pair is drawn behind the body for depth separation.
        val swingA = sin(walkPhase)
        val swingB = -swingA  // sin(walkPhase + PI)

        val legNearColor = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        val legFarColor = RenderConstants.blendWithAmbient(Color.rgb(160, 70, 24), ambientColor, 0.3f)
        val legW = 0.28f * s
        val hoofSize = 0.16f * s
        val hipY = -0.15f * s
        val kneeY = 0.45f * s
        val strideAmp = 0.42f * s
        val liftAmp = 0.34f * s

        fun drawLeg(hipX: Float, swing: Float, legColor: Int) {
            val stride = swing * strideAmp
            val lift = maxOf(0f, swing) * liftAmp
            val hoofX = hipX + stride
            val hoofY = 1.0f * s - lift
            val kneeX = hipX + stride * 0.4f
            strokePaint.color = legColor
            strokePaint.strokeWidth = legW
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(hipX, hipY)
            path.lineTo(kneeX, kneeY)
            path.lineTo(hoofX, hoofY)
            canvas.drawPath(path, strokePaint)
            paint.color = legColor
            canvas.drawOval(hoofX - hoofSize, hoofY - hoofSize, hoofX + hoofSize, hoofY + hoofSize * 0.6f, paint)
        }

        // Back legs (behind torso): far leg first, then near. Tops reach into the torso.
        drawLeg(-0.6f * s, swingB, legFarColor)
        drawLeg(-1.6f * s, swingA, legNearColor)
        
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
        
        // Front legs (in front of torso): far leg first, then near.
        drawLeg(0.8f * s, swingB, legFarColor)
        drawLeg(1.6f * s, swingA, legNearColor)

        // Restore shared stroke join to the default for subsequent events.
        strokePaint.strokeJoin = Paint.Join.MITER
        
        // Tail tip (white), seated inside the brush end
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(-3.6f*s, -0.05f*s, 0.25f*s, paint)
        
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
