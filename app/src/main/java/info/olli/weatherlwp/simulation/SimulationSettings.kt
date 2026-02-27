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
    
    // Shadow settings - separate for mountains and trees
    var mountainShadowsEnabled: Boolean = true
        private set
    var treeShadowsEnabled: Boolean = true
        private set
    var shadowStrength: Float = 0.5f
        private set
    
    // Night shade strength (0-1: 0=bright, 0.35=default, 1=very dark)
    var nightShadeStrength: Float = 0.35f
        private set
    
    // Legacy - kept for compatibility but mapped to both settings
    val shadowsEnabled: Boolean
        get() = mountainShadowsEnabled || treeShadowsEnabled
    
    // Moon phase (for simulation mode)
    var moonPhase: Float = 0.5f  // 0-1: 0=new, 0.5=full, 1=new (cycle end)
        private set
    
    // Season transition
    var seasonTransitionDays: Int = 14  // 0=instant, 30=very gradual
        private set
    var terrainSeasonOverride: String = "auto"
        private set
    var seasonDayOfYearOverride: Int = 0  // 0=auto, 1-365=override day
        private set
    
    // Debug
    var debugEnabled: Boolean = false
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
            windStrength = 0.05f  // Default fallback if no weather data (5% reduces illusion of 50% speed)
            useWeatherWind = true  // Enable weather-based wind
        }
        
        // Wind multiplier for increasing/decreasing wind effect (0-300% range)
        val windMultVal = prefs.getInt("pref_wind_multiplier", info.olli.weatherlwp.PreferenceDefaults.WIND_MULTIPLIER)
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
        
        // Shadow settings - separate for mountains and trees
        mountainShadowsEnabled = prefs.getBoolean("pref_mountain_shadows_enabled", true)
        treeShadowsEnabled = prefs.getBoolean("pref_tree_shadows_enabled", true)
        val shadowVal = prefs.getInt("pref_shadow_strength", 50)
        shadowStrength = shadowVal / 100f
        
        // Night shade strength (0-100 -> 0.0-1.0, default 35 = 0.35)
        val nightShadeVal = prefs.getInt("pref_night_shade_strength", 35)
        nightShadeStrength = nightShadeVal / 100f
        
        // Moon phase - only used in simulation mode (0-100 -> 0.0-1.0)
        val moonPhaseVal = prefs.getInt("pref_moon_phase", 50)
        moonPhase = moonPhaseVal / 100f
        
        // Season transition speed (0-30 days, default 14)
        seasonTransitionDays = prefs.getInt("pref_season_transition_days", 
            info.olli.weatherlwp.PreferenceDefaults.SEASON_TRANSITION_DAYS)
        
        // Terrain season override
        terrainSeasonOverride = if (debugEnabled) {
            prefs.getString("pref_terrain_season", "auto") ?: "auto"
        } else {
            "auto"
        }
        
        // Day-of-year override (only in debug mode, 0=auto)
        seasonDayOfYearOverride = if (debugEnabled) {
            prefs.getInt("pref_season_day_override", 0)
        } else {
            0
        }
        
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
        } else {
            debugWeatherOverride = null
        }
    }
    
    /**
     * Get effective time in minutes.
     */
    fun getEffectiveTimeMinutes(): Int {
        return manualTimeMinutes
    }
    
    /**
     * Get effective weather, considering debug overrides.
     */
    fun getEffectiveWeather(realWeather: WeatherType): WeatherType {
        val override = debugWeatherOverride
        if (debugEnabled && override != null) {
            // Handle special cases for UI-friendly names
            return when (override.lowercase()) {
                "none", "clear" -> WeatherType.CLEAR
                "rain" -> WeatherType.RAIN
                "snow" -> WeatherType.SNOW
                "lightning", "thunderstorm" -> WeatherType.THUNDERSTORM
                "fog" -> WeatherType.FOG
                "hail" -> WeatherType.HAIL
                "cloudy" -> WeatherType.CLOUDY
                "overcast" -> WeatherType.OVERCAST
                "partly_cloudy" -> WeatherType.PARTLY_CLOUDY
                else -> {
                    // Try direct enum match as fallback
                    try {
                        WeatherType.valueOf(override.uppercase())
                    } catch (e: Exception) {
                        realWeather
                    }
                }
            }
        }
        return if (useRealWeather) realWeather else manualWeather
    }
}
