package com.example.weatherlwp.simulation

import com.example.weatherlwp.weather.WeatherType

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
     * - LIGHT: Always some visible clouds on screen, but sparse and lighter
     * - MEDIUM: Comfortable amount of clouds
     * - HEAVY: Dense cloud cover
     * - OVERCAST: Sky mostly covered
     */
    fun getCloudCount(): Int = when (cloudDensity) {
        CloudDensity.NONE -> 0
        CloudDensity.LIGHT -> 15       // Few but always visible
        CloudDensity.MEDIUM -> 35      // Moderate coverage
        CloudDensity.HEAVY -> 60       // Dense clouds
        CloudDensity.OVERCAST -> 100   // Full coverage
        CloudDensity.AUTO -> when (weather) {
            WeatherType.CLEAR -> 8
            WeatherType.PARTLY_CLOUDY -> 25
            WeatherType.CLOUDY -> 45
            WeatherType.OVERCAST -> 100
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> 70
            WeatherType.SNOW -> 50
            WeatherType.FOG -> 80
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
