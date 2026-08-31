package info.olli.weatherlwp.simulation

import info.olli.weatherlwp.weather.WeatherType

/**
 * Immutable snapshot of the current simulation state.
 * 
 * All layers read from this state - it's the single source of truth
 * for what the simulation looks like at any moment.
 */
data class SimulationState(
    // Time
    val timeOfDayHours: Float,          // 0-24
    val timeSeconds: Float,             // Absolute time for animations
    val absoluteTimeMs: Long,           // Epoch time for consistent positions
    val isNight: Boolean,
    val isDawn: Boolean,
    val isDusk: Boolean,
    val dayProgress: Float,             // 0-1
    
    // Smoothed time for eased transitions
    val smoothedTimeOfDayHours: Float = timeOfDayHours,
    val smoothedProgress: Float = dayProgress,
    
    // Dynamic sun times from weather data
    val sunriseHours: Float = 6.0f,     // e.g., 7.05 (7:03 AM) - default 6:00
    val sunsetHours: Float = 20.0f,     // e.g., 15.58 (3:35 PM) - default 20:00
    val isGoldenHour: Boolean = false,  // True during golden hour (sunrise/sunset ±45min)
    val isBlueHour: Boolean = false,    // True during blue hour (pre-dawn/post-dusk ±30min)
    
    // Weather
    val weather: WeatherType,
    val cloudDensity: CloudDensity,
    
    // Extended weather data
    val precipProbability: Float = 0f,  // 0-1 (from 0-100%)
    val precipIntensity: Float = 0f,    // 0-1 effective precipitation intensity
    val cloudCover: Float = 0f,         // 0-1 (from 0-100%)
    val humidity: Float = 0f,           // 0-1 (from 0-100%)
    val visibility: Float = 1f,         // 0-1 normalized (0=poor, 1=excellent)
    val pressure: Int = 1013,           // hPa
    val moonPhase: Float = 0.5f,        // 0-1 (0=new, 0.5=full, 1=new again) - default full
    val moonIllumination: Float = 0f,   // 0-1 (0=new moon dark, 1=full moon bright)
    
    // Wind
    val windStrength: Float,            // 0-1 normalized base wind
    val windDirection: Float,           // Degrees (0 = right, 90 = up)
    val windGust: Float = 0f,           // 0-1 current gust intensity (peaks during gusts)
    val windMultiplier: Float = 1f,     // User setting multiplier for wind effect
    val treeSwaySpeed: Float = 1f,      // User setting multiplier for tree sway speed
    
    // Shadows & Lighting - separate toggles for mountains and trees
    val mountainShadowsEnabled: Boolean = true,  // Mountain side shading
    val treeShadowsEnabled: Boolean = true,      // Tree shadows on ground
    val shadowStrength: Float = 0.5f,            // 0-1 shadow intensity from settings
    val nightShadeStrength: Float = 0f,          // 0-1 night darkening (0=bright, 0.35=default, 1=very dark)
    
    // Legacy computed property for backward compatibility
    val shadowsEnabled: Boolean = mountainShadowsEnabled || treeShadowsEnabled,
    
    // Screen
    val screenWidth: Float,
    val screenHeight: Float,
    
    // Weather history derived values
    val snowLevel: Float = 0f,          // 0-1 accumulated snow depth from weather history
    val riverLevel: Float = 0f,         // 0-1 river water level from rainfall/snowmelt
    val temperatureCelsius: Float = Float.NaN,  // Current temperature (NaN = unknown)
    val weatherDataFresh: Boolean = true,  // False when weather data is stale (>1h old)
    
    // Season transition (gradual blending between seasons)
    val seasonTransitionProgress: Float = 1f,  // 0=fully previous, 1=fully current
    val currentSeason: String = "summer",       // Primary season key
    val previousSeason: String = "summer",      // Season we're blending from
    val seasonTransitionDays: Int = 14,         // Configured transition duration
    
    // Debug/Override flags
    val isDebugMode: Boolean
) {
    // Pre-calculated wind direction trig values (computed once per state creation)
    private val windDirRad: Double = Math.toRadians(windDirection.toDouble())
    private val windCosVal: Float = kotlin.math.cos(windDirRad).toFloat()
    private val windSinVal: Float = kotlin.math.sin(windDirRad).toFloat()
    
    /**
     * Get wind direction X component (cos of wind angle, -1 to 1).
     * Uses precalculated value for efficiency. For use without strength multiplier.
     */
    fun getWindDirX(): Float = windCosVal
    
    /**
     * Get wind direction Y component (sin of wind angle, -1 to 1).
     * Uses precalculated value for efficiency. For use without strength multiplier.
     */
    fun getWindDirY(): Float = windSinVal
    
    /**
     * Get wind X component (-1 to 1).
     * Uses precalculated cos value for efficiency.
     */
    fun getWindX(): Float = windStrength * windCosVal
    
    /**
     * Get wind Y component (-1 to 1).
     * Uses precalculated sin value for efficiency.
     */
    fun getWindY(): Float = windStrength * windSinVal
    
    /**
     * Get ambient light level (0.0 = deep night, 1.0 = bright day).
     * Uses actual sunrise/sunset times with SmoothStep for natural transitions.
     * 
     * Timeline (with default 6:00 sunrise, 20:00 sunset):
     * - Night: before dawn, after dusk
     * - Dawn: sunrise-1h to sunrise (smooth transition)
     * - Day: sunrise to sunset
     * - Dusk: sunset to sunset+1h (smooth transition)
     */
    // Light level is frame-global and read many times per frame (every layer plus
    // ~3× per drawn tree), so it is computed once per immutable state and cached.
    private val cachedLightLevel: Float = computeLightLevel()

    fun getLightLevel(): Float = cachedLightLevel

    private fun computeLightLevel(): Float {
        val hour = timeOfDayHours
        val transitionDuration = 1.0f  // 1 hour for full transition
        
        // 1. Dawn Calculation (Night -> Day)
        // Starts 1 hour before sunrise, ends at sunrise
        val dawnStart = sunriseHours - transitionDuration
        if (hour in dawnStart..sunriseHours) {
            val progress = (hour - dawnStart) / transitionDuration
            return smoothStep(0.15f, 1.0f, progress)
        }
        
        // 2. Dusk Calculation (Day -> Night)
        // Starts at sunset, ends 1 hour after
        val duskEnd = sunsetHours + transitionDuration
        if (hour in sunsetHours..duskEnd) {
            val progress = (hour - sunsetHours) / transitionDuration
            return smoothStep(1.0f, 0.15f, progress)
        }
        
        // 3. Static States
        return when {
            hour in sunriseHours..sunsetHours -> 1.0f  // Day
            else -> 0.15f                               // Night
        }
    }
    
    /**
     * Returns an "Atmosphere Phase" value (-1 to 1) to drive gradient mixing.
     * -1.0 = Deep Night
     * -0.5 = Blue Hour (Pre-dawn / Post-dusk)
     *  0.0 = Golden Hour (Sunrise / Sunset)
     *  1.0 = Day
     *  
     * Use this to blend between day/night color palettes with natural transitions.
     * Derives phase from timeOfDayHours and sun times for consistency with copy().
     */
    fun getAtmospherePhase(): Float {
        val hour = timeOfDayHours
        val goldenHourDuration = 0.75f  // ~45 mins
        val blueHourDuration = 0.5f     // ~30 mins

        return when {
            hour > (sunriseHours + goldenHourDuration) &&
            hour < (sunsetHours - goldenHourDuration) -> 1.0f
            hour in sunriseHours..(sunriseHours + goldenHourDuration) -> 0.0f
            hour in (sunsetHours - goldenHourDuration)..sunsetHours -> 0.0f
            hour in (sunriseHours - blueHourDuration)..sunriseHours -> -0.5f
            hour in sunsetHours..(sunsetHours + blueHourDuration) -> -0.5f
            else -> -1.0f
        }
    }
    
    /**
     * SmoothStep interpolation for natural easing.
     * Uses cubic Hermite interpolation for smooth acceleration/deceleration.
     */
    private fun smoothStep(start: Float, end: Float, amount: Float): Float {
        val t = amount.coerceIn(0f, 1f)
        val smoothed = t * t * (3.0f - 2.0f * t)  // Cubic Hermite interpolation
        return start + smoothed * (end - start)
    }
    
    /**
     * Get effective shadow intensity combining settings and light level.
     * Uses continuous interpolation (no boolean branch) so shadows never jump
     * at sunrise/sunset boundaries.
     * Note: Callers should check mountainShadowsEnabled or treeShadowsEnabled first.
     * @return 0.0 = no shadow, 1.0 = full shadow intensity
     */
    fun getEffectiveShadowStrength(): Float = cachedEffectiveShadowStrength

    // Effective shadow strength is frame-global and read once per drawn tree; cache it.
    private val cachedEffectiveShadowStrength: Float = run {
        val light = cachedLightLevel  // 0.15 (night) to 1.0 (day), smooth transitions
        // Normalized day factor: 0 at deep night, 1 during full day
        val dayFactor = ((light - 0.15f) / 0.85f).coerceIn(0f, 1f)
        // Smoothly blend: day shadows scale with sunlight, night shadows with moonlight
        val lightFactor = dayFactor * light + (1f - dayFactor) * moonIllumination * 0.4f
        shadowStrength * lightFactor
    }
    
    /**
     * Get the light source angle in radians.
     * This is used to determine shadow direction from entities.
     * 
     * During day: Sun moves from left (6am) across top to right (6pm)
     * During night: Moon follows similar arc
     * 
     * @return Angle in radians where:
     *   - 0 = light from right
     *   - PI/2 = light from above
     *   - PI = light from left
     */
    fun getLightSourceAngle(): Float = lightSourceAngle
    
    // Pre-calculated light source angle and shadow trig values (computed once per state)
    //
    // CONTINUOUS at sunrise/sunset: daytime arc (PI→0) meets nighttime arc (0→PI)
    // so the angle matches at both boundaries and shadows never flip direction.
    //   Day:   sunrise(PI) → noon(PI/2) → sunset(0)
    //   Night: sunset(0)   → midnight(PI/2) → sunrise(PI)
    private val lightSourceAngle: Float = run {
        val hour = timeOfDayHours
        val sunrise = sunriseHours
        val sunset = sunsetHours
        val PI = kotlin.math.PI.toFloat()

        if (hour >= sunrise && hour <= sunset) {
            // Daytime: PI at sunrise → 0 at sunset
            val dayDuration = (sunset - sunrise).coerceAtLeast(0.5f)
            val dayProgress = ((hour - sunrise) / dayDuration).coerceIn(0f, 1f)
            PI * (1f - dayProgress)
        } else {
            // Nighttime: 0 at sunset → PI at sunrise (continuous at both boundaries)
            val nightDuration = (24f - sunset + sunrise).coerceAtLeast(0.5f)
            val nightProgress = if (hour >= sunset) {
                (hour - sunset) / nightDuration
            } else {
                (24f - sunset + hour) / nightDuration
            }
            PI * nightProgress.coerceIn(0f, 1f)
        }
    }
    
    // Pre-calculated shadow direction values (avoids per-tree sin/cos)
    private val shadowCos: Float = kotlin.math.cos(lightSourceAngle)
    private val shadowSin: Float = kotlin.math.sin(lightSourceAngle)

    // === Shared scene lighting model ===
    // Every layer (mountains, hills, trees, bushes) must agree on where the light is.
    // These three values are the single source of truth for that.

    /** Light elevation: 0 at the horizon, 1 at the zenith. True sin of the light angle. */
    val lightElevation01: Float = shadowSin.coerceIn(0f, 1f)

    /** Horizontal light direction: -1 = from the left (sunrise), +1 = from the right (sunset). */
    val lightDirX: Float = shadowCos

    /** Horizontal direction ground shadows fall in; always opposite the light. */
    val shadowDirX: Float = -shadowCos

    /**
     * Ground-shadow length as a multiple of object height: cot(elevation), the true
     * projection of an upright object. Clamped so shadows stay usable near the horizon.
     * Length is deliberately independent of shadow strength - strength only sets opacity.
     */
    fun shadowLengthFactor(): Float {
        val e = lightElevation01.coerceAtLeast(0.15f)
        val cosE = kotlin.math.sqrt((1f - e * e).coerceAtLeast(0f))
        return (cosE / e).coerceIn(0.3f, 4f)
    }

    /** Shared opacity for every ground shadow in the scene. */
    fun groundShadowAlpha(): Int =
        (20f + 130f * cachedEffectiveShadowStrength).toInt().coerceIn(0, 150)
    
    /**
     * Get shadow offset for an entity based on light source position.
     * Returns (dx, dy) offset to apply to shadow relative to object.
     * Uses precalculated sin/cos values for efficiency (called per-tree).
     * 
     * @param objectHeight Relative height of the object (affects shadow length)
     * @return Pair of (shadowOffsetX, shadowOffsetY) normalized -1 to 1
     */
    fun getShadowOffset(objectHeight: Float = 1f): Pair<Float, Float> {
        if (!shadowsEnabled) return Pair(0f, 0f)
        val shadowLength = shadowLengthFactor() * objectHeight
        return Pair(shadowDirX * shadowLength, shadowLength * 0.4f)
    }

    /**
     * Horizontal shadow offset only, allocation-free. Equivalent to `getShadowOffset(objectHeight).first`
     * but avoids the per-tree [Pair] boxing. Tree rendering only consumes the horizontal (dx) component.
     */
    fun getShadowDx(objectHeight: Float = 1f): Float {
        if (!shadowsEnabled) return 0f
        return shadowDirX * shadowLengthFactor() * objectHeight
    }
    
    /**
     * Get cloud count based on density setting.
     * Cloud counts map directly to number of cloud groups:
     * - NONE: 0 clouds
     * - LIGHT: 1-2 very fine, nearly transparent clouds
     * - MEDIUM: 4-6 clouds, some sky visible
     * - HEAVY: 12-18 clouds, significant coverage
     * - OVERCAST: 30-40 clouds, full sky coverage with overlap
     * 
     * CloudLayer uses cloud groups (each group is one visual cloud blob).
     * Groups have internal puffs controlled by fluffiness setting.
     * 
     * AUTO mode uses cloudCover data from weather API.
     * Only THUNDERSTORM forces storm clouds; rain/snow use cloudCover.
     */
    fun getCloudCount(): Int = when (cloudDensity) {
        CloudDensity.NONE -> 0
        CloudDensity.LIGHT -> 3         // 2-3 very fine clouds
        CloudDensity.MEDIUM -> 10       // Several clouds
        CloudDensity.HEAVY -> 25        // Significant coverage
        CloudDensity.OVERCAST -> 50     // Full sky coverage (increased from 35)
        CloudDensity.AUTO -> when (weather) {
            // Thunderstorm ALWAYS forces storm clouds (45+)
            WeatherType.THUNDERSTORM -> 45
            // Fog still needs full coverage for atmospheric effect
            WeatherType.FOG -> 50
            // Other weather types: use cloudCover, but enforce a weather-appropriate
            // minimum so missing/incomplete API cover data still looks correct.
            else -> cloudCoverToCloudCount(effectiveAutoCloudCover(weather, cloudCover))
        }
    }

    /**
     * Enforce minimum cloud cover based on weather type.
     * Weather APIs may report 0% cover even for CLOUDY/OVERCAST conditions
     * (missing data or incomplete report). This ensures the visual matches
     * the weather type regardless of API data quality.
     */
    internal fun effectiveAutoCloudCover(weather: WeatherType, cloudCover: Float): Float {
        val cover = cloudCover.coerceIn(0f, 1f)
        return when (weather) {
            WeatherType.OVERCAST -> cover.coerceAtLeast(0.90f)
            WeatherType.CLOUDY -> cover.coerceAtLeast(0.72f)
            WeatherType.PARTLY_CLOUDY -> cover.coerceAtLeast(0.35f)
            WeatherType.RAIN, WeatherType.SNOW, WeatherType.HAIL -> cover.coerceAtLeast(0.55f)
            else -> cover
        }
    }
    
    /**
     * Convert cloudCover percentage (0-1) to cloud count.
     * Maps weather API cloud cover data to visual cloud density.
     */
    private fun cloudCoverToCloudCount(cover: Float): Int {
        // Map 0-100% cloud cover to 0-50 clouds
        // Use non-linear mapping so low cover doesn't create too many clouds
        return when {
            cover < 0.1f -> (cover * 20).toInt().coerceIn(0, 2)      // 0-10% → 0-2 clouds
            cover < 0.3f -> (2 + (cover - 0.1f) * 30).toInt()        // 10-30% → 2-8 clouds
            cover < 0.6f -> (8 + (cover - 0.3f) * 47).toInt()        // 30-60% → 8-22 clouds
            cover < 0.85f -> (22 + (cover - 0.6f) * 92).toInt()      // 60-85% → 22-45 clouds
            else -> (45 + (cover - 0.85f) * 33.34f).toInt().coerceAtMost(50)  // 85-100% → 45-50 clouds
        }
    }
    
    companion object {
        /**
         * Create a default state for testing or initialization.
         */
        fun default(): SimulationState = SimulationState(
            timeOfDayHours = 12f,
            timeSeconds = 0f,
            absoluteTimeMs = System.currentTimeMillis(),
            isNight = false,
            isDawn = false,
            isDusk = false,
            dayProgress = 0.5f,
            sunriseHours = 6.0f,
            sunsetHours = 20.0f,
            isGoldenHour = false,
            isBlueHour = false,
            weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.AUTO,
            precipProbability = 0f,
            precipIntensity = 0f,
            cloudCover = 0f,
            humidity = 0.5f,
            visibility = 1f,
            pressure = 1013,
            moonPhase = 0.5f,  // Default to full moon
            moonIllumination = 0f,
            windStrength = 0.5f,
            windDirection = 0f,
            windGust = 0f,
            windMultiplier = 1f,
            treeSwaySpeed = 1f,
            screenWidth = 1080f,
            screenHeight = 1920f,
            snowLevel = 0f,
            riverLevel = 0f,
            temperatureCelsius = Float.NaN,
            seasonTransitionProgress = 1f,
            currentSeason = "summer",
            previousSeason = "summer",
            seasonTransitionDays = 14,
            isDebugMode = false
        )
    }
}

/**
 * Cloud density options matching preferences.
 */
enum class CloudDensity {
    NONE,
    LIGHT,
    MEDIUM,
    HEAVY,
    OVERCAST,
    AUTO
}
