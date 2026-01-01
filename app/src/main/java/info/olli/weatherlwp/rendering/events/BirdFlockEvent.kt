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
 * Bird Flock with V-shape strokes, glide/flap behavior.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class BirdFlockEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains, between clouds and terrain
    
    private val birds = List(3 + random.nextInt(5)) { Bird(random) }
    private val baseY = 0.15f + random.nextFloat() * 0.25f
    // Reduced speed significantly: was 0.0003-0.0005, now 0.00006-0.00012 (5x slower)
    private val speed = 0.00003f + random.nextFloat() * 0.00003f  // Slower bird movement
    override val durationMs: Long = (1.35f / speed).toLong()
    
    private val path = Path()
    
    // Flapping pattern is deterministic based on seed
    private val flapCycleMs = 3000L + random.nextLong(4000)
    private val flapUpDuration = 800L
    
    private class Bird(random: Random) {
        val offsetX = random.nextFloat() * 0.08f
        val offsetY = (random.nextFloat() - 0.5f) * 0.04f
        val wingPhase = random.nextFloat() * 6.28f
        val size = 4f + random.nextFloat() * 3f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Calculate x position from elapsed time
        val x = -0.15f + speed * elapsedMs
        
        // Deterministic flap pattern: cycle between glide and flap
        val cyclePos = elapsedMs % flapCycleMs
        val isFlapping = cyclePos > (flapCycleMs - flapUpDuration)
        
        // Y position oscillates based on flap state
        val cycleOffset = if (isFlapping) {
            -0.01f * (cyclePos - (flapCycleMs - flapUpDuration)) / flapUpDuration.toFloat()
        } else {
            0.008f * cyclePos / (flapCycleMs - flapUpDuration).toFloat()
        }
        val y = baseY + cycleOffset
        
        val px = parallaxCalculator.getOffsetXForDepth(0.4f)
        val py = parallaxCalculator.getOffsetYForDepth(0.4f)
        canvas.save()
        canvas.translate(px, py)
        
        strokePaint.color = RenderConstants.blendWithAmbient(Color.rgb(30, 30, 30), ambientColor, 0.4f)
        strokePaint.style = Paint.Style.STROKE
        
        for (bird in birds) {
            val birdX = (x + bird.offsetX) * width - extendedOffset
            val birdY = (y + bird.offsetY) * height
            
            val wingY = if (isFlapping) {
                sin(timeSeconds * 18f + bird.wingPhase) * bird.size * 1.0f
            } else {
                sin(timeSeconds * 1.5f + bird.wingPhase) * bird.size * 0.15f - bird.size * 0.3f
            }
            
            strokePaint.strokeWidth = (bird.size * 0.35f).coerceAtLeast(1.2f)
            
            path.reset()
            path.moveTo(birdX - bird.size, birdY)
            path.quadTo(birdX - bird.size * 0.2f, birdY - wingY, birdX, birdY)
            path.quadTo(birdX + bird.size * 0.2f, birdY - wingY, birdX + bird.size, birdY)
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }
}
