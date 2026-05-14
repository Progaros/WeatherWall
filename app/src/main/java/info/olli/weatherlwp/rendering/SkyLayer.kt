package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.ColorMath
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

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
        color = Color.parseColor("#1A1A24")  // Earthshine/shadow color
    }
    private val moonPath = android.graphics.Path()  // Reusable path for moon phase
    private val moonRect = RectF()  // Reusable rect for moon disc
    private val termRect = RectF()  // Reusable rect for terminator ellipse
    private val moonShadowColor = Color.parseColor("#1A1A24")  // Pre-parsed shadow color
    
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
    private val stars = ArrayList<FloatArray>()
    
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
    private var celestialArcHeight = 1.0f  // 1.0 = full arc, 0.0 = near horizon

    private var lastSkyBucketId = Int.MIN_VALUE

    private data class SkyGradientBucket(
        val id: Int,
        val hour: Float,
        val sunrise: Float,
        val sunset: Float,
        val nightShade: Float
    )

    private companion object {
        private const val SKY_TIME_BUCKETS = 256
        private const val SKY_SUN_EVENT_BUCKETS = 64
        private const val SKY_SHADE_BUCKETS = 32
    }

    init {
        generateStars()
        reloadSettings()
    }

    private fun generateStars() {
        stars.clear()
        val random = Random(42) // Fixed seed for consistency
        for (i in 0 until RenderConstants.STAR_COUNT) {
            stars.add(floatArrayOf(
                random.nextFloat(), // x (0-1)
                random.nextFloat() * 0.98f, // y (0-0.98, almost full visible sky)
                random.nextFloat() * 3f + 1f // radius
            ))
        }
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

        val id = hourBucket or
            (sunriseBucket shl 8) or
            (sunsetBucket shl 14) or
            (shadeBucket shl 20)

        return SkyGradientBucket(
            id = id,
            hour = fromBucket(hourBucket, 0f, 24f, SKY_TIME_BUCKETS),
            sunrise = fromBucket(sunriseBucket, 0f, 24f, SKY_SUN_EVENT_BUCKETS),
            sunset = fromBucket(sunsetBucket, 0f, 24f, SKY_SUN_EVENT_BUCKETS),
            nightShade = fromBucket(shadeBucket, 0f, 1f, SKY_SHADE_BUCKETS)
        )
    }

    private fun updateColorsForTime(state: SimulationState) {
        val bucket = buildSkyGradientBucket(state)
        if (bucket.id == lastSkyBucketId) return
        lastSkyBucketId = bucket.id

        // Use bucketized time/solar inputs to avoid per-frame shader churn.
        val hour = bucket.hour
        val sunrise = bucket.sunrise
        val sunset = bucket.sunset
        val dawnStart = (sunrise - 1.0f).coerceAtLeast(3.0f)   // 1h before sunrise
        val dawnEnd = sunrise + 0.5f                            // 30min after sunrise
        val duskStart = sunset - 0.5f                           // 30min before sunset
        val duskEnd = (sunset + 1.0f).coerceAtMost(23.0f)      // 1h after sunset
        
        // 3-stop gradient: Top, Middle (40% down), Horizon
        val (newTop, newMid, newHorizon) = when {
            hour < dawnStart -> {
                Triple(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_NIGHT_BOTTOM)
            }
            hour < dawnStart + 1f -> {
                // Transition from night to dawn - use eased transitions
                val t = hour - dawnStart
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_DAWN_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_DAWN_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_BOTTOM, RenderConstants.SKY_DAWN_BOTTOM, t)
                )
            }
            hour < dawnEnd -> {
                // Dawn to day - use eased transitions
                val dawnToDay = dawnEnd - (dawnStart + 1f)
                val t = ((hour - (dawnStart + 1f)) / dawnToDay.coerceAtLeast(0.5f)).coerceIn(0f, 1f)
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_TOP, RenderConstants.SKY_DAY_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_MID, RenderConstants.SKY_DAY_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_BOTTOM, RenderConstants.SKY_DAY_BOTTOM, t)
                )
            }
            hour < duskStart -> {
                Triple(RenderConstants.SKY_DAY_TOP, RenderConstants.SKY_DAY_MID, RenderConstants.SKY_DAY_BOTTOM)
            }
            hour < sunset -> {
                // Day to dusk - use eased transitions
                val dayToDusk = sunset - duskStart
                val t = ((hour - duskStart) / dayToDusk.coerceAtLeast(0.5f)).coerceIn(0f, 1f)
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_TOP, RenderConstants.SKY_DUSK_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_MID, RenderConstants.SKY_DUSK_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_BOTTOM, RenderConstants.SKY_DUSK_BOTTOM, t)
                )
            }
            hour < duskEnd -> {
                // Dusk to night - use eased transitions
                val duskToNight = duskEnd - sunset
                val t = ((hour - sunset) / duskToNight.coerceAtLeast(0.5f)).coerceIn(0f, 1f)
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_TOP, RenderConstants.SKY_NIGHT_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_MID, RenderConstants.SKY_NIGHT_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_BOTTOM, RenderConstants.SKY_NIGHT_BOTTOM, t)
                )
            }
            else -> Triple(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_NIGHT_BOTTOM)
        }
        
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

        skyTop = shadedTop
        skyMid = shadedMid
        skyHorizon = shadedHorizon
        updateGradient()
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
        
        // Sky moves opposite to create depth illusion (infinite distance)
        val overscan = 300f + abs(skyPx) * 0.4f + abs(skyPy) * 0.4f
        
        canvas.save()
        canvas.translate(skyPx, skyPy)
        canvas.drawRect(-overscan, -overscan, width + overscan, height + overscan, skyPaint)
        canvas.restore()

        // Stars visible at night, dusk, and dawn (fading as sunrise approaches)
        // Per atmospheric science: stars remain visible until civil twilight
        // Smooth star alpha based on time of day to avoid abrupt transitions
        val starAlphaBase = computeNightAlpha(state, 150, 80, 60)
        if (starAlphaBase > 5) {
            val time = state.absoluteTimeMs
            
            val (starPx, starPy) = parallaxCalculator.getOffsetForDepth(RenderConstants.PARALLAX_STARS)
            
            for (star in stars) {
                val twinkle = sin((time * RenderConstants.STAR_TWINKLE_SPEED + star[0] * 10).toDouble())
                val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(5, 220)
                starPaint.alpha = alpha

                // Map normalized coordinates directly to viewport for full-sky coverage.
                val x = SkyViewportMapper.toScreenX(star[0], width, starPx)
                val y = SkyViewportMapper.toScreenY(star[1], height, starPy)
                val radiusScale = SkyViewportMapper.starRadiusScale(width, height)

                canvas.drawCircle(x, y, star[2] * radiusScale, starPaint)
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
    
    /**
     * Draw a radial vignette (darker corners) for atmospheric effect.
     * Uses cached gradient to avoid allocations every frame.
     */
    /**
     * Compute a smooth alpha value for night/dusk/dawn effects based on time of day.
     * Uses continuous interpolation instead of hard boolean checks.
     * 
     * @param nightVal Alpha when fully night
     * @param duskVal Alpha at dusk peak
     * @param dawnVal Alpha at dawn peak
     * @return Smoothly interpolated alpha value (0 during full day)
     */
    private fun computeNightAlpha(state: SimulationState, nightVal: Int, duskVal: Int, dawnVal: Int): Int {
        val hour = state.timeOfDayHours
        val sunrise = state.sunriseHours
        val sunset = state.sunsetHours
        
        // Pre-dawn: Night→Dawn (1h before sunrise to sunrise)
        val dawnStart = sunrise - 1.0f
        if (hour >= dawnStart && hour < sunrise) {
            val t = (hour - dawnStart) / (sunrise - dawnStart)
            return (nightVal + (dawnVal - nightVal) * t).toInt()
        }
        // Dawn→Day: (sunrise to sunrise+0.5h)
        if (hour >= sunrise && hour < sunrise + 0.5f) {
            val t = (hour - sunrise) / 0.5f
            return (dawnVal * (1f - t)).toInt()
        }
        // Day→Dusk: (sunset-1.5h to sunset)
        if (hour >= sunset - 1.5f && hour < sunset) {
            val t = (hour - (sunset - 1.5f)) / 1.5f
            return (duskVal * t).toInt()
        }
        // Dusk→Night: (sunset to sunset+1h)
        if (hour >= sunset && hour < sunset + 1.0f) {
            val t = (hour - sunset) / 1.0f
            return (duskVal + (nightVal - duskVal) * t).toInt()
        }
        // Full day
        if (hour >= sunrise + 0.5f && hour < sunset - 1.5f) return 0
        // Full night
        return nightVal
    }
    
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
        
        // X position: left to right across screen
        val sunX = width * (0.1f + dayProgress * 0.8f)
        
        // Y position: parabolic arc, highest at noon
        // At progress 0.5 (noon), should be at top. At 0 and 1, near horizon.
        // celestialArcHeight: 1.0 = full arc (high), 0.0 = near horizon
        val maxArcHeight = height * 0.7f  // Maximum arc height at setting = 100
        val minArcHeight = height * 0.15f  // Minimum arc height at setting = 0
        val arcHeight = minArcHeight + (maxArcHeight - minArcHeight) * celestialArcHeight
        val baseY = height * 0.85f  // Horizon level
        val arcProgress = 1f - 4f * (dayProgress - 0.5f) * (dayProgress - 0.5f)  // Parabola: 0 at edges, 1 at center
        val sunY = baseY - arcHeight * arcProgress
        
        // Sun size
        val baseRadius = kotlin.math.min(width, height) * 0.04f
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
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, glowRadius, sunPaint)
        
        // Solid sun core
        sunPaint.shader = null
        sunPaint.color = Color.argb(255, 255, 255, 220)
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, radius, sunPaint)
    }

    /**
     * Draw the moon as a pale circle with crescent effect.
     * Visible at night, follows arc opposite to sun using dynamic sunset/sunrise times.
     * Uses smoothedTimeOfDayHours for animated transitions when simulation time changes.
     */
    private fun drawMoon(canvas: Canvas, state: SimulationState, parallaxX: Float, parallaxY: Float) {
        // Use smoothed time for animated position transitions (matches sky gradient)
        val hour = state.smoothedTimeOfDayHours
        
        // Moon visible from sunset to sunrise (using dynamic times)
        val sunriseHour = state.sunriseHours
        val sunsetHour = state.sunsetHours
        if (hour >= sunriseHour && hour <= sunsetHour) return
        
        // Calculate position along arc (normalize night hours)
        // Night runs from sunset to midnight and midnight to sunrise
        val nightDuration = (24f - sunsetHour) + sunriseHour
        val nightProgress = if (hour >= sunsetHour) {
            (hour - sunsetHour) / nightDuration
        } else {
            ((24f - sunsetHour) + hour) / nightDuration
        }
        
        // X position: left to right across screen
        val moonX = width * (0.1f + nightProgress * 0.8f) + parallaxX
        
        // Y position: parabolic arc, highest at midnight
        // celestialArcHeight: 1.0 = full arc (high), 0.0 = near horizon
        val maxArcHeight = height * 0.6f  // Maximum arc height at setting = 100
        val minArcHeight = height * 0.12f  // Minimum arc height at setting = 0
        val arcHeight = minArcHeight + (maxArcHeight - minArcHeight) * celestialArcHeight
        val baseY = height * 0.85f
        val arcProgress = 1f - 4f * (nightProgress - 0.5f) * (nightProgress - 0.5f)
        val moonY = baseY - arcHeight * arcProgress + parallaxY
        
        // Moon size
        val baseRadius = kotlin.math.min(width, height) * 0.03f
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
        canvas.drawCircle(moonX, moonY, glowRadius, moonPaint)
        moonPaint.shader = null
        
        // Draw the shadow/dark side (earthshine) - use pre-parsed color, no shader
        moonShadowPaint.color = moonShadowColor
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
            moonPaint.color = Color.argb(255, 245, 245, 240)  // Cream white
            canvas.drawPath(moonPath, moonPaint)
            
            // Draw simple maria as low-alpha circles (no gradients for performance)
            moonPaint.color = Color.argb(20, 100, 110, 130)
            canvas.save()
            canvas.clipPath(moonPath)
            canvas.drawCircle(moonX - radius * 0.25f, moonY - radius * 0.15f, radius * 0.4f, moonPaint)
            canvas.drawCircle(moonX + radius * 0.15f, moonY + radius * 0.2f, radius * 0.3f, moonPaint)
            canvas.restore()
        }
        
        moonPaint.shader = null
    }

    override fun reloadSettings() {
        if (context == null) {
            android.util.Log.w("SkyLayer", "reloadSettings() skipped: context is null")
            return
        }
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        sunEnabled = settings.getBoolean("pref_sun_enabled", true)
        moonEnabled = settings.getBoolean("pref_moon_enabled", true)
        celestialSize = settings.getInt("pref_celestial_size", 100) / 100f
        celestialArcHeight = settings.getInt("pref_celestial_arc_height", 100) / 100f
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
