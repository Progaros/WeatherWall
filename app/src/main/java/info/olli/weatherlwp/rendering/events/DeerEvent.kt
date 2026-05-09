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

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val x = getPreviewAnchorXRatio(absoluteTimeMs)
        return hillHeightProvider?.invoke(x, depthValue) ?: (0.65f + depthValue * 0.1f)
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Check if deer is grazing (stops periodically)
        val cycleTime = elapsedMs % (grazeInterval + grazeDuration)
        val isGrazing = cycleTime >= grazeInterval
        
        // Calculate position considering grazing pauses
        val movingTime = movingElapsedMs(absoluteTimeMs)
        val x = startX + direction * speed * movingTime
        
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
        
        // Leg animation (elapsed-time based for stable motion independent of frame pacing).
        val walkPhase = if (isGrazing) 0f else movingTime * 0.003f + legPhaseOffset
        val legSwing = if (isGrazing) 0f else sin(walkPhase) * 0.13f
        
        // Calculate body position (head lowers when grazing)
        val headLower = if (isGrazing) unit * 3f else 0f
        val bodyY = -unit * 8f
        val headX = unit * 5.5f
        val headY = bodyY - unit * 7f + headLower

        // Legs (4 legs with walking animation) — split by depth: back before body, front after body.
        val legWidth = unit * 0.6f
        val frontLegX1 = unit * 2f
        val frontLegX2 = unit * 0.5f
        val frontSwing1 = legSwing
        val frontSwing2 = -legSwing * 0.8f
        val backLegX1 = -unit * 2f
        val backLegX2 = -unit * 3.5f
        val backSwing1 = -legSwing * 0.9f
        val backSwing2 = legSwing * 0.7f
        val hoofSize = unit * 0.35f
        
        strokePaint.color = bodyColor
        strokePaint.strokeWidth = legWidth
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND

        // Back legs (behind body)
        path.reset()
        path.moveTo(backLegX1, bodyY + unit * 1.5f)
        path.quadTo(backLegX1 + backSwing1 * unit * 3f, bodyY + unit * 4f,
            backLegX1 + backSwing1 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)

        path.reset()
        path.moveTo(backLegX2, bodyY + unit * 1.5f)
        path.quadTo(backLegX2 + backSwing2 * unit * 3f, bodyY + unit * 4f,
            backLegX2 + backSwing2 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)

        // Back hooves remain behind body as well.
        paint.color = Color.argb((180 * alpha).toInt(), 50, 40, 30)
        canvas.drawOval(backLegX1 + backSwing1 * unit * 2f - hoofSize, -hoofSize,
            backLegX1 + backSwing1 * unit * 2f + hoofSize, hoofSize * 0.5f, paint)
        canvas.drawOval(backLegX2 + backSwing2 * unit * 2f - hoofSize, -hoofSize,
            backLegX2 + backSwing2 * unit * 2f + hoofSize, hoofSize * 0.5f, paint)

        paint.color = bodyColor

        // Body (horizontal oval)
        canvas.drawOval(-unit * 4f, bodyY - unit * 2.5f, unit * 4f, bodyY + unit * 2.5f, paint)
        
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
        
        paint.color = bodyColor
        
        // Neck (overlaps torso/head slightly so joints read as connected in all poses)
        path.reset()
        path.moveTo(unit * 1.8f, bodyY - unit * 0.2f)
        path.quadTo(unit * 3.4f, bodyY - unit * 3.8f + headLower * 0.2f,
            headX - unit * 0.25f, headY - unit * 0.55f)
        path.lineTo(headX - unit * 1.05f, headY + unit * 0.1f)
        path.quadTo(unit * 3.1f, bodyY - unit * 2.2f + headLower * 0.15f,
            unit * 2.0f, bodyY + unit * 0.25f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Head
        canvas.drawOval(headX - unit * 1.5f, headY - unit * 1f, 
                        headX + unit * 2f, headY + unit * 1.2f, paint)
        
        // Muzzle (lighter patch)
        val muzzleColor = if (isFawn) Color.rgb(220, 200, 170) else Color.rgb(190, 160, 130)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((200 * alpha).toInt(),
                       Color.red(muzzleColor), Color.green(muzzleColor), Color.blue(muzzleColor)),
            ambientColor, 0.25f)
        canvas.drawOval(headX + unit * 0.5f, headY - unit * 0.3f,
                        headX + unit * 1.8f, headY + unit * 0.8f, paint)
        
        // Eye
        paint.color = Color.argb((180 * alpha).toInt(), 30, 25, 20)
        canvas.drawCircle(headX + unit * 0.3f, headY - unit * 0.2f, unit * 0.25f, paint)
        
        // Nose
        paint.color = Color.argb((200 * alpha).toInt(), 40, 35, 30)
        canvas.drawCircle(headX + unit * 1.7f, headY + unit * 0.3f, unit * 0.2f, paint)
        
        paint.color = bodyColor
        
        // Ears (larger, alert)
        path.reset()
        path.moveTo(headX - unit * 0.5f, headY - unit * 0.8f)
        path.lineTo(headX - unit * 1f, headY - unit * 2.5f)
        path.lineTo(headX + unit * 0.3f, headY - unit * 0.5f)
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(headX + unit * 0.8f, headY - unit * 0.8f)
        path.lineTo(headX + unit * 0.5f, headY - unit * 2.5f)
        path.lineTo(headX + unit * 1.5f, headY - unit * 0.5f)
        canvas.drawPath(path, paint)
        
        // Antlers (only on adult males, 50% of adults)
        if (!isFawn && direction > 0) {  // Simplified: only show antlers when facing right
            strokePaint.color = RenderConstants.blendWithAmbient(
                Color.argb((200 * alpha).toInt(), 120, 90, 60), ambientColor, 0.25f)
            strokePaint.strokeWidth = unit * 0.4f
            strokePaint.style = Paint.Style.STROKE
            
            // Left antler
            path.reset()
            path.moveTo(headX - unit * 0.3f, headY - unit * 1.5f)
            path.lineTo(headX - unit * 1.5f, headY - unit * 4f)
            path.moveTo(headX - unit * 1f, headY - unit * 3f)
            path.lineTo(headX - unit * 2f, headY - unit * 3.5f)
            canvas.drawPath(path, strokePaint)
            
            // Right antler
            path.reset()
            path.moveTo(headX + unit * 0.5f, headY - unit * 1.5f)
            path.lineTo(headX + unit * 1.5f, headY - unit * 4f)
            path.moveTo(headX + unit * 1f, headY - unit * 3f)
            path.lineTo(headX + unit * 2f, headY - unit * 3.5f)
            canvas.drawPath(path, strokePaint)
        }
        
        // Tail (small, white-tipped)
        val tailColor = RenderConstants.blendWithAmbient(
            Color.argb((200 * alpha).toInt(), 220, 210, 190), ambientColor, 0.25f)
        paint.color = tailColor
        canvas.drawOval(-unit * 4.5f, bodyY - unit * 0.5f, -unit * 3.5f, bodyY + unit * 1f, paint)
        
        paint.color = bodyColor

        // Front legs (in front of body)
        strokePaint.color = bodyColor
        strokePaint.strokeWidth = legWidth
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        
        path.reset()
        path.moveTo(frontLegX1, bodyY + unit * 1.5f)
        path.quadTo(frontLegX1 + frontSwing1 * unit * 3f, bodyY + unit * 4f, 
                    frontLegX1 + frontSwing1 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)
        
        // Front left leg
        path.reset()
        path.moveTo(frontLegX2, bodyY + unit * 1.5f)
        path.quadTo(frontLegX2 + frontSwing2 * unit * 3f, bodyY + unit * 4f,
                    frontLegX2 + frontSwing2 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)

        // Front hooves (in front layer)
        paint.color = Color.argb((180 * alpha).toInt(), 50, 40, 30)
        canvas.drawOval(frontLegX1 + frontSwing1 * unit * 2f - hoofSize, -hoofSize,
                        frontLegX1 + frontSwing1 * unit * 2f + hoofSize, hoofSize * 0.5f, paint)
        canvas.drawOval(frontLegX2 + frontSwing2 * unit * 2f - hoofSize, -hoofSize,
                        frontLegX2 + frontSwing2 * unit * 2f + hoofSize, hoofSize * 0.5f, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
