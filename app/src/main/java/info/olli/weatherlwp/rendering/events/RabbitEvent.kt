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
 * Rabbit with bunny silhouette using local coordinate system.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Depth is based on groundY - animals lower on screen are in front.
 * Now follows hill contour using hillHeightProvider.
 * 
 * DEPTH SYSTEM: Uses discrete foreground hill depths (last 60% of hill range) to ensure
 * the rabbit is drawn at the correct layer relative to trees. The depthFactor is chosen from
 * values that will match actual hill depthFactors when getHillYAtWorldX snaps to closest hill.
 */
class RabbitEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : QuadrupedWalkerEvent() {
    override val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.03f, viewportRatio = viewportRatio)
    private val size = 0.5f + random.nextFloat() * 0.2f
    // Random vertical offset from hill surface (-2% to +1% of screen height)
    private val groundOffset = -0.02f + random.nextFloat() * 0.03f
    
    // Choose a foreground depth (last 60% of hill range: 0.62 to 0.80)
    // This ensures the rabbit walks on visible foreground hills, not hidden in the background
    // The exact depthFactor will be matched to the closest hill by hillHeightProvider
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.6f  // 0.4 to 1.0
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    
    // Fallback ground Y based on depth (used when no hill data available)
    override val fallbackGroundY = 0.82f + normalizedDepth * 0.08f  // 0.82 to 0.90
    
    // Average over the hop cycle
    override val speed = 0.00003f
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        minDurationMs = 5000L
    )
    
    /**
     * Get current Y position for Y-based draw ordering with trees.
     */
    override fun getCurrentYRatio(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?,
        screenHeight: Float
    ): Float = super.getCurrentYRatio(absoluteTimeMs, hillHeightProvider, screenHeight) + groundOffset
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        val hopPhase = (elapsedMs * 0.006f) % (2f * 3.14159f)  // Slower hop animation
        
        // Calculate X position based on average speed (height-scaled)
        val x = traversalX(absoluteTimeMs, height)
        
        // Use depthFactor for parallax so rabbit moves with terrain at correct depth
        saveParallax(canvas, parallaxCalculator)
        
        val cx = x * width - extendedOffset
        val unit = minOf(width, height)
        val hopHeight = (sin(hopPhase).coerceAtLeast(0f)) * unit * 0.02f * size
        
        val cy = (groundY(x, hillHeightProvider) + groundOffset) * height - hopHeight
        
        val s = size * unit * 0.015f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        
        saveFacing(canvas, cx, cy)

        // Leg gather in body units (multiplied by s where drawn).
        val hopLeg = sin(hopPhase) * 0.2f
        // 0 while grounded, 1 at the apex: legs pull up and the ears lean back in flight.
        val airborne = sin(hopPhase).coerceAtLeast(0f)
        // Capped so the tucked hoof never rises above the body silhouette and vanishes.
        val legTuck = airborne * 0.2f

        // Three-segment legs so the hop reads as gathered legs rather than blocky stumps.
        // Fur is captured up front because the leg draw mutates the shared paint and the
        // body/head colours are drawn between the leg calls.
        val fur = paint.color
        val legFarColor = RenderConstants.blendWithAmbient(Color.rgb(110, 94, 78), ambientColor, 0.35f)
        val hipY = -0.1f * s
        val legW = 0.22f * s
        val hindLegW = 0.36f * s
        val hoofSize = 0.14f * s
        val thighLen = 0.74f * s
        val shinLen = 1.14f * s
        val hindFootLen = 0.7f * s
        val upperArmLen = 0.56f * s
        val forearmLen = 0.51f * s
        val foreFootLen = 0.25f * s

        // Hind: big thigh to a forward stifle, shin back down to a low hock, and a long foot
        // lying almost flat to the toe. Gathering lifts the heel so the foot tilts up.
        fun drawHindLeg(hipX: Float, gatherT: Float, legColor: Int) {
            val gather = gatherT * s
            val toeX = hipX - gather * 0.55f   // the foot drags back as it gathers
            val toeY = 1.2f * s - gather
            val footPitch = (0.2f + gatherT * 1.2f).coerceAtLeast(0.05f)
            val hockX = toeX - cos(footPitch) * hindFootLen
            val hockY = toeY - sin(footPitch) * hindFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, thighLen, shinLen, true,
                hockX, hockY, toeX, toeY, hindLegW * 0.75f, legColor, legColor, hoofSize, 0.6f, hindLegW * 1.4f)
        }

        // Fore: short and nearly straight; gathering folds the paw back behind the wrist.
        fun drawForeLeg(hipX: Float, gatherT: Float, legColor: Int) {
            val gather = gatherT * s
            val baseX = hipX - gather * 0.55f
            val pawY = 1.2f * s - gather
            val fold = (gatherT * 2f).coerceAtLeast(0f)
            val foldX = sin(fold) * foreFootLen * 0.5f
            val wristY = pawY - cos(fold) * foreFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, upperArmLen, forearmLen, false,
                baseX + foldX, wristY, baseX - foldX, pawY, legW, legColor, legColor, hoofSize, 0.6f)
        }

        // Leg anchors: rabbits bound, so each pair moves together; the near/far offset is
        // wide enough that all four legs stay readable instead of overlapping into two.
        val backFarX = -2.05f * s
        val backNearX = -1.45f * s
        val frontFarX = 0.15f * s
        val frontNearX = 0.8f * s

        // Far-side legs go behind the body and near-side legs in front, so the darker far
        // pair never paints a stripe across the belly.
        drawHindLeg(backFarX, legTuck + hopLeg, legFarColor)
        drawForeLeg(frontFarX, legTuck + hopLeg, legFarColor)

        // Body + haunch
        paint.color = fur
        canvas.drawOval(-1.8f*s, -0.9f*s, 1.6f*s, 0.8f*s, paint)
        canvas.drawOval(-2.3f*s, -0.6f*s, -1.0f*s, 0.6f*s, paint)

        // Shoulder/neck bridge to avoid detached head look.
        canvas.drawOval(0.7f*s, -0.95f*s, 1.5f*s, -0.2f*s, paint)
        
        // Head
        canvas.drawOval(1.0f*s, -1.2f*s, 2.4f*s, -0.1f*s, paint)
        
        // Ears, pivoted at their base so they lag backwards through the hop
        canvas.save()
        canvas.rotate(-airborne * 14f, 1.8f*s, -1.1f*s)

        // Ears (outer)
        canvas.drawRoundRect(1.4f*s, -3.0f*s, 1.8f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        canvas.drawRoundRect(1.9f*s, -2.8f*s, 2.3f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        
        // Inner ear (pink)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 150, 150), ambientColor, 0.3f)
        canvas.drawRoundRect(1.5f*s, -2.7f*s, 1.7f*s, -1.3f*s, 0.3f*s, 0.3f*s, paint)
        canvas.drawRoundRect(2.0f*s, -2.5f*s, 2.2f*s, -1.3f*s, 0.3f*s, 0.3f*s, paint)
        canvas.restore()
        
        // Eye (dark dot on head)
        paint.color = Color.rgb(20, 15, 10)
        canvas.drawCircle(2.0f*s, -0.75f*s, 0.12f*s, paint)
        
        // Nose (small pink dot)
        paint.color = Color.rgb(180, 120, 120)
        canvas.drawCircle(2.35f*s, -0.5f*s, 0.08f*s, paint)

        // Cottontail before the near hind leg, or the blob covers the hock and the rabbit
        // reads as one stick under a white ball.
        paint.color = Color.rgb(230, 225, 215)
        canvas.drawCircle(-2.15f * s, -0.15f * s, 0.32f * s, paint)

        // Near-side legs, drawn over the body they share a colour with.
        drawHindLeg(backNearX, legTuck - hopLeg, fur)
        drawForeLeg(frontNearX, legTuck - hopLeg, fur)

        // Whiskers
        strokePaint.color = Color.argb(100, 80, 70, 60)
        strokePaint.strokeWidth = 0.04f * s
        canvas.drawLine(2.3f*s, -0.45f*s, 3.0f*s, -0.7f*s, strokePaint)
        canvas.drawLine(2.3f*s, -0.5f*s, 3.0f*s, -0.5f*s, strokePaint)
        canvas.drawLine(2.3f*s, -0.45f*s, 3.0f*s, -0.25f*s, strokePaint)
        
        canvas.restore()
        canvas.restore()
    }
}
