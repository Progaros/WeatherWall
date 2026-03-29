package info.olli.weatherlwp.simulation

import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.weather.WeatherType

/**
 * Immutable settings snapshot used by the render/simulation pipeline.
 *
 * A full preference snapshot is embedded so layers can read additional
 * keys without reaching back into SharedPreferences.
 */
data class SimulationSettings(
    // Time settings
    val useRealTime: Boolean = true,
    val manualTimeMinutes: Int = 720,

    // Weather settings
    val useRealWeather: Boolean = true,
    val manualWeather: WeatherType = WeatherType.CLEAR,

    // Wind settings
    val windStrength: Float = 0.5f,
    val useWeatherWind: Boolean = true,
    val windMultiplier: Float = 1.0f,
    val treeSwaySpeed: Float = 1.0f,

    // Cloud settings
    val cloudsEnabled: Boolean = true,
    val cloudDensity: CloudDensity = CloudDensity.AUTO,

    // Shadow settings
    val mountainShadowsEnabled: Boolean = true,
    val treeShadowsEnabled: Boolean = true,
    val shadowStrength: Float = 0.5f,

    // Night shading
    val nightShadeStrength: Float = 0.35f,

    // Moon phase (simulation mode)
    val moonPhase: Float = 0.5f,

    // Season transition
    val seasonTransitionDays: Int = PreferenceDefaults.SEASON_TRANSITION_DAYS,
    val terrainSeasonOverride: String = "auto",
    val seasonDayOfYearOverride: Int = 0,

    // Weather history overrides
    val useSimulatedWeatherHistory: Boolean = false,
    val simulatedSnowLevel: Float = 0f,
    val simulatedRiverLevel: Float = 0f,

    // Debug
    val debugEnabled: Boolean = false,
    val debugWeatherOverride: String? = null,

    // Raw immutable preference snapshot for rendering-layer settings
    val snapshot: Map<String, Any?> = emptyMap()
) {
    // Legacy compatibility
    val shadowsEnabled: Boolean
        get() = mountainShadowsEnabled || treeShadowsEnabled

    fun getEffectiveTimeMinutes(): Int = manualTimeMinutes

    /**
     * Get effective weather, considering debug overrides.
     */
    fun getEffectiveWeather(realWeather: WeatherType): WeatherType {
        val override = debugWeatherOverride
        if (debugEnabled && override != null) {
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
                    try {
                        WeatherType.valueOf(override.uppercase())
                    } catch (_: Exception) {
                        realWeather
                    }
                }
            }
        }
        return if (useRealWeather) realWeather else manualWeather
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        val value = snapshot[key]
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> default
        }
    }

    fun getInt(key: String, default: Int): Int {
        val value = snapshot[key]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    fun getLong(key: String, default: Long): Long {
        val value = snapshot[key]
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Float -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    fun getString(key: String, default: String): String {
        val value = snapshot[key]
        return when (value) {
            is String -> value
            else -> default
        }
    }

    fun getNullableString(key: String): String? {
        val value = snapshot[key]
        return value as? String
    }

    companion object {
        fun fromSnapshot(sourceSnapshot: Map<String, *>): SimulationSettings {
            val snapshot = HashMap<String, Any?>(sourceSnapshot.size)
            for ((key, value) in sourceSnapshot) {
                snapshot[key] = value
            }

            fun snapBoolean(key: String, default: Boolean): Boolean =
                when (val value = snapshot[key]) {
                    is Boolean -> value
                    is String -> value.equals("true", ignoreCase = true)
                    else -> default
                }

            fun snapInt(key: String, default: Int): Int =
                when (val value = snapshot[key]) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Float -> value.toInt()
                    is Double -> value.toInt()
                    is String -> value.toIntOrNull() ?: default
                    else -> default
                }

            fun snapString(key: String, default: String): String =
                (snapshot[key] as? String) ?: default

            val debugEnabled = snapBoolean("pref_debug_enabled", false)
            val useRealTime = if (debugEnabled) false else snapBoolean("pref_daynight_enabled", true)
            val manualTimeMinutes = snapInt("pref_manual_time", 720)

            val useRealWeather = true
            val manualWeather = WeatherType.CLEAR

            val windStrength: Float
            val useWeatherWind: Boolean
            if (debugEnabled) {
                windStrength = snapInt("pref_wind_strength", 50) / 100f
                useWeatherWind = false
            } else {
                windStrength = 0.05f
                useWeatherWind = true
            }

            val windMultiplier = snapInt("pref_wind_multiplier", PreferenceDefaults.WIND_MULTIPLIER) / 100f
            val treeSwaySpeed = snapInt("pref_tree_sway_speed", 100) / 100f

            val cloudsEnabled = snapBoolean("pref_clouds_enabled", true)
            var cloudDensity = if (!cloudsEnabled) CloudDensity.NONE else CloudDensity.AUTO

            val mountainShadowsEnabled = snapBoolean("pref_mountain_shadows_enabled", true)
            val treeShadowsEnabled = snapBoolean("pref_tree_shadows_enabled", true)
            val shadowStrength = snapInt("pref_shadow_strength", 50) / 100f

            val nightShadeStrength = snapInt("pref_night_shade_strength", 35) / 100f
            val moonPhase = snapInt("pref_moon_phase", 50) / 100f

            val seasonTransitionDays = snapInt("pref_season_transition_days", PreferenceDefaults.SEASON_TRANSITION_DAYS)
            val terrainSeasonOverride = if (debugEnabled) snapString("pref_terrain_season", "auto") else "auto"
            val seasonDayOfYearOverride = if (debugEnabled) snapInt("pref_season_day_override", 0) else 0

            val useSimulatedWeatherHistory: Boolean
            val simulatedSnowLevel: Float
            val simulatedRiverLevel: Float
            if (debugEnabled) {
                useSimulatedWeatherHistory = snapBoolean("pref_sim_weather_history", false)
                simulatedSnowLevel = snapInt("pref_sim_snow_level", 0) / 100f
                simulatedRiverLevel = snapInt("pref_sim_river_level", 0) / 100f
            } else {
                useSimulatedWeatherHistory = false
                simulatedSnowLevel = 0f
                simulatedRiverLevel = 0f
            }

            val debugWeatherOverride = if (debugEnabled) {
                snapString("pref_weather_type", "auto").takeUnless { it == "auto" }
            } else {
                null
            }

            if (debugEnabled) {
                val debugCloudStr = snapString("pref_debug_cloud_override", "auto")
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
            }

            return SimulationSettings(
                useRealTime = useRealTime,
                manualTimeMinutes = manualTimeMinutes,
                useRealWeather = useRealWeather,
                manualWeather = manualWeather,
                windStrength = windStrength,
                useWeatherWind = useWeatherWind,
                windMultiplier = windMultiplier,
                treeSwaySpeed = treeSwaySpeed,
                cloudsEnabled = cloudsEnabled,
                cloudDensity = cloudDensity,
                mountainShadowsEnabled = mountainShadowsEnabled,
                treeShadowsEnabled = treeShadowsEnabled,
                shadowStrength = shadowStrength,
                nightShadeStrength = nightShadeStrength,
                moonPhase = moonPhase,
                seasonTransitionDays = seasonTransitionDays,
                terrainSeasonOverride = terrainSeasonOverride,
                seasonDayOfYearOverride = seasonDayOfYearOverride,
                useSimulatedWeatherHistory = useSimulatedWeatherHistory,
                simulatedSnowLevel = simulatedSnowLevel,
                simulatedRiverLevel = simulatedRiverLevel,
                debugEnabled = debugEnabled,
                debugWeatherOverride = debugWeatherOverride,
                snapshot = snapshot
            )
        }
    }
}
