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
    val moonPhase: Float = 0f,          // 0-1 (0=new, 0.5=full, 1=new again)
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
    
    // Legacy computed property for backward compatibility
    val shadowsEnabled: Boolean = mountainShadowsEnabled || treeShadowsEnabled,
    
    // Screen
    val screenWidth: Float,
    val screenHeight: Float,
    
    // Debug/Override flags
    val isDebugMode: Boolean
) {
    /**
     * Get wind X component (-1 to 1).
     */
    fun getWindX(): Float = windStrength * kotlin.math.cos(Math.toRadians(windDirection.toDouble())).toFloat()
    
    /**
     * Get wind Y component (-1 to 1).
     */
    fun getWindY(): Float = windStrength * kotlin.math.sin(Math.toRadians(windDirection.toDouble())).toFloat()
    
    /**
     * Get ambient light level (0.0 = deep night, 1.0 = bright day).
     * Used to adjust colors of fog, rain, and other weather effects.
     * 
     * Timeline:
     * - 0:00-5:00: Night (0.15)
     * - 5:00-7:00: Dawn transition (0.15 -> 1.0)
     * - 7:00-17:00: Day (1.0)
     * - 17:00-20:00: Dusk transition (1.0 -> 0.15)
     * - 20:00-24:00: Night (0.15)
     */
    fun getLightLevel(): Float {
        val hour = timeOfDayHours
        return when {
            hour < 5f -> 0.15f  // Night
            hour < 7f -> 0.15f + (hour - 5f) / 2f * 0.85f  // Dawn transition
            hour < 17f -> 1f  // Day
            hour < 20f -> 1f - (hour - 17f) / 3f * 0.85f  // Dusk transition  
            else -> 0.15f  // Night
        }
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
    fun getLightSourceAngle(): Float {
        val hour = timeOfDayHours
        return when {
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
    
    /**
     * Get shadow offset for an entity based on light source position.
     * Returns (dx, dy) offset to apply to shadow relative to object.
     * 
     * @param objectHeight Relative height of the object (affects shadow length)
     * @return Pair of (shadowOffsetX, shadowOffsetY) normalized -1 to 1
     */
    fun getShadowOffset(objectHeight: Float = 1f): Pair<Float, Float> {
        if (!shadowsEnabled) return Pair(0f, 0f)
        
        val angle = getLightSourceAngle()
        val strength = getEffectiveShadowStrength()
        
        // Shadow length based on light angle (longer shadows at sunrise/sunset)
        val lightHeight = kotlin.math.sin(angle).coerceIn(0.1f, 1f)
        // Dramatically longer shadows at low sun angles for visibility
        val shadowLength = (1.5f / lightHeight).coerceIn(0.5f, 4f) * objectHeight * strength
        
        // Shadow direction is opposite to light source (dx determines left/right)
        val dx = -kotlin.math.cos(angle) * shadowLength
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
     */
    fun getCloudCount(): Int = when (cloudDensity) {
        CloudDensity.NONE -> 0
        CloudDensity.LIGHT -> 3         // 2-3 very fine clouds
        CloudDensity.MEDIUM -> 10       // Several clouds
        CloudDensity.HEAVY -> 25        // Significant coverage
        CloudDensity.OVERCAST -> 50     // Full sky coverage (increased from 35)
        CloudDensity.AUTO -> when (weather) {
            WeatherType.CLEAR -> 2              // Maybe one wispy cloud
            WeatherType.PARTLY_CLOUDY -> 8      // A few clouds
            WeatherType.CLOUDY -> 22            // Good coverage
            WeatherType.OVERCAST -> 50          // Full coverage (increased from 35)
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> 45  // Near full coverage
            WeatherType.SNOW -> 30              // Moderate-heavy
            WeatherType.HAIL -> 40              // Heavy
            WeatherType.FOG -> 50               // Full coverage
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
            weather = WeatherType.CLEAR,
            cloudDensity = CloudDensity.AUTO,
            precipProbability = 0f,
            precipIntensity = 0f,
            cloudCover = 0f,
            humidity = 0.5f,
            visibility = 1f,
            pressure = 1013,
            moonPhase = 0f,
            moonIllumination = 0f,
            windStrength = 0.5f,
            windDirection = 0f,
            windGust = 0f,
            windMultiplier = 1f,
            treeSwaySpeed = 1f,
            screenWidth = 1080f,
            screenHeight = 1920f,
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
