package info.olli.weatherlwp.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Repository for weather data from Breezy Weather via Gadgetbridge intent.
 * Stores weather data in SharedPreferences for persistence and uses an
 * in-memory cache for zero-allocation per-frame reads.
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
    private const val KEY_TEMPERATURE = "weather_temperature"  // Celsius * 10 (for 0.1° precision)
    private const val KEY_TEMPERATURE_FEELS = "weather_temperature_feels"  // Celsius * 10
    private const val KEY_DEW_POINT = "weather_dew_point"  // Celsius * 10
    
    // All weather-related keys for cache invalidation
    private val WEATHER_KEYS = setOf(
        KEY_WEATHER_TYPE, KEY_SUNRISE, KEY_SUNSET, KEY_LAST_UPDATE,
        KEY_WIND_SPEED, KEY_PRECIP_PROBABILITY, KEY_CLOUD_COVER, KEY_HUMIDITY,
        KEY_VISIBILITY, KEY_PRESSURE, KEY_MOON_PHASE, KEY_MOON_RISE,
        KEY_MOON_SET, KEY_WIND_DIRECTION, KEY_TEMPERATURE, KEY_TEMPERATURE_FEELS,
        KEY_DEW_POINT
    )
    
    // ============== In-Memory Cache ==============
    // Populated on first access, invalidated by SP listener on weather key changes.
    // Render thread reads cache (zero allocation), receiver thread writes SP + invalidates.
    
    @Volatile private var cachedWeatherType: WeatherType? = null
    @Volatile private var cachedSunrise: Long = -1L
    @Volatile private var cachedSunset: Long = -1L
    @Volatile private var cachedLastUpdate: Long = 0L
    @Volatile private var cachedWindSpeed: Float = 0f
    @Volatile private var cachedExtended: ExtendedWeatherData? = null
    @Volatile private var cacheInitialized = false
    
    // SharedPreferences listener to invalidate cache when weather data changes
    private val cacheInvalidationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in WEATHER_KEYS) {
            cacheInitialized = false  // Force re-read on next access
        }
    }
    @Volatile private var listenerRegistered = false
    
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
        val windDirection: Int = 0,         // 0-360 degrees
        val temperatureCelsius: Float = Float.NaN,  // Current temp in °C (NaN = unknown)
        val feelsLikeCelsius: Float = Float.NaN,    // Feels-like temp in °C (NaN = unknown)
        val dewPointCelsius: Float = Float.NaN      // Dew point in °C (NaN = unknown)
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
        ensureListenerRegistered(context)
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
            // Temperature stored as Celsius * 10 for 0.1° precision in Int storage
            if (!extendedData.temperatureCelsius.isNaN()) {
                editor.putInt(KEY_TEMPERATURE, (extendedData.temperatureCelsius * 10).toInt())
            }
            if (!extendedData.feelsLikeCelsius.isNaN()) {
                editor.putInt(KEY_TEMPERATURE_FEELS, (extendedData.feelsLikeCelsius * 10).toInt())
            }
            if (!extendedData.dewPointCelsius.isNaN()) {
                editor.putInt(KEY_DEW_POINT, (extendedData.dewPointCelsius * 10).toInt())
            }
        }
        
        editor.apply()
        
        // Directly populate cache from the values we just wrote (avoid re-reading SP)
        cachedWeatherType = type
        cachedSunrise = sunrise
        cachedSunset = sunset
        cachedWindSpeed = windSpeed
        cachedLastUpdate = System.currentTimeMillis()
        if (extendedData != null) {
            cachedExtended = extendedData
        }
        cacheInitialized = true
        
        Log.d(TAG, "Weather updated: type=$type, sunrise=$sunrise, sunset=$sunset, wind=$windSpeed m/s" +
            (extendedData?.let { ", precip=${it.precipProbability}%, clouds=${it.cloudCover}%" } ?: ""))
    }
    
    /**
     * Ensure the cache invalidation listener is registered.
     * Called lazily on first access to avoid lifecycle issues.
     */
    private fun ensureListenerRegistered(context: Context) {
        if (!listenerRegistered) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.registerOnSharedPreferenceChangeListener(cacheInvalidationListener)
            listenerRegistered = true
        }
    }
    
    /**
     * Populate cache from SharedPreferences if not already valid.
     */
    private fun ensureCachePopulated(context: Context) {
        if (cacheInitialized) return
        ensureListenerRegistered(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        val typeName = prefs.getString(KEY_WEATHER_TYPE, WeatherType.CLEAR.name) ?: WeatherType.CLEAR.name
        cachedWeatherType = try { WeatherType.valueOf(typeName) } catch (_: Exception) { WeatherType.CLEAR }
        cachedSunrise = prefs.getLong(KEY_SUNRISE, -1L)
        cachedSunset = prefs.getLong(KEY_SUNSET, -1L)
        cachedLastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        cachedWindSpeed = prefs.getFloat(KEY_WIND_SPEED, 0f)
        
        // Build extended data from SP
        val storedDirection = prefs.getInt(KEY_WIND_DIRECTION, -1)
        val windDirection = if (storedDirection >= 0) storedDirection else getDailyWindDirection()
        val tempRaw = prefs.getInt(KEY_TEMPERATURE, Int.MIN_VALUE)
        val feelsRaw = prefs.getInt(KEY_TEMPERATURE_FEELS, Int.MIN_VALUE)
        val dewRaw = prefs.getInt(KEY_DEW_POINT, Int.MIN_VALUE)
        
        cachedExtended = ExtendedWeatherData(
            precipProbability = prefs.getInt(KEY_PRECIP_PROBABILITY, 0),
            cloudCover = prefs.getInt(KEY_CLOUD_COVER, 0),
            humidity = prefs.getInt(KEY_HUMIDITY, 0),
            visibility = prefs.getInt(KEY_VISIBILITY, 10000),
            pressure = prefs.getInt(KEY_PRESSURE, 1013),
            moonPhase = prefs.getInt(KEY_MOON_PHASE, 180),
            moonRise = prefs.getLong(KEY_MOON_RISE, -1L),
            moonSet = prefs.getLong(KEY_MOON_SET, -1L),
            windDirection = windDirection,
            temperatureCelsius = if (tempRaw != Int.MIN_VALUE) tempRaw / 10f else Float.NaN,
            feelsLikeCelsius = if (feelsRaw != Int.MIN_VALUE) feelsRaw / 10f else Float.NaN,
            dewPointCelsius = if (dewRaw != Int.MIN_VALUE) dewRaw / 10f else Float.NaN
        )
        
        cacheInitialized = true
    }
    
    fun getWeatherType(context: Context): WeatherType {
        ensureCachePopulated(context)
        return cachedWeatherType ?: WeatherType.CLEAR
    }
    
    fun getSunrise(context: Context): Long {
        ensureCachePopulated(context)
        return cachedSunrise
    }
    
    fun getSunset(context: Context): Long {
        ensureCachePopulated(context)
        return cachedSunset
    }
    
    /**
     * Get wind speed in m/s (0-50+ typical range).
     * Returns 0 if no wind data available.
     */
    fun getWindSpeed(context: Context): Float {
        ensureCachePopulated(context)
        return cachedWindSpeed
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
        ensureCachePopulated(context)
        return cachedLastUpdate
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
    // These all read from the in-memory cache for zero-allocation per-frame access.
    
    /**
     * Get precipitation probability (0-100%).
     * Used to determine rain/snow intensity.
     */
    fun getPrecipProbability(context: Context): Int {
        ensureCachePopulated(context)
        return cachedExtended?.precipProbability ?: 0
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
        ensureCachePopulated(context)
        return cachedExtended?.cloudCover ?: 0
    }
    
    /**
     * Get humidity percentage (0-100%).
     */
    fun getHumidity(context: Context): Int {
        ensureCachePopulated(context)
        return cachedExtended?.humidity ?: 0
    }
    
    /**
     * Get visibility in meters.
     */
    fun getVisibility(context: Context): Int {
        ensureCachePopulated(context)
        return cachedExtended?.visibility ?: 10000
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
        ensureCachePopulated(context)
        return cachedExtended?.pressure ?: 1013
    }
    
    /**
     * Get moon phase in degrees (0-360).
     * 0 = new moon, 90 = first quarter, 180 = full moon, 270 = last quarter
     */
    fun getMoonPhase(context: Context): Int {
        ensureCachePopulated(context)
        return cachedExtended?.moonPhase ?: 180
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
        ensureCachePopulated(context)
        return cachedExtended?.moonRise ?: -1L
    }
    
    /**
     * Get moon set time (Unix timestamp in seconds).
     */
    fun getMoonSet(context: Context): Long {
        ensureCachePopulated(context)
        return cachedExtended?.moonSet ?: -1L
    }
    
    /**
     * Get wind direction in degrees (0-360, 0=North, 90=East, etc.)
     * If no weather data, returns a deterministic "random" direction based on day.
     */
    fun getWindDirection(context: Context): Int {
        ensureCachePopulated(context)
        return cachedExtended?.windDirection ?: getDailyWindDirection()
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
     * Returns cached data for zero-allocation per-frame access.
     */
    fun getExtendedData(context: Context): ExtendedWeatherData {
        ensureCachePopulated(context)
        return cachedExtended ?: ExtendedWeatherData()
    }
}
