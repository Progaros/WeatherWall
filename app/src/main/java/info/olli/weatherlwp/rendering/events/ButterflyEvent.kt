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
    
    // Spawn fully off-screen and cross the full viewport before expiring.
    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.12f)
    private val startY = 0.30f + random.nextFloat() * 0.18f  // 0.30-0.48: above terrain, visible in MID_SKY layer
    private val travelSpeed = 0.000028f + random.nextFloat() * 0.000016f
    override val durationMs = EventTraversal.durationMsForPath(
        path = traversal,
        speed = travelSpeed,
        minDurationMs = 8000L
    )
    
    private val path = Path()
    private val flutterPhase = random.nextFloat() * 6.28f
    private val color = when (random.nextInt(4)) {
        0 -> Color.rgb(255, 160, 40)   // Monarch Orange
        1 -> Color.rgb(100, 180, 255)  // Blue
        2 -> Color.rgb(255, 240, 80)   // Yellow
        else -> Color.rgb(255, 180, 200) // Pink
    }
    // Reduced movement values for more natural, gentle flight
    private val driftSpeedY = 0.002f + random.nextFloat() * 0.003f  // Slower vertical drift
    private val wobbleFreqX = 0.3f + random.nextFloat() * 0.2f     // Slower wobble frequency
    private val wobbleFreqY = 0.2f + random.nextFloat() * 0.15f    // Slower vertical wobble
    private val wobbleAmpX = 0.015f + random.nextFloat() * 0.02f   // Reduced horizontal amplitude
    private val wobbleAmpY = 0.01f + random.nextFloat() * 0.015f   // Reduced vertical amplitude
    // Parallax based on distance
    private val parallaxDepth = 0.55f + distance * 0.25f

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        val progress = (elapsedMs / durationMs.toFloat()).coerceIn(0f, 1f)
        return EventTraversal.xAtProgress(traversal, progress) +
            sin(t * wobbleFreqX + flutterPhase) * wobbleAmpX
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        return startY +
            sin(t * wobbleFreqY + flutterPhase * 0.7f + t * driftSpeedY) * wobbleAmpY +
            cos(t * wobbleFreqY * 0.5f) * wobbleAmpY * 0.3f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Butterflies: 0.4-0.6% of screen height
        val unit = height * 0.003f * sizeScale
        val size = unit * 4f  // About 1.2% max wingspan (24px on 2000px screen)
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        val progress = (elapsedMs / durationMs.toFloat()).coerceIn(0f, 1f)
        
        // Horizontal movement is guaranteed to traverse from off-screen to off-screen.
        val x = EventTraversal.xAtProgress(traversal, progress) +
            sin(t * wobbleFreqX + flutterPhase) * wobbleAmpX
        
        val y = startY + 
            sin(t * wobbleFreqY + flutterPhase * 0.7f + t * driftSpeedY) * wobbleAmpY +
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
        paint.style = Paint.Style.FILL
        
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
        
        // Wing spots (darker circles on each wing)
        val spotColor = RenderConstants.blendWithAmbient(
            Color.argb(140, 40, 30, 20), ambientColor, 0.2f)
        paint.color = spotColor
        // Spots scale with wing opening
        canvas.save()
        canvas.scale(leftScale, 1f, currentX, currentY)
        canvas.drawCircle(currentX - size * 1.2f, currentY - size * 0.6f, size * 0.25f, paint)
        canvas.drawCircle(currentX - size * 0.8f, currentY + size * 0.5f, size * 0.18f, paint)
        canvas.restore()
        canvas.save()
        canvas.scale(rightScale, 1f, currentX, currentY)
        canvas.drawCircle(currentX + size * 1.2f, currentY - size * 0.6f, size * 0.25f, paint)
        canvas.drawCircle(currentX + size * 0.8f, currentY + size * 0.5f, size * 0.18f, paint)
        canvas.restore()
        
        // Body (thin dark line between wings)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(40, 30, 20), ambientColor, 0.2f)
        canvas.drawOval(currentX - size * 0.08f, currentY - size * 0.4f, 
                        currentX + size * 0.08f, currentY + size * 0.4f, paint)
        
        // Antennae
        strokePaint.color = paint.color
        strokePaint.strokeWidth = size * 0.04f
        strokePaint.style = Paint.Style.STROKE
        canvas.drawLine(currentX - size * 0.05f, currentY - size * 0.35f,
                        currentX - size * 0.5f, currentY - size * 1.2f, strokePaint)
        canvas.drawLine(currentX + size * 0.05f, currentY - size * 0.35f,
                        currentX + size * 0.5f, currentY - size * 1.2f, strokePaint)
        // Antenna tips (small dots)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(currentX - size * 0.5f, currentY - size * 1.2f, size * 0.06f, paint)
        canvas.drawCircle(currentX + size * 0.5f, currentY - size * 1.2f, size * 0.06f, paint)
        
        canvas.restore()
    }
}
