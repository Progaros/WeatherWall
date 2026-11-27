package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frog event: a frog sitting on the ground, occasionally hopping.
 * Best paired with lake or river features for thematic consistency.
 * Draws a small green frog that sits, then hops to a nearby position.
 */
class FrogEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND

    // Frog sits in one area, doesn't cross the whole screen
    private val baseX = 0.15f + random.nextFloat() * 0.7f
    private val direction = if (random.nextBoolean()) 1f else -1f

    // Choose mid-foreground depth
    private val normalizedDepth = 0.5f + random.nextFloat() * 0.4f
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    private val fallbackGroundY = 0.83f + normalizedDepth * 0.07f
    private val groundOffset = -0.005f + random.nextFloat() * 0.01f

    // Frog sits for a while, hops, sits again
    override val durationMs: Long = 8000L + random.nextLong(4000L)

    private val frogSize = 0.5f + random.nextFloat() * 0.3f
    private val frogPath = Path()

    // Hop timing: sit-hop-sit pattern
    private val hopStartProgress = 0.3f + random.nextFloat() * 0.2f  // When to start hopping
    private val hopEndProgress = hopStartProgress + 0.15f              // Hop lasts ~15% of duration
    private val hopDistance = 0.03f + random.nextFloat() * 0.04f       // How far to hop

    // Frog belly color variation
    private val bellyBrightness = 180 + random.nextInt(50)

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        return getCurrentX(absoluteTimeMs)
    }

    // Hop position follows event progress, not height-scaled traversal, so screenHeight is unused.
    override fun getCurrentYRatio(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?,
        screenHeight: Float
    ): Float {
        val currentX = getCurrentX(absoluteTimeMs)
        return (hillHeightProvider?.invoke(currentX, depthFactor) ?: fallbackGroundY) + groundOffset
    }

    private fun getCurrentX(absoluteTimeMs: Long): Float {
        val progress = getProgress(absoluteTimeMs)
        return if (progress < hopStartProgress) {
            baseX
        } else if (progress < hopEndProgress) {
            val hopProgress = (progress - hopStartProgress) / (hopEndProgress - hopStartProgress)
            baseX + direction * hopDistance * hopProgress
        } else {
            baseX + direction * hopDistance
        }
    }

    private fun getHopHeight(absoluteTimeMs: Long): Float {
        val progress = getProgress(absoluteTimeMs)
        if (progress < hopStartProgress || progress > hopEndProgress) return 0f
        val hopProgress = (progress - hopStartProgress) / (hopEndProgress - hopStartProgress)
        // Parabolic arc
        return (1f - (hopProgress * 2f - 1f).let { it * it }) * 0.04f * frogSize
    }

    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float,
                  absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val progress = getProgress(absoluteTimeMs)
        if (progress < 0f || progress > 1f) return

        // Fade in/out
        val alpha = when {
            progress < 0.08f -> progress / 0.08f
            progress > 0.92f -> (1f - progress) / 0.08f
            else -> 1f
        }

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)

        val x = getCurrentX(absoluteTimeMs)
        val groundY = (hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY) + groundOffset
        val hopH = getHopHeight(absoluteTimeMs) * height
        val cx = x * width - extendedOffset
        val cy = groundY * height - hopH
        val unit = minOf(width, height)
        val s = frogSize * unit * 0.01f

        val flip = if (direction > 0) 1f else -1f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)

        // Throat pulsing (breathing animation when sitting)
        val isHopping = progress >= hopStartProgress && progress <= hopEndProgress
        val throatPulse = if (!isHopping) abs(sin((absoluteTimeMs - spawnTimeMs) * 0.004f)) * 0.28f * s else 0f

        // Body (dark green)
        val bodyColor = RenderConstants.blendWithAmbient(Color.rgb(50, 100, 40), ambientColor, 0.3f)
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(bodyColor), Color.green(bodyColor), Color.blue(bodyColor))

        // Back legs (drawn first, behind body)
        val legTuck = if (isHopping) -0.3f * s else 0f
        // Back leg (folded when sitting, extended when hopping)
        frogPath.reset()
        if (isHopping) {
            // Extended back legs
            frogPath.moveTo(-1.0f * s, 0.2f * s)
            frogPath.lineTo(-2.0f * s, 0.8f * s)
            frogPath.lineTo(-2.5f * s, 0.5f * s)
        } else {
            // Folded back leg: a hunched mass against the rump. A backward spike here reads
            // as a fish tail rather than a crouched frog.
            frogPath.moveTo(-0.9f * s, -0.25f * s)
            frogPath.lineTo(-1.7f * s, 0.05f * s)
            frogPath.lineTo(-1.5f * s, 0.5f * s)
            frogPath.lineTo(-0.7f * s, 0.5f * s)
        }
        canvas.drawPath(frogPath, paint)

        // Body oval
        canvas.drawOval(-1.2f * s, -0.5f * s, 1.0f * s, 0.5f * s, paint)

        // Belly (lighter)
        paint.color = Color.argb((alpha * 200).toInt(), bellyBrightness, bellyBrightness, bellyBrightness - 30)
        canvas.drawOval(-0.8f * s, -0.1f * s - throatPulse * 0.5f, 0.6f * s, 0.4f * s + throatPulse * 0.5f, paint)

        // Head
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(bodyColor), Color.green(bodyColor), Color.blue(bodyColor))
        canvas.drawOval(0.4f * s, -0.6f * s, 1.5f * s, 0.2f * s, paint)

        // Eyes (large, protruding)
        val eyeColor = Color.rgb(200, 180, 40)
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(eyeColor), Color.green(eyeColor), Color.blue(eyeColor))
        canvas.drawCircle(0.9f * s, -0.7f * s, 0.2f * s, paint)
        canvas.drawCircle(1.3f * s, -0.7f * s, 0.2f * s, paint)

        // Pupils (horizontal slit)
        paint.color = Color.argb((alpha * 255).toInt(), 10, 10, 10)
        canvas.drawOval(0.8f * s, -0.72f * s, 1.0f * s, -0.68f * s, paint)
        canvas.drawOval(1.2f * s, -0.72f * s, 1.4f * s, -0.68f * s, paint)

        // Front legs (small)
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(bodyColor), Color.green(bodyColor), Color.blue(bodyColor))
        canvas.drawRoundRect(0.3f * s, 0.2f * s, 0.55f * s, 0.6f * s + legTuck, 0.05f * s, 0.05f * s, paint)
        canvas.drawRoundRect(0.6f * s, 0.2f * s, 0.85f * s, 0.6f * s + legTuck, 0.05f * s, 0.05f * s, paint)

        canvas.restore()
        canvas.restore()
    }
}
