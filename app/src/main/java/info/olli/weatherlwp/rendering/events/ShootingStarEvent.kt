package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shooting Star - Night only, fast diagonal streak with gradient tail.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Spawns off-screen (top-left) and fades out near end (burning up effect).
 */
class ShootingStarEvent internal constructor(
    override val spawnTimeMs: Long,
    private val startX: Float,
    private val startY: Float,
    private val angle: Float,
    private val speed: Float,
    private val length: Float,
    /** Delay before the shooting star becomes visible. */
    private val spawnDelayMs: Long,
    /** Travel distance before fade-out completes. */
    private val totalTravel: Float
) : RandomEvent {

    // Argument order is the order of random draws; keep it so seeded spawns stay unchanged.
    constructor(random: Random, spawnTimeMs: Long = System.currentTimeMillis()) : this(
        spawnTimeMs = spawnTimeMs,
        startX = -0.15f + random.nextFloat() * 0.3f,      // mostly off-screen left
        startY = -0.1f + random.nextFloat() * 0.1f,       // off-screen top
        angle = 0.4f + random.nextFloat() * 0.3f,         // ~23-40 degrees down-right
        speed = 0.00015f + random.nextFloat() * 0.0001f,  // graceful arc across sky
        length = 0.10f + random.nextFloat() * 0.08f,
        spawnDelayMs = 800L + (random.nextFloat() * 400).toLong(),
        totalTravel = 1.0f + random.nextFloat() * 0.3f
    )

    override val layer = EventLayer.FAR_SKY  // Draw behind mountains
    
    // Fade out during last 20% of journey
    private val fadeStartProgress = totalTravel * 0.8f
    
    override val durationMs: Long = spawnDelayMs + (totalTravel / speed).toLong()

    companion object {
        /** Peak light a meteor casts onto water. */
        const val METEOR_GLOW = 0.35f

        /** A fast, short Perseid whose trail points back to the radiant. */
        fun perseid(random: Random, spawnTimeMs: Long): ShootingStarEvent =
            showerMeteor(random, spawnTimeMs, MeteorShowers.Shower.PERSEIDS)

        /** A shower meteor whose trail points back to the shower's radiant. */
        fun showerMeteor(random: Random, spawnTimeMs: Long, shower: MeteorShowers.Shower): ShootingStarEvent {
            // Away from the radiant, spread around the direction towards the sky's centre.
            val towardsCenter = atan2(0.45 - shower.radiantY, 0.5 - shower.radiantX)
            val angle = (towardsCenter + (random.nextDouble() - 0.5) * 1.2).toFloat()
            val offset = 0.05f + random.nextFloat() * 0.3f
            return ShootingStarEvent(
                spawnTimeMs = spawnTimeMs,
                startX = shower.radiantX + cos(angle) * offset,
                startY = shower.radiantY + sin(angle) * offset,
                angle = angle,
                speed = (0.00028f + random.nextFloat() * 0.00009f) * shower.speedScale,
                length = 0.05f + random.nextFloat() * 0.05f,
                spawnDelayMs = 0L,
                totalTravel = 0.45f + random.nextFloat() * 0.35f
            )
        }
    }
    
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Unit-length (0,0)->(1,0) gradients at full strength; fade-out is applied via paint alpha
    private val glowShader = LinearGradient(
        0f, 0f, 1f, 0f,
        Color.argb(0, 180, 200, 255),
        Color.argb(90, 200, 215, 255),
        Shader.TileMode.CLAMP
    )
    private val coreShader = LinearGradient(
        0f, 0f, 1f, 0f,
        Color.argb(0, 255, 255, 255),
        Color.argb(255, 255, 255, 240),
        Shader.TileMode.CLAMP
    )
    private val trailMatrix = Matrix()
    private val trailMatrixValues = FloatArray(9)

    override fun skyGlow(absoluteTimeMs: Long): Float {
        val activeElapsedMs = absoluteTimeMs - spawnTimeMs - spawnDelayMs
        if (activeElapsedMs < 0L) return 0f
        val progress = speed * activeElapsedMs
        if (progress >= totalTravel) return 0f
        val fade = if (progress >= fadeStartProgress) {
            1f - (progress - fadeStartProgress) / (totalTravel - fadeStartProgress)
        } else {
            1f
        }
        return METEOR_GLOW * fade
    }

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val activeElapsedMs = (elapsedMs - spawnDelayMs).coerceAtLeast(0L)
        val progress = speed * activeElapsedMs
        return startX + cos(angle) * progress * 0.5f
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val activeElapsedMs = (elapsedMs - spawnDelayMs).coerceAtLeast(0L)
        val progress = speed * activeElapsedMs
        return startY + sin(angle) * progress * 0.5f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        
        // Don't draw during the delay period
        if (elapsedMs < spawnDelayMs) return
        
        // Adjust progress for the delay (height-scaled traversal)
        val activeElapsedMs = elapsedMs - spawnDelayMs
        val traversalElapsed = EventTraversal.scaledElapsed(activeElapsedMs, height)
        val progress = speed * traversalElapsed
        
        // Calculate fade-out alpha (1.0 -> 0.0 during last 20% of journey)
        val fadeAlpha = if (progress >= fadeStartProgress) {
            val fadeProgress = (progress - fadeStartProgress) / (totalTravel - fadeStartProgress)
            (1f - fadeProgress).coerceIn(0f, 1f)
        } else {
            1f
        }
        
        // Skip drawing if fully faded
        if (fadeAlpha <= 0f) return
        
        // Scale size to 0.5% of height - thin elegant streak
        val unit = height * 0.005f
        
        val px = parallaxCalculator.getOffsetXForDepth(0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(0.05f)
        canvas.withTranslation(px, py) {
            val headX = (startX + cos(angle) * progress * 0.5f) * width - extendedOffset
            val headY = EventTraversal.mapFieldY(startY + sin(angle) * progress * 0.5f, height, extendedOffsetY)

            // Tail length shrinks during fade-out (meteor burning up); long and thin reads as a meteor.
            val effectiveLength = length * 2.5f * (0.3f + 0.7f * fadeAlpha)
            val tailX = (startX + cos(angle) * (progress - effectiveLength) * 0.5f) * width - extendedOffset
            val tailY = EventTraversal.mapFieldY(startY + sin(angle) * (progress - effectiveLength) * 0.5f, height, extendedOffsetY)

            // Apply fade alpha to all colors
            val headAlpha = (255 * fadeAlpha).toInt()
            val glowAlpha = (200 * fadeAlpha).toInt()

            // Similarity transform mapping (0,0)->tail and (1,0)->head
            val dx = headX - tailX
            val dy = headY - tailY
            trailMatrixValues[Matrix.MSCALE_X] = dx
            trailMatrixValues[Matrix.MSKEW_X] = -dy
            trailMatrixValues[Matrix.MTRANS_X] = tailX
            trailMatrixValues[Matrix.MSKEW_Y] = dy
            trailMatrixValues[Matrix.MSCALE_Y] = dx
            trailMatrixValues[Matrix.MTRANS_Y] = tailY
            trailMatrixValues[Matrix.MPERSP_0] = 0f
            trailMatrixValues[Matrix.MPERSP_1] = 0f
            trailMatrixValues[Matrix.MPERSP_2] = 1f
            trailMatrix.setValues(trailMatrixValues)

            // Faint wide glow under a thin bright core, both fading towards the tail
            trailPaint.alpha = headAlpha
            glowShader.setLocalMatrix(trailMatrix)
            trailPaint.shader = glowShader
            trailPaint.strokeWidth = 1.6f * unit
            canvas.drawLine(tailX, tailY, headX, headY, trailPaint)

            coreShader.setLocalMatrix(trailMatrix)
            trailPaint.shader = coreShader
            trailPaint.strokeWidth = 0.45f * unit
            canvas.drawLine(tailX, tailY, headX, headY, trailPaint)

            // Small hot head with a soft halo
            val headSize = unit * (0.5f + 0.5f * fadeAlpha)
            paint.color = Color.argb((glowAlpha * 0.25f).toInt(), 235, 240, 255)
            canvas.drawCircle(headX, headY, headSize * 1.5f, paint)
            paint.color = Color.argb(headAlpha, 255, 255, 250)
            canvas.drawCircle(headX, headY, headSize * 0.7f, paint)

            trailPaint.shader = null
        }
    }
}
