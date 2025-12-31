package info.olli.weatherlwp.simulation

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.weather.WeatherType

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
    var windMultiplier: Float = 1.0f
        private set
    var treeSwaySpeed: Float = 1.0f
        private set
    
    // Cloud settings
    var cloudsEnabled: Boolean = true
        private set
    var cloudDensity: CloudDensity = CloudDensity.AUTO
        private set
    
    // Shadow settings
    var shadowsEnabled: Boolean = true
        private set
    var shadowStrength: Float = 0.5f
        private set
    var cloudShadowsEnabled: Boolean = true
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
        // Debug mode toggle
        debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        
        // Time - in debug mode, use manual time; otherwise check auto day/night
        if (debugEnabled) {
            useRealTime = false  // Debug mode always uses manual time
        } else {
            useRealTime = prefs.getBoolean("pref_daynight_enabled", true)
        }
        manualTimeMinutes = prefs.getInt("pref_manual_time", 720)
        
        // Weather - for now we always use real weather from Breezy
        useRealWeather = true
        
        // Wind - in debug/simulation mode, use manual setting; otherwise use real weather data
        if (debugEnabled) {
            val windVal = prefs.getInt("pref_wind_strength", 50)
            windStrength = windVal / 100f
            useWeatherWind = false  // Simulation mode uses manual wind
        } else {
            // Use real weather wind data when available
            windStrength = 0.5f  // Default fallback if no weather data
            useWeatherWind = true  // Enable weather-based wind
        }
        
        // Wind multiplier for increasing/decreasing wind effect (0-300% range)
        val windMultVal = prefs.getInt("pref_wind_multiplier", 100)
        windMultiplier = windMultVal / 100f
        
        // Tree sway speed (0-200% range, default 100%)
        val swaySpeedVal = prefs.getInt("pref_tree_sway_speed", 100)
        treeSwaySpeed = swaySpeedVal / 100f
        
        // Cloud settings
        // Note: pref_cloud_density is now an Int (0-50) handled directly by CloudLayer.
        // We keep cloudDensity as AUTO here since CloudLayer reads the actual count.
        cloudsEnabled = prefs.getBoolean("pref_clouds_enabled", true)
        cloudDensity = if (!cloudsEnabled) {
            CloudDensity.NONE
        } else {
            CloudDensity.AUTO
        }
        
        // Shadow settings
        shadowsEnabled = prefs.getBoolean("pref_shadows_enabled", true)
        val shadowVal = prefs.getInt("pref_shadow_strength", 50)
        shadowStrength = shadowVal / 100f
        cloudShadowsEnabled = prefs.getBoolean("pref_cloud_shadows", true)
        
        // Debug - also handle cloud and weather overrides from simulation mode
        if (debugEnabled) {
            // Weather override - pref_weather_type is the actual key
            debugWeatherOverride = prefs.getString("pref_weather_type", "auto")
            if (debugWeatherOverride == "auto") debugWeatherOverride = null
            
            // Cloud override - pref_debug_cloud_override is the actual key
            val debugCloudStr = prefs.getString("pref_debug_cloud_override", "auto") ?: "auto"
            if (debugCloudStr != "auto") {
                cloudDensity = when (debugCloudStr.lowercase()) {
                    "none" -> CloudDensity.NONE
                    "light" -> CloudDensity.LIGHT
                    "medium" -> CloudDensity.MEDIUM
                    "heavy" -> CloudDensity.HEAVY
                    "overcast" -> CloudDensity.OVERCAST
                    else -> cloudDensity
                }
            }
            
            debugTimeOverride = prefs.getString("pref_debug_time", null)
        } else {
            debugTimeOverride = null
            debugWeatherOverride = null
        }
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
