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
 * Fish jumping out of water in a graceful arc.
 * Best paired with lake or river features enabled.
 * Draws a small fish leaping upward with a splash effect.
 */
class FishJumpEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND

    // Spawn at a random X position
    private val xPos = 0.15f + random.nextFloat() * 0.7f

    // Choose mid-to-foreground depth where water is visible
    private val normalizedDepth = 0.4f + random.nextFloat() * 0.5f
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    private val fallbackGroundY = 0.82f + normalizedDepth * 0.08f

    // Short event: jump lasts ~3 seconds
    override val durationMs: Long = 3000L

    private val fishSize = 0.6f + random.nextFloat() * 0.4f
    private val facingRight = random.nextBoolean()

    // Fish color (silver/blue variations)
    private val fishR = 120 + random.nextInt(40)
    private val fishG = 140 + random.nextInt(50)
    private val fishB = 160 + random.nextInt(60)

    private val fishPath = Path()

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        return xPos
    }

    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return hillHeightProvider?.invoke(xPos, depthFactor) ?: fallbackGroundY
    }

    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float,
                  absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val progress = getProgress(absoluteTimeMs)
        if (progress < 0f || progress > 1f) return

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)

        val groundY = (hillHeightProvider?.invoke(xPos, depthFactor) ?: fallbackGroundY) * height
        val cx = xPos * width - extendedOffset
        val unit = minOf(width, height)
        val s = fishSize * unit * 0.012f

        // Parabolic arc: fish jumps up and comes back down
        val arcPhase = progress * 2f - 1f  // -1 to 1
        val jumpHeight = (1f - arcPhase * arcPhase) * unit * 0.06f * fishSize

        // Rotation: fish tilts with jump direction
        val rotation = arcPhase * 45f * (if (facingRight) -1f else 1f)

        // Alpha: fade in/out
        val alpha = if (progress < 0.1f) progress / 0.1f
        else if (progress > 0.9f) (1f - progress) / 0.1f
        else 1f

        val cy = groundY - jumpHeight

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        if (!facingRight) canvas.scale(-1f, 1f)

        // Draw fish body
        val fishColor = RenderConstants.blendWithAmbient(
            Color.rgb(fishR, fishG, fishB), ambientColor, 0.3f
        )
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(fishColor), Color.green(fishColor), Color.blue(fishColor))

        // Fish body (oval)
        canvas.drawOval(-2f * s, -0.6f * s, 2f * s, 0.6f * s, paint)

        // Tail fin - overlaps into the peduncle so it stays fused with the body.
        // At x = -1.4s the body ellipse is still ~0.43s tall, giving a solid join.
        fishPath.reset()
        fishPath.moveTo(-1.4f * s, -0.35f * s)
        fishPath.lineTo(-3.2f * s, -0.85f * s)
        fishPath.lineTo(-3.2f * s, 0.85f * s)
        fishPath.lineTo(-1.4f * s, 0.35f * s)
        fishPath.close()
        canvas.drawPath(fishPath, paint)

        // Dorsal fin
        fishPath.reset()
        fishPath.moveTo(-0.5f * s, -0.5f * s)
        fishPath.lineTo(0f, -1.2f * s)
        fishPath.lineTo(0.8f * s, -0.5f * s)
        fishPath.close()
        canvas.drawPath(fishPath, paint)

        // Eye
        paint.color = Color.argb((alpha * 255).toInt(), 20, 20, 20)
        canvas.drawCircle(1.3f * s, -0.1f * s, 0.12f * s, paint)

        // Belly highlight
        paint.color = Color.argb((alpha * 80).toInt(), 255, 255, 255)
        canvas.drawOval(-1.5f * s, 0f, 1.5f * s, 0.4f * s, paint)

        canvas.restore()

        // Splash droplets when entering/exiting water
        if (progress < 0.15f || progress > 0.85f) {
            val splashAlpha = if (progress < 0.15f) (0.15f - progress) / 0.15f
            else (progress - 0.85f) / 0.15f
            paint.color = Color.argb((splashAlpha * 100).toInt(), 200, 220, 240)
            for (i in 0..4) {
                val dropX = cx + (i - 2) * s * 0.8f
                val dropY = groundY - splashAlpha * s * (1 + i % 3)
                canvas.drawCircle(dropX, dropY, s * 0.1f, paint)
            }
        }

        canvas.restore()
    }
}
