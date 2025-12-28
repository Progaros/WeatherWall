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
    
    // Weather
    val weather: WeatherType,
    val cloudDensity: CloudDensity,
    
    // Wind
    val windStrength: Float,            // 0-1 normalized
    val windDirection: Float,           // Degrees (0 = right, 90 = up)
    
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
        CloudDensity.MEDIUM -> 40      // Moderate coverage
        CloudDensity.HEAVY -> 80       // Dense clouds
        CloudDensity.OVERCAST -> 150   // Full sky coverage
        CloudDensity.AUTO -> when (weather) {
            WeatherType.CLEAR -> 8
            WeatherType.PARTLY_CLOUDY -> 30
            WeatherType.CLOUDY -> 60
            WeatherType.OVERCAST -> 150
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> 100
            WeatherType.SNOW -> 70
            WeatherType.FOG -> 120
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
            windStrength = 0.5f,
            windDirection = 0f,
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
