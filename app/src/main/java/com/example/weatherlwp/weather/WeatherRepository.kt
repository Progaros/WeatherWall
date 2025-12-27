package com.example.weatherlwp.weather

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
    
    fun updateWeather(context: Context, type: WeatherType, sunrise: Long, sunset: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(KEY_WEATHER_TYPE, type.name)
            .putLong(KEY_SUNRISE, sunrise)
            .putLong(KEY_SUNSET, sunset)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
        
        Log.d(TAG, "Weather updated: type=$type, sunrise=$sunrise, sunset=$sunset")
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
    
    fun getLastUpdate(context: Context): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }
    
    fun isDataFresh(context: Context, maxAgeMs: Long = 3600000L): Boolean {
        val lastUpdate = getLastUpdate(context)
        return (System.currentTimeMillis() - lastUpdate) < maxAgeMs
    }
}
