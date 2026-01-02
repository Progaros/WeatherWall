package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Butterfly with distinct wing shapes and natural erratic flight pattern.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class ButterflyEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // Between mountains and terrain, near flowers
    
    // Distance for depth: 0.3-0.8 (butterflies mostly close, some farther)
    private val distance = 0.3f + random.nextFloat() * 0.5f
    private val sizeScale = 1.0f - distance * 0.4f  // 0.68-1.0 based on distance
    
    private val startX = random.nextFloat()
    private val startY = 0.55f + random.nextFloat() * 0.15f
    override val durationMs = 8000L + random.nextLong(5000)
    
    private val path = Path()
    private val flutterPhase = random.nextFloat() * 6.28f
    private val color = when (random.nextInt(4)) {
        0 -> Color.rgb(255, 160, 40)   // Monarch Orange
        1 -> Color.rgb(100, 180, 255)  // Blue
        2 -> Color.rgb(255, 240, 80)   // Yellow
        else -> Color.rgb(255, 180, 200) // Pink
    }
    private val driftSpeedX = 0.005f + random.nextFloat() * 0.008f
    private val driftSpeedY = 0.003f + random.nextFloat() * 0.005f
    private val wobbleFreqX = 0.6f + random.nextFloat() * 0.4f
    private val wobbleFreqY = 0.4f + random.nextFloat() * 0.3f
    private val wobbleAmpX = 0.03f + random.nextFloat() * 0.04f
    private val wobbleAmpY = 0.02f + random.nextFloat() * 0.03f
    private val direction = if (random.nextBoolean()) 1f else -1f
    
    // Parallax based on distance
    private val parallaxDepth = 0.55f + distance * 0.25f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Butterflies: 0.4-0.6% of screen height
        val unit = height * 0.003f * sizeScale
        val size = unit * 4f  // About 1.2% max wingspan (24px on 2000px screen)
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        
        // Calculate position from elapsed time
        val x = startX + direction * t * driftSpeedX * 0.1f + 
            sin(t * wobbleFreqX + flutterPhase) * wobbleAmpX +
            sin(t * wobbleFreqX * 2.3f) * wobbleAmpX * 0.4f
        
        val y = startY + 
            sin(t * wobbleFreqY + flutterPhase * 0.7f) * wobbleAmpY +
            sin(t * wobbleFreqY * 1.7f) * wobbleAmpY * 0.5f +
            cos(t * wobbleFreqY * 0.5f) * wobbleAmpY * 0.3f
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = x * width - extendedOffset
        val currentY = y * height
        
        val flutter = sin(timeSeconds * 16f + flutterPhase) * 
            (0.8f + sin(timeSeconds * 3f) * 0.2f)
        
        paint.color = RenderConstants.blendWithAmbient(color, ambientColor, 0.25f)
        
        fun drawWing(dir: Float) {
            path.reset()
            path.moveTo(currentX, currentY)
            path.cubicTo(
                currentX + (size * 1.8f * dir), currentY - size * 2.2f,
                currentX + (size * 3.2f * dir), currentY - size * 0.3f,
                currentX, currentY
            )
            path.cubicTo(
                currentX + (size * 2.0f * dir), currentY + size * 1.2f,
                currentX + (size * 0.4f * dir), currentY + size * 2.0f,
                currentX, currentY
            )
            canvas.drawPath(path, paint)
        }

        val wingScale = flutter
        
        canvas.save()
        val leftScale = (0.2f + (1f + wingScale) * 0.4f).coerceIn(0.15f, 1f)
        canvas.scale(leftScale, 1f, currentX, currentY)
        drawWing(-1f)
        canvas.restore()
        
        canvas.save()
        val rightScale = (0.2f + (1f - wingScale) * 0.4f).coerceIn(0.15f, 1f)
        canvas.scale(rightScale, 1f, currentX, currentY)
        drawWing(1f)
        canvas.restore()
        
        canvas.restore()
    }
}
