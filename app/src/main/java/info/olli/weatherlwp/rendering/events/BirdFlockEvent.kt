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
 * Distance affects size, speed, and parallax for depth perception.
 */
class BirdFlockEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.2-0.8 (birds can be close or far)
    private val distance = 0.2f + random.nextFloat() * 0.6f
    
    // Size scales inversely with distance
    private val sizeScale = 1.0f - distance * 0.6f  // 0.52-1.0 based on distance
    
    // Far birds are higher in the sky
    private val baseY = 0.12f + random.nextFloat() * 0.20f + distance * 0.06f
    
    // Speed scales with distance (far = slower)
    private val baseSpeed = 0.00004f - distance * 0.00002f
    private val speed = baseSpeed + random.nextFloat() * 0.00002f
    override val durationMs: Long = (1.35f / speed).toLong()
    
    // Fewer birds in distant flocks
    private val birdCount = (3 + (1f - distance) * 5).toInt().coerceIn(2, 7)
    private val birds = List(birdCount) { Bird(random, sizeScale) }
    
    private val path = Path()
    
    // Flapping pattern is deterministic based on seed
    private val flapCycleMs = 3000L + random.nextLong(4000)
    private val flapUpDuration = 800L
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.25f + distance * 0.25f
    
    private class Bird(random: Random, sizeScale: Float) {
        val offsetX = random.nextFloat() * 0.06f * sizeScale
        val offsetY = (random.nextFloat() - 0.5f) * 0.03f * sizeScale
        val wingPhase = random.nextFloat() * 6.28f
        val sizeVariation = 0.8f + random.nextFloat() * 0.4f  // Individual bird size variation
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
        
        // Y position oscillates based on flap state (less movement for distant birds)
        val cycleOffset = if (isFlapping) {
            -0.008f * sizeScale * (cyclePos - (flapCycleMs - flapUpDuration)) / flapUpDuration.toFloat()
        } else {
            0.006f * sizeScale * cyclePos / (flapCycleMs - flapUpDuration).toFloat()
        }
        val y = baseY + cycleOffset
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        // Scale: 0.3-0.5% of height based on distance (was 1%)
        val unit = height * 0.004f * sizeScale
        
        // Distant birds are more faded
        val alpha = (1f - distance * 0.3f)
        strokePaint.color = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 30, 30, 30), ambientColor, 0.4f)
        strokePaint.style = Paint.Style.STROKE
        
        for (bird in birds) {
            val size = bird.sizeVariation * unit * 5f  // Scale bird size
            val birdX = (x + bird.offsetX) * width - extendedOffset
            val birdY = (y + bird.offsetY) * height
            
            val wingY = if (isFlapping) {
                sin(timeSeconds * 18f + bird.wingPhase) * size * 0.9f
            } else {
                sin(timeSeconds * 1.5f + bird.wingPhase) * size * 0.12f - size * 0.25f
            }
            
            strokePaint.strokeWidth = (size * 0.3f).coerceAtLeast(1f)
            
            path.reset()
            path.moveTo(birdX - size, birdY)
            path.quadTo(birdX - size * 0.2f, birdY - wingY, birdX, birdY)
            path.quadTo(birdX + size * 0.2f, birdY - wingY, birdX + size, birdY)
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }
}
