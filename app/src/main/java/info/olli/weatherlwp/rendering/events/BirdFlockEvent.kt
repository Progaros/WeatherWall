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
 * Bird Flock with V-shape strokes, glide/flap behavior
 */
class BirdFlockEvent(random: Random) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains, between clouds and terrain
    
    private val birds = List(3 + random.nextInt(5)) { Bird(random) }
    private var x = -0.15f
    private var y = 0.15f + random.nextFloat() * 0.25f
    private val speed = 0.0003f + random.nextFloat() * 0.0002f
    private val path = Path()
    private var flapping = false
    private var flapTimer = 0L
    private var nextFlapTime = 2000L + random.nextLong(4000)
    private var flapDuration = 0L
    private val eventRandom = random
    
    private class Bird(random: Random) {
        val offsetX = random.nextFloat() * 0.08f
        val offsetY = (random.nextFloat() - 0.5f) * 0.04f
        val wingPhase = random.nextFloat() * 6.28f
        val size = 4f + random.nextFloat() * 3f
        var localY = 0f
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += speed * deltaTimeMs
        
        flapTimer += deltaTimeMs
        if (flapping) {
            flapDuration += deltaTimeMs
            y -= 0.000015f * deltaTimeMs
            if (flapDuration > 800) {
                flapping = false
                flapDuration = 0
                flapTimer = 0
                nextFlapTime = 2000L + eventRandom.nextLong(5000)
            }
        } else {
            y += 0.000008f * deltaTimeMs
            if (flapTimer > nextFlapTime) {
                flapping = true
            }
        }
        
        return x < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.4f)
        val py = parallaxCalculator.getOffsetYForDepth(0.4f)
        canvas.save()
        canvas.translate(px, py)
        
        strokePaint.color = RenderConstants.blendWithAmbient(Color.rgb(30, 30, 30), ambientColor, 0.4f)
        strokePaint.style = Paint.Style.STROKE
        
        for (bird in birds) {
            val birdX = (x + bird.offsetX) * width
            val birdY = (y + bird.offsetY) * height
            
            val wingY = if (flapping) {
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
