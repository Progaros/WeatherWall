package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rare UFO/Alien event - Night only, very rare
 * Classic saucer shape with pulsing lights and erratic movement
 */
class AlienEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private var y = 0.1f + random.nextFloat() * 0.15f
    private val speed = 0.0004f + random.nextFloat() * 0.0003f
    private val size = 12f + random.nextFloat() * 6f
    private val path = Path()
    private var wobblePhase = random.nextFloat() * 6.28f
    private var hoverPhase = random.nextFloat() * 6.28f
    private val lightColors = listOf(
        Color.rgb(255, 100, 100),
        Color.rgb(100, 255, 100),
        Color.rgb(100, 100, 255),
        Color.rgb(255, 255, 100),
        Color.rgb(255, 100, 255)
    )
    private var lightPhase = 0f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += direction * speed * deltaTimeMs
        wobblePhase += deltaTimeMs * 0.005f
        hoverPhase += deltaTimeMs * 0.003f
        lightPhase += deltaTimeMs * 0.02f
        
        // Erratic vertical movement
        y += sin(hoverPhase * 2f) * 0.00002f * deltaTimeMs
        y = y.coerceIn(0.05f, 0.3f)
        
        return x > -0.15f && x < 1.15f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.15f)
        val py = parallaxCalculator.getOffsetYForDepth(0.15f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = x * width
        // Add slight wobble to position
        val wobbleX = sin(wobblePhase) * size * 0.3f
        val wobbleY = sin(hoverPhase) * size * 0.5f
        val currentY = y * height + wobbleY
        
        // Tilt based on movement
        canvas.save()
        canvas.translate(currentX + wobbleX, currentY)
        val tilt = sin(wobblePhase * 0.5f) * 8f
        canvas.rotate(tilt)
        
        // Glow effect (soft outer light)
        val glowAlpha = (100 + sin(lightPhase) * 50).toInt()
        paint.color = Color.argb(glowAlpha / 3, 150, 200, 255)
        canvas.drawOval(-size * 2.2f, -size * 0.4f, size * 2.2f, size * 0.6f, paint)
        
        // Main saucer body (dark metallic)
        paint.color = Color.rgb(60, 65, 75)
        path.reset()
        // Top dome
        path.addArc(-size * 0.8f, -size * 0.8f, size * 0.8f, size * 0.4f, 180f, 180f)
        canvas.drawPath(path, paint)
        
        // Lower saucer disk
        paint.color = Color.rgb(80, 85, 95)
        canvas.drawOval(-size * 1.8f, -size * 0.2f, size * 1.8f, size * 0.5f, paint)
        
        // Metallic rim
        paint.color = Color.rgb(140, 145, 155)
        strokePaint.color = paint.color
        strokePaint.strokeWidth = size * 0.08f
        canvas.drawOval(-size * 1.6f, -size * 0.1f, size * 1.6f, size * 0.4f, strokePaint)
        
        // Pulsing lights around the rim
        val numLights = 6
        for (i in 0 until numLights) {
            val angle = (i.toFloat() / numLights) * 6.28f
            val lightX = cos(angle) * size * 1.4f
            val lightY = sin(angle) * size * 0.2f + size * 0.15f
            
            // Pulsing effect - lights cycle through colors
            val colorIndex = ((i + (lightPhase / 3f).toInt()) % lightColors.size)
            val pulseAlpha = (180 + sin(lightPhase * 2f + i * 1.2f) * 75).toInt()
            
            // Glow
            paint.color = Color.argb(pulseAlpha / 2, 
                Color.red(lightColors[colorIndex]),
                Color.green(lightColors[colorIndex]),
                Color.blue(lightColors[colorIndex]))
            canvas.drawCircle(lightX, lightY, size * 0.15f, paint)
            
            // Core
            paint.color = Color.argb(pulseAlpha,
                Color.red(lightColors[colorIndex]),
                Color.green(lightColors[colorIndex]),
                Color.blue(lightColors[colorIndex]))
            canvas.drawCircle(lightX, lightY, size * 0.08f, paint)
        }
        
        // Dome window with reflection
        paint.color = Color.argb(200, 100, 150, 180)
        canvas.drawOval(-size * 0.5f, -size * 0.6f, size * 0.5f, -size * 0.1f, paint)
        
        // Window highlight
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.drawOval(-size * 0.3f, -size * 0.55f, size * 0.1f, -size * 0.25f, paint)
        
        // Bottom light beam (occasional)
        val beamIntensity = sin(lightPhase * 0.5f)
        if (beamIntensity > 0.3f) {
            val beamAlpha = ((beamIntensity - 0.3f) / 0.7f * 80).toInt()
            paint.color = Color.argb(beamAlpha, 200, 255, 200)
            path.reset()
            path.moveTo(-size * 0.3f, size * 0.4f)
            path.lineTo(-size * 1.5f, height * 0.5f)
            path.lineTo(size * 1.5f, height * 0.5f)
            path.lineTo(size * 0.3f, size * 0.4f)
            path.close()
            canvas.drawPath(path, paint)
        }
        
        canvas.restore()
        canvas.restore()
    }
}
