package info.olli.weatherlwp.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.simulation.Astronomy

/**
 * Repository for weather data from Breezy Weather via Gadgetbridge intent.
 * Stores weather data in SharedPreferences for persistence and uses an
 * in-memory cache for zero-allocation per-frame reads.
 */
object WeatherRepository {
    private const val TAG = "WeatherRepository"
    private const val PREFS_NAME = "weather_data"
    
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
    // Latitude implied by provider sunrise/sunset; lets the sky project day length when data goes stale
    private const val KEY_INFERRED_LATITUDE = "weather_inferred_latitude"
    
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
    // Single immutable snapshot ensures atomic reads — no torn state across fields.
    
    private data class WeatherCache(
        val weatherType: WeatherType = WeatherType.CLEAR,
        val sunrise: Long = -1L,
        val sunset: Long = -1L,
        val lastUpdate: Long = 0L,
        val windSpeed: Float = 0f,
        val extended: ExtendedWeatherData = ExtendedWeatherData()
    )
    
    @Volatile private var cached: WeatherCache? = null
    
    // SharedPreferences listener to invalidate cache when weather data changes
    private val cacheInvalidationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in WEATHER_KEYS) {
            cached = null  // Force re-read on next access
        }
    }

    private class Binding(val app: Context, val prefs: SharedPreferences)
    @Volatile private var binding: Binding? = null
    
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
        prefs(context).edit {
            putString(KEY_WEATHER_TYPE, type.name)
            putLong(KEY_SUNRISE, sunrise)
            putLong(KEY_SUNSET, sunset)
            putFloat(KEY_WIND_SPEED, windSpeed)
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            if (sunrise > 0 && sunset > 0) {
                Astronomy.locationFromSunTimes(sunrise * 1000L, sunset * 1000L)?.first?.let {
                    putFloat(KEY_INFERRED_LATITUDE, it.toFloat())
                }
            }

            if (rawJson != null) {
                putString(KEY_RAW_JSON, rawJson)
            }

            // Store extended data if provided
            if (extendedData != null) {
                putInt(KEY_PRECIP_PROBABILITY, extendedData.precipProbability)
                putInt(KEY_CLOUD_COVER, extendedData.cloudCover)
                putInt(KEY_HUMIDITY, extendedData.humidity)
                putInt(KEY_VISIBILITY, extendedData.visibility)
                putInt(KEY_PRESSURE, extendedData.pressure)
                putInt(KEY_MOON_PHASE, extendedData.moonPhase)
                putLong(KEY_MOON_RISE, extendedData.moonRise)
                putLong(KEY_MOON_SET, extendedData.moonSet)
                putInt(KEY_WIND_DIRECTION, extendedData.windDirection)
                // Temperature stored as Celsius * 10 for 0.1° precision in Int storage
                if (!extendedData.temperatureCelsius.isNaN()) {
                    putInt(KEY_TEMPERATURE, (extendedData.temperatureCelsius * 10).toInt())
                }
                if (!extendedData.feelsLikeCelsius.isNaN()) {
                    putInt(KEY_TEMPERATURE_FEELS, (extendedData.feelsLikeCelsius * 10).toInt())
                }
                if (!extendedData.dewPointCelsius.isNaN()) {
                    putInt(KEY_DEW_POINT, (extendedData.dewPointCelsius * 10).toInt())
                }
            }
        }
        
        // Atomically populate cache from the values we just wrote (avoid re-reading SP)
        cached = WeatherCache(
            weatherType = type,
            sunrise = sunrise,
            sunset = sunset,
            windSpeed = windSpeed,
            lastUpdate = System.currentTimeMillis(),
            extended = extendedData ?: cached?.extended ?: ExtendedWeatherData()
        )
        
        Log.d(TAG, "Weather updated: type=$type, sunrise=$sunrise, sunset=$sunset, wind=$windSpeed m/s" +
            (extendedData?.let { ", precip=${it.precipProbability}%, clouds=${it.cloudCover}%" } ?: ""))
    }
    
    /**
     * Weather data lives outside the default prefs so updates never look like settings changes.
     * First access per application migrates legacy keys and registers the cache listener.
     */
    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        binding?.takeIf { it.app === app }?.let { return it.prefs }
        synchronized(this) {
            binding?.takeIf { it.app === app }?.let { return it.prefs }
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            migrateLegacyKeys(PreferenceManager.getDefaultSharedPreferences(app), prefs)
            binding?.prefs?.unregisterOnSharedPreferenceChangeListener(cacheInvalidationListener)
            prefs.registerOnSharedPreferenceChangeListener(cacheInvalidationListener)
            binding = Binding(app, prefs)
            cached = null
            return prefs
        }
    }

    private fun migrateLegacyKeys(legacy: SharedPreferences, target: SharedPreferences) {
        val old = legacy.all.filterKeys { it in WEATHER_KEYS || it == KEY_RAW_JSON }
        if (old.isEmpty()) return
        target.edit(commit = true) {
            for ((key, value) in old) {
                if (target.contains(key)) continue
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
        legacy.edit { old.keys.forEach { remove(it) } }
    }
    
    /**
     * Populate cache from SharedPreferences if not already valid.
     * Uses synchronized double-checked locking to prevent torn reads.
     */
    private fun ensureCachePopulated(context: Context) {
        if (cached != null) return
        val prefs = prefs(context)
        synchronized(this) {
            if (cached != null) return
            
            val typeName = prefs.getString(KEY_WEATHER_TYPE, WeatherType.CLEAR.name) ?: WeatherType.CLEAR.name
            val weatherType = try { WeatherType.valueOf(typeName) } catch (_: Exception) { WeatherType.CLEAR }
            val sunrise = prefs.getLong(KEY_SUNRISE, -1L)
            val sunset = prefs.getLong(KEY_SUNSET, -1L)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
            val windSpeed = prefs.getFloat(KEY_WIND_SPEED, 0f)
            
            // Build extended data from SP
            val storedDirection = prefs.getInt(KEY_WIND_DIRECTION, -1)
            val windDirection = if (storedDirection >= 0) storedDirection else getDailyWindDirection()
            val tempRaw = prefs.getInt(KEY_TEMPERATURE, Int.MIN_VALUE)
            val feelsRaw = prefs.getInt(KEY_TEMPERATURE_FEELS, Int.MIN_VALUE)
            val dewRaw = prefs.getInt(KEY_DEW_POINT, Int.MIN_VALUE)
            
            val extended = ExtendedWeatherData(
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
            
            cached = WeatherCache(
                weatherType = weatherType,
                sunrise = sunrise,
                sunset = sunset,
                lastUpdate = lastUpdate,
                windSpeed = windSpeed,
                extended = extended
            )
        }
    }
    
    fun getWeatherType(context: Context): WeatherType {
        ensureCachePopulated(context)
        val snapshot = cached ?: return WeatherType.CLEAR
        return snapshot.weatherType
    }
    
    fun getSunrise(context: Context): Long {
        ensureCachePopulated(context)
        val snapshot = cached ?: return -1L
        return snapshot.sunrise
    }
    
    fun getSunset(context: Context): Long {
        ensureCachePopulated(context)
        val snapshot = cached ?: return -1L
        return snapshot.sunset
    }
    
    /**
     * Get wind speed in m/s (0-50+ typical range).
     * Returns 0 if no wind data available.
     */
    fun getWindSpeed(context: Context): Float {
        ensureCachePopulated(context)
        val snapshot = cached ?: return 0f
        return snapshot.windSpeed
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
        val snapshot = cached ?: return 0L
        return snapshot.lastUpdate
    }
    
    fun isDataFresh(context: Context, maxAgeMs: Long = 3600000L): Boolean {
        val lastUpdate = getLastUpdate(context)
        return (System.currentTimeMillis() - lastUpdate) < maxAgeMs
    }
    
    /**
     * Get raw JSON data from last Breezy Weather update.
     * Useful for debugging and checking available data fields.
     */
    fun getRawJson(context: Context): String? = prefs(context).getString(KEY_RAW_JSON, null)
    
    // ============== Extended Weather Data Getters ==============
    // These all read from the in-memory cache for zero-allocation per-frame access.
    
    /**
     * Get precipitation probability (0-100%).
     * Used to determine rain/snow intensity.
     */
    fun getPrecipProbability(context: Context): Int {
        ensureCachePopulated(context)
        val snapshot = cached ?: return 0
        return snapshot.extended.precipProbability
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
        val snapshot = cached ?: return 0
        return snapshot.extended.cloudCover
    }
    
    /**
     * Get humidity percentage (0-100%).
     */
    fun getHumidity(context: Context): Int {
        ensureCachePopulated(context)
        val snapshot = cached ?: return 0
        return snapshot.extended.humidity
    }
    
    /**
     * Get visibility in meters.
     */
    fun getVisibility(context: Context): Int {
        ensureCachePopulated(context)
        val snapshot = cached ?: return 10000
        return snapshot.extended.visibility
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
        val snapshot = cached ?: return 1013
        return snapshot.extended.pressure
    }
    
    /**
     * Latitude implied by the provider's last sunrise/sunset outside the equinox weeks, or null.
     * Read rarely (sun times refresh hourly), so it bypasses the frame cache.
     */
    fun getInferredLatitude(context: Context): Double? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_INFERRED_LATITUDE)) prefs.getFloat(KEY_INFERRED_LATITUDE, 0f).toDouble() else null
    }
    
    /**
     * Get wind direction in degrees (0-360, 0=North, 90=East, etc.)
     * If no weather data, returns a deterministic "random" direction based on day.
     */
    fun getWindDirection(context: Context): Int {
        ensureCachePopulated(context)
        val snapshot = cached ?: return getDailyWindDirection()
        return snapshot.extended.windDirection
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
        val snapshot = cached ?: return ExtendedWeatherData()
        return snapshot.extended
    }
}
