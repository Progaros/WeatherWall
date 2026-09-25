package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dragonfly hovering and darting around near water/flowers.
 * Seasonal: Summer only - these insects thrive in warm weather.
 */
class DragonflyEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis(), viewportRatio: Float = 1.0f) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Distance for depth: 0.3-0.7 (dragonflies in mid-range)
    private val distance = 0.3f + random.nextFloat() * 0.4f
    private val sizeScale = 1.0f - distance * 0.4f
    
    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.04f, viewportRatio = viewportRatio)
    private val direction = traversal.direction
    private val startY = 0.50f + random.nextFloat() * 0.20f  // Lower in the sky, near plants
    private val travelSpeed = 0.000035f + random.nextFloat() * 0.000018f
    override val durationMs = EventTraversal.durationMsForPath(
        path = traversal,
        speed = travelSpeed,
        minDurationMs = 6000L
    )
    
    private val path = Path()
    
    // Dragonfly colors - iridescent blues and greens (lazy to avoid Android dependency in tests)
    private val colorVariant = random.nextInt(3)
    private val bodyColor by lazy {
        when (colorVariant) {
            0 -> Color.rgb(30, 144, 255)   // Dodger blue
            1 -> Color.rgb(0, 206, 209)    // Dark turquoise
            else -> Color.rgb(50, 205, 50) // Lime green
        }
    }
    private val wingColor by lazy { Color.argb(90, 210, 215, 225) }  // Translucent wings
    
    // Movement - dragonflies dart and hover
    private val hoverFreqX = 1.5f + random.nextFloat() * 1.0f
    private val hoverFreqY = 2.0f + random.nextFloat() * 1.0f
    private val hoverAmpX = 0.02f + random.nextFloat() * 0.02f
    private val hoverAmpY = 0.015f + random.nextFloat() * 0.015f
    private val driftX = 0.0015f + random.nextFloat() * 0.0015f
    
    // Wing beat is very fast
    private val wingPhase = random.nextFloat() * 6.28f
    
    // Parallax
    private val parallaxDepth = 0.45f + distance * 0.25f

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        val progress = (elapsedMs / durationMs.toFloat()).coerceIn(0f, 1f)
        val dartPhase = sin(t * 0.3f)
        val dartBoost = if (dartPhase > 0.9f) 0.012f else 0f
        return EventTraversal.xAtProgress(traversal, progress) +
            direction * t * driftX * dartBoost +
            sin(t * hoverFreqX + wingPhase) * hoverAmpX
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        return startY + sin(t * hoverFreqY) * hoverAmpY
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val unit = height * 0.002f * sizeScale
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val progress = (traversalElapsed / durationMs.toFloat()).coerceIn(0f, 1f)
        
        // Hovering movement with occasional darts
        val dartPhase = sin(t * 0.3f)
        val dartBoost = if (dartPhase > 0.9f) 0.012f else 0f
        
        val x = EventTraversal.xAtProgress(traversal, progress) +
            direction * t * driftX * dartBoost +
            sin(t * hoverFreqX + wingPhase) * hoverAmpX
        val y = startY + sin(t * hoverFreqY) * hoverAmpY
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val screenX = x * width - extendedOffset
        val screenY = EventTraversal.mapFieldY(y, height, extendedOffsetY)
        
        // 40 rad/s on the wrapped 24h float clock steps by ~18 degrees. Elapsed time does not.
        val wingBeat = sin(t * 40f + wingPhase)
        
        paint.color = RenderConstants.blendWithAmbient(bodyColor, ambientColor, 0.2f)
        
        canvas.save()
        if (direction < 0) {
            canvas.scale(-1f, 1f, screenX, screenY)
        }
        
        // Draw body (long thin abdomen + thorax)
        // Thorax
        canvas.drawCircle(screenX, screenY, unit * 1.5f, paint)
        
        // Abdomen (long segmented tail), widest at the thorax and tapering to the tip.
        // It used to be a triangle with its point at the thorax, so the body met the
        // abdomen at zero width and read as two separate pieces.
        path.reset()
        path.moveTo(screenX - unit * 0.5f, screenY - unit * 0.75f)
        path.lineTo(screenX - unit * 8f, screenY - unit * 0.14f)
        path.lineTo(screenX - unit * 8f, screenY + unit * 0.14f)
        path.lineTo(screenX - unit * 0.5f, screenY + unit * 0.75f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Head
        canvas.drawCircle(screenX + unit * 2f, screenY, unit * 1.0f, paint)
        
        // Big compound eyes
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawCircle(screenX + unit * 2.5f, screenY - unit * 0.3f, unit * 0.5f, paint)
        canvas.drawCircle(screenX + unit * 2.5f, screenY + unit * 0.3f, unit * 0.5f, paint)
        
        // Wings (4 wings, translucent)
        paint.color = RenderConstants.blendWithAmbient(wingColor, ambientColor, 0.15f)
        val wingAngle = wingBeat  // Full-amplitude beat, applied per wing pair below

        // Top wings (front pair)
        canvas.save()
        canvas.rotate(wingAngle * 13f, screenX, screenY)
        canvas.drawOval(
            screenX - unit * 0.2f, screenY - unit * 5f,
            screenX + unit * 1.5f, screenY - unit * 0.4f,
            paint
        )
        canvas.restore()

        canvas.save()
        canvas.rotate(-wingAngle * 13f, screenX, screenY)
        canvas.drawOval(
            screenX - unit * 0.2f, screenY + unit * 0.4f,
            screenX + unit * 1.5f, screenY + unit * 5f,
            paint
        )
        canvas.restore()

        // Bottom wings (rear pair). Both pairs attach to the thorax on a real dragonfly,
        // so this pair sits just behind the front one rather than out on the abdomen.
        canvas.save()
        canvas.rotate(-wingAngle * 11f, screenX - unit * 1.3f, screenY)
        canvas.drawOval(
            screenX - unit * 2.1f, screenY - unit * 4.2f,
            screenX - unit * 0.6f, screenY - unit * 0.3f,
            paint
        )
        canvas.restore()

        canvas.save()
        canvas.rotate(wingAngle * 11f, screenX - unit * 1.3f, screenY)
        canvas.drawOval(
            screenX - unit * 2.1f, screenY + unit * 0.3f,
            screenX - unit * 0.6f, screenY + unit * 4.2f,
            paint
        )
        canvas.restore()
        
        canvas.restore()
        canvas.restore()
    }
}
