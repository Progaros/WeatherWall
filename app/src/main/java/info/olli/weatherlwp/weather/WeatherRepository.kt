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
    
    // Extended weather data
    private const val KEY_PRECIP_PROBABILITY = "weather_precip_probability"  // 0-100%
    private const val KEY_CLOUD_COVER = "weather_cloud_cover"  // 0-100%
    private const val KEY_HUMIDITY = "weather_humidity"  // 0-100%
    private const val KEY_VISIBILITY = "weather_visibility"  // meters
    private const val KEY_PRESSURE = "weather_pressure"  // hPa
    private const val KEY_MOON_PHASE = "weather_moon_phase"  // 0-360 degrees
    private const val KEY_MOON_RISE = "weather_moon_rise"  // Unix timestamp
    private const val KEY_MOON_SET = "weather_moon_set"  // Unix timestamp
    private const val KEY_WIND_DIRECTION = "weather_wind_direction"  // 0-360 degrees
    
    /**
     * Extended weather data from Breezy Weather.
     */
    data class ExtendedWeatherData(
        val precipProbability: Int = 0,     // 0-100%
        val cloudCover: Int = 0,            // 0-100%
        val humidity: Int = 0,              // 0-100%
        val visibility: Int = 0,            // meters
        val pressure: Int = 1013,           // hPa
        val moonPhase: Int = 180,           // 0-360 degrees (0=new, 180=full) - default full
        val moonRise: Long = -1L,           // Unix timestamp
        val moonSet: Long = -1L,            // Unix timestamp
        val windDirection: Int = 0          // 0-360 degrees
    )
    
    fun updateWeather(
        context: Context,
        type: WeatherType,
        sunrise: Long,
        sunset: Long,
        windSpeed: Float = 0f,
        rawJson: String? = null,
        extendedData: ExtendedWeatherData? = null
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
            .putString(KEY_WEATHER_TYPE, type.name)
            .putLong(KEY_SUNRISE, sunrise)
            .putLong(KEY_SUNSET, sunset)
            .putFloat(KEY_WIND_SPEED, windSpeed)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
        
        if (rawJson != null) {
            editor.putString(KEY_RAW_JSON, rawJson)
        }
        
        // Store extended data if provided
        if (extendedData != null) {
            editor.putInt(KEY_PRECIP_PROBABILITY, extendedData.precipProbability)
            editor.putInt(KEY_CLOUD_COVER, extendedData.cloudCover)
            editor.putInt(KEY_HUMIDITY, extendedData.humidity)
            editor.putInt(KEY_VISIBILITY, extendedData.visibility)
            editor.putInt(KEY_PRESSURE, extendedData.pressure)
            editor.putInt(KEY_MOON_PHASE, extendedData.moonPhase)
            editor.putLong(KEY_MOON_RISE, extendedData.moonRise)
            editor.putLong(KEY_MOON_SET, extendedData.moonSet)
            editor.putInt(KEY_WIND_DIRECTION, extendedData.windDirection)
        }
        
        editor.apply()
        
        Log.d(TAG, "Weather updated: type=$type, sunrise=$sunrise, sunset=$sunset, wind=$windSpeed m/s" +
            (extendedData?.let { ", precip=${it.precipProbability}%, clouds=${it.cloudCover}%" } ?: ""))
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
    
    // ============== Extended Weather Data Getters ==============
    
    /**
     * Get precipitation probability (0-100%).
     * Used to determine rain/snow intensity.
     */
    fun getPrecipProbability(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_PRECIP_PROBABILITY, 0)
    }
    
    /**
     * Get normalized precipitation intensity (0.0 to 1.0).
     * Combines precipitation probability with weather type for effect intensity.
     */
    fun getNormalizedPrecipIntensity(context: Context): Float {
        val prob = getPrecipProbability(context)
        return (prob / 100f).coerceIn(0f, 1f)
    }
    
    /**
     * Get cloud cover percentage (0-100%).
     */
    fun getCloudCover(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_CLOUD_COVER, 0)
    }
    
    /**
     * Get humidity percentage (0-100%).
     */
    fun getHumidity(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_HUMIDITY, 0)
    }
    
    /**
     * Get visibility in meters.
     */
    fun getVisibility(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_VISIBILITY, 10000)
    }
    
    /**
     * Get normalized visibility (0.0 = very poor, 1.0 = excellent).
     * Maps 0-10km to 0-1 range.
     */
    fun getNormalizedVisibility(context: Context): Float {
        val vis = getVisibility(context)
        return (vis / 10000f).coerceIn(0f, 1f)
    }
    
    /**
     * Get atmospheric pressure in hPa.
     */
    fun getPressure(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_PRESSURE, 1013)
    }
    
    /**
     * Get moon phase in degrees (0-360).
     * 0 = new moon, 90 = first quarter, 180 = full moon, 270 = last quarter
     */
    fun getMoonPhase(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(KEY_MOON_PHASE, 180)
    }
    
    /**
     * Get normalized moon illumination (0.0 = new moon, 1.0 = full moon).
     */
    fun getMoonIllumination(context: Context): Float {
        val phase = getMoonPhase(context)
        // Moon illumination peaks at 180 degrees (full moon)
        // cos(phase) gives -1 at 180, we invert and normalize to 0-1
        return ((1 - kotlin.math.cos(Math.toRadians(phase.toDouble()))) / 2f).toFloat()
    }
    
    /**
     * Get moon rise time (Unix timestamp in seconds).
     */
    fun getMoonRise(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_MOON_RISE, -1L)
    }
    
    /**
     * Get moon set time (Unix timestamp in seconds).
     */
    fun getMoonSet(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_MOON_SET, -1L)
    }
    
    /**
     * Get wind direction in degrees (0-360, 0=North, 90=East, etc.)
     * If no weather data, returns a deterministic "random" direction based on day.
     */
    fun getWindDirection(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val storedDirection = prefs.getInt(KEY_WIND_DIRECTION, -1)  // -1 means not set
        if (storedDirection >= 0) {
            return storedDirection
        }
        // Generate deterministic wind direction based on current day
        // Changes once per day, consistent across all instances
        return getDailyWindDirection()
    }
    
    /**
     * Get a deterministic wind direction that changes once per day.
     * Based on day number since epoch, so all instances see same direction.
     */
    private fun getDailyWindDirection(): Int {
        val dayNumber = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()
        // Use a simple hash to create pseudo-random but deterministic direction
        val seed = dayNumber * 1234567 + 987654
        return (seed % 360 + 360) % 360  // Ensure positive 0-359
    }
    
    /**
     * Get all extended weather data as a data class.
     */
    fun getExtendedData(context: Context): ExtendedWeatherData {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Use daily wind direction if not set in weather data
        val storedDirection = prefs.getInt(KEY_WIND_DIRECTION, -1)
        val windDirection = if (storedDirection >= 0) storedDirection else getDailyWindDirection()
        
        return ExtendedWeatherData(
            precipProbability = prefs.getInt(KEY_PRECIP_PROBABILITY, 0),
            cloudCover = prefs.getInt(KEY_CLOUD_COVER, 0),
            humidity = prefs.getInt(KEY_HUMIDITY, 0),
            visibility = prefs.getInt(KEY_VISIBILITY, 10000),
            pressure = prefs.getInt(KEY_PRESSURE, 1013),
            moonPhase = prefs.getInt(KEY_MOON_PHASE, 180),
            moonRise = prefs.getLong(KEY_MOON_RISE, -1L),
            moonSet = prefs.getLong(KEY_MOON_SET, -1L),
            windDirection = windDirection
        )
    }
}
