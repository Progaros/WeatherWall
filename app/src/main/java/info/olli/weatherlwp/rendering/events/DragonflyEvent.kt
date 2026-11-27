package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withRotation
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
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
    private val farWingColor by lazy { Color.rgb(165, 172, 188) }
    private val bodyDarkColor by lazy {
        Color.rgb(
            (Color.red(bodyColor) * 0.5f).toInt(),
            (Color.green(bodyColor) * 0.5f).toInt(),
            (Color.blue(bodyColor) * 0.5f).toInt()
        )
    }
    private val eyeColor by lazy { Color.rgb(45, 50, 60) }
    private val eyeHighlightColor by lazy { Color.rgb(160, 170, 185) }
    private val legColor by lazy { Color.rgb(40, 40, 45) }
    
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
        // Hindwings lead the forewings slightly, as on a real dragonfly.
        val hindBeat = sin(t * 40f + wingPhase + 1.2f)
        // Side view, facing +x: negative angles sweep the wing up and back.
        val foreAngle = -32f + wingBeat * 24f
        val hindAngle = -50f + hindBeat * 24f
        
        canvas.save()
        if (direction < 0) {
            canvas.scale(-1f, 1f, screenX, screenY)
        }
        
        val foreRootX = screenX + unit * 0.3f
        val hindRootX = screenX - unit * 0.5f
        val wingRootY = screenY - unit * 0.7f
        
        // Far-side wings, behind the body: slightly offset, more swept and darker
        val farWing = RenderConstants.blendWithAmbient(farWingColor, ambientColor, 0.15f)
        drawWing(canvas, paint, hindRootX - unit * 0.25f, wingRootY, hindAngle - 8f, unit, 4.8f, farWing, 85, 0)
        drawWing(canvas, paint, foreRootX - unit * 0.25f, wingRootY, foreAngle - 8f, unit, 5.2f, farWing, 85, 0)
        
        // Legs tucked forward under the thorax
        paint.color = RenderConstants.blendWithAmbient(legColor, ambientColor, 0.2f)
        val legHalfWidth = unit * 0.1f
        path.reset()
        for (i in 0..2) {
            val ax = screenX + unit * (-0.6f + 0.55f * i)
            val ay = screenY + unit * 0.7f
            val ex = ax + unit * 0.55f
            val ey = ay + unit * 0.8f
            path.moveTo(ax - legHalfWidth, ay)
            path.lineTo(ax + legHalfWidth, ay)
            path.lineTo(ex + legHalfWidth, ey)
            path.lineTo(ex - legHalfWidth, ey)
            path.close()
        }
        canvas.drawPath(path, paint)
        
        // Abdomen: long and thin, drooping slightly toward the tip
        paint.color = RenderConstants.blendWithAmbient(bodyColor, ambientColor, 0.2f)
        path.reset()
        path.moveTo(screenX - unit * 0.8f, screenY - unit * 0.55f)
        path.quadTo(screenX - unit * 5f, screenY - unit * 0.35f, screenX - unit * 9f, screenY + unit * 0.5f)
        path.lineTo(screenX - unit * 9.1f, screenY + unit * 0.8f)
        path.quadTo(screenX - unit * 5f, screenY + unit * 0.35f, screenX - unit * 0.8f, screenY + unit * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Segment rings, placed on the same quadratic edges as the abdomen outline
        paint.color = RenderConstants.blendWithAmbient(bodyDarkColor, ambientColor, 0.2f)
        for (i in 1..6) {
            val s = i / 7f
            val u = 1f - s
            val segX = screenX - unit * (0.8f + 8.2f * s)
            val top = u * u * -0.55f + 2f * s * u * -0.35f + s * s * 0.5f
            val bottom = u * u * 0.6f + 2f * s * u * 0.35f + s * s * 0.8f
            canvas.drawRect(
                segX - unit * 0.12f, screenY + unit * top,
                segX + unit * 0.12f, screenY + unit * bottom,
                paint
            )
        }
        
        // Thorax and head
        paint.color = RenderConstants.blendWithAmbient(bodyColor, ambientColor, 0.2f)
        canvas.drawOval(
            screenX - unit * 1.3f, screenY - unit * 1.05f,
            screenX + unit * 1.1f, screenY + unit * 0.95f,
            paint
        )
        canvas.drawCircle(screenX + unit * 1.75f, screenY - unit * 0.05f, unit * 0.8f, paint)
        
        // Big round compound eye at the front of the head
        paint.color = RenderConstants.blendWithAmbient(eyeColor, ambientColor, 0.2f)
        canvas.drawCircle(screenX + unit * 2.05f, screenY - unit * 0.25f, unit * 0.75f, paint)
        paint.color = RenderConstants.blendWithAmbient(eyeHighlightColor, ambientColor, 0.2f)
        canvas.drawCircle(screenX + unit * 2.3f, screenY - unit * 0.55f, unit * 0.22f, paint)
        
        // Near-side wings over the body: faint motion-blur ghost at the opposite end
        // of the stroke, then the main wing with an alpha shimmer and dark pterostigma.
        val nearWing = RenderConstants.blendWithAmbient(wingColor, ambientColor, 0.15f)
        val stigma = RenderConstants.blendWithAmbient(legColor, ambientColor, 0.2f)
        drawWing(canvas, paint, hindRootX, wingRootY, -50f - hindBeat * 24f, unit, 4.8f, nearWing, 40, 0)
        drawWing(canvas, paint, foreRootX, wingRootY, -32f - wingBeat * 24f, unit, 5.2f, nearWing, 40, 0)
        drawWing(canvas, paint, hindRootX, wingRootY, hindAngle, unit, 4.8f, nearWing,
            (105 + 35 * hindBeat).toInt(), stigma)
        drawWing(canvas, paint, foreRootX, wingRootY, foreAngle, unit, 5.2f, nearWing,
            (105 + 35 * wingBeat).toInt(), stigma)
        paint.alpha = 255
        
        canvas.restore()
        canvas.restore()
    }
    
    /** Long narrow wing pointing up from its root, rotated by [angleDeg]. [stigmaColor] 0 = none. */
    private fun drawWing(canvas: Canvas, paint: Paint, rootX: Float, rootY: Float, angleDeg: Float,
                         unit: Float, length: Float, color: Int, alpha: Int, stigmaColor: Int) {
        canvas.withRotation(angleDeg, rootX, rootY) {
            paint.color = color
            paint.alpha = alpha
            canvas.drawOval(
                rootX - unit * 0.5f, rootY - unit * length,
                rootX + unit * 0.5f, rootY + unit * 0.2f,
                paint
            )
            if (stigmaColor != 0) {
                // +x side is the leading edge once the wing is swept back
                paint.color = stigmaColor
                canvas.drawOval(
                    rootX + unit * 0.05f, rootY - unit * (length - 0.5f),
                    rootX + unit * 0.4f, rootY - unit * (length - 1.1f),
                    paint
                )
            }
        }
    }
}
