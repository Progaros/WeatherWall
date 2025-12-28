package com.example.weatherlwp.weather

/**
 * Interface for weather data providers.
 * 
 * Implementations can provide weather from:
 * - Breezy Weather app via Gadgetbridge intent
 * - Simulated/mock data for testing
 * - Other weather APIs in the future
 */
interface WeatherProvider {
    
    /**
     * Get current weather conditions.
     */
    fun getWeatherType(): WeatherType
    
    /**
     * Get wind speed in m/s.
     */
    fun getWindSpeed(): Float
    
    /**
     * Get normalized wind strength (0.0 to 1.0).
     */
    fun getNormalizedWindStrength(): Float
    
    /**
     * Get sunrise time (epoch ms) or -1 if unknown.
     */
    fun getSunrise(): Long
    
    /**
     * Get sunset time (epoch ms) or -1 if unknown.
     */
    fun getSunset(): Long
    
    /**
     * Check if weather data is fresh (within maxAgeMs).
     */
    fun isDataFresh(maxAgeMs: Long = 3600000L): Boolean
    
    /**
     * Get timestamp of last weather update.
     */
    fun getLastUpdate(): Long
}
