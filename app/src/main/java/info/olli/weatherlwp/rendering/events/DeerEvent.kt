package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deer grazing or walking slowly at the edge of hills.
 * Ground event for autumn/winter - majestic silhouette.
 * Seasonal: Autumn/Winter (cooler months when deer are more active)
 */
class DeerEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : RandomEvent {
    private companion object {
        // Precomputed fawn spot coordinates (x, y) to keep draw() allocation-free.
        private val FAWN_SPOT_COORDS = floatArrayOf(
            -2f, -1.5f,
            -0.5f, -1.8f,
            1.5f, -1.2f,
            -1f, -0.5f,
            1f, -0.8f,
            2.5f, -1.5f,
            0f, -1f,
            -2.5f, -0.8f
        )
    }

    override val layer = EventLayer.GROUND
    
    // Depth: mapped to hill parallax range for consistent terrain following
    private val depthValue = RenderConstants.getHillParallaxDepth(0.3f + random.nextFloat() * 0.5f)
    override val depthFactor: Float = depthValue
    private val sizeScale = 1.0f - (depthValue - RenderConstants.PARALLAX_HILLS_MIN) * 0.3f
    
    // Starting position and movement
    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.05f, viewportRatio = viewportRatio)
    private val startX = traversal.startX
    private val direction = traversal.direction
    
    // Very slow, graceful movement
    private val speed = 0.000008f + random.nextFloat() * 0.000005f
    
    // Behavioral state
    private val grazeInterval = 3000L + random.nextLong(2500L)  // How often deer stops to graze
    private val grazeDuration = 1500L + random.nextLong(1000L)  // How long deer grazes
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        pauseIntervalMs = grazeInterval,
        pauseDurationMs = grazeDuration,
        minDurationMs = 15000L
    )
    private val legPhaseOffset = random.nextFloat() * 6.28f
    
    private val path = Path()
    
    // Is this deer an adult or young fawn (20% chance for fawn)
    private val isFawn = random.nextFloat() < 0.2f
    private val fawnScale = if (isFawn) 0.6f else 1.0f

    // Adult bucks (60% of adults) carry antlers; fawns never do.
    private val hasAntlers = !isFawn && random.nextFloat() < 0.6f

    private fun movingElapsedMs(absoluteTimeMs: Long): Long {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val cycleTime = elapsedMs % (grazeInterval + grazeDuration)
        val isGrazing = cycleTime >= grazeInterval
        return if (isGrazing) {
            val fullCycles = elapsedMs / (grazeInterval + grazeDuration)
            fullCycles * grazeInterval + grazeInterval
        } else {
            val fullCycles = elapsedMs / (grazeInterval + grazeDuration)
            fullCycles * grazeInterval + cycleTime
        }
    }

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val movingTime = movingElapsedMs(absoluteTimeMs)
        return startX + direction * speed * movingTime
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val x = getPreviewAnchorXRatio(absoluteTimeMs)
        return hillHeightProvider?.invoke(x, depthValue) ?: (0.65f + depthValue * 0.1f)
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Check if deer is grazing (stops periodically)
        val cycleTime = elapsedMs % (grazeInterval + grazeDuration)
        val isGrazing = cycleTime >= grazeInterval
        
        // Calculate position considering grazing pauses (height-scaled)
        val movingTime = movingElapsedMs(absoluteTimeMs)
        val scaledMovingTime = EventTraversal.scaledElapsed(movingTime, height)
        val x = startX + direction * speed * scaledMovingTime
        
        // Skip if off-screen
        if (x < -0.2f || x > 1.2f) return
        
        // Get ground height
        val groundY = hillHeightProvider?.invoke(x, depthValue) ?: (0.65f + depthValue * 0.1f)
        
        val px = parallaxCalculator.getOffsetXForDepth(depthValue)
        val py = parallaxCalculator.getOffsetYForDepth(depthValue)
        canvas.save()
        canvas.translate(px, py)
        
        val deerX = x * width - extendedOffset
        val deerY = groundY * height
        
        // Size: deer are larger animals
        val unit = height * 0.006f * sizeScale * fawnScale
        
        // Brown color with depth fading
        val distanceFactor = (depthValue - RenderConstants.PARALLAX_HILLS_MIN) / 
                             (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)
        val alpha = 1f - distanceFactor * 0.4f
        val brownBase = if (isFawn) Color.rgb(180, 140, 100) else Color.rgb(140, 100, 70)
        val bodyColor = RenderConstants.blendWithAmbient(
            Color.argb((220 * alpha).toInt(), 
                       Color.red(brownBase), Color.green(brownBase), Color.blue(brownBase)),
            ambientColor, 0.25f)
        
        paint.color = bodyColor
        paint.style = Paint.Style.FILL
        
        // Flip based on direction
        canvas.save()
        canvas.translate(deerX, deerY)
        if (direction < 0) canvas.scale(-1f, 1f)
        
        // Smooth grazing transition: the head eases down and back up instead of snapping,
        // and the legs settle to a planted stance as the deer stops to graze.
        val grazeTransitionMs = 400f
        val grazeElapsedMs = if (isGrazing) (cycleTime - grazeInterval).toFloat() else 0f
        val grazeProgress = if (isGrazing) {
            val ramp = grazeTransitionMs.coerceAtMost(grazeDuration / 2f)
            val rampIn = (grazeElapsedMs / ramp).coerceIn(0f, 1f)
            val rampOut = ((grazeDuration.toFloat() - grazeElapsedMs) / ramp).coerceIn(0f, 1f)
            RenderConstants.easeInOutCubic(minOf(rampIn, rampOut))
        } else 0f
        val gaitActivity = 1f - grazeProgress

        // Chewing: once the muzzle is down the deer works the grass instead of holding a
        // frozen pose. Squared weight keeps the nibble out of the lowering transition.
        val nibbleWeight = grazeProgress * grazeProgress
        val nibblePhase = grazeElapsedMs * 0.010f
        val nibbleBob = sin(nibblePhase * 2.3f) * unit * 0.30f * nibbleWeight
        val nibbleReach = sin(nibblePhase) * unit * 0.35f * nibbleWeight
        val nibbleTiltDeg = sin(nibblePhase * 1.7f) * 6f * nibbleWeight

        // Leg + gait animation (elapsed-time based for stable motion independent of frame pacing).
        val walkPhase = movingTime * 0.004f + legPhaseOffset

        // Subtle vertical body bob synced to the stride (rises twice per gait cycle).
        val bodyBob = sin(walkPhase * 2f) * unit * 0.15f * gaitActivity
        val bodyY = -unit * 6.5f + bodyBob

        // Head travel. Alert: carried high and forward. Grazing: the neck *reaches* down and
        // forward so the muzzle lands on the grass - dropping the head straight down instead
        // buried it in the chest and left the neck as a vertical sliver.
        val headAlertX = unit * 4f
        val headAlertY = bodyY - unit * 3.8f + sin(walkPhase * 2f + 0.9f) * unit * 0.25f * gaitActivity
        val headGrazeX = unit * 5.4f + nibbleReach
        val headGrazeY = -unit * 1.9f + nibbleBob
        // Slight outward arc through the transition so the head swings rather than slides.
        val reachArc = sin(grazeProgress * Math.PI.toFloat()) * unit * 0.9f
        val headX = headAlertX + (headGrazeX - headAlertX) * grazeProgress + reachArc
        val headY = headAlertY + (headGrazeY - headAlertY) * grazeProgress
        // Skull pitches nose-down as it descends, so the muzzle (not the cheek) meets the ground.
        val headTiltDeg = grazeProgress * 68f + nibbleTiltDeg
        val headTiltRad = headTiltDeg * (Math.PI.toFloat() / 180f)
        val headCos = cos(headTiltRad)
        val headSin = sin(headTiltRad)

        // Diagonal trot gait: each diagonal pair (front-near + back-far) opposes the other
        // (front-far + back-near) so legs alternate naturally instead of moving in unison.
        val swingA = sin(walkPhase)
        val swingB = sin(walkPhase + Math.PI.toFloat())

        val legWidth = unit * 0.85f
        val hoofSize = unit * 0.38f
        val hipY = bodyY + unit * 1.5f
        val strideAmp = unit * 0.65f
        val liftAmp = unit * 0.5f
        val hoofColor = Color.argb((190 * alpha).toInt(), 45, 35, 28)

        // Slightly darker shade for the far-side legs to add depth separation.
        val legFarColor = RenderConstants.blendWithAmbient(
            Color.argb((220 * alpha).toInt(),
                (Color.red(brownBase) * 0.72f).toInt(),
                (Color.green(brownBase) * 0.72f).toInt(),
                (Color.blue(brownBase) * 0.72f).toInt()),
            ambientColor, 0.25f)

        // Jointed-leg renderer: hip -> knee -> hoof with a forward stride and a small lift
        // on the swing phase. kneeBend shapes the joint (fore legs bend forward, hind back).
        // plantX braces the hoof for the grazing stance, where the gait swing is zero.
        fun drawLeg(hipX: Float, swing: Float, kneeBend: Float, legColor: Int, plantX: Float = 0f) {
            val stride = swing * strideAmp * gaitActivity + plantX
            val lift = maxOf(0f, swing) * liftAmp * gaitActivity
            val hoofX = hipX + stride
            val hoofY = -lift
            val kneeX = hipX + stride * 0.4f + kneeBend
            val kneeY = hipY * 0.46f
            strokePaint.color = legColor
            strokePaint.strokeWidth = legWidth
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(hipX, hipY)
            path.lineTo(kneeX, kneeY)
            path.lineTo(hoofX, hoofY)
            canvas.drawPath(path, strokePaint)
            paint.color = hoofColor
            canvas.drawOval(hoofX - hoofSize, hoofY - hoofSize, hoofX + hoofSize, hoofY + hoofSize * 0.5f, paint)
        }

        // Leg anchors (profile view): a near and far leg for each pair; far drawn darker.
        // Front and rear pairs are kept equidistant from the body centre, otherwise the
        // stance reads as lopsided.
        val frontNearX = unit * 2.9f
        val frontFarX = unit * 2.2f
        val backNearX = -unit * 2.3f
        val backFarX = -unit * 3.0f
        val frontKneeBend = unit * 0.2f
        val backKneeBend = -unit * 0.45f

        // Grazing stance: the fore legs splay so the chest drops toward the grass, and the
        // hind legs brace back slightly.
        val frontNearPlant = grazeProgress * unit * 0.9f
        val frontFarPlant = grazeProgress * unit * -0.5f
        val backPlant = grazeProgress * unit * -0.35f

        // --- Back legs (drawn behind the body) ---
        drawLeg(backFarX, swingA, backKneeBend, legFarColor, backPlant)
        drawLeg(backNearX, swingB, backKneeBend, bodyColor, backPlant)

        paint.color = bodyColor

        // Body (horizontal oval)
        canvas.drawOval(-unit * 4f, bodyY - unit * 2.5f, unit * 4f, bodyY + unit * 2.5f, paint)

        // Neck: band from the withers to the head base, drawn before the belly so its
        // anchor can sit inside the torso without leaving a seam on the underside.
        // Thickness is measured perpendicular to the neck axis - measuring it vertically
        // collapsed the neck to a sliver whenever the head was held high and steep.
        val neckBaseX = unit * 2.6f
        val neckBaseY = bodyY - unit * 0.9f + grazeProgress * unit * 0.6f
        // Neck meets the skull just behind the eye; that attachment rotates with the head,
        // otherwise the band detaches from the jaw as the muzzle pitches down.
        val neckAttachLocalX = -unit * 1.2f
        val neckAttachLocalY = unit * 0.2f
        val neckTopX = headX + neckAttachLocalX * headCos - neckAttachLocalY * headSin
        val neckTopY = headY + neckAttachLocalX * headSin + neckAttachLocalY * headCos
        val neckDx = neckTopX - neckBaseX
        val neckDy = neckTopY - neckBaseY
        val neckLen = sqrt(neckDx * neckDx + neckDy * neckDy).coerceAtLeast(0.0001f)
        val neckNx = -neckDy / neckLen
        val neckNy = neckDx / neckLen
        val neckBaseHalf = unit * 1.0f
        val neckTopHalf = unit * 0.8f
        path.reset()
        path.moveTo(neckBaseX + neckNx * neckBaseHalf, neckBaseY + neckNy * neckBaseHalf)
        path.lineTo(neckTopX + neckNx * neckTopHalf, neckTopY + neckNy * neckTopHalf)
        path.lineTo(neckTopX - neckNx * neckTopHalf, neckTopY - neckNy * neckTopHalf)
        path.lineTo(neckBaseX - neckNx * neckBaseHalf, neckBaseY - neckNy * neckBaseHalf)
        path.close()
        canvas.drawPath(path, paint)

        // Belly (lighter underside)
        val bellyBase = if (isFawn) Color.rgb(210, 180, 150) else Color.rgb(180, 150, 120)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((200 * alpha).toInt(),
                       Color.red(bellyBase), Color.green(bellyBase), Color.blue(bellyBase)),
            ambientColor, 0.25f)
        canvas.drawOval(-unit * 3f, bodyY - unit * 0.5f, unit * 3f, bodyY + unit * 2f, paint)

        // Fawn spots (white dots on flanks)
        if (isFawn) {
            paint.color = Color.argb((120 * alpha).toInt(), 255, 250, 240)
            var i = 0
            while (i < FAWN_SPOT_COORDS.size) {
                val sx = FAWN_SPOT_COORDS[i]
                val sy = FAWN_SPOT_COORDS[i + 1]
                canvas.drawCircle(unit * sx, bodyY + unit * sy, unit * 0.35f, paint)
                i += 2
            }
        }

        // Tail (small, white-tipped) against the rump.
        val tailColor = RenderConstants.blendWithAmbient(
            Color.argb((200 * alpha).toInt(), 220, 210, 190), ambientColor, 0.25f)
        paint.color = tailColor
        canvas.drawOval(-unit * 4.5f, bodyY - unit * 0.5f, -unit * 3.5f, bodyY + unit * 1f, paint)

        paint.color = bodyColor

        // Head and everything attached to it are drawn in head-local space so the whole skull
        // pitches together when the deer lowers to graze.
        canvas.save()
        canvas.translate(headX, headY)
        canvas.rotate(headTiltDeg)

        // Head
        canvas.drawOval(-unit * 1.8f, -unit * 1.2f, unit * 2f, unit * 1.3f, paint)

        // Muzzle (lighter patch)
        val muzzleColor = if (isFawn) Color.rgb(220, 200, 170) else Color.rgb(190, 160, 130)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((200 * alpha).toInt(),
                       Color.red(muzzleColor), Color.green(muzzleColor), Color.blue(muzzleColor)),
            ambientColor, 0.25f)
        canvas.drawOval(unit * 0.5f, -unit * 0.3f, unit * 1.8f, unit * 0.8f, paint)

        // Eye
        paint.color = Color.argb((180 * alpha).toInt(), 30, 25, 20)
        canvas.drawCircle(unit * 0.3f, -unit * 0.2f, unit * 0.25f, paint)

        // Nose
        paint.color = Color.argb((200 * alpha).toInt(), 40, 35, 30)
        canvas.drawCircle(unit * 1.7f, unit * 0.3f, unit * 0.2f, paint)

        paint.color = bodyColor

        // Ears (angled back from the head base)
        val headBaseX = -unit * 1.7f
        path.reset()
        path.moveTo(headBaseX + unit * 0.2f, -unit * 0.6f)
        path.lineTo(headBaseX - unit * 0.6f, -unit * 2.2f)
        path.lineTo(headBaseX + unit * 1.0f, -unit * 0.4f)
        path.close()
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(headBaseX + unit * 0.9f, -unit * 0.7f)
        path.lineTo(headBaseX + unit * 1.0f, -unit * 2.3f)
        path.lineTo(headBaseX + unit * 1.8f, -unit * 0.5f)
        path.close()
        canvas.drawPath(path, paint)

        // Antlers (adult bucks only): symmetric branched beams with two tines each.
        if (hasAntlers) {
            strokePaint.color = RenderConstants.blendWithAmbient(
                Color.argb((210 * alpha).toInt(), 150, 120, 80), ambientColor, 0.25f)
            strokePaint.strokeWidth = unit * 0.32f
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            val baseY = -unit * 1.1f
            // Back beam + tines
            path.reset()
            path.moveTo(-unit * 0.2f, baseY)
            path.quadTo(-unit * 1.4f, baseY - unit * 2.2f, -unit * 1.0f, baseY - unit * 4.2f)
            path.moveTo(-unit * 1.05f, baseY - unit * 1.9f)
            path.lineTo(-unit * 2.3f, baseY - unit * 2.4f)
            path.moveTo(-unit * 1.02f, baseY - unit * 3.1f)
            path.lineTo(-unit * 2.1f, baseY - unit * 3.9f)
            canvas.drawPath(path, strokePaint)
            // Front beam + tines
            path.reset()
            path.moveTo(unit * 0.5f, baseY)
            path.quadTo(unit * 1.5f, baseY - unit * 2.2f, unit * 1.1f, baseY - unit * 4.2f)
            path.moveTo(unit * 1.15f, baseY - unit * 1.9f)
            path.lineTo(unit * 2.4f, baseY - unit * 2.4f)
            path.moveTo(unit * 1.12f, baseY - unit * 3.1f)
            path.lineTo(unit * 2.2f, baseY - unit * 3.9f)
            canvas.drawPath(path, strokePaint)
        }

        canvas.restore()

        // --- Front legs (drawn in front of the body) ---
        drawLeg(frontFarX, swingB, frontKneeBend, legFarColor, frontFarPlant)
        drawLeg(frontNearX, swingA, frontKneeBend, bodyColor, frontNearPlant)

        // Restore shared stroke join to the default for subsequent events.
        strokePaint.strokeJoin = Paint.Join.MITER

        canvas.restore()
        canvas.restore()
    }
}
