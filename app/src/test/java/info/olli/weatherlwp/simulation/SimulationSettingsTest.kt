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

    // ============== Effective weather resolution ==============

    @Test
    fun `manual weather override applies without simulation mode`() {
        val settings = SimulationSettings.fromSnapshot(
            mapOf(
                "pref_debug_enabled" to false,
                "pref_weather_type" to "fog"
            )
        )
        assertEquals(WeatherType.FOG, settings.getEffectiveWeather(WeatherType.CLEAR))
    }

    @Test
    fun `auto weather type keeps real weather`() {
        val settings = SimulationSettings.fromSnapshot(
            mapOf("pref_weather_type" to "auto")
        )
        assertEquals(WeatherType.RAIN, settings.getEffectiveWeather(WeatherType.RAIN))
    }

    @Test
    fun `disabling breezy weather falls back to manual weather`() {
        val settings = SimulationSettings.fromSnapshot(
            mapOf(
                "pref_use_breezy_weather" to false,
                "pref_weather_type" to "auto"
            )
        )
        assertEquals(WeatherType.CLEAR, settings.getEffectiveWeather(WeatherType.RAIN))
    }

    /** Delegates to the production mapping so the test cannot drift from it. */
    private fun mapWeatherOverride(override: String, fallback: WeatherType): WeatherType =
        SimulationSettings.parseWeatherOverride(override) ?: fallback
}
