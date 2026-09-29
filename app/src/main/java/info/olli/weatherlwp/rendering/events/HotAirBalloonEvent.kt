package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hot Air Balloon - Day only, slow drift at varying distances.
 * Distance affects size, speed, and parallax for depth perception.
 */
class HotAirBalloonEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.3-1.0 (balloons can be fairly close or far)
    private val distance = 0.3f + random.nextFloat() * 0.7f
    
    // Size scales inversely with distance
    private val sizeScale = 1.0f - distance * 0.6f  // 0.4-1.0 based on distance
    
    // Far balloons are higher in the sky
    private val y = 0.08f + random.nextFloat() * 0.12f + distance * 0.08f
    
    // Speed scales with distance (far = slower perceived movement)
    private val baseSpeed = 0.000025f - distance * 0.000015f
    private val speed = baseSpeed + random.nextFloat() * 0.000008f
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val path = Path()
    private val domeRect = RectF()
    private val color = when (random.nextInt(5)) {
        0 -> Color.rgb(220, 80, 80)
        1 -> Color.rgb(80, 150, 220)
        2 -> Color.rgb(220, 180, 80)
        3 -> Color.rgb(80, 180, 120)
        else -> Color.rgb(180, 100, 180)
    }
    private val stripeColor = when (random.nextInt(3)) {
        0 -> Color.WHITE
        1 -> Color.rgb(255, 220, 100)
        else -> Color.rgb(200, 200, 200)
    }
    private val bobPhase = random.nextFloat() * 6.28f
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.15f + distance * 0.20f

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        return progress * 1.3f
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val elapsedSec = (absoluteTimeMs - spawnTimeMs) / 1000f
        val bobOffsetRatio = sin(elapsedSec * 0.4f + bobPhase) * (0.0045f * sizeScale * sizeScale)
        return y + bobOffsetRatio
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        // Scale: 0.4-0.8% of height based on distance (was 1%)
        val unit = height * 0.005f * sizeScale
        val size = unit * 6f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val progress = -0.1f + speed * traversalElapsed
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = progress * 1.3f * width - extendedOffset
        // Less bobbing for distant balloons
        val bobIntensity = sizeScale * 0.15f
        val bobOffset = sin(timeSeconds * 0.4f + bobPhase) * size * bobIntensity
        val currentY = EventTraversal.mapFieldY(y, height, extendedOffsetY) + bobOffset
        
        val balloonHeight = size * 2.0f
        val topY = currentY - balloonHeight
        val throatY = currentY + size * 0.2f
        val throatHalf = size * 0.3f
        // Slightly flattened circular dome; its widest point (equator) sits at domeCy.
        val domeRx = size * 1.0f
        val domeRy = size * 0.95f
        val domeCy = topY + domeRy
        val taper = throatY - domeCy
        
        // Envelope: round dome on top, bulging sides tapering to a narrow mouth.
        path.reset()
        path.moveTo(currentX - throatHalf, throatY)
        path.cubicTo(
            currentX - throatHalf - size * 0.18f, throatY - taper * 0.3f,
            currentX - domeRx, domeCy + taper * 0.5f,
            currentX - domeRx, domeCy
        )
        domeRect.set(currentX - domeRx, domeCy - domeRy, currentX + domeRx, domeCy + domeRy)
        path.arcTo(domeRect, 180f, 180f, false)
        path.cubicTo(
            currentX + domeRx, domeCy + taper * 0.5f,
            currentX + throatHalf + size * 0.18f, throatY - taper * 0.3f,
            currentX + throatHalf, throatY
        )
        path.close()
        
        val envelopeColor = RenderConstants.blendWithAmbient(color, ambientColor, 0.3f)
        val gorePaintColor = RenderConstants.blendWithAmbient(stripeColor, ambientColor, 0.3f)
        paint.color = envelopeColor
        canvas.drawPath(path, paint)
        
        // Gores: horizontally squeezed copies of the envelope follow its meridians,
        // converging at the crown and the mouth like real balloon panels.
        canvas.save()
        canvas.scale(0.72f, 1f, currentX, currentY)
        paint.color = gorePaintColor
        canvas.drawPath(path, paint)
        canvas.restore()
        canvas.save()
        canvas.scale(0.5f, 1f, currentX, currentY)
        paint.color = envelopeColor
        canvas.drawPath(path, paint)
        canvas.restore()
        canvas.save()
        canvas.scale(0.16f, 1f, currentX, currentY)
        paint.color = gorePaintColor
        canvas.drawPath(path, paint)
        canvas.restore()
        
        // Basket hangs below the throat. Ropes were skipped past distance 0.7,
        // which left the basket floating under about a third of balloons.
        val basketColor = RenderConstants.blendWithAmbient(Color.rgb(139, 90, 43), ambientColor, 0.3f)
        paint.color = basketColor
        val basketY = currentY + size * 0.55f
        canvas.drawRect(currentX - size * 0.24f, basketY,
            currentX + size * 0.24f, basketY + size * 0.34f, paint)

        strokePaint.color = basketColor
        strokePaint.strokeWidth = (size * 0.045f).coerceAtLeast(1f)
        canvas.drawLine(currentX - size * 0.28f, throatY, currentX - size * 0.22f, basketY, strokePaint)
        canvas.drawLine(currentX + size * 0.28f, throatY, currentX + size * 0.22f, basketY, strokePaint)
        canvas.drawLine(currentX - size * 0.08f, throatY, currentX - size * 0.08f, basketY, strokePaint)
        canvas.drawLine(currentX + size * 0.08f, throatY, currentX + size * 0.08f, basketY, strokePaint)
        
        canvas.restore()
    }
}
