package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.Astronomy
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.simulation.inHourWindow
import info.olli.weatherlwp.util.ColorMath
import info.olli.weatherlwp.weather.WeatherType
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders the sky background with gradient colors, stars, sun, moon, and vignette.
 * 
 * Uses SimulationState for:
 * - Time of day (colors, star visibility, sun/moon position)
 * - absoluteTimeMs (star twinkle animation)
 * 
 * Uses RenderConstants for:
 * - Sky colors for different times
 * - Parallax factors
 * - Time thresholds
 * 
 * Design notes:
 * - Night scenes should have darker corners (vignette effect)
 * - Sun follows arc across sky during day
 * - Moon visible at night
 */
class SkyLayer(private val context: Context? = null) : SceneLayer {
    override val layerType = LayerType.SKY
    private val skyPaint = Paint().apply {
        // Dithering reduces visible banding in quantized gradients, especially on OLED panels.
        isDither = true
    }
    private val starPaint = Paint().apply { 
        color = Color.WHITE 
        isAntiAlias = true 
        style = Paint.Style.FILL 
    }
    private val vignettePaint = Paint().apply {
        isAntiAlias = true
    }
    private val sunPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val moonPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val moonShadowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val moonPath = android.graphics.Path()  // Reusable path for moon phase
    private val moonRect = RectF()  // Reusable rect for moon disc
    private val termRect = RectF()  // Reusable rect for terminator ellipse
    private val earthshineColor = Color.rgb(70, 76, 96)
    
    private var width = 0f
    private var height = 0f
    
    // Cached vignette gradient to avoid allocations every frame
    private var cachedVignetteGradient: RadialGradient? = null
    private var cachedVignetteAlpha = -1
    
    // Cached sun glow gradient to avoid per-frame RadialGradient allocation
    private var cachedSunGradient: RadialGradient? = null
    private var cachedSunGlowCenterX = Float.NaN
    private var cachedSunGlowCenterY = Float.NaN
    private var cachedSunGlowRadius = 0f
    private var cachedSunElevation = -1f
    
    // Cached moon glow gradient to avoid per-frame RadialGradient allocation
    private var cachedMoonGradient: RadialGradient? = null
    private var cachedMoonGlowCenterX = Float.NaN
    private var cachedMoonGlowCenterY = Float.NaN
    private var cachedMoonGlowRadius = 0f
    
    // Stars - generated once with fixed seed for consistency
    private val stars = StarField.stars

    /**
     * Lets events hide, flare, add and drop background stars. Fills the overlay and returns true
     * when any star-field event is active.
     */
    var starFieldEffects: ((StarFieldOverlay, Long) -> Boolean)? = null
    private val starOverlay = StarFieldOverlay()
    private val meteorPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    // Current 3-stop gradient colors (Top, Middle, Horizon)
    private var skyTop = Color.BLUE
    private var skyMid = Color.CYAN
    private var skyHorizon = Color.WHITE
    
    // Night shade strength for darker nights
    private var nightShadeStrength = 0f
    
    // Settings
    private var sunEnabled = true
    private var moonEnabled = true
    private var celestialSize = 1.0f
    private var celestialArcHeight = PreferenceDefaults.CELESTIAL_ARC_HEIGHT / 100f  // 1.0 = full arc, 0.0 = near horizon

    private var lastSkyBucketId = Int.MIN_VALUE

    private data class SkyGradientBucket(
        val id: Int,
        val hour: Float,
        val sunrise: Float,
        val sunset: Float,
        val nightShade: Float,
        val precipDull: Float
    )

    private companion object {
        private const val SKY_TIME_BUCKETS = 256
        private const val SKY_SUN_EVENT_BUCKETS = 64
        private const val SKY_SHADE_BUCKETS = 32
        private const val SKY_DULL_BUCKETS = 16
        private const val METEOR_TRAIL_SEGMENTS = 5
        private const val STAR_PEAK_ALPHA = 235f
        private const val STAR_FADE_START_H = 0.4f
        private const val STAR_FADE_DURATION_H = 0.7f
        /** Screen-height fraction where horizon extinction starts, and over which it reaches full. */
        private const val HORIZON_FADE_START = 0.35f
        private const val HORIZON_FADE_SPAN = 0.33f
        /** How much a daytime moon fades against the bright sky. */
        private const val DAY_MOON_FADE = 0.55f
    }

    init {
        reloadSettings(SimulationSettings.fromSnapshot(emptyMap<String, Any?>()))
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        // Force one gradient rebuild for the new viewport dimensions.
        lastSkyBucketId = Int.MIN_VALUE
        updateGradient()
        // Invalidate cached vignette gradient on resize
        cachedVignetteGradient = null
        cachedVignetteAlpha = -1
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Track night shade strength continuously using light level
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        nightShadeStrength = state.nightShadeStrength * (1f - dayFactor)
        
        updateColorsForTime(state)
    }
    
    private fun toBucket(value: Float, min: Float, max: Float, steps: Int): Int {
        if (steps <= 1 || max <= min) return 0
        val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return (t * (steps - 1)).toInt()
    }

    private fun fromBucket(bucket: Int, min: Float, max: Float, steps: Int): Float {
        if (steps <= 1 || max <= min) return min
        val t = bucket.coerceIn(0, steps - 1).toFloat() / (steps - 1).toFloat()
        return min + t * (max - min)
    }

    private fun buildSkyGradientBucket(state: SimulationState): SkyGradientBucket {
        val hourBucket = toBucket(state.smoothedTimeOfDayHours, 0f, 24f, SKY_TIME_BUCKETS)
        val sunriseBucket = toBucket(state.sunriseHours, 0f, 24f, SKY_SUN_EVENT_BUCKETS)
        val sunsetBucket = toBucket(state.sunsetHours, 0f, 24f, SKY_SUN_EVENT_BUCKETS)
        val shadeBucket = toBucket(nightShadeStrength, 0f, 1f, SKY_SHADE_BUCKETS)
        val dullBucket = toBucket(precipDullness(state), 0f, 1f, SKY_DULL_BUCKETS)

        val id = hourBucket or
            (sunriseBucket shl 8) or
            (sunsetBucket shl 14) or
            (shadeBucket shl 20) or
            (dullBucket shl 25)

        return SkyGradientBucket(
            id = id,
            hour = fromBucket(hourBucket, 0f, 24f, SKY_TIME_BUCKETS),
            sunrise = fromBucket(sunriseBucket, 0f, 24f, SKY_SUN_EVENT_BUCKETS),
            sunset = fromBucket(sunsetBucket, 0f, 24f, SKY_SUN_EVENT_BUCKETS),
            nightShade = fromBucket(shadeBucket, 0f, 1f, SKY_SHADE_BUCKETS),
            precipDull = fromBucket(dullBucket, 0f, 1f, SKY_DULL_BUCKETS)
        )
    }

    /** Rain and snow curtains wash the colour out of the sky, most of all the twilight glow. */
    private fun precipDullness(state: SimulationState): Float = when (state.weather) {
        WeatherType.RAIN, WeatherType.HAIL, WeatherType.THUNDERSTORM, WeatherType.SNOW ->
            state.overcastFactor() * (0.4f + 0.6f * state.precipIntensity.coerceIn(0f, 1f))
        else -> 0f
    }

    private fun updateColorsForTime(state: SimulationState) {
        val bucket = buildSkyGradientBucket(state)
        if (bucket.id == lastSkyBucketId) return
        lastSkyBucketId = bucket.id

        // Use bucketized time/solar inputs to avoid per-frame shader churn.
        val hour = bucket.hour
        val sunrise = bucket.sunrise
        val sunset = bucket.sunset
        
        // 3-stop gradient: Top, Middle (40% down), Horizon
        val newTop = RenderConstants.twilightColor(
            hour, sunrise, sunset,
            RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_DAWN_TOP, RenderConstants.SKY_DAY_TOP, RenderConstants.SKY_DUSK_TOP,
            RenderConstants::easeSkyColorTransition
        )
        val newMid = RenderConstants.twilightColor(
            hour, sunrise, sunset,
            RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_DAWN_MID, RenderConstants.SKY_DAY_MID, RenderConstants.SKY_DUSK_MID,
            RenderConstants::easeSkyColorTransition
        )
        val newHorizon = RenderConstants.twilightColor(
            hour, sunrise, sunset,
            RenderConstants.SKY_NIGHT_BOTTOM, RenderConstants.SKY_DAWN_BOTTOM, RenderConstants.SKY_DAY_BOTTOM, RenderConstants.SKY_DUSK_BOTTOM,
            RenderConstants::easeSkyColorTransition
        )
        
        // Apply night shade darkening if at night
        val (shadedTop, shadedMid, shadedHorizon) = if (bucket.nightShade > 0f) {
            Triple(
                ColorMath.shadeRgb(newTop, bucket.nightShade),
                ColorMath.shadeRgb(newMid, bucket.nightShade),
                ColorMath.shadeRgb(newHorizon, bucket.nightShade)
            )
        } else {
            Triple(newTop, newMid, newHorizon)
        }

        skyTop = RenderConstants.applyOvercastLight(shadedTop, bucket.precipDull)
        skyMid = RenderConstants.applyOvercastLight(shadedMid, bucket.precipDull)
        skyHorizon = RenderConstants.applyOvercastLight(shadedHorizon, bucket.precipDull)
        updateGradient()
    }

    /** Sky gradient colour at screen [y] (before parallax), matching the gradient stops. */
    private fun skyColorAt(y: Float): Int {
        val t = if (height > 0f) (y / height).coerceIn(0f, 1f) else 0f
        return if (t < 0.4f) RenderConstants.lerpColor(skyTop, skyMid, t / 0.4f)
        else RenderConstants.lerpColor(skyMid, skyHorizon, (t - 0.4f) / 0.6f)
    }

    private fun updateGradient() {
        if (height > 0) {
            // 3-stop gradient: Top (0%) -> Middle (40%) -> Horizon (100%)
            // This creates the "horizon glow" effect
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(skyTop, skyMid, skyHorizon),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Use getOffsetForDepth for proper perspective support
        // PARALLAX_SKY is the depth factor (0 = background, 1 = foreground)
        val (skyPx, skyPy) = parallaxCalculator.getOffsetForDepth(RenderConstants.PARALLAX_SKY)

        // Sky draw rect must cover the viewport even at maximum translate offset.
        // Since the sky layer has the highest parallax multiplier (lowest depth = most
        // movement with perspective=1.0), its own max offset is the relevant bound.
        val skyMax = parallaxCalculator.getMaxOffsetForDepth(RenderConstants.PARALLAX_SKY)
        val safeRect = LayerViewportMath.computeParallaxSafeDrawRect(
            viewportWidth = width,
            viewportHeight = height,
            maxOffsetX = skyMax.first,
            maxOffsetY = skyMax.second,
            safetyPx = 4f
        )
        
        canvas.withTranslation(skyPx, skyPy) {
            canvas.drawRect(safeRect, skyPaint)
        }

        // Stars only once the sky is really dark (after civil twilight); clouds hide them.
        val starVisibility = starVisibility(state) * (1f - 0.95f * state.overcastFactor())
        val starAlphaBase = (STAR_PEAK_ALPHA * starVisibility).toInt()
        if (starAlphaBase > 3) {
            val time = state.absoluteTimeMs
            
            val (starPx, starPy) = parallaxCalculator.getOffsetForDepth(RenderConstants.PARALLAX_STARS)
            
            val radiusScale = SkyViewportMapper.starRadiusScale(width, height)
            val overlay = if (starFieldEffects?.invoke(starOverlay, time) == true) starOverlay else null
            for (i in stars.indices) {
                val star = stars[i]
                val visibility = overlay?.starAlpha?.get(i) ?: 1f
                if (visibility <= 0f) continue
                val brightness = star[StarField.BRIGHTNESS] * flicker(time, star[StarField.FLICKER])

                // Map normalized coordinates using uniform field scaling.
                val x = SkyViewportMapper.toScreenX(star[0], width, height, starPx)
                val y = SkyViewportMapper.toScreenY(star[1], width, height, starPy)

                // Thicker air near the horizon dims the stars
                val h = ((y / height - HORIZON_FADE_START) / HORIZON_FADE_SPAN).coerceIn(0f, 1f)
                val extinction = 1f - 0.75f * h * h * (3f - 2f * h)
                val alpha = (starAlphaBase * brightness * visibility * extinction).toInt()
                if (alpha <= 0) continue
                starPaint.alpha = alpha.coerceAtMost(255)

                canvas.drawCircle(x, y, star[2] * radiusScale, starPaint)
            }
            if (overlay != null) {
                drawStarOverlay(canvas, overlay, starAlphaBase, starPx, starPy, radiusScale)
            }
        }
        
        // Sun and Moon: celestial bodies at "infinite" distance, should match sky parallax
        // Using the same depth as sky (PARALLAX_SKY = 0.05f) keeps them fixed relative to sky
        val celestialPx = skyPx
        val celestialPy = skyPy
        
        if (sunEnabled) {
            drawSun(canvas, state, celestialPx, celestialPy)
        }
        
        if (moonEnabled) {
            drawMoon(canvas, state, celestialPx, celestialPy)
        }
        
        // Vignette effect for night/dusk/dawn - darker corners for atmosphere
        // Smooth vignette alpha to avoid abrupt transitions
        val vignetteAlpha = computeNightAlpha(state, 80, 40, 30)
        if (vignetteAlpha > 3) {
            drawVignette(canvas, vignetteAlpha)
        }
    }

    /** Extra stars and falling-star streaks from star-field events, in the same projection as the stars. */
    private fun drawStarOverlay(
        canvas: Canvas,
        overlay: StarFieldOverlay,
        starAlphaBase: Int,
        starPx: Float,
        starPy: Float,
        radiusScale: Float
    ) {
        for (i in 0 until overlay.extraCount) {
            val alpha = (starAlphaBase * 0.8f * overlay.extraAlpha[i]).toInt()
            if (alpha <= 0) continue
            starPaint.alpha = alpha.coerceAtMost(255)
            canvas.drawCircle(
                SkyViewportMapper.toScreenX(overlay.extraX[i], width, height, starPx),
                SkyViewportMapper.toScreenY(overlay.extraY[i], width, height, starPy),
                overlay.extraRadius[i] * radiusScale,
                starPaint
            )
        }

        // Burning stars outshine the field; brightness still follows the night-time star alpha.
        val meteorBase = (starAlphaBase * 1.6f).coerceAtMost(255f)
        for (i in 0 until overlay.meteorCount) {
            val headX = SkyViewportMapper.toScreenX(overlay.meteorHeadX[i], width, height, starPx)
            val headY = SkyViewportMapper.toScreenY(overlay.meteorHeadY[i], width, height, starPy)
            val tailX = SkyViewportMapper.toScreenX(overlay.meteorTailX[i], width, height, starPx)
            val tailY = SkyViewportMapper.toScreenY(overlay.meteorTailY[i], width, height, starPy)
            val radius = overlay.meteorRadius[i] * radiusScale
            val alpha = meteorBase * overlay.meteorAlpha[i]
            meteorPaint.strokeWidth = radius * 1.2f
            // Trail brightens towards the head; segments avoid allocating a gradient per frame.
            for (s in 0 until METEOR_TRAIL_SEGMENTS) {
                val t0 = s / METEOR_TRAIL_SEGMENTS.toFloat()
                val t1 = (s + 1) / METEOR_TRAIL_SEGMENTS.toFloat()
                meteorPaint.color = Color.argb((alpha * t1 * t1).toInt(), 235, 240, 255)
                canvas.drawLine(
                    tailX + (headX - tailX) * t0, tailY + (headY - tailY) * t0,
                    tailX + (headX - tailX) * t1, tailY + (headY - tailY) * t1,
                    meteorPaint
                )
            }
            starPaint.alpha = alpha.toInt()
            canvas.drawCircle(headX, headY, radius * 1.3f, starPaint)
        }
    }
    
    /**
     * 0 by day and through early twilight, rising to 1 between ~25 and ~65 min after sunset
     * (mirrored before sunrise), so no stars ever show against a bright sky.
     */
    private fun starVisibility(state: SimulationState): Float {
        val hour = state.smoothedTimeOfDayHours
        val sunrise = state.sunriseHours
        val sunset = state.sunsetHours
        if (inHourWindow(hour, sunrise, sunset)) return 0f
        val sinceSunset = ((hour - sunset) % 24f + 24f) % 24f
        val untilSunrise = ((sunrise - hour) % 24f + 24f) % 24f
        val t = ((minOf(sinceSunset, untilSunrise) - STAR_FADE_START_H) / STAR_FADE_DURATION_H).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Brightness factor for the few flickering stars (phase > 0); steady stars return 1. */
    private fun flicker(timeMs: Long, phase: Float): Float {
        if (phase <= 0f) return 1f
        // Double math: epoch ms in Float has ~2 min resolution.
        val t = timeMs.toDouble()
        val wave = 0.5 * sin(t * 0.0047 + phase) + 0.5 * sin(t * 0.0113 + phase * 1.7)
        return (0.75 + 0.25 * wave).toFloat()
    }

    /**
     * Compute a smooth alpha value for night/dusk/dawn effects based on time of day.
     * Uses continuous interpolation instead of hard boolean checks.
     * 
     * @param nightVal Alpha when fully night
     * @param duskVal Alpha at dusk peak
     * @param dawnVal Alpha at dawn peak
     * @return Smoothly interpolated alpha value (0 during full day)
     */
    private fun computeNightAlpha(state: SimulationState, nightVal: Int, duskVal: Int, dawnVal: Int): Int =
        RenderConstants.twilightColor(
            state.smoothedTimeOfDayHours, state.sunriseHours, state.sunsetHours,
            nightVal, dawnVal, 0, duskVal
        ) { from, to, t -> (from + (to - from) * t).toInt() }
    
    /**
     * Draw a radial vignette (darker corners) for atmospheric effect.
     * Uses cached gradient to avoid allocations every frame.
     */
    private fun drawVignette(canvas: Canvas, alpha: Int) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = kotlin.math.max(width, height) * 0.9f
        
        // Only recreate gradient if alpha changed significantly (>10 units)
        // This avoids RadialGradient allocation every frame
        if (cachedVignetteGradient == null || kotlin.math.abs(alpha - cachedVignetteAlpha) > 10) {
            cachedVignetteGradient = RadialGradient(
                centerX, centerY, radius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(alpha / 2, 0, 0, 20),
                    Color.argb(alpha, 5, 5, 30)
                ),
                floatArrayOf(0f, 0.5f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            cachedVignetteAlpha = alpha
        }
        
        vignettePaint.shader = cachedVignetteGradient
        canvas.drawRect(0f, 0f, width, height, vignettePaint)
        vignettePaint.shader = null
    }

    /**
     * Draw the sun as a bright circle following an arc across the sky.
     * Position is based on time of day using dynamic sunrise/sunset from weather data.
     * Arc goes from horizon at sunrise, peaks at solar noon, to horizon at sunset.
     * Uses smoothedTimeOfDayHours for animated transitions when simulation time changes.
     */
    private fun drawSun(canvas: Canvas, state: SimulationState, parallaxX: Float, parallaxY: Float) {
        // Use smoothed time for animated position transitions (matches sky gradient)
        val hour = state.smoothedTimeOfDayHours
        
        // Use dynamic sunrise/sunset from weather data
        val sunriseHour = state.sunriseHours   // e.g., 7.05
        val sunsetHour = state.sunsetHours     // e.g., 17.8
        
        // Sun visible from sunrise to sunset (with slight buffer for rising/setting glow)
        val sunVisibleStart = sunriseHour - 0.3f  // slight pre-sunrise glow
        val sunVisibleEnd = sunsetHour + 0.3f     // slight post-sunset glow
        if (hour < sunVisibleStart || hour > sunVisibleEnd) return
        
        // Calculate position along arc (0 = sunrise, 1 = sunset)
        val dayDuration = sunsetHour - sunriseHour
        val dayProgress = ((hour - sunriseHour) / dayDuration).coerceIn(0f, 1f)
        
        // X position: left to right across height-based field (uniform with Y scaling)
        val fieldWidth = SkyViewportMapper.fieldWidth(width, height)
        val fieldOffset = SkyViewportMapper.fieldOffset(width, height)
        val sunX = fieldWidth * (0.1f + dayProgress * 0.8f) - fieldOffset
        
        // Y position: parabolic arc, highest at noon (uniform field scaling)
        val fieldHeight = SkyViewportMapper.fieldHeight(width, height)
        val fieldOffsetY = SkyViewportMapper.fieldOffsetY(width, height)
        val maxArcHeight = fieldHeight * 0.7f
        val minArcHeight = fieldHeight * 0.15f
        val arcHeight = minArcHeight + (maxArcHeight - minArcHeight) * celestialArcHeight
        val baseY = fieldHeight * 0.85f - fieldOffsetY
        val arcProgress = 1f - 4f * (dayProgress - 0.5f) * (dayProgress - 0.5f)  // Parabola: 0 at edges, 1 at center
        val sunY = baseY - arcHeight * arcProgress
        
        // Sun size (height-based for uniform scaling)
        val baseRadius = height * 0.04f * RenderConstants.REFERENCE_ASPECT_RATIO
        val radius = baseRadius * celestialSize
        
        // Sun glow gradient - color shifts with elevation (atmospheric scattering)
        // Near horizon: sun appears more orange/red (longer path through atmosphere)
        // High in sky: sun appears white-yellow (less scattering)
        // Based on Rayleigh scattering: shorter wavelengths (blue) scattered more at low angles
        val elevationFactor = arcProgress.coerceIn(0f, 1f)  // 0 at horizon, 1 at zenith
        val centerR = 255
        val centerG = (230 + 25 * elevationFactor).toInt()  // 230 (orange) → 255 (white) at zenith
        val centerB = (160 + 80 * elevationFactor).toInt()   // 160 (warm) → 240 (white) at zenith
        val midR = 255
        val midG = (200 + 30 * elevationFactor).toInt()
        val midB = (100 + 80 * elevationFactor).toInt()
        val outerR = 255
        val outerG = (160 + 40 * elevationFactor).toInt()
        val outerB = (50 + 80 * elevationFactor).toInt()
        
        val glowRadius = radius * 3f
        val cx = sunX + parallaxX
        val cy = sunY + parallaxY
        // Only recreate gradient when position or elevation changes meaningfully
        val elevBucket = (elevationFactor * 10).toInt() // 10 discrete steps
        if (cachedSunGradient == null
            || kotlin.math.abs(cx - cachedSunGlowCenterX) > 2f
            || kotlin.math.abs(cy - cachedSunGlowCenterY) > 2f
            || kotlin.math.abs(glowRadius - cachedSunGlowRadius) > 1f
            || elevBucket.toFloat() / 10f != cachedSunElevation) {
            cachedSunGlowCenterX = cx
            cachedSunGlowCenterY = cy
            cachedSunGlowRadius = glowRadius
            cachedSunElevation = elevBucket.toFloat() / 10f
            cachedSunGradient = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(
                    Color.argb(255, centerR, centerG, centerB),
                    Color.argb(200, midR, midG, midB),
                    Color.argb(100, outerR, outerG, outerB),
                    Color.argb(0, 255, 180, 50)
                ),
                floatArrayOf(0f, 0.3f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        
        sunPaint.shader = cachedSunGradient
        // Hidden behind a closed deck, but still glowing through low near the horizon
        val sunAlpha = 1f - state.overcastFactor() * (0.5f + 0.45f * elevationFactor)
        sunPaint.alpha = (255 * sunAlpha).toInt()
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, glowRadius, sunPaint)
        
        // Solid sun core
        sunPaint.shader = null
        sunPaint.color = Color.argb((255 * sunAlpha).toInt(), 255, 255, 220)
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, radius, sunPaint)
    }

    /**
     * Draw the moon as a pale circle with crescent effect.
     * Rises and sets according to its phase (a full moon is up all night, a first quarter
     * moon from noon to midnight), so it can also appear faintly in the daytime sky.
     * Uses smoothedTimeOfDayHours for animated transitions when simulation time changes.
     */
    private fun drawMoon(canvas: Canvas, state: SimulationState, parallaxX: Float, parallaxY: Float) {
        // Use smoothed time for animated position transitions (matches sky gradient)
        val hour = state.smoothedTimeOfDayHours
        val solarNoon = (state.sunriseHours + state.sunsetHours) / 2f
        val nightProgress = Astronomy.moonArcProgress(hour, state.moonPhase, solarNoon) ?: return

        // Washed out by daylight: pale disc, no dark earthshine side.
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val cloudVisibility = 1f - 0.6f * state.overcastFactor()
        val moonAlpha = (1f - DAY_MOON_FADE * dayFactor) * cloudVisibility
        
        // X position: left to right across height-based field (uniform with Y scaling)
        val fieldWidth = SkyViewportMapper.fieldWidth(width, height)
        val fieldOffset = SkyViewportMapper.fieldOffset(width, height)
        val moonX = fieldWidth * (0.1f + nightProgress * 0.8f) - fieldOffset + parallaxX
        
        // Y position: parabolic arc, highest at midnight (uniform field scaling)
        val fieldHeight = SkyViewportMapper.fieldHeight(width, height)
        val fieldOffsetY = SkyViewportMapper.fieldOffsetY(width, height)
        val maxArcHeight = fieldHeight * 0.6f
        val minArcHeight = fieldHeight * 0.12f
        val arcHeight = minArcHeight + (maxArcHeight - minArcHeight) * celestialArcHeight
        val baseY = fieldHeight * 0.85f - fieldOffsetY
        val arcProgress = 1f - 4f * (nightProgress - 0.5f) * (nightProgress - 0.5f)
        val moonY = baseY - arcHeight * arcProgress + parallaxY
        
        // Moon size (height-based for uniform scaling)
        val baseRadius = height * 0.03f * RenderConstants.REFERENCE_ASPECT_RATIO
        val radius = baseRadius * celestialSize
        
        // Draw moon phase using procedural rendering
        // moonPhase: 0 = new moon, 0.5 = full moon, 1 = new moon (end of cycle)
        val phase = state.moonPhase
        
        // Calculate illumination fraction (0 at new moon, 1 at full moon)
        val illumination = 1f - 2f * abs(phase - 0.5f)  // 0 at new, 1 at full
        
        // Determine if waxing (right side lit) or waning (left side lit)
        val isWaxing = phase < 0.5f
        
        // Draw outer soft glow first (uses cached shader to avoid per-frame allocation)
        val glowRadius = radius * 2.5f
        if (cachedMoonGradient == null
            || kotlin.math.abs(moonX - cachedMoonGlowCenterX) > 2f
            || kotlin.math.abs(moonY - cachedMoonGlowCenterY) > 2f
            || kotlin.math.abs(glowRadius - cachedMoonGlowRadius) > 1f) {
            cachedMoonGlowCenterX = moonX
            cachedMoonGlowCenterY = moonY
            cachedMoonGlowRadius = glowRadius
            cachedMoonGradient = RadialGradient(
                moonX, moonY, glowRadius,
                intArrayOf(
                    Color.argb(40, 200, 220, 255),
                    Color.argb(15, 180, 200, 240),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        moonPaint.shader = cachedMoonGradient
        moonPaint.alpha = (255 * moonAlpha).toInt()
        canvas.drawCircle(moonX, moonY, glowRadius, moonPaint)
        moonPaint.shader = null
        
        // Dark side: the sky colour behind it plus faint earthshine, so it hides stars without a dark rim
        moonShadowPaint.color = RenderConstants.lerpColor(skyColorAt(moonY - parallaxY), earthshineColor, 0.1f)
        moonShadowPaint.alpha = (255 * (1f - dayFactor) * cloudVisibility).toInt()
        canvas.drawCircle(moonX, moonY, radius, moonShadowPaint)
        
        // Now draw the lit portion using a path
        if (illumination > 0.01f) {  // Skip at new moon
            moonPath.reset()
            
            // Reuse pre-allocated RectF for moon disc
            moonRect.set(moonX - radius, moonY - radius, moonX + radius, moonY + radius)
            
            // The terminator curve is an ellipse
            // k controls the ellipse width: k=0 -> straight line, k=1 -> full circle
            val k = abs(2f * illumination - 1f)  // 0 at quarter, 1 at full/new
            val terminatorX = radius * k
            
            if (illumination >= 0.5f) {
                // More than half lit - gibbous
                if (isWaxing) {
                    moonPath.arcTo(moonRect, -90f, 180f, true)
                    termRect.set(moonX - terminatorX, moonY - radius, 
                                 moonX + terminatorX, moonY + radius)
                    moonPath.arcTo(termRect, 90f, 180f, false)
                } else {
                    moonPath.arcTo(moonRect, 90f, 180f, true)
                    termRect.set(moonX - terminatorX, moonY - radius, 
                                 moonX + terminatorX, moonY + radius)
                    moonPath.arcTo(termRect, -90f, 180f, false)
                }
            } else {
                // Less than half lit - crescent
                if (isWaxing) {
                    moonPath.arcTo(moonRect, -90f, 180f, true)
                    termRect.set(moonX - terminatorX, moonY - radius, 
                                 moonX + terminatorX, moonY + radius)
                    moonPath.arcTo(termRect, 90f, -180f, false)
                } else {
                    moonPath.arcTo(moonRect, 90f, 180f, true)
                    termRect.set(moonX - terminatorX, moonY - radius, 
                                 moonX + terminatorX, moonY + radius)
                    moonPath.arcTo(termRect, -90f, -180f, false)
                }
            }
            moonPath.close()
            
            // Draw lit portion - simple solid color for performance
            moonPaint.color = Color.argb((255 * moonAlpha).toInt(), 245, 245, 240)  // Cream white
            canvas.drawPath(moonPath, moonPaint)
            
            // Draw simple maria as low-alpha circles (no gradients for performance)
            moonPaint.color = Color.argb((20 * moonAlpha).toInt(), 100, 110, 130)
            canvas.withClip(moonPath) {
                canvas.drawCircle(moonX - radius * 0.25f, moonY - radius * 0.15f, radius * 0.4f, moonPaint)
                canvas.drawCircle(moonX + radius * 0.15f, moonY + radius * 0.2f, radius * 0.3f, moonPaint)
            }
        }
        
        moonPaint.shader = null
    }

    override fun reloadSettings(settings: SimulationSettings) {
        sunEnabled = settings.getBoolean("pref_sun_enabled", PreferenceDefaults.SUN_ENABLED)
        moonEnabled = settings.getBoolean("pref_moon_enabled", PreferenceDefaults.MOON_ENABLED)
        celestialSize = settings.getInt("pref_celestial_size", PreferenceDefaults.CELESTIAL_SIZE) / 100f
        celestialArcHeight = settings.getInt("pref_celestial_arc_height", PreferenceDefaults.CELESTIAL_ARC_HEIGHT) / 100f
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
