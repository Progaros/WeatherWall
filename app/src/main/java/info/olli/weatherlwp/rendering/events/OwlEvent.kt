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
 * Owl silhouette - Night only, side-profile view with glide-burst animation.
 * Features asymmetric body, flap bursts, and wing rotation.
 * Uses absolute time for deterministic position across preview/wallpaper.
 */
class OwlEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // Flying in sky, in front of mountains
    
    // Distance for depth: 0.2-0.6 (owls fly at various distances)
    private val distance = 0.2f + random.nextFloat() * 0.4f
    private val sizeScale = 1.0f - distance * 0.5f  // 0.7-1.0 based on distance
    
    private val y = 0.18f + random.nextFloat() * 0.12f
    private val speed = 0.000025f + random.nextFloat() * 0.000015f
    override val durationMs: Long = (1.35f / speed).toLong()

    // Animation tuning (random per owl)
    private val flapInterval = 1.8f + random.nextFloat() * 0.8f
    private val flapDuration = 0.45f + random.nextFloat() * 0.2f
    private val flapRate = 2.4f + random.nextFloat() * 0.8f
    private val wingPhase = random.nextFloat() * 6.28f
    private val bank = (-8f + random.nextFloat() * 16f)
    private val bobPhase = random.nextFloat() * 6.28f
    
    // Parallax based on distance
    private val parallaxDepth = 0.2f + distance * 0.15f

    private val path = Path()

    private fun smoothstep01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun draw(
        canvas: Canvas, paint: Paint, strokePaint: Paint,
        parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
        timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long
    ) {
        // Scale: ~0.8-1.2% of height based on distance
        val unit = height * 0.004f * sizeScale
        val size = unit * 6f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val localTime = elapsedMs * 0.001f
        val progress = -0.15f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)

        val eased = smoothstep01((progress + 0.15f) / 1.15f)
        val currentX = (-0.2f + eased * 1.4f) * width - extendedOffset
        val baseY = y * height
        val bob = sin(localTime * 0.7f + bobPhase) * size * 0.04f
        val currentY = baseY + bob

        // Flap animation
        val cycleT = (localTime + wingPhase) % flapInterval
        val burst01 = (1f - (cycleT / flapDuration)).coerceIn(0f, 1f)
        val envelope = smoothstep01(burst01)
        val beat = sin(localTime * flapRate * 6.28318f + wingPhase)
        val wingDown = beat * envelope * size * 0.4f  // How much wings move down

        canvas.save()
        canvas.translate(px, py)
        canvas.save()
        canvas.rotate(bank * 0.3f, currentX, currentY)

        // Owl color - dark brown silhouette
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(40, 32, 25), ambientColor, 0.4f)

        // Compact owl body - rounded shape
        val bodyW = size * 0.5f
        val bodyH = size * 0.4f
        
        // Body - oval
        canvas.drawOval(currentX - bodyW, currentY - bodyH * 0.3f,
                        currentX + bodyW * 0.6f, currentY + bodyH, paint)
        
        // Large round head (owls have big heads)
        val headR = size * 0.35f
        val headX = currentX + bodyW * 0.3f
        val headY = currentY - bodyH * 0.2f
        canvas.drawCircle(headX, headY, headR, paint)
        
        // Ear tufts (small triangles)
        path.reset()
        path.moveTo(headX - headR * 0.5f, headY - headR * 0.7f)
        path.lineTo(headX - headR * 0.7f, headY - headR * 1.3f)
        path.lineTo(headX - headR * 0.2f, headY - headR * 0.8f)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(headX + headR * 0.5f, headY - headR * 0.7f)
        path.lineTo(headX + headR * 0.7f, headY - headR * 1.3f)
        path.lineTo(headX + headR * 0.2f, headY - headR * 0.8f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Short broad wings - owl wings are rounded
        val wingSpan = size * 1.4f
        val wingH = size * 0.3f + wingDown
        
        // Left wing
        path.reset()
        path.moveTo(currentX - bodyW * 0.3f, currentY)
        path.quadTo(currentX - wingSpan * 0.5f, currentY - wingH,
                    currentX - wingSpan, currentY + size * 0.1f)
        path.quadTo(currentX - wingSpan * 0.6f, currentY + wingH * 0.3f,
                    currentX - bodyW * 0.3f, currentY + bodyH * 0.5f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail - short fan
        path.reset()
        path.moveTo(currentX - bodyW * 0.8f, currentY + bodyH * 0.3f)
        path.lineTo(currentX - bodyW * 1.2f, currentY + bodyH * 0.1f)
        path.lineTo(currentX - bodyW * 1.4f, currentY + bodyH * 0.4f)
        path.lineTo(currentX - bodyW * 1.2f, currentY + bodyH * 0.7f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Eyes - glowing at night
        val eyeGlow = 0.3f + 0.7f * envelope
        paint.color = Color.argb((200 * eyeGlow).toInt(), 255, 210, 80)
        canvas.drawCircle(headX - headR * 0.25f, headY - headR * 0.1f, headR * 0.15f, paint)
        canvas.drawCircle(headX + headR * 0.25f, headY - headR * 0.1f, headR * 0.15f, paint)

        canvas.restore() // banking
        canvas.restore() // parallax
    }
}
