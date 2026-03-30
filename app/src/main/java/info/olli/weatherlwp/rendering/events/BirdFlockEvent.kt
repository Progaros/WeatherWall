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

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        return -0.15f + speed * elapsedMs
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedSec = (absoluteTimeMs - spawnTimeMs) / 1000f
        val flapCycleSec = flapCycleMs / 1000f
        val cyclePhase = (elapsedSec % flapCycleSec) / flapCycleSec
        val verticalWave = sin(cyclePhase * 6.28f) * 0.008f * sizeScale
        return baseY + verticalWave
    }
    
    private class Bird(random: Random, sizeScale: Float) {
        val offsetX = random.nextFloat() * 0.06f * sizeScale
        val offsetY = (random.nextFloat() - 0.5f) * 0.03f * sizeScale
        val wingPhase = random.nextFloat() * 6.28f
        val sizeVariation = 0.8f + random.nextFloat() * 0.4f  // Individual bird size variation
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val elapsedSec = elapsedMs / 1000f
        
        // Calculate x position from elapsed time
        val x = -0.15f + speed * elapsedMs
        
        // Smooth flap cycle using sine - continuous oscillation
        val flapCycleSec = flapCycleMs / 1000f
        val cyclePhase = (elapsedSec % flapCycleSec) / flapCycleSec  // 0-1 through cycle
        
        // Smooth vertical movement: birds rise during flap, glide down slowly
        // Use smoothstep for natural easing
        val flapPortion = 0.25f  // Flapping takes 25% of cycle
        val isFlapping = cyclePhase < flapPortion
        
        // Vertical position: smooth sine wave with bias upward during flap
        val verticalWave = sin(cyclePhase * 6.28f) * 0.008f * sizeScale
        val y = baseY + verticalWave
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        // Scale: 0.4-0.7% of height based on distance - bird V-shapes
        val unit = height * 0.003f * sizeScale
        
        // Distant birds are more faded
        val alpha = (1f - distance * 0.3f)
        strokePaint.color = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 30, 30, 30), ambientColor, 0.4f)
        strokePaint.style = Paint.Style.STROKE
        
        for (bird in birds) {
            val size = bird.sizeVariation * unit * 4f
            val birdX = (x + bird.offsetX) * width - extendedOffset
            val birdY = (y + bird.offsetY) * height
            
            // Smooth wing animation using sine
            // During flap: fast wing beats with larger amplitude
            // During glide: slow gentle movement with wings mostly up
            val wingBeatSpeed = if (isFlapping) 10f else 1.5f
            val wingAmplitude = if (isFlapping) size * 0.35f else size * 0.1f
            // Base wing angle: wings curve upward (negative Y = up on screen)
            val baseWingUp = size * 0.4f
            
            // Wing lift oscillates around base position, always keeping wings angled up
            // Use abs(sin) to keep wings always curved upward, varying lift amount
            val sineValue = sin(elapsedSec * wingBeatSpeed + bird.wingPhase)
            val wingLift = baseWingUp + sineValue * wingAmplitude
            
            strokePaint.strokeWidth = (size * 0.25f).coerceAtLeast(1.5f)
            
            // Draw bird as smooth curved V-shape (wings always curve up from body)
            // Wingtips are above body (birdY - wingLift), body is at center
            path.reset()
            path.moveTo(birdX - size, birdY - wingLift)  // Left wingtip (above body)
            path.quadTo(birdX - size * 0.3f, birdY - wingLift * 0.3f, birdX, birdY)  // Left wing curves down to body
            path.quadTo(birdX + size * 0.3f, birdY - wingLift * 0.3f, birdX + size, birdY - wingLift)  // Right wing curves up to tip
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }
}
