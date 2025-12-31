package info.olli.weatherlwp.weather

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Repository for weather data from Breezy Weather via Gadgetbridge intent.
 * Stores weather data in SharedPreferences for access by the wallpaper.
 */
object WeatherRepository {
    private const val TAG = "WeatherRepository"
    
    private const val KEY_WEATHER_TYPE = "weather_current_type"
    private const val KEY_SUNRISE = "weather_sunrise"
    private const val KEY_SUNSET = "weather_sunset"
    private const val KEY_LAST_UPDATE = "weather_last_update"
    private const val KEY_WIND_SPEED = "weather_wind_speed"  // Wind speed in m/s
    private const val KEY_RAW_JSON = "weather_raw_json"  // Full JSON for debugging
    
    fun updateWeather(context: Context, type: WeatherType, sunrise: Long, sunset: Long, windSpeed: Float = 0f, rawJson: String? = null) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(KEY_WEATHER_TYPE, type.name)
            .putLong(KEY_SUNRISE, sunrise)
            .putLong(KEY_SUNSET, sunset)
            .putFloat(KEY_WIND_SPEED, windSpeed)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply {
                if (rawJson != null) {
                    putString(KEY_RAW_JSON, rawJson)
                }
            }
            .apply()
        
        Log.d(TAG, "Weather updated: type=$type, sunrise=$sunrise, sunset=$sunset, wind=$windSpeed m/s")
    }
    
    fun getWeatherType(context: Context): WeatherType {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val typeName = prefs.getString(KEY_WEATHER_TYPE, WeatherType.CLEAR.name) ?: WeatherType.CLEAR.name
        return try {
            WeatherType.valueOf(typeName)
        } catch (e: Exception) {
            WeatherType.CLEAR
        }
    }
    
    fun getSunrise(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_SUNRISE, -1L)
    }
    
    fun getSunset(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_SUNSET, -1L)
    }
    
    /**
     * Get wind speed in m/s (0-50+ typical range).
     * Returns 0 if no wind data available.
     */
    fun getWindSpeed(context: Context): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getFloat(KEY_WIND_SPEED, 0f)
    }
    
    /**
     * Get normalized wind strength (0.0 to 1.0).
     * Maps 0-20 m/s to 0-1 range (20+ m/s is strong wind/storm).
     */
    fun getNormalizedWindStrength(context: Context): Float {
        val speed = getWindSpeed(context)
        return (speed / 20f).coerceIn(0f, 1f)
    }
    
    fun getLastUpdate(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }
    
    fun isDataFresh(context: Context, maxAgeMs: Long = 3600000L): Boolean {
        val lastUpdate = getLastUpdate(context)
        return (System.currentTimeMillis() - lastUpdate) < maxAgeMs
    }
    
    /**
     * Get raw JSON data from last Breezy Weather update.
     * Useful for debugging and checking available data fields.
     */
    fun getRawJson(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_RAW_JSON, null)
    }
}
