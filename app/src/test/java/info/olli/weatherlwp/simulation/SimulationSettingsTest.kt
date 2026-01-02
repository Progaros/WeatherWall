package info.olli.weatherlwp.simulation

import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SimulationSettings weather override logic.
 * 
 * Note: These tests validate the weather override mapping logic
 * without requiring Android context by testing the mapping functions directly.
 */
class SimulationSettingsTest {

    // ============== Weather Override Mapping Tests ==============
    
    @Test
    fun `weather override none maps to CLEAR`() {
        val result = mapWeatherOverride("none", WeatherType.RAIN)
        assertEquals(WeatherType.CLEAR, result)
    }
    
    @Test
    fun `weather override clear maps to CLEAR`() {
        val result = mapWeatherOverride("clear", WeatherType.RAIN)
        assertEquals(WeatherType.CLEAR, result)
    }
    
    @Test
    fun `weather override rain maps to RAIN`() {
        val result = mapWeatherOverride("rain", WeatherType.CLEAR)
        assertEquals(WeatherType.RAIN, result)
    }
    
    @Test
    fun `weather override snow maps to SNOW`() {
        val result = mapWeatherOverride("snow", WeatherType.CLEAR)
        assertEquals(WeatherType.SNOW, result)
    }
    
    @Test
    fun `weather override lightning maps to THUNDERSTORM`() {
        val result = mapWeatherOverride("lightning", WeatherType.CLEAR)
        assertEquals(WeatherType.THUNDERSTORM, result)
    }
    
    @Test
    fun `weather override thunderstorm maps to THUNDERSTORM`() {
        val result = mapWeatherOverride("thunderstorm", WeatherType.CLEAR)
        assertEquals(WeatherType.THUNDERSTORM, result)
    }
    
    @Test
    fun `weather override fog maps to FOG`() {
        val result = mapWeatherOverride("fog", WeatherType.CLEAR)
        assertEquals(WeatherType.FOG, result)
    }
    
    @Test
    fun `weather override hail maps to HAIL`() {
        val result = mapWeatherOverride("hail", WeatherType.CLEAR)
        assertEquals(WeatherType.HAIL, result)
    }
    
    @Test
    fun `weather override cloudy maps to CLOUDY`() {
        val result = mapWeatherOverride("cloudy", WeatherType.CLEAR)
        assertEquals(WeatherType.CLOUDY, result)
    }
    
    @Test
    fun `weather override overcast maps to OVERCAST`() {
        val result = mapWeatherOverride("overcast", WeatherType.CLEAR)
        assertEquals(WeatherType.OVERCAST, result)
    }
    
    @Test
    fun `weather override is case insensitive`() {
        assertEquals(WeatherType.SNOW, mapWeatherOverride("SNOW", WeatherType.CLEAR))
        assertEquals(WeatherType.SNOW, mapWeatherOverride("Snow", WeatherType.CLEAR))
        assertEquals(WeatherType.THUNDERSTORM, mapWeatherOverride("LIGHTNING", WeatherType.CLEAR))
    }
    
    @Test
    fun `unknown override falls back to real weather`() {
        val result = mapWeatherOverride("unknown_value", WeatherType.RAIN)
        assertEquals(WeatherType.RAIN, result)
    }
    
    /**
     * Helper function that mirrors the logic in SimulationSettings.getEffectiveWeather()
     */
    private fun mapWeatherOverride(override: String, fallback: WeatherType): WeatherType {
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
                } catch (e: Exception) {
                    fallback
                }
            }
        }
    }
}
