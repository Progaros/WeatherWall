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

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = -0.15f + speed * elapsedMs
        val eased = smoothstep01((progress + 0.15f) / 1.15f)
        return -0.2f + eased * 1.4f
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val elapsedSec = (absoluteTimeMs - spawnTimeMs) / 1000f
        val bob = sin(elapsedSec * 0.7f + bobPhase) * 0.0016f * sizeScale
        return y + bob
    }

    override fun draw(
        canvas: Canvas, paint: Paint, strokePaint: Paint,
        parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
        timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float
    ) {
        // Scale: ~0.8-1.2% of height based on distance
        val unit = height * 0.005f * sizeScale
        val size = unit * 8f
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val localTime = elapsedMs * 0.001f
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val progress = -0.15f + speed * traversalElapsed
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)

        val eased = smoothstep01((progress + 0.15f) / 1.15f)
        val currentX = (-0.2f + eased * 1.4f) * width - extendedOffset
        val baseScreenY = EventTraversal.mapFieldY(y, height, extendedOffsetY)
        val bob = sin(localTime * 0.7f + bobPhase) * size * 0.04f
        val currentY = baseScreenY + bob

        // Flap animation - owls have slow, deep wing beats
        val cycleT = (localTime + wingPhase) % flapInterval
        val burst01 = (1f - (cycleT / flapDuration)).coerceIn(0f, 1f)
        val envelope = smoothstep01(burst01)
        val beat = sin(localTime * flapRate * 6.28318f + wingPhase)
        val wingAngle = beat * envelope * 25f  // Wing rotation in degrees

        canvas.save()
        canvas.translate(px, py)
        canvas.save()
        canvas.rotate(bank * 0.3f, currentX, currentY)

        // Owl colors - rich brown tones
        val bodyColor = RenderConstants.blendWithAmbient(Color.rgb(65, 50, 35), ambientColor, 0.35f)
        val darkColor = RenderConstants.blendWithAmbient(Color.rgb(35, 28, 20), ambientColor, 0.35f)
        val faceColor = RenderConstants.blendWithAmbient(Color.rgb(180, 165, 140), ambientColor, 0.3f)

        // === BODY ===
        // Compact, barrel-shaped body (owls are stocky)
        val bodyW = size * 0.35f
        val bodyH = size * 0.45f
        paint.color = bodyColor
        canvas.drawOval(
            currentX - bodyW * 0.8f, currentY - bodyH * 0.4f,
            currentX + bodyW * 0.5f, currentY + bodyH * 0.6f, paint
        )
        
        // === HEAD ===
        // Large round head - owls have proportionally huge heads
        val headR = size * 0.32f
        val headX = currentX + bodyW * 0.15f
        val headY = currentY - bodyH * 0.35f
        paint.color = bodyColor
        canvas.drawCircle(headX, headY, headR, paint)
        
        // Facial disc - distinctive owl feature (lighter heart-shaped area)
        paint.color = faceColor
        val faceW = headR * 0.75f
        val faceH = headR * 0.85f
        canvas.drawOval(headX - faceW, headY - faceH * 0.6f,
                        headX + faceW, headY + faceH * 0.5f, paint)
        
        // Brow ridge (darker line above eyes)
        paint.color = darkColor
        path.reset()
        path.moveTo(headX - headR * 0.5f, headY - headR * 0.25f)
        path.quadTo(headX, headY - headR * 0.45f, headX + headR * 0.5f, headY - headR * 0.25f)
        path.lineTo(headX + headR * 0.4f, headY - headR * 0.15f)
        path.quadTo(headX, headY - headR * 0.3f, headX - headR * 0.4f, headY - headR * 0.15f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Ear tufts (great horned owl style)
        paint.color = bodyColor
        path.reset()
        path.moveTo(headX - headR * 0.55f, headY - headR * 0.6f)
        path.lineTo(headX - headR * 0.75f, headY - headR * 1.25f)
        path.lineTo(headX - headR * 0.25f, headY - headR * 0.7f)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(headX + headR * 0.55f, headY - headR * 0.6f)
        path.lineTo(headX + headR * 0.75f, headY - headR * 1.25f)
        path.lineTo(headX + headR * 0.25f, headY - headR * 0.7f)
        path.close()
        canvas.drawPath(path, paint)
        
        // === WINGS ===
        // Broad, rounded wings - owls have wide wings for silent flight
        val wingSpan = size * 0.9f
        
        canvas.save()
        // Left wing (top wing when flying right) - rotates with flap
        canvas.rotate(-wingAngle * 0.7f, currentX - bodyW * 0.2f, currentY)
        paint.color = bodyColor
        path.reset()
        path.moveTo(currentX - bodyW * 0.2f, currentY - bodyH * 0.2f)
        path.quadTo(currentX - bodyW * 0.6f, currentY - wingSpan * 0.6f,
                    currentX - wingSpan, currentY - wingSpan * 0.15f)
        // Rounded wing tip with feather notches
        path.quadTo(currentX - wingSpan * 1.05f, currentY + bodyH * 0.1f,
                    currentX - wingSpan * 0.85f, currentY + bodyH * 0.25f)
        path.quadTo(currentX - bodyW * 0.5f, currentY + bodyH * 0.3f,
                    currentX - bodyW * 0.3f, currentY + bodyH * 0.2f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Wing pattern - darker flight feathers
        paint.color = darkColor
        path.reset()
        path.moveTo(currentX - bodyW * 0.4f, currentY)
        path.quadTo(currentX - wingSpan * 0.5f, currentY - wingSpan * 0.35f,
                    currentX - wingSpan * 0.9f, currentY - wingSpan * 0.05f)
        path.lineTo(currentX - wingSpan * 0.85f, currentY + bodyH * 0.15f)
        path.quadTo(currentX - bodyW * 0.5f, currentY + bodyH * 0.2f,
                    currentX - bodyW * 0.4f, currentY + bodyH * 0.1f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()

        // Right wing (lower wing, partially visible below body)
        canvas.save()
        canvas.rotate(wingAngle * 0.7f, currentX + bodyW * 0.1f, currentY)
        paint.color = darkColor
        path.reset()
        path.moveTo(currentX + bodyW * 0.1f, currentY + bodyH * 0.2f)
        path.quadTo(currentX + bodyW * 0.5f, currentY + wingSpan * 0.5f,
                    currentX + wingSpan * 0.7f, currentY + wingSpan * 0.2f)
        path.quadTo(currentX + wingSpan * 0.75f, currentY + bodyH * 0.4f,
                    currentX + wingSpan * 0.6f, currentY + bodyH * 0.45f)
        path.quadTo(currentX + bodyW * 0.3f, currentY + bodyH * 0.5f,
                    currentX + bodyW * 0.1f, currentY + bodyH * 0.35f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
        
        // === TAIL ===
        // Short, broad tail
        paint.color = bodyColor
        path.reset()
        path.moveTo(currentX - bodyW * 0.7f, currentY + bodyH * 0.3f)
        path.lineTo(currentX - bodyW * 1.1f, currentY + bodyH * 0.15f)
        path.lineTo(currentX - bodyW * 1.3f, currentY + bodyH * 0.35f)
        path.lineTo(currentX - bodyW * 1.1f, currentY + bodyH * 0.55f)
        path.lineTo(currentX - bodyW * 0.7f, currentY + bodyH * 0.45f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail barring (dark stripes)
        paint.color = darkColor
        strokePaint.color = darkColor
        strokePaint.strokeWidth = size * 0.05f
        val tailCx = currentX - bodyW * 1.0f
        val tailCy = currentY + bodyH * 0.35f
        canvas.drawLine(tailCx - size * 0.08f, tailCy - size * 0.06f,
                        tailCx + size * 0.04f, tailCy - size * 0.04f, strokePaint)
        canvas.drawLine(tailCx - size * 0.06f, tailCy + size * 0.02f,
                        tailCx + size * 0.05f, tailCy + size * 0.03f, strokePaint)
        
        // === EYES ===
        // Large, forward-facing eyes - defining owl feature
        val eyeR = headR * 0.22f
        val eyeSpacing = headR * 0.35f
        val eyeY = headY - headR * 0.05f
        
        // Eye glow intensity varies with wing beat
        val eyeGlow = 0.5f + 0.5f * envelope
        
        // Left eye
        paint.color = Color.rgb(30, 25, 20)  // Dark iris
        canvas.drawCircle(headX - eyeSpacing, eyeY, eyeR, paint)
        paint.color = Color.argb((220 * eyeGlow).toInt(), 255, 200, 50)  // Glowing pupil
        canvas.drawCircle(headX - eyeSpacing, eyeY, eyeR * 0.5f, paint)
        
        // Right eye
        paint.color = Color.rgb(30, 25, 20)
        canvas.drawCircle(headX + eyeSpacing, eyeY, eyeR, paint)
        paint.color = Color.argb((220 * eyeGlow).toInt(), 255, 200, 50)
        canvas.drawCircle(headX + eyeSpacing, eyeY, eyeR * 0.5f, paint)
        
        // Beak - small, hooked
        paint.color = Color.rgb(60, 55, 45)
        path.reset()
        path.moveTo(headX, headY + headR * 0.1f)
        path.lineTo(headX - headR * 0.08f, headY + headR * 0.25f)
        path.lineTo(headX, headY + headR * 0.35f)
        path.lineTo(headX + headR * 0.08f, headY + headR * 0.25f)
        path.close()
        canvas.drawPath(path, paint)

        canvas.restore() // banking
        canvas.restore() // parallax
    }
}
