package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shooting Star - Night only, fast diagonal streak with gradient tail.
 * Uses absolute time for deterministic position across preview/wallpaper.
 * Spawns off-screen (top-left) and fades out near end (burning up effect).
 */
class ShootingStarEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.FAR_SKY  // Draw behind mountains
    
    // Spawn off-screen to the upper-left area, will streak diagonally across
    private val startX = -0.15f + random.nextFloat() * 0.3f  // -0.15 to 0.15 (mostly off-screen left)
    private val startY = -0.1f + random.nextFloat() * 0.1f   // -0.1 to 0.0 (off-screen top)
    private val angle = 0.4f + random.nextFloat() * 0.3f     // ~23-40 degrees down-right
    private val speed = 0.00015f + random.nextFloat() * 0.0001f  // Graceful arc across sky
    private val length = 0.10f + random.nextFloat() * 0.08f
    
    // Delay before the shooting star becomes visible (~1 second)
    private val spawnDelayMs = 800L + (random.nextFloat() * 400).toLong()  // 0.8-1.2 sec
    
    // Total travel distance before fade-out completes (travel across 100-130% of screen width)
    private val totalTravel = 1.0f + random.nextFloat() * 0.3f
    
    // Fade out during last 20% of journey
    private val fadeStartProgress = totalTravel * 0.8f
    
    override val durationMs: Long = spawnDelayMs + (totalTravel / speed).toLong()
    
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Unit-length (0,0)->(1,0) gradients; rebuilt only when their end alpha changes (fade-out)
    private var glowShader: LinearGradient? = null
    private var glowShaderAlpha = -1
    private var coreShader: LinearGradient? = null
    private var coreShaderAlpha = -1
    private val trailMatrix = Matrix()
    private val trailMatrixValues = FloatArray(9)

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
            val glowAlpha90 = (90 * fadeAlpha).toInt()
            val glow = glowShader?.takeIf { glowShaderAlpha == glowAlpha90 } ?: LinearGradient(
                0f, 0f, 1f, 0f,
                Color.argb(0, 180, 200, 255),
                Color.argb(glowAlpha90, 200, 215, 255),
                Shader.TileMode.CLAMP
            ).also { glowShader = it; glowShaderAlpha = glowAlpha90 }
            glow.setLocalMatrix(trailMatrix)
            trailPaint.shader = glow
            trailPaint.strokeWidth = 1.6f * unit
            canvas.drawLine(tailX, tailY, headX, headY, trailPaint)

            val core = coreShader?.takeIf { coreShaderAlpha == headAlpha } ?: LinearGradient(
                0f, 0f, 1f, 0f,
                Color.argb(0, 255, 255, 255),
                Color.argb(headAlpha, 255, 255, 240),
                Shader.TileMode.CLAMP
            ).also { coreShader = it; coreShaderAlpha = headAlpha }
            core.setLocalMatrix(trailMatrix)
            trailPaint.shader = core
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
