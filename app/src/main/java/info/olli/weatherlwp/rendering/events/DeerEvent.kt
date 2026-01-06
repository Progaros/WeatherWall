package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deer grazing or walking slowly at the edge of hills.
 * Ground event for autumn/winter - majestic silhouette.
 * Seasonal: Autumn/Winter (cooler months when deer are more active)
 */
class DeerEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND
    
    // Depth: 0.4-0.8 (ground level parallax)
    private val depthValue = RenderConstants.getHillParallaxDepth(0.3f + random.nextFloat() * 0.5f)
    private val sizeScale = 1.0f - (depthValue - RenderConstants.PARALLAX_HILLS_MIN) * 0.3f
    
    // Starting position and movement
    private val startX = if (random.nextBoolean()) -0.15f else 1.15f
    private val direction = if (startX < 0f) 1f else -1f
    
    // Very slow, graceful movement
    private val speed = 0.000008f + random.nextFloat() * 0.000005f
    override val durationMs: Long = 15000L  // 15 seconds
    
    // Behavioral state
    private val grazeInterval = 3000L + random.nextLong(2500L)  // How often deer stops to graze
    private val grazeDuration = 1500L + random.nextLong(1000L)  // How long deer grazes
    private val legPhaseOffset = random.nextFloat() * 6.28f
    
    private val path = Path()
    
    // Is this deer an adult or young fawn (20% chance for fawn)
    private val isFawn = random.nextFloat() < 0.2f
    private val fawnScale = if (isFawn) 0.6f else 1.0f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Check if deer is grazing (stops periodically)
        val cycleTime = elapsedMs % (grazeInterval + grazeDuration)
        val isGrazing = cycleTime >= grazeInterval
        
        // Calculate position considering grazing pauses
        val movingTime = if (isGrazing) {
            val fullCycles = elapsedMs / (grazeInterval + grazeDuration)
            fullCycles * grazeInterval + grazeInterval
        } else {
            val fullCycles = elapsedMs / (grazeInterval + grazeDuration)
            fullCycles * grazeInterval + cycleTime
        }
        
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
        
        // Leg animation (walking gait or stationary if grazing)
        val walkPhase = if (isGrazing) 0f else timeSeconds * 3f + legPhaseOffset
        val legSwing = if (isGrazing) 0f else sin(walkPhase) * 0.15f
        
        // Calculate body position (head lowers when grazing)
        val headLower = if (isGrazing) unit * 3f else 0f
        
        // Body (horizontal oval)
        val bodyY = -unit * 8f
        canvas.drawOval(-unit * 4f, bodyY - unit * 2.5f, unit * 4f, bodyY + unit * 2.5f, paint)
        
        // Neck
        path.reset()
        path.moveTo(unit * 3f, bodyY - unit * 1f)
        path.quadTo(unit * 5f, bodyY - unit * 4f + headLower * 0.3f, 
                    unit * 6f, bodyY - unit * 6f + headLower)
        path.lineTo(unit * 4f, bodyY - unit * 6f + headLower)
        path.quadTo(unit * 3.5f, bodyY - unit * 3f + headLower * 0.3f, 
                    unit * 2f, bodyY - unit * 1f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Head
        val headX = unit * 5.5f
        val headY = bodyY - unit * 7f + headLower
        canvas.drawOval(headX - unit * 1.5f, headY - unit * 1f, 
                        headX + unit * 2f, headY + unit * 1.2f, paint)
        
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
        
        // Legs (4 legs with walking animation)
        val legWidth = unit * 0.6f
        strokePaint.color = bodyColor
        strokePaint.strokeWidth = legWidth
        strokePaint.style = Paint.Style.STROKE
        
        // Front legs
        val frontLegX1 = unit * 2f
        val frontLegX2 = unit * 0.5f
        val frontSwing1 = legSwing
        val frontSwing2 = -legSwing * 0.8f
        
        // Front right leg
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
        
        // Back legs
        val backLegX1 = -unit * 2f
        val backLegX2 = -unit * 3.5f
        val backSwing1 = -legSwing * 0.9f
        val backSwing2 = legSwing * 0.7f
        
        // Back right leg
        path.reset()
        path.moveTo(backLegX1, bodyY + unit * 1.5f)
        path.quadTo(backLegX1 + backSwing1 * unit * 3f, bodyY + unit * 4f,
                    backLegX1 + backSwing1 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)
        
        // Back left leg
        path.reset()
        path.moveTo(backLegX2, bodyY + unit * 1.5f)
        path.quadTo(backLegX2 + backSwing2 * unit * 3f, bodyY + unit * 4f,
                    backLegX2 + backSwing2 * unit * 2f, 0f)
        canvas.drawPath(path, strokePaint)
        
        canvas.restore()
        canvas.restore()
    }
}
