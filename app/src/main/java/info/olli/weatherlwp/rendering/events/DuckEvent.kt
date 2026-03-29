package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.sin
import kotlin.random.Random

/**
 * Duck event: a duck waddling across the terrain (or swimming if near water).
 * Simple silhouette that walks left-to-right or right-to-left.
 * Best paired with lake or river features.
 */
class DuckEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.GROUND

    private val traversal = EventTraversal.randomHorizontalPath(random, margin = 0.08f)
    private val startX = traversal.startX
    private val direction = traversal.direction

    // Choose mid-foreground depth
    private val normalizedDepth = 0.5f + random.nextFloat() * 0.4f
    override val depthFactor: Float = RenderConstants.getHillParallaxDepth(normalizedDepth)
    private val fallbackGroundY = 0.83f + normalizedDepth * 0.07f

    private val groundOffset = -0.01f + random.nextFloat() * 0.02f

    // Duck walks slowly
    private val speed = 0.000025f + random.nextFloat() * 0.00001f
    override val durationMs: Long = EventTraversal.durationMsForPath(
        path = traversal,
        speed = speed,
        minDurationMs = 5000L
    )

    private val duckSize = 0.5f + random.nextFloat() * 0.3f
    private val isDuckling = random.nextFloat() < 0.3f  // 30% chance of duckling (smaller)

    // Duck color
    private val isMale = random.nextBoolean()

    private val duckPath = Path()

    override fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val x = startX + direction * speed * elapsedMs
        return (hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY) + groundOffset
    }

    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float,
                      absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val x = startX + direction * speed * elapsedMs

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)

        val groundY = (hillHeightProvider?.invoke(x, depthFactor) ?: fallbackGroundY) + groundOffset
        val cx = x * width - extendedOffset
        val cy = groundY * height
        val unit = minOf(width, height)
        val scaleFactor = if (isDuckling) 0.5f else 1f
        val s = duckSize * scaleFactor * unit * 0.012f

        val waddle = sin(elapsedMs * 0.008f) * 2f  // Subtle body waddle
        val legPhase = elapsedMs * 0.01f

        val flip = if (direction > 0) 1f else -1f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        canvas.rotate(waddle)

        // Legs are split into back/front layers around the body for depth readability.
        val legOffset = sin(legPhase) * 0.3f * s
        val legColor = RenderConstants.blendWithAmbient(Color.rgb(210, 140, 30), ambientColor, 0.2f)

        // Back leg + foot (behind body)
        paint.color = legColor
        canvas.drawRoundRect(-0.8f * s, 0.4f * s, -0.5f * s, 1.0f * s - legOffset, 0.05f * s, 0.05f * s, paint)
        canvas.drawOval(-1.0f * s, 0.9f * s - legOffset, -0.3f * s, 1.1f * s - legOffset, paint)

        // Body (oval)
        val bodyColor = if (isMale && !isDuckling) {
            RenderConstants.blendWithAmbient(Color.rgb(80, 100, 60), ambientColor, 0.3f)  // Green-brown
        } else {
            RenderConstants.blendWithAmbient(Color.rgb(140, 120, 80), ambientColor, 0.3f)  // Brown
        }
        paint.color = bodyColor
        canvas.drawOval(-1.8f * s, -0.7f * s, 1.2f * s, 0.7f * s, paint)

        // Neck and head
        val headColor = if (isMale && !isDuckling) {
            RenderConstants.blendWithAmbient(Color.rgb(30, 90, 40), ambientColor, 0.25f)  // Green head
        } else {
            bodyColor
        }
        paint.color = headColor
        // Neck
        canvas.drawRoundRect(0.8f * s, -1.2f * s, 1.4f * s, -0.3f * s, 0.3f * s, 0.3f * s, paint)
        // Head
        canvas.drawOval(0.7f * s, -1.8f * s, 1.7f * s, -1.0f * s, paint)

        // Bill (orange)
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(220, 160, 40), ambientColor, 0.2f)
        duckPath.reset()
        duckPath.moveTo(1.5f * s, -1.5f * s)
        duckPath.lineTo(2.3f * s, -1.3f * s)
        duckPath.lineTo(1.5f * s, -1.2f * s)
        duckPath.close()
        canvas.drawPath(duckPath, paint)

        // Eye
        paint.color = Color.rgb(15, 15, 15)
        canvas.drawCircle(1.2f * s, -1.45f * s, 0.08f * s, paint)

        // Wing (slightly darker body color)
        paint.color = Color.argb(180, Color.red(bodyColor), Color.green(bodyColor), Color.blue(bodyColor))
        canvas.drawOval(-1.2f * s, -0.5f * s, 0.6f * s, 0.3f * s, paint)

        // Front leg + foot (in front of body)
        paint.color = legColor
        canvas.drawRoundRect(-0.2f * s, 0.4f * s, 0.1f * s, 1.0f * s + legOffset, 0.05f * s, 0.05f * s, paint)
        canvas.drawOval(-0.4f * s, 0.9f * s + legOffset, 0.3f * s, 1.1f * s + legOffset, paint)

        // Tail (small up-curve)
        paint.color = bodyColor
        duckPath.reset()
        duckPath.moveTo(-1.7f * s, -0.3f * s)
        duckPath.lineTo(-2.3f * s, -0.8f * s)
        duckPath.lineTo(-2.0f * s, -0.1f * s)
        duckPath.close()
        canvas.drawPath(duckPath, paint)

        canvas.restore()
        canvas.restore()
    }
}
