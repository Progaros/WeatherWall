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
 * Squirrel scurrying across the ground, stops occasionally to look around.
 * Seasonal: Autumn (gathering nuts for winter)
 */
class SquirrelEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : QuadrupedWalkerEvent() {
    // Depth determines size and parallax (0.4-0.8 - squirrels stay in mid-foreground)
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.4f
    override val depthFactor = RenderConstants.getHillParallaxDepth(normalizedDepth)
    
    private val sizeScale = 1.0f - normalizedDepth * 0.3f
    
    // Direction: left or right
    override val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.03f, viewportRatio = viewportRatio)
    
    // Baseline Y - will be adjusted by hill height
    override val fallbackGroundY = 0.78f + random.nextFloat() * 0.08f
    
    // Movement speed (squirrels are quick but not unrealistic)
    override val speed = 0.00004f + random.nextFloat() * 0.00002f
    
    // Animation phases
    private val animPhase = random.nextFloat() * 6.28f
    
    // Colors - lazy to avoid Android dependency in tests
    private val bodyColor by lazy { Color.rgb(139, 90, 43) }  // Brown
    private val bellyColor by lazy { Color.rgb(222, 184, 135) }  // Tan
    
    // Stop and look behavior - squirrels pause periodically
    private val stopIntervalMs = 2000L + random.nextLong(1500)
    private val stopDurationMs = 400L + random.nextLong(300)

    // Include expected stop pauses so event never expires while still visible.
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        pauseIntervalMs = stopIntervalMs,
        pauseDurationMs = stopDurationMs,
        minDurationMs = 5000L
    )

    override fun movingElapsedMs(elapsedMs: Long): Long {
        val cycleLength = stopIntervalMs + stopDurationMs
        val cyclePos = elapsedMs % cycleLength
        val fullCycles = elapsedMs / cycleLength
        return if (cyclePos > stopIntervalMs) {
            fullCycles * stopIntervalMs + stopIntervalMs
        } else {
            elapsedMs - fullCycles * stopDurationMs
        }
    }
    
    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return groundY(currentX(absoluteTimeMs), hillHeightProvider)
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val unit = height * 0.006f * sizeScale
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Check if squirrel is stopped to look around
        val cyclePos = elapsedMs % (stopIntervalMs + stopDurationMs)
        val isStopped = cyclePos > stopIntervalMs
        
        // Calculate position (height-scaled traversal)
        val effectiveElapsed = movingElapsedMs(
            EventTraversal.scaledElapsed(elapsedMs, height)
        )
        
        val x = xAt(effectiveElapsed)
        val y = groundY(x, hillHeightProvider)
        
        saveParallax(canvas, parallaxCalculator)
        
        val screenX = x * width - extendedOffset
        val screenY = y * height - unit * 3f  // Offset above ground
        
        // Running animation - elapsed-time based for stable movement across frame rates.
        val runPhase = if (isStopped) 0f else sin(elapsedMs * 0.02f + animPhase)

        // Tail bob with reduced amplitude to keep torso/tail seam tighter.
        val tailBob = sin(elapsedMs * 0.003f + animPhase) * unit * 0.26f
        
        // Body color with ambient
        paint.color = RenderConstants.blendWithAmbient(bodyColor, ambientColor, 0.3f)
        
        canvas.save()
        // Flip based on direction
        if (direction < 0) {
            canvas.scale(-1f, 1f, screenX, screenY)
        }

        val fur = paint.color
        val legFarColor = RenderConstants.blendWithAmbient(Color.rgb(110, 70, 32), ambientColor, 0.3f)
        val legW = unit * 0.22f
        val hoofSize = unit * 0.12f
        val strideAmp = unit * 0.42f
        val liftAmp = unit * 0.32f
        val swingA = runPhase
        val swingB = -swingA
        val hipY = screenY + unit * 0.15f
        val groundPawY = screenY + unit * 1.15f
        val thighLen = unit * 0.43f
        val shinLen = unit * 0.64f
        val hindFootLen = unit * 0.4f
        val upperArmLen = unit * 0.41f
        val forearmLen = unit * 0.41f
        val foreFootLen = unit * 0.2f

        // Bound gait: lift only on the forward half, so the foot stays planted on the return.
        // Hind: stifle solved forward, hock behind the paw on a slanted, rabbit-like foot
        // that tilts further as the paw lifts.
        fun drawHindLeg(hipX: Float, swing: Float, legColor: Int) {
            val liftT = maxOf(0f, swing)
            val pawX = hipX + swing * strideAmp
            val pawY = groundPawY - liftT * liftAmp
            val hockTilt = 0.85f + liftT * 0.35f
            val hockX = pawX - sin(hockTilt) * hindFootLen
            val hockY = pawY - cos(hockTilt) * hindFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, thighLen, shinLen, true,
                hockX, hockY, pawX, pawY, legW, legColor, legColor, hoofSize, 0.5f, legW * 1.5f)
        }

        // Fore: straight in stance; the paw folds back behind the wrist as it lifts.
        fun drawForeLeg(hipX: Float, swing: Float, legColor: Int) {
            val liftT = maxOf(0f, swing)
            val baseX = hipX + swing * strideAmp
            val pawY = groundPawY - liftT * liftAmp
            val fold = liftT * 1.1f
            val foldX = sin(fold) * foreFootLen * 0.5f
            val wristY = pawY - cos(fold) * foreFootLen
            JointedLeg.drawSolved(canvas, path, strokePaint, paint, hipX, hipY, upperArmLen, forearmLen, false,
                baseX + foldX, wristY, baseX - foldX, pawY, legW, legColor, legColor, hoofSize, 0.5f)
        }

        val backFarX = screenX - unit * 1.15f
        val backNearX = screenX - unit * 0.7f
        val frontFarX = screenX - unit * 0.05f
        val frontNearX = screenX + unit * 0.4f

        // Far pair behind the body. Diagonal phase, or the same-side legs lock together.
        drawHindLeg(backFarX, swingA, legFarColor)
        drawForeLeg(frontFarX, swingB, legFarColor)
        
        // Draw tail (large, fluffy, curved upward)
        paint.color = fur
        path.reset()
        path.moveTo(screenX - unit * 1.5f, screenY)
        path.quadTo(
            screenX - unit * 2.5f, screenY - unit * 2f + tailBob,
            screenX - unit * 1.8f, screenY - unit * 3f + tailBob
        )
        path.quadTo(
            screenX - unit * 1.2f, screenY - unit * 2.5f + tailBob,
            screenX - unit * 1.2f, screenY - unit * 0.5f
        )
        path.close()
        canvas.drawPath(path, paint)
        
        // Draw body (oval)
        canvas.drawOval(
            screenX - unit * 1.5f, screenY - unit * 0.8f,
            screenX + unit * 0.8f, screenY + unit * 0.8f,
            paint
        )
        
        // Near pair over the torso. Drawn before the belly so the light underside covers
        // the hip, instead of a dark stroke sliding across it.
        drawHindLeg(backNearX, swingB, fur)
        drawForeLeg(frontNearX, swingA, fur)

        // Belly (lighter underside)
        paint.color = RenderConstants.blendWithAmbient(bellyColor, ambientColor, 0.3f)
        canvas.drawOval(
            screenX - unit * 1.2f, screenY - unit * 0.2f,
            screenX + unit * 0.5f, screenY + unit * 0.6f,
            paint
        )
        
        // Draw head
        paint.color = RenderConstants.blendWithAmbient(bodyColor, ambientColor, 0.3f)
        canvas.drawCircle(screenX + unit * 1.2f, screenY - unit * 0.2f, unit * 0.7f, paint)
        
        // Ear
        path.reset()
        path.moveTo(screenX + unit * 1.0f, screenY - unit * 0.7f)
        path.lineTo(screenX + unit * 1.3f, screenY - unit * 1.4f)
        path.lineTo(screenX + unit * 1.5f, screenY - unit * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Eye
        paint.color = Color.BLACK
        canvas.drawCircle(screenX + unit * 1.5f, screenY - unit * 0.3f, unit * 0.12f, paint)
        
        // Nose
        paint.color = Color.rgb(60, 40, 30)
        canvas.drawCircle(screenX + unit * 1.85f, screenY - unit * 0.1f, unit * 0.08f, paint)
        
        // Cheek tuft
        paint.color = RenderConstants.blendWithAmbient(bellyColor, ambientColor, 0.3f)
        canvas.drawOval(
            screenX + unit * 1.0f, screenY + unit * 0.0f,
            screenX + unit * 1.6f, screenY + unit * 0.4f,
            paint
        )
        
        canvas.restore()
        canvas.restore()
    }
}
