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
     */
    fun getCloudCount(): Int = when (cloudDensity) {
        CloudDensity.NONE -> 0
        CloudDensity.LIGHT -> 4
        CloudDensity.MEDIUM -> 10
        CloudDensity.HEAVY -> 20
        CloudDensity.OVERCAST -> 35
        CloudDensity.AUTO -> when (weather) {
            WeatherType.CLEAR -> 2
            WeatherType.PARTLY_CLOUDY -> 8
            WeatherType.CLOUDY -> 15
            WeatherType.OVERCAST -> 35
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> 25
            WeatherType.SNOW -> 20
            WeatherType.FOG -> 30
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
