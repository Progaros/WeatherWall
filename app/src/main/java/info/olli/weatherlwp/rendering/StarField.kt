package info.olli.weatherlwp.rendering

import kotlin.random.Random

/**
 * The fixed background star field in normalized isotropic coordinates (see [SkyViewportMapper]).
 * Shared by [SkyLayer] and events that make real background stars fall.
 */
object StarField {
    const val BRIGHTNESS = 3
    /** Flicker phase for the few twinkling stars, 0 for steady ones. */
    const val FLICKER = 4

    /**
     * Per star: x, y (0..1), radius at the reference height, brightness (0..1) and flicker phase.
     * Fixed seeds, so identical everywhere; positions keep the original seed-42 sequence.
     */
    val stars: Array<FloatArray> = run {
        val random = Random(42)
        val traits = Random(4242)
        Array(RenderConstants.STAR_COUNT) {
            val x = random.nextFloat()
            val y = random.nextFloat() * 0.98f // almost full visible sky
            val size = random.nextFloat()
            // Most stars are faint pinpricks; only a handful are big and bright.
            val standout = traits.nextFloat() < 0.05f
            val radius = if (standout) 1.6f + size * 0.4f else 0.6f + size * size * size * 0.9f
            val brightness = if (standout) 1f else 0.35f + 0.5f * size * size + traits.nextFloat() * 0.1f
            val flicker = if (traits.nextFloat() < 0.07f) 1f + traits.nextFloat() * 100f else 0f
            floatArrayOf(x, y, radius, brightness.coerceAtMost(1f), flicker)
        }
    }
}

/**
 * Per-frame changes to the star field made by events, drawn by [SkyLayer]: visibility of each
 * background star, extra stars and falling-star streaks (all in [StarField] coordinates).
 */
class StarFieldOverlay {
    /** Visibility multiplier per background star (values above 1 flare the star). */
    val starAlpha = FloatArray(StarField.stars.size)

    val extraX = FloatArray(MAX_EXTRA)
    val extraY = FloatArray(MAX_EXTRA)
    val extraRadius = FloatArray(MAX_EXTRA)
    val extraAlpha = FloatArray(MAX_EXTRA)
    var extraCount = 0
        private set

    val meteorHeadX = FloatArray(MAX_METEORS)
    val meteorHeadY = FloatArray(MAX_METEORS)
    val meteorTailX = FloatArray(MAX_METEORS)
    val meteorTailY = FloatArray(MAX_METEORS)
    val meteorRadius = FloatArray(MAX_METEORS)
    val meteorAlpha = FloatArray(MAX_METEORS)
    var meteorCount = 0
        private set

    fun reset() {
        starAlpha.fill(1f)
        extraCount = 0
        meteorCount = 0
    }

    fun addStar(x: Float, y: Float, radius: Float, alpha: Float) {
        if (extraCount == MAX_EXTRA || alpha <= 0f) return
        extraX[extraCount] = x
        extraY[extraCount] = y
        extraRadius[extraCount] = radius
        extraAlpha[extraCount] = alpha
        extraCount++
    }

    fun addMeteor(headX: Float, headY: Float, tailX: Float, tailY: Float, radius: Float, alpha: Float) {
        if (meteorCount == MAX_METEORS || alpha <= 0f) return
        meteorHeadX[meteorCount] = headX
        meteorHeadY[meteorCount] = headY
        meteorTailX[meteorCount] = tailX
        meteorTailY[meteorCount] = tailY
        meteorRadius[meteorCount] = radius
        meteorAlpha[meteorCount] = alpha
        meteorCount++
    }

    private companion object {
        const val MAX_EXTRA = 48
        const val MAX_METEORS = 48
    }
}
