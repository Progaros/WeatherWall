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
    
    // Shadows & Lighting
    val shadowsEnabled: Boolean = true,      // Global shadow toggle
    val shadowStrength: Float = 0.5f,        // 0-1 shadow intensity from settings
    val cloudShadowsEnabled: Boolean = true, // Cloud shadows on terrain
    
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
     * Shadows are strongest during bright day and fade at night.
     * @return 0.0 = no shadow, 1.0 = full shadow intensity
     */
    fun getEffectiveShadowStrength(): Float {
        if (!shadowsEnabled) return 0f
        // Shadows scale with light level - no point in shadows at night
        return shadowStrength * getLightLevel()
    }
    
    /**
     * Get cloud count based on density setting.
     * Cloud counts are tuned so:
     * - LIGHT: Few clouds visible, mostly clear sky
     * - MEDIUM: Partial cloud cover, blue sky visible
     * - HEAVY: Significant cloud cover
     * - OVERCAST: Sky fully covered with clouds
     * 
     * Based on CloudLayer: clouds use 5-40% vertical, avg size 0.15-0.20.
     * For true overcast coverage, need significant overlap:
     * - Screen width in world coords = 9 units
     * - Avg cloud width = ~0.5-0.8 world units
     * - For full horizontal coverage = ~15 clouds per row
     * - Clouds at varying depths = 3-4 rows = 45-60 base
     * - For overlap/fullness = 2-3x = 120-180 clouds for overcast
     */
    fun getCloudCount(): Int = when (cloudDensity) {
        CloudDensity.NONE -> 0
        CloudDensity.LIGHT -> 15       // Few but visible
        CloudDensity.MEDIUM -> 35      // Moderate coverage
        CloudDensity.HEAVY -> 70       // Dense clouds
        CloudDensity.OVERCAST -> 120   // Full sky coverage
        CloudDensity.AUTO -> when (weather) {
            WeatherType.CLEAR -> 8
            WeatherType.PARTLY_CLOUDY -> 30
            WeatherType.CLOUDY -> 60
            WeatherType.OVERCAST -> 120
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> 90
            WeatherType.SNOW -> 70
            WeatherType.HAIL -> 85       // Similar to rain but slightly less
            WeatherType.FOG -> 100
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
