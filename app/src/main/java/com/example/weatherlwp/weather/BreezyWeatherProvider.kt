package com.example.weatherlwp.weather

import android.content.Context

/**
 * Weather provider that reads from WeatherRepository.
 * 
 * WeatherRepository is populated by BreezyWeatherReceiver when
 * the Gadgetbridge intent arrives from Breezy Weather app.
 */
class BreezyWeatherProvider(private val context: Context) : WeatherProvider {
    
    override fun getWeatherType(): WeatherType {
        return WeatherRepository.getWeatherType(context)
    }
    
    override fun getWindSpeed(): Float {
        return WeatherRepository.getWindSpeed(context)
    }
    
    override fun getNormalizedWindStrength(): Float {
        return WeatherRepository.getNormalizedWindStrength(context)
    }
    
    override fun getSunrise(): Long {
        return WeatherRepository.getSunrise(context)
    }
    
    override fun getSunset(): Long {
        return WeatherRepository.getSunset(context)
    }
    
    override fun isDataFresh(maxAgeMs: Long): Boolean {
        return WeatherRepository.isDataFresh(context, maxAgeMs)
    }
    
    override fun getLastUpdate(): Long {
        return WeatherRepository.getLastUpdate(context)
    }
}
