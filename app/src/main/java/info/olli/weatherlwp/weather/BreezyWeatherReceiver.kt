package info.olli.weatherlwp.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import info.olli.weatherlwp.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receives weather data from Breezy Weather via the Gadgetbridge intent.
 * 
 * To enable this in Breezy Weather:
 * 1. Open Breezy Weather app
 * 2. Go to Settings -> External Modules
 * 3. Enable "Send Gadgetbridge data" 
 * 4. Select "Broadcast to all" or "Gadgetbridge"
 */
class BreezyWeatherReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BreezyWeatherReceiver"
        private const val ACTION_WEATHER = "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"
        // Single-thread executor shared across broadcasts to avoid spawning a thread per intent
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BreezyWeatherWorker").apply { isDaemon = true }
        }

        // The receiver is exported without a permission, so every value below arrives from an
        // untrusted sender and is bounded before it can reach storage or the renderer.
        private const val MAX_PAYLOAD_CHARS = 64 * 1024
        private const val MAX_WIND_KMH = 500.0
        private const val MIN_TEMP_C = -120f
        private const val MAX_TEMP_C = 80f
        private const val MAX_EPOCH_SECONDS = 4_102_444_800L  // 2100-01-01

        /** Convert a temperature that may be in Kelvin or Celsius to Celsius. */
        fun kelvinOrCelsiusToC(value: Double): Float {
            return if (!value.isNaN() && value > 200) (value - 273.15).toFloat()
                   else if (!value.isNaN()) value.toFloat()
                   else Float.NaN
        }

        /**
         * Clamp to a physically plausible range. NaN and infinities become NaN, which callers
         * already treat as "unknown" — note that coerceIn alone would let NaN through unchanged.
         */
        fun sanitizeTemperature(celsius: Float): Float =
            if (celsius.isFinite()) celsius.coerceIn(MIN_TEMP_C, MAX_TEMP_C) else Float.NaN

        /** Wind in km/h to m/s, bounded; non-finite input yields calm. */
        fun sanitizeWindSpeedToMs(kmh: Double): Float =
            if (kmh.isFinite()) (kmh.coerceIn(0.0, MAX_WIND_KMH) / 3.6).toFloat() else 0f

        /** Epoch seconds; anything implausible collapses to the -1 "unknown" sentinel. */
        fun sanitizeEpochSeconds(seconds: Long): Long =
            if (seconds in 0..MAX_EPOCH_SECONDS) seconds else -1L
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")

        // Fail fast: ignore broadcasts that are not our weather action.
        if (intent.action != ACTION_WEATHER) return

        // Validate the payload on the caller thread before acquiring an async token
        // and dispatching to the worker. A missing or blank extra means there is
        // nothing to parse, so there is no reason to keep the receiver alive.
        val jsonString = intent.getStringExtra("WeatherJson")
        if (jsonString.isNullOrBlank()) {
            Log.w(TAG, "No WeatherJson in intent")
            return
        }

        // An oversized payload would be persisted into the default SharedPreferences file,
        // which is read in full on every process start.
        if (jsonString.length > MAX_PAYLOAD_CHARS) {
            Log.w(TAG, "WeatherJson too large (${jsonString.length} chars), ignoring")
            return
        }

        // Use goAsync() to avoid ANR - JSON parsing can take time.
        val pendingResult = goAsync()
        executor.execute {
            try {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Received weather JSON: ${jsonString.take(200)}...")
                }
                parseWeatherData(context, jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing weather data", e)
            } finally {
                // Must call finish() to release the BroadcastReceiver
                pendingResult.finish()
            }
        }
    }

    private fun parseWeatherData(context: Context, json: String) {
        try {
            val data = JSONObject(json)
            
            // Weather condition code (OpenWeatherMap style)
            // Breezy sends 'currentConditionCode' (Int) or 'currentCondition' (String)
            val conditionCode = data.optInt("currentConditionCode", 800)
            
            // Map to our internal types
            val weatherType = when (conditionCode) {
                in 200..232 -> WeatherType.THUNDERSTORM  // Thunderstorm
                in 300..321 -> WeatherType.RAIN          // Drizzle
                in 500..531 -> WeatherType.RAIN          // Rain
                in 600..610 -> WeatherType.SNOW          // Snow
                in 611..616 -> WeatherType.HAIL          // Sleet/Hail
                in 617..622 -> WeatherType.SNOW          // Snow shower variants
                in 701..781 -> WeatherType.FOG           // Atmosphere (fog, mist, etc.)
                800 -> WeatherType.CLEAR                 // Clear
                801 -> WeatherType.PARTLY_CLOUDY         // Few clouds (11-25%)
                802 -> WeatherType.PARTLY_CLOUDY         // Scattered clouds (25-50%)
                803 -> WeatherType.CLOUDY                // Broken clouds (51-84%)
                804 -> WeatherType.OVERCAST               // Overcast (85-100%)
                else -> WeatherType.CLEAR
            }
            
            // Sunrise/sunset (Unix timestamps in seconds)
            val sunrise = sanitizeEpochSeconds(data.optLong("sunRise", data.optLong("todaySunrise", -1)))
            val sunset = sanitizeEpochSeconds(data.optLong("sunSet", data.optLong("todaySunset", -1)))
            
            // Wind speed - Breezy Weather sends in km/h, we need m/s
            val windSpeedKmh = data.optDouble("currentWindSpeed", 
                data.optDouble("windSpeed", 0.0))
            val windSpeed = sanitizeWindSpeedToMs(windSpeedKmh)
            
            // Temperature data (Breezy Weather sends in Kelvin or Celsius depending on version)
            // Try currentTemp first, then temp, then temperature
            val tempK = data.optDouble("currentTemp", 
                data.optDouble("temp",
                    data.optDouble("temperature", Double.NaN)))
            val feelsLikeK = data.optDouble("currentFeelsLikeTemp",
                data.optDouble("feelsLikeTemp", Double.NaN))
            val dewPointK = data.optDouble("dewPoint", Double.NaN)
            
            // Breezy Weather via Gadgetbridge sends temperature in Kelvin
            // Convert to Celsius: K - 273.15
            val tempC = sanitizeTemperature(kelvinOrCelsiusToC(tempK))
            val feelsC = sanitizeTemperature(kelvinOrCelsiusToC(feelsLikeK))
            val dewC = sanitizeTemperature(kelvinOrCelsiusToC(dewPointK))
            
            // Extended weather data
            val extendedData = WeatherRepository.ExtendedWeatherData(
                precipProbability = data.optInt("precipProbability", 0).coerceIn(0, 100),
                cloudCover = data.optInt("cloudCover", 0).coerceIn(0, 100),
                humidity = data.optInt("currentHumidity", data.optInt("humidity", 0)).coerceIn(0, 100),
                visibility = data.optInt("visibility", 10000).coerceIn(0, 100_000),
                pressure = data.optInt("pressure", 1013).coerceIn(300, 1100),
                moonPhase = data.optInt("moonPhase", 0).coerceIn(0, 360),
                moonRise = sanitizeEpochSeconds(data.optLong("moonRise", -1L)),
                moonSet = sanitizeEpochSeconds(data.optLong("moonSet", -1L)),
                windDirection = ((data.optInt("windDirection", 0) % 360) + 360) % 360,
                temperatureCelsius = tempC,
                feelsLikeCelsius = feelsC,
                dewPointCelsius = dewC
            )
            
            // Store in repository (including raw JSON for debugging)
            WeatherRepository.updateWeather(context, weatherType, sunrise, sunset, windSpeed, json, extendedData)
            
            Log.i(TAG, "Weather updated: $weatherType (code=$conditionCode), wind=${windSpeed}m/s, " +
                "temp=${if (tempC.isNaN()) "?" else "%.1f°C".format(tempC)}, " +
                "precip=${extendedData.precipProbability}%, clouds=${extendedData.cloudCover}%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse weather JSON", e)
        }
    }
}
