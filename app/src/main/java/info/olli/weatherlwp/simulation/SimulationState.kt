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
    fun getLightLevel(): Float {
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
     * Shadows are strongest during bright day, and at night scale with moon illumination.
     * Note: Callers should check mountainShadowsEnabled or treeShadowsEnabled first.
     * @return 0.0 = no shadow, 1.0 = full shadow intensity
     */
    fun getEffectiveShadowStrength(): Float {
        // During day, shadows scale with light level
        // At night, shadows scale with moon illumination (full moon casts shadows, new moon doesn't)
        val lightFactor = if (isNight) {
            moonIllumination * 0.4f  // Moon shadows are subtler than sun shadows
        } else {
            getLightLevel()
        }
        return shadowStrength * lightFactor
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
    private val lightSourceAngle: Float = run {
        val hour = timeOfDayHours
        when {
            // Day: 6am-6pm, sun arc from left to right
            hour in 6f..18f -> {
                val dayProgress = (hour - 6f) / 12f  // 0 to 1
                // Start at PI (left), arc through PI/2 (top), end at 0 (right)
                kotlin.math.PI.toFloat() * (1f - dayProgress)
            }
            // Night: moon arc similar to sun
            else -> {
                val nightProgress = if (hour >= 18f) {
                    (hour - 18f) / 12f  // 6pm to midnight = 0 to 0.5
                } else {
                    (hour + 6f) / 12f  // midnight to 6am = 0.5 to 1
                }
                kotlin.math.PI.toFloat() * (1f - nightProgress)
            }
        }
    }
    
    // Pre-calculated shadow direction values (avoids per-tree sin/cos)
    private val shadowCos: Float = kotlin.math.cos(lightSourceAngle)
    private val shadowSin: Float = kotlin.math.sin(lightSourceAngle)
    
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
        
        val strength = getEffectiveShadowStrength()
        
        // Shadow length based on light angle (longer shadows at sunrise/sunset)
        val lightHeight = shadowSin.coerceIn(0.1f, 1f)
        // Dramatically longer shadows at low sun angles for visibility
        val shadowLength = (1.5f / lightHeight).coerceIn(0.5f, 4f) * objectHeight * strength
        
        // Shadow direction is opposite to light source (dx determines left/right)
        val dx = -shadowCos * shadowLength
        // Shadow extends along ground (positive dy = downward on screen = extending along ground plane)
        val dy = shadowLength * 0.4f  // Shadow projects forward onto ground
        
        return Pair(dx, dy)
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
            // All other weather types: use cloudCover data from Breezy Weather
            // This includes RAIN, SNOW, HAIL - they don't automatically create clouds
            else -> cloudCoverToCloudCount(cloudCover)
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
