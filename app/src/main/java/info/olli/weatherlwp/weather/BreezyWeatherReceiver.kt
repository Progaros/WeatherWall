package info.olli.weatherlwp.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

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
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
        if (intent.action == ACTION_WEATHER) {
            // Use goAsync() to avoid ANR - JSON parsing can take time
            val pendingResult = goAsync()
            
            Thread {
                try {
                    // Data is sent as JSON string
                    val jsonString = intent.getStringExtra("WeatherJson")
                    if (jsonString != null) {
                        Log.d(TAG, "Received weather JSON: ${jsonString.take(200)}...")
                        parseWeatherData(context, jsonString)
                    } else {
                        Log.w(TAG, "No WeatherJson in intent")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing weather data", e)
                } finally {
                    // Must call finish() to release the BroadcastReceiver
                    pendingResult.finish()
                }
            }.start()
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
                in 600..622 -> WeatherType.SNOW          // Snow
                in 701..781 -> WeatherType.FOG           // Atmosphere (fog, mist, etc.)
                800 -> WeatherType.CLEAR                 // Clear
                801 -> WeatherType.CLEAR                 // Few clouds
                in 802..804 -> WeatherType.CLOUDY        // Clouds
                else -> WeatherType.CLEAR
            }
            
            // Sunrise/sunset (Unix timestamps in seconds)
            val sunrise = data.optLong("todaySunrise", -1)
            val sunset = data.optLong("todaySunset", -1)
            
            // Wind speed in m/s (Breezy sends currentWindSpeed or windSpeed)
            val windSpeed = data.optDouble("currentWindSpeed", 
                data.optDouble("windSpeed", 0.0)).toFloat()
            
            // Store in repository
            WeatherRepository.updateWeather(context, weatherType, sunrise, sunset, windSpeed)
            
            Log.i(TAG, "Weather updated: $weatherType (code=$conditionCode), wind=$windSpeed m/s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse weather JSON", e)
        }
    }
}
