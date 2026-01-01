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
 * Hot Air Balloon - Day only, slow drift in far background.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class HotAirBalloonEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains
    
    private val y = 0.12f + random.nextFloat() * 0.15f
    private val speed = 0.000015f + random.nextFloat() * 0.00001f  // Slowed 2x - graceful drift
    override val durationMs: Long = (1.3f / speed).toLong()
    
    private val size = 8f + random.nextFloat() * 4f
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
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.1f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(0.25f)  // Between mountains and clouds
        val py = parallaxCalculator.getOffsetYForDepth(0.25f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = progress * 1.2f * width - extendedOffset
        val bobOffset = sin(timeSeconds * 0.4f + bobPhase) * size * 0.15f
        val currentY = y * height + bobOffset
        
        val balloonHeight = size * 2.5f
        val balloonWidth = size * 1.8f
        
        paint.color = RenderConstants.blendWithAmbient(color, ambientColor, 0.3f)
        path.reset()
        path.moveTo(currentX, currentY - balloonHeight)
        path.cubicTo(
            currentX + balloonWidth, currentY - balloonHeight * 0.8f,
            currentX + balloonWidth * 0.8f, currentY,
            currentX, currentY + size * 0.3f
        )
        path.cubicTo(
            currentX - balloonWidth * 0.8f, currentY,
            currentX - balloonWidth, currentY - balloonHeight * 0.8f,
            currentX, currentY - balloonHeight
        )
        canvas.drawPath(path, paint)
        
        paint.color = RenderConstants.blendWithAmbient(stripeColor, ambientColor, 0.3f)
        canvas.drawRect(currentX - balloonWidth * 0.7f, currentY - balloonHeight * 0.5f,
            currentX + balloonWidth * 0.7f, currentY - balloonHeight * 0.35f, paint)
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(139, 90, 43), ambientColor, 0.3f)
        val basketY = currentY + size * 0.5f
        canvas.drawRect(currentX - size * 0.4f, basketY,
            currentX + size * 0.4f, basketY + size * 0.5f, paint)
        
        strokePaint.color = paint.color
        strokePaint.strokeWidth = 1f
        canvas.drawLine(currentX - size * 0.35f, basketY, currentX - size * 0.3f, currentY + size * 0.3f, strokePaint)
        canvas.drawLine(currentX + size * 0.35f, basketY, currentX + size * 0.3f, currentY + size * 0.3f, strokePaint)
        
        canvas.restore()
    }
}
