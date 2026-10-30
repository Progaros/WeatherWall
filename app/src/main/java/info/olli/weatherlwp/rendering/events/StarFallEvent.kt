package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.StarField
import info.olli.weatherlwp.rendering.StarFieldOverlay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Events that alter the background star field; [info.olli.weatherlwp.rendering.SkyLayer] draws the result. */
interface StarFieldEffect {
    fun applyTo(overlay: StarFieldOverlay, absoluteTimeMs: Long)
}

/**
 * Falling stars: a small group of real background stars flare, detach one after another and
 * streak down until they burn out. Later, new stars slowly appear at another spot of the sky.
 * Near the end the sky crossfades back to the regular field, so the fixed star field stays intact.
 *
 * The live sky renders this through [applyTo] (behind clouds, with the night-time star alpha);
 * [draw] is a compact stand-alone rendering used only by the settings preview.
 */
class StarFallEvent(random: Random, override val spawnTimeMs: Long) : RandomEvent, StarFieldEffect {
    override val layer = EventLayer.FAR_SKY

    private class Fall(val star: Int, val startMs: Long, val durationMs: Long, val dx: Float, val dy: Float)
    private class Appearance(val x: Float, val y: Float, val radius: Float, val startMs: Long, val fadeMs: Long)

    private val stars = StarField.stars
    private val falls: List<Fall>
    private val appearances: List<Appearance>
    /** Unaffected neighbours, drawn by the preview for context. */
    private val contextStars: List<Int>
    private val centerX: Float
    private val centerY: Float
    private val restoreStartMs: Long
    override val durationMs: Long

    init {
        val candidates = stars.indices.filter { stars[it][0] in REGION_X && stars[it][1] in REGION_Y }
            .ifEmpty { stars.indices.toList() }
        val center = candidates[random.nextInt(candidates.size)]
        centerX = stars[center][0]
        centerY = stars[center][1]
        val byDistance = stars.indices.sortedBy { distance(it, centerX, centerY) }

        val count = 3 + random.nextInt(3)
        var startMs = 600L
        falls = byDistance.take(count).map { index ->
            val angle = PI / 2 + (random.nextDouble() - 0.5) * 1.0  // within ~30° of straight down
            val distance = 0.08f + random.nextFloat() * 0.08f
            val fall = Fall(
                star = index,
                startMs = startMs,
                durationMs = 1300L + random.nextInt(900),
                dx = (cos(angle) * distance).toFloat(),
                dy = (sin(angle) * distance).toFloat()
            )
            startMs += 450L + random.nextInt(750)
            fall
        }
        contextStars = byDistance.subList(count, count + CONTEXT_STARS)

        // New stars gather around another star of the same sky region ("somewhere else").
        var spotX = centerX
        var spotY = centerY + 0.1f
        for (attempt in 0 until 20) {
            val pick = candidates[random.nextInt(candidates.size)]
            if (distance(pick, centerX, centerY) in 0.08f..0.15f) {
                spotX = stars[pick][0]
                spotY = stars[pick][1]
                break
            }
        }
        var appearMs = falls.maxOf { it.startMs + it.durationMs } + 800L
        appearances = List(count) {
            val appearance = Appearance(
                x = spotX + (random.nextFloat() - 0.5f) * 0.1f,
                y = spotY + (random.nextFloat() - 0.5f) * 0.08f,
                radius = 0.7f + random.nextFloat() * 1.1f,
                startMs = appearMs,
                fadeMs = 5000L + random.nextInt(4000)
            )
            appearMs += 1200L + random.nextInt(1500)
            appearance
        }
        restoreStartMs = appearances.maxOf { it.startMs + it.fadeMs } + HOLD_MS
        durationMs = restoreStartMs + RESTORE_MS
    }

    private fun distance(index: Int, x: Float, y: Float): Float {
        val dx = stars[index][0] - x
        val dy = stars[index][1] - y
        return sqrt(dx * dx + dy * dy)
    }

    override fun applyTo(overlay: StarFieldOverlay, absoluteTimeMs: Long) {
        val elapsed = absoluteTimeMs - spawnTimeMs
        val restore = ((elapsed - restoreStartMs) / RESTORE_MS.toFloat()).coerceIn(0f, 1f)

        for (fall in falls) {
            val local = elapsed - fall.startMs
            val star = stars[fall.star]
            when {
                local < 0 -> {
                    // Brief flare right before the star lets go.
                    val flare = ((local + FLARE_MS) / FLARE_MS.toFloat()).coerceIn(0f, 1f)
                    overlay.starAlpha[fall.star] *= 1f + 0.8f * flare
                }
                local < fall.durationMs -> {
                    overlay.starAlpha[fall.star] = 0f
                    val p = local / fall.durationMs.toFloat()
                    val eased = p * p  // accelerates as it drops
                    val tail = (eased - TRAIL * p).coerceAtLeast(0f)
                    overlay.addMeteor(
                        headX = star[0] + fall.dx * eased, headY = star[1] + fall.dy * eased,
                        tailX = star[0] + fall.dx * tail, tailY = star[1] + fall.dy * tail,
                        radius = star[2],
                        alpha = ((1f - p) / BURN_OUT_FRACTION).coerceIn(0f, 1f)
                    )
                }
                else -> overlay.starAlpha[fall.star] = restore
            }
        }

        for (appearance in appearances) {
            val local = elapsed - appearance.startMs
            if (local <= 0) continue
            val t = (local / appearance.fadeMs.toFloat()).coerceAtMost(1f)
            overlay.addStar(appearance.x, appearance.y, appearance.radius, t * t * (3f - 2f * t) * (1f - restore))
        }
    }

    private val previewOverlay by lazy { StarFieldOverlay() }

    override fun skyGlow(absoluteTimeMs: Long): Float {
        val elapsed = absoluteTimeMs - spawnTimeMs
        var glow = 0f
        for (fall in falls) {
            val local = elapsed - fall.startMs
            if (local < 0 || local >= fall.durationMs) continue
            val p = local / fall.durationMs.toFloat()
            glow = maxOf(glow, ShootingStarEvent.METEOR_GLOW * ((1f - p) / BURN_OUT_FRACTION).coerceIn(0f, 1f))
        }
        return glow
    }

    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val overlay = previewOverlay
        overlay.reset()
        applyTo(overlay, absoluteTimeMs)

        // Compressed around the group so the whole scene fits a small preview circle.
        val cx = width * 0.5f - extendedOffset
        val cy = height * 0.5f
        val spread = height * PREVIEW_SPREAD
        val dot = height * 0.006f
        paint.style = Paint.Style.FILL

        fun drawStar(x: Float, y: Float, sizeFactor: Float, alpha: Float) {
            if (alpha <= 0f) return
            paint.color = Color.argb((220 * alpha).toInt().coerceIn(0, 255), 255, 255, 245)
            canvas.drawCircle(cx + (x - centerX) * spread, cy + (y - centerY) * spread, dot * sizeFactor, paint)
        }

        for (index in contextStars) drawStar(stars[index][0], stars[index][1], 0.8f, 0.8f)
        for (fall in falls) {
            drawStar(stars[fall.star][0], stars[fall.star][1], 1f, overlay.starAlpha[fall.star].coerceAtMost(1.2f))
        }
        for (i in 0 until overlay.extraCount) {
            drawStar(overlay.extraX[i], overlay.extraY[i], 0.6f + overlay.extraRadius[i] * 0.2f, overlay.extraAlpha[i])
        }

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dot * 0.9f
        for (i in 0 until overlay.meteorCount) {
            strokePaint.color = Color.argb((230 * overlay.meteorAlpha[i]).toInt(), 235, 240, 255)
            canvas.drawLine(
                cx + (overlay.meteorTailX[i] - centerX) * spread, cy + (overlay.meteorTailY[i] - centerY) * spread,
                cx + (overlay.meteorHeadX[i] - centerX) * spread, cy + (overlay.meteorHeadY[i] - centerY) * spread,
                strokePaint
            )
            drawStar(overlay.meteorHeadX[i], overlay.meteorHeadY[i], 1.2f, overlay.meteorAlpha[i])
        }
    }

    private companion object {
        /** Sky area visible in portrait wallpapers and wide previews alike (star field coordinates). */
        val REGION_X = 0.33f..0.67f
        val REGION_Y = 0.22f..0.48f
        const val CONTEXT_STARS = 6
        const val FLARE_MS = 500L
        /** Trail length as a fraction of the distance travelled at the end of the fall. */
        const val TRAIL = 0.45f
        /** Last part of the fall during which the meteor fades out. */
        const val BURN_OUT_FRACTION = 0.4f
        const val HOLD_MS = 15_000L
        const val RESTORE_MS = 20_000L
        const val PREVIEW_SPREAD = 0.68f
    }
}
