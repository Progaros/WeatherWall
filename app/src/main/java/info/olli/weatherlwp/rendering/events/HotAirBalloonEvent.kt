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
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Scale: 0.4-0.8% of height based on distance (was 1%)
        val unit = height * 0.005f * sizeScale
        val size = unit * 6f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = progress * 1.3f * width - extendedOffset
        // Less bobbing for distant balloons
        val bobIntensity = sizeScale * 0.15f
        val bobOffset = sin(timeSeconds * 0.4f + bobPhase) * size * bobIntensity
        val currentY = y * height + bobOffset
        
        val balloonHeight = size * 2.0f
        val balloonWidth = size * 1.6f
        
        // Balloon envelope (proper hot air balloon shape - wide dome with narrowing bottom)
        paint.color = RenderConstants.blendWithAmbient(color, ambientColor, 0.3f)
        path.reset()
        // Start at top center
        path.moveTo(currentX, currentY - balloonHeight)
        // Right side curve: top dome is wider and rounder
        path.cubicTo(
            currentX + balloonWidth * 1.1f, currentY - balloonHeight * 0.7f,  // Wide control point
            currentX + balloonWidth * 0.9f, currentY - balloonHeight * 0.2f,  // Curves inward
            currentX + size * 0.35f, currentY + size * 0.2f  // Bottom right (narrower opening)
        )
        // Bottom (the opening/throat)
        path.lineTo(currentX - size * 0.35f, currentY + size * 0.2f)
        // Left side curve (mirror)
        path.cubicTo(
            currentX - balloonWidth * 0.9f, currentY - balloonHeight * 0.2f,
            currentX - balloonWidth * 1.1f, currentY - balloonHeight * 0.7f,
            currentX, currentY - balloonHeight  // Back to top
        )
        path.close()
        canvas.drawPath(path, paint)
        
        // Vertical stripes/panels for realism (curved sections)
        paint.color = RenderConstants.blendWithAmbient(stripeColor, ambientColor, 0.3f)
        // Center stripe
        path.reset()
        path.moveTo(currentX - size * 0.15f, currentY - balloonHeight)
        path.cubicTo(
            currentX - size * 0.12f, currentY - balloonHeight * 0.5f,
            currentX - size * 0.1f, currentY - size * 0.1f,
            currentX - size * 0.08f, currentY + size * 0.15f
        )
        path.lineTo(currentX + size * 0.08f, currentY + size * 0.15f)
        path.cubicTo(
            currentX + size * 0.1f, currentY - size * 0.1f,
            currentX + size * 0.12f, currentY - balloonHeight * 0.5f,
            currentX + size * 0.15f, currentY - balloonHeight
        )
        path.close()
        canvas.drawPath(path, paint)
        
        // Basket
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(139, 90, 43), ambientColor, 0.3f)
        val basketY = currentY + size * 0.45f
        canvas.drawRect(currentX - size * 0.3f, basketY,
            currentX + size * 0.3f, basketY + size * 0.4f, paint)
        
        // Ropes (only for closer balloons)
        if (distance < 0.7f) {
            strokePaint.color = paint.color
            strokePaint.strokeWidth = (2f - distance).coerceAtLeast(0.5f)
            canvas.drawLine(currentX - size * 0.25f, basketY, currentX - size * 0.2f, currentY + size * 0.25f, strokePaint)
            canvas.drawLine(currentX + size * 0.25f, basketY, currentX + size * 0.2f, currentY + size * 0.25f, strokePaint)
        }
        
        canvas.restore()
    }
}
