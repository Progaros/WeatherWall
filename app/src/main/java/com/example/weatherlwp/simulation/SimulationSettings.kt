package com.example.weatherlwp.simulation

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.example.weatherlwp.weather.WeatherType

/**
 * Manages simulation settings from SharedPreferences.
 * 
 * This class reads user preferences and debug overrides,
 * providing a clean API for the SimulationController.
 */
class SimulationSettings(context: Context) {
    
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    // Time settings
    var useRealTime: Boolean = true
        private set
    var manualTimeMinutes: Int = 720
        private set
    
    // Weather settings
    var useRealWeather: Boolean = true
        private set
    var manualWeather: WeatherType = WeatherType.CLEAR
        private set
    
    // Wind settings
    var windStrength: Float = 0.5f
        private set
    var useWeatherWind: Boolean = true
        private set
    
    // Cloud settings
    var cloudDensity: CloudDensity = CloudDensity.AUTO
        private set
    
    // Debug
    var debugEnabled: Boolean = false
        private set
    var debugTimeOverride: String? = null
        private set
    var debugWeatherOverride: String? = null
        private set
    
    init {
        reload()
    }
    
    /**
     * Reload all settings from SharedPreferences.
     */
    fun reload() {
        // Time
        useRealTime = prefs.getBoolean("pref_daynight_enabled", true)
        manualTimeMinutes = prefs.getInt("pref_manual_time", 720)
        
        // Weather - for now we always use real weather from Breezy
        useRealWeather = true
        
        // Wind - read from settings (0-100 -> 0.0-1.0)
        val windVal = prefs.getInt("pref_wind_strength", 50)
        windStrength = windVal / 100f
        
        // Cloud density
        val densityStr = prefs.getString("pref_cloud_density", "auto") ?: "auto"
        cloudDensity = when (densityStr.lowercase()) {
            "none" -> CloudDensity.NONE
            "light" -> CloudDensity.LIGHT
            "medium" -> CloudDensity.MEDIUM
            "heavy" -> CloudDensity.HEAVY
            "overcast" -> CloudDensity.OVERCAST
            else -> CloudDensity.AUTO
        }
        
        // Debug
        debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        debugTimeOverride = if (debugEnabled) {
            prefs.getString("pref_debug_time", null)
        } else null
        debugWeatherOverride = if (debugEnabled) {
            prefs.getString("pref_debug_weather", null)
        } else null
    }
    
    /**
     * Get effective time in minutes, considering debug overrides.
     */
    fun getEffectiveTimeMinutes(): Int {
        if (debugEnabled && debugTimeOverride != null) {
            return when (debugTimeOverride) {
                "night" -> 0
                "dawn" -> 6 * 60
                "day" -> 12 * 60
                "dusk" -> 19 * 60
                else -> manualTimeMinutes
            }
        }
        return manualTimeMinutes
    }
    
    /**
     * Get effective weather, considering debug overrides.
     */
    fun getEffectiveWeather(realWeather: WeatherType): WeatherType {
        if (debugEnabled && debugWeatherOverride != null) {
            return try {
                WeatherType.valueOf(debugWeatherOverride!!.uppercase())
            } catch (e: Exception) {
                realWeather
            }
        }
        return if (useRealWeather) realWeather else manualWeather
    }
}
