package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.cos
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
 * values that will match actual hill depthFactors when getHillYAtWorldX snaps to closest hill.
 */
class FoxEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : QuadrupedWalkerEvent() {
    override val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.04f, viewportRatio = viewportRatio)
    private val size = 0.7f + random.nextFloat() * 0.3f
    // Random vertical offset from hill surface (-2% to +1% of screen height)
    private val groundOffset = -0.02f + random.nextFloat() * 0.03f
    
    // Choose a foreground depth (last 60% of hill range: 0.62 to 0.80)
    // This ensures the fox walks on visible foreground hills, not hidden in the background
    // The exact depthFactor will be matched to the closest hill by hillHeightProvider
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.6f  // 0.4 to 1.0
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    
    // Fallback ground Y based on depth (used when no hill data available)
    override val fallbackGroundY = 0.78f + normalizedDepth * 0.10f  // 0.78 to 0.88
    
    // Reduced speed: was 0.00008, now 0.000025 (~3x slower)
    override val speed = 0.000025f
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        minDurationMs = 5000L
    )
    
    /**
     * Get current Y position for Y-based draw ordering with trees.
     */
    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return groundY(currentX(absoluteTimeMs), hillHeightProvider) + groundOffset
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val x = xAt(traversalElapsed)
        val walkPhase = elapsedMs * 0.008f  // Slower walk animation
        
        // Use depthFactor for parallax so fox moves with terrain at correct depth
        saveParallax(canvas, parallaxCalculator)
        
        val cx = x * width - extendedOffset
        val cy = (groundY(x, hillHeightProvider) + groundOffset) * height
        
        val unit = height
        val s = size * unit * 0.009f
        
        saveFacing(canvas, cx, cy)
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)

        // Three-segment legs. Mirrors the deer renderer: the lift only happens on the forward
        // half of the step, so the paw stays planted through the recovery swing.
        val swingA = sin(walkPhase)
        val swingB = -swingA  // sin(walkPhase + PI)

        val furColor = paint.color
        val legFarColor = RenderConstants.blendWithAmbient(Color.rgb(160, 70, 24), ambientColor, 0.3f)
        val legW = 0.28f * s
        val thighW = 0.42f * s
        val hoofSize = 0.16f * s
        val hipY = -0.15f * s
        val strideAmp = 0.42f * s
        val liftAmp = 0.34f * s
        val thighLen = 0.47f * s
        val shinLen = 0.49f * s
        val hindFootLen = 0.47f * s
        val upperArmLen = 0.48f * s
        val forearmLen = 0.48f * s
        val foreFootLen = 0.26f * s

        // Hind: the stride moves the paw; the hock sits behind and above it on a near-vertical
        // metatarsus that tilts further as the paw lifts, and the stifle is solved forward.
        fun drawHindLeg(hipX: Float, swing: Float, legColor: Int) {
            val liftT = maxOf(0f, swing)
            val pawX = hipX + swing * strideAmp
            val pawY = 1.0f * s - liftT * liftAmp
            val hockTilt = 0.3f + liftT * 0.45f
            val hockX = pawX - sin(hockTilt) * hindFootLen
            val hockY = pawY - cos(hockTilt) * hindFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, thighLen, shinLen, true,
                hockX, hockY, pawX, pawY, legW, legColor, legColor, hoofSize, 0.6f, thighW)
        }

        // Fore: a straight column in stance; as the paw lifts the lower leg folds back so the
        // wrist leads. The fold is split about the stride point so the reach stays put.
        fun drawForeLeg(hipX: Float, swing: Float, legColor: Int) {
            val liftT = maxOf(0f, swing)
            val baseX = hipX + swing * strideAmp
            val pawY = 1.0f * s - liftT * liftAmp
            val fold = liftT * 1.0f
            val foldX = sin(fold) * foreFootLen * 0.5f
            val wristY = pawY - cos(fold) * foreFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, upperArmLen, forearmLen, false,
                baseX + foldX, wristY, baseX - foldX, pawY, legW, legColor, legColor, hoofSize, 0.6f)
        }

        // Leg anchors: the pairs sit under the shoulder and haunch with a clear gap between
        // them, otherwise four evenly spaced legs read as a centipede rather than a fox.
        // Hips stay inside the torso oval, which curves away towards its ends.
        val backFarX = -1.9f * s
        val backNearX = -1.25f * s
        val frontFarX = 1.0f * s
        val frontNearX = 1.65f * s

        // Far-side legs go behind the torso and near-side legs in front, so the darker far
        // pair never paints a stripe across the belly. Diagonal pairs share a phase
        // (far-hind with near-fore), otherwise the same-side legs swing together and the
        // walk reads as a camel pace.
        drawHindLeg(backFarX, swingA, legFarColor)
        drawForeLeg(frontFarX, swingB, legFarColor)

        // Body
        paint.color = furColor
        canvas.drawOval(-2.4f*s, -1.2f*s, 2.0f*s, 0.2f*s, paint)

        // Shoulder bridge keeps head connected to torso silhouette.
        canvas.drawOval(1.2f*s, -1.25f*s, 2.1f*s, -0.35f*s, paint)
        
        // Belly (lighter underside)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(230, 190, 140), ambientColor, 0.3f)
        canvas.drawOval(-1.8f*s, -0.5f*s, 1.5f*s, 0.15f*s, paint)
        
        // Head + snout
        paint.color = furColor
        canvas.drawOval(1.7f*s, -1.8f*s, 3.2f*s, -0.6f*s, paint)
        paint.color = Color.rgb(240, 200, 180)
        canvas.drawOval(2.7f*s, -1.4f*s, 3.7f*s, -0.9f*s, paint)

        // Tail after the torso, so the oval cannot swallow it, but before the near hind
        // leg so the hock stays in front of the brush. It drops below the body line; a
        // same-colour extension along that line just reads as a longer body.
        paint.color = furColor
        path.reset()
        path.moveTo(-1.7f*s, -0.55f*s)
        path.cubicTo(-2.8f*s, -0.35f*s, -3.5f*s, 0.35f*s, -3.55f*s, 1.05f*s)
        path.cubicTo(-3.05f*s, 1.25f*s, -2.3f*s, 0.45f*s, -1.45f*s, 0.05f*s)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(-3.4f*s, 1.1f*s, 0.28f*s, paint)
        
        // Near-side legs, drawn over the torso they share a colour with.
        drawHindLeg(backNearX, swingB, furColor)
        drawForeLeg(frontNearX, swingA, furColor)
        
        // Ears
        paint.color = furColor
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
