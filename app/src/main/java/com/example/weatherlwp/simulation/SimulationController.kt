package com.example.weatherlwp.simulation

import android.content.Context
import com.example.weatherlwp.weather.WeatherRepository
import com.example.weatherlwp.weather.WeatherType

/**
 * Main controller for the simulation.
 * 
 * Coordinates the clock, settings, and weather data to produce
 * a consistent SimulationState that layers can use for rendering.
 */
class SimulationController(private val context: Context) {
    
    private val clock = SimulationClock()
    private val settings = SimulationSettings(context)
    
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    
    /**
     * Reload settings from preferences.
     */
    fun reloadSettings() {
        settings.reload()
        
        // Update clock with settings
        clock.setUseRealTime(settings.useRealTime)
        clock.setManualTime(settings.getEffectiveTimeMinutes())
    }
    
    /**
     * Update screen dimensions.
     */
    fun resize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }
    
    /**
     * Get current simulation state.
     * This is called every frame to get the latest state.
     */
    fun getState(): SimulationState {
        // Get real weather from repository
        val realWeather = WeatherRepository.getWeatherType(context)
        val effectiveWeather = settings.getEffectiveWeather(realWeather)
        
        // Get wind - combine settings with weather
        val weatherWindStrength = WeatherRepository.getNormalizedWindStrength(context)
        val effectiveWind = if (settings.useWeatherWind && WeatherRepository.isDataFresh(context)) {
            // Blend settings wind with weather wind
            (settings.windStrength + weatherWindStrength) / 2f
        } else {
            settings.windStrength
        }
        
        return SimulationState(
            timeOfDayHours = clock.getTimeOfDayHours(),
            timeSeconds = clock.getTimeSeconds(),
            absoluteTimeMs = clock.getAbsoluteTimeMs(),
            isNight = clock.isNight(),
            isDawn = clock.isDawn(),
            isDusk = clock.isDusk(),
            dayProgress = clock.getDayProgress(),
            weather = effectiveWeather,
            cloudDensity = settings.cloudDensity,
            windStrength = effectiveWind.coerceIn(0f, 1f),
            windDirection = 0f,  // Default: wind blows right
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isDebugMode = settings.debugEnabled
        )
    }
    
    /**
     * Get the clock for direct time queries.
     */
    fun getClock(): SimulationClock = clock
    
    /**
     * Get the settings for direct access.
     */
    fun getSettings(): SimulationSettings = settings
}
