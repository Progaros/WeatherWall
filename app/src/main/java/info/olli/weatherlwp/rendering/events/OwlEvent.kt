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
    
    private val y = 0.18f + random.nextFloat() * 0.12f
    // Reduced speed: was 0.00008-0.00012, now 0.000025-0.000040 (~3x slower)
    private val speed = 0.000025f + random.nextFloat() * 0.000015f
    override val durationMs: Long = (1.35f / speed).toLong()
    
    private val baseSize = 14f + random.nextFloat() * 6f  // Renamed: will be scaled by height

    // Animation tuning (random per owl)
    private val flapInterval = 1.8f + random.nextFloat() * 0.8f   // seconds between flap bursts
    private val flapDuration = 0.45f + random.nextFloat() * 0.2f   // seconds of active flapping
    private val flapRate = 2.4f + random.nextFloat() * 0.8f        // wing beats per second during burst
    private val wingPhase = random.nextFloat() * 6.28f
    private val bank = (-8f + random.nextFloat() * 16f)            // slight tilt in degrees
    private val bobPhase = random.nextFloat() * 6.28f

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
        // Scale size to 0.6% of height - medium-sized night bird
        val unit = height * 0.006f
        val size = baseSize * unit
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val localTime = elapsedMs * 0.001f
        val progress = -0.15f + speed * elapsedMs
        
        val px = parallaxCalculator.getOffsetXForDepth(0.25f)
        val py = parallaxCalculator.getOffsetYForDepth(0.25f)

        val eased = smoothstep01((progress + 0.15f) / 1.15f) // nicer entry/exit pacing
        val currentX = (-0.2f + eased * 1.4f) * width - extendedOffset
        val baseY = y * height
        val bob = sin(localTime * 0.7f + bobPhase) * size * 0.06f
        val currentY = baseY + bob

        // Flap envelope: mostly 0, spikes during a burst
        val cycleT = (localTime + wingPhase) % flapInterval
        val burst01 = (1f - (cycleT / flapDuration)).coerceIn(0f, 1f)
        val envelope = smoothstep01(burst01) // quick-ish rise/decay

        val beat = sin(localTime * flapRate * 6.28318f + wingPhase) // -1..1
        val beat01 = (beat * 0.5f + 0.5f) // 0..1

        // Angle + lift are almost zero when gliding
        val wingAngle = (-18f + 36f * beat01) * envelope // degrees
        val wingLift = size * (0.10f + 0.35f * beat01) * envelope

        canvas.save()
        canvas.translate(px, py)

        // Optional subtle banking (whole owl)
        canvas.save()
        canvas.rotate(bank, currentX, currentY)

        // Color
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(35, 30, 25), ambientColor, 0.5f)
        strokePaint.color = paint.color
        strokePaint.strokeWidth = size * 0.18f
        strokePaint.strokeCap = Paint.Cap.ROUND

        // --- Side-profile body (asymmetric) ---
        // Body ellipse (slightly longer)
        canvas.drawOval(
            currentX - size * 0.55f, currentY - size * 0.38f,
            currentX + size * 0.45f, currentY + size * 0.38f, paint
        )

        // Head (front)
        val headCx = currentX + size * 0.38f
        val headCy = currentY - size * 0.18f
        canvas.drawCircle(headCx, headCy, size * 0.28f, paint)

        // Beak (small triangle)
        path.reset()
        path.moveTo(headCx + size * 0.28f, headCy)
        path.lineTo(headCx + size * 0.42f, headCy + size * 0.06f)
        path.lineTo(headCx + size * 0.28f, headCy + size * 0.12f)
        path.close()
        canvas.drawPath(path, paint)

        // Tail wedge (back)
        path.reset()
        path.moveTo(currentX - size * 0.60f, currentY + size * 0.05f)
        path.lineTo(currentX - size * 0.85f, currentY + size * 0.18f)
        path.lineTo(currentX - size * 0.62f, currentY + size * 0.28f)
        path.close()
        canvas.drawPath(path, paint)

        // --- Main wing (near side): rotate around shoulder ---
        val shoulderX = currentX + size * 0.05f
        val shoulderY = currentY + size * 0.02f

        canvas.save()
        canvas.translate(shoulderX, shoulderY)
        canvas.rotate(wingAngle)

        path.reset()
        path.moveTo(0f, 0f)
        path.quadTo(-size * 0.55f, -wingLift, -size * 1.15f, size * 0.12f)
        canvas.drawPath(path, strokePaint)

        canvas.restore()

        // Far wing hint (very subtle, smaller amplitude)
        val farLift = wingLift * 0.45f
        path.reset()
        path.moveTo(currentX, currentY + size * 0.03f)
        path.quadTo(currentX - size * 0.45f, currentY - farLift, currentX - size * 0.95f, currentY + size * 0.10f)
        strokePaint.alpha = 120
        canvas.drawPath(path, strokePaint)
        strokePaint.alpha = 255

        // Single eye glow (front side only)
        paint.color = Color.argb((140f * (0.3f + 0.7f * envelope)).toInt(), 255, 220, 100)
        canvas.drawCircle(headCx + size * 0.06f, headCy - size * 0.02f, size * 0.05f, paint)

        canvas.restore() // banking
        canvas.restore() // parallax translate
    }
}
