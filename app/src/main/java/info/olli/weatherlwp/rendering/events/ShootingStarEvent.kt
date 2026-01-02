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
 * Spawns off-screen (top-left) and moves through the visible field.
 */
class ShootingStarEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.FAR_SKY  // Draw behind mountains
    
    // Spawn off-screen to the upper-left area, will streak diagonally across
    private val startX = -0.15f + random.nextFloat() * 0.3f  // -0.15 to 0.15 (mostly off-screen left)
    private val startY = -0.1f + random.nextFloat() * 0.1f   // -0.1 to 0.0 (off-screen top)
    private val angle = 0.4f + random.nextFloat() * 0.3f     // ~23-40 degrees down-right
    private val speed = 0.0005f + random.nextFloat() * 0.00025f  // Slowed 4x - more graceful streak
    private val length = 0.08f + random.nextFloat() * 0.06f
    
    // Delay before the shooting star becomes visible (~1 second)
    private val spawnDelayMs = 800L + (random.nextFloat() * 400).toLong()  // 0.8-1.2 sec
    
    override val durationMs: Long = spawnDelayMs + ((1.2f + length) / speed).toLong()
    
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
        
        // Scale size to 0.5% of height - thin elegant streak
        val unit = height * 0.005f
        
        val px = parallaxCalculator.getOffsetXForDepth(0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(0.05f)
        canvas.save()
        canvas.translate(px, py)
        
        val headX = (startX + cos(angle) * progress * 0.5f) * width - extendedOffset
        val headY = (startY + sin(angle) * progress * 0.5f) * height
        val tailX = (startX + cos(angle) * (progress - length) * 0.5f) * width - extendedOffset
        val tailY = (startY + sin(angle) * (progress - length) * 0.5f) * height
        
        trailPaint.shader = android.graphics.LinearGradient(
            tailX, tailY, headX, headY,
            Color.argb(0, 255, 255, 255),
            Color.argb(255, 255, 255, 240),
            android.graphics.Shader.TileMode.CLAMP
        )
        trailPaint.strokeWidth = 3f * unit
        canvas.drawLine(tailX, tailY, headX, headY, trailPaint)
        
        trailPaint.shader = android.graphics.LinearGradient(
            tailX, tailY, headX, headY,
            Color.argb(0, 255, 255, 255),
            Color.argb(200, 255, 255, 200),
            android.graphics.Shader.TileMode.CLAMP
        )
        trailPaint.strokeWidth = 1.5f * unit
        canvas.drawLine(tailX + (headX-tailX)*0.5f, tailY + (headY-tailY)*0.5f, headX, headY, trailPaint)
        
        paint.color = Color.WHITE
        canvas.drawCircle(headX, headY, 2.5f * unit, paint)
        
        trailPaint.shader = null
        canvas.restore()
    }
}
