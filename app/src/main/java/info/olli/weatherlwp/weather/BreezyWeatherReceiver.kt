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
            val sunrise = data.optLong("sunRise", data.optLong("todaySunrise", -1))
            val sunset = data.optLong("sunSet", data.optLong("todaySunset", -1))
            
            // Wind speed - Breezy Weather sends in km/h, we need m/s
            // Convert km/h to m/s by dividing by 3.6
            val windSpeedKmh = data.optDouble("currentWindSpeed", 
                data.optDouble("windSpeed", 0.0)).toFloat()
            val windSpeed = windSpeedKmh / 3.6f
            
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
            val tempC = if (!tempK.isNaN() && tempK > 100) (tempK - 273.15).toFloat()
                       else if (!tempK.isNaN()) tempK.toFloat()  // Already Celsius
                       else Float.NaN
            val feelsC = if (!feelsLikeK.isNaN() && feelsLikeK > 100) (feelsLikeK - 273.15).toFloat()
                        else if (!feelsLikeK.isNaN()) feelsLikeK.toFloat()
                        else Float.NaN
            val dewC = if (!dewPointK.isNaN() && dewPointK > 100) (dewPointK - 273.15).toFloat()
                      else if (!dewPointK.isNaN()) dewPointK.toFloat()
                      else Float.NaN
            
            // Extended weather data
            val extendedData = WeatherRepository.ExtendedWeatherData(
                precipProbability = data.optInt("precipProbability", 0),
                cloudCover = data.optInt("cloudCover", 0),
                humidity = data.optInt("currentHumidity", data.optInt("humidity", 0)),
                visibility = data.optInt("visibility", 10000),
                pressure = data.optInt("pressure", 1013),
                moonPhase = data.optInt("moonPhase", 0),
                moonRise = data.optLong("moonRise", -1L),
                moonSet = data.optLong("moonSet", -1L),
                windDirection = data.optInt("windDirection", 0),
                temperatureCelsius = tempC,
                feelsLikeCelsius = feelsC,
                dewPointCelsius = dewC
            )
            
            // Store in repository (including raw JSON for debugging)
            WeatherRepository.updateWeather(context, weatherType, sunrise, sunset, windSpeed, json, extendedData)
            
            Log.i(TAG, "Weather updated: $weatherType (code=$conditionCode), wind=${windSpeedKmh}km/h (${windSpeed}m/s), " +
                "temp=${if (tempC.isNaN()) "?" else "%.1f°C".format(tempC)}, " +
                "precip=${extendedData.precipProbability}%, clouds=${extendedData.cloudCover}%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse weather JSON", e)
        }
    }
}
