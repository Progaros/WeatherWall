package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shooting Star - Night only, fast diagonal streak with gradient tail.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Spawns off-screen (top-left) and fades out near end (burning up effect).
 */
class ShootingStarEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.FAR_SKY  // Draw behind mountains
    
    // Spawn off-screen to the upper-left area, will streak diagonally across
    private val startX = -0.15f + random.nextFloat() * 0.3f  // -0.15 to 0.15 (mostly off-screen left)
    private val startY = -0.1f + random.nextFloat() * 0.1f   // -0.1 to 0.0 (off-screen top)
    private val angle = 0.4f + random.nextFloat() * 0.3f     // ~23-40 degrees down-right
    private val speed = 0.00015f + random.nextFloat() * 0.0001f  // Slower - graceful arc across sky
    private val length = 0.10f + random.nextFloat() * 0.08f   // Slightly longer tail
    
    // Delay before the shooting star becomes visible (~1 second)
    private val spawnDelayMs = 800L + (random.nextFloat() * 400).toLong()  // 0.8-1.2 sec
    
    // Total travel distance before fade-out completes (travel across 100-130% of screen width)
    private val totalTravel = 1.0f + random.nextFloat() * 0.3f
    
    // Fade out during last 20% of journey
    private val fadeStartProgress = totalTravel * 0.8f
    
    override val durationMs: Long = spawnDelayMs + (totalTravel / speed).toLong()
    
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val activeElapsedMs = (elapsedMs - spawnDelayMs).coerceAtLeast(0L)
        val progress = speed * activeElapsedMs
        return startX + cos(angle) * progress * 0.5f
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val activeElapsedMs = (elapsedMs - spawnDelayMs).coerceAtLeast(0L)
        val progress = speed * activeElapsedMs
        return startY + sin(angle) * progress * 0.5f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Don't draw during the delay period
        if (elapsedMs < spawnDelayMs) return
        
        // Adjust progress for the delay
        val activeElapsedMs = elapsedMs - spawnDelayMs
        val progress = speed * activeElapsedMs
        
        // Calculate fade-out alpha (1.0 -> 0.0 during last 20% of journey)
        val fadeAlpha = if (progress >= fadeStartProgress) {
            val fadeProgress = (progress - fadeStartProgress) / (totalTravel - fadeStartProgress)
            (1f - fadeProgress).coerceIn(0f, 1f)
        } else {
            1f
        }
        
        // Skip drawing if fully faded
        if (fadeAlpha <= 0f) return
        
        // Scale size to 0.5% of height - thin elegant streak
        val unit = height * 0.005f
        
        val px = parallaxCalculator.getOffsetXForDepth(0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(0.05f)
        canvas.save()
        canvas.translate(px, py)
        
        val headX = (startX + cos(angle) * progress * 0.5f) * width - extendedOffset
        val headY = (startY + sin(angle) * progress * 0.5f) * height
        
        // Tail length shrinks during fade-out (meteor burning up)
        val effectiveLength = length * (0.3f + 0.7f * fadeAlpha)
        val tailX = (startX + cos(angle) * (progress - effectiveLength) * 0.5f) * width - extendedOffset
        val tailY = (startY + sin(angle) * (progress - effectiveLength) * 0.5f) * height
        
        // Apply fade alpha to all colors
        val headAlpha = (255 * fadeAlpha).toInt()
        val glowAlpha = (200 * fadeAlpha).toInt()
        
        trailPaint.shader = android.graphics.LinearGradient(
            tailX, tailY, headX, headY,
            Color.argb(0, 255, 255, 255),
            Color.argb(headAlpha, 255, 255, 240),
            android.graphics.Shader.TileMode.CLAMP
        )
        trailPaint.strokeWidth = 3f * unit
        canvas.drawLine(tailX, tailY, headX, headY, trailPaint)
        
        trailPaint.shader = android.graphics.LinearGradient(
            tailX, tailY, headX, headY,
            Color.argb(0, 255, 255, 255),
            Color.argb(glowAlpha, 255, 255, 200),
            android.graphics.Shader.TileMode.CLAMP
        )
        trailPaint.strokeWidth = 1.5f * unit
        canvas.drawLine(tailX + (headX-tailX)*0.5f, tailY + (headY-tailY)*0.5f, headX, headY, trailPaint)
        
        paint.color = Color.argb(headAlpha, 255, 255, 255)
        // Head shrinks during fade-out
        val headSize = 2.5f * unit * (0.5f + 0.5f * fadeAlpha)
        canvas.drawCircle(headX, headY, headSize, paint)
        
        trailPaint.shader = null
        canvas.restore()
    }
}
