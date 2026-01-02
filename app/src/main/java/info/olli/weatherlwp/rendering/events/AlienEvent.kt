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
 * Rare UFO/Alien event - Night only, very rare.
 * Classic saucer shape with pulsing lights and erratic movement.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class AlienEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains
    
    private val startX = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (startX < 0.5f) 1f else -1f
    private val startY = 0.1f + random.nextFloat() * 0.15f
    private val speed = 0.00004f + random.nextFloat() * 0.00003f
    override val durationMs: Long = (1.25f / speed).toLong()
    
    // Distance for depth: 0.3-0.7 (UFOs in the distance)
    private val distance = 0.3f + random.nextFloat() * 0.4f
    private val sizeScale = 1.0f - distance * 0.5f  // 0.65-0.85 based on distance
    
    private val path = Path()
    private val initialWobblePhase = random.nextFloat() * 6.28f
    private val initialHoverPhase = random.nextFloat() * 6.28f
    private val lightColors = listOf(
        Color.rgb(255, 100, 100),
        Color.rgb(100, 255, 100),
        Color.rgb(100, 100, 255),
        Color.rgb(255, 255, 100),
        Color.rgb(255, 100, 255)
    )
    
    // Parallax based on distance
    private val parallaxDepth = 0.1f + distance * 0.1f
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?) {
        // Scale: ~0.6-1% of height - distant UFO
        val unit = height * 0.004f * sizeScale
        val size = unit * 5f  // About 2% saucer width max (40px on 2000px screen)
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val x = startX + direction * speed * elapsedMs
        // Slow, gentle movement - much calmer UFO drift
        val wobblePhase = initialWobblePhase + elapsedMs * 0.0008f  // Very slow wobble
        val hoverPhase = initialHoverPhase + elapsedMs * 0.0005f   // Very slow hover bob
        val lightPhase = elapsedMs * 0.008f  // Slower light pulse
        
        // Y position with gentle vertical bob
        val verticalOffset = sin(hoverPhase) * 0.015f  // Gentle constant bob, no accumulation
        val y = (startY + verticalOffset).coerceIn(0.05f, 0.3f)
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = x * width - extendedOffset
        // Very subtle wobble
        val wobbleX = sin(wobblePhase) * size * 0.15f
        val wobbleY = sin(hoverPhase * 1.3f) * size * 0.2f
        val currentY = y * height + wobbleY
        
        // Tilt based on movement - very subtle
        canvas.save()
        canvas.translate(currentX + wobbleX, currentY)
        val tilt = sin(wobblePhase * 0.3f) * 4f  // Reduced tilt
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
        
        // Bottom light beam (occasional) - with fade at bottom
        val beamIntensity = sin(lightPhase * 0.5f)
        if (beamIntensity > 0.3f) {
            val beamAlpha = ((beamIntensity - 0.3f) / 0.7f * 80).toInt()
            
            // Create a vertical gradient that fades out at the bottom
            val beamStartY = size * 0.4f
            val beamEndY = height * 0.5f
            val beamGradient = android.graphics.LinearGradient(
                0f, beamStartY,
                0f, beamEndY,
                intArrayOf(
                    Color.argb(beamAlpha, 200, 255, 200),      // Full color at top
                    Color.argb(beamAlpha / 2, 200, 255, 200),  // Half alpha in middle
                    Color.argb(0, 200, 255, 200)               // Transparent at bottom
                ),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = beamGradient
            
            path.reset()
            path.moveTo(-size * 0.3f, beamStartY)
            path.lineTo(-size * 1.5f, beamEndY)
            path.lineTo(size * 1.5f, beamEndY)
            path.lineTo(size * 0.3f, beamStartY)
            path.close()
            canvas.drawPath(path, paint)
            
            paint.shader = null
        }
        
        canvas.restore()
        canvas.restore()
    }
}
