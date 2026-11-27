package info.olli.weatherlwp.weather

import android.content.Context
import android.os.Looper
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherRepositoryStorageTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val defaults = PreferenceManager.getDefaultSharedPreferences(context)
    private val weatherPrefs = context.getSharedPreferences("weather_data", Context.MODE_PRIVATE)

    @Test
    fun `weather update does not change settings snapshot`() {
        defaults.edit().putBoolean("pref_debug_enabled", true).commit()
        val repository = SimulationSettingsRepository(context)
        val before = repository.settingsFlow.value
        val beforeAll = defaults.all

        WeatherRepository.updateWeather(
            context, WeatherType.RAIN, 1_718_424_000L, 1_718_474_400L, 5f, rawJson = "{\"x\":1}"
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(beforeAll, defaults.all)
        assertEquals(before, SimulationSettings.fromSnapshot(defaults.all))
        assertEquals(before, repository.settingsFlow.value)
        assertTrue(weatherPrefs.contains("weather_raw_json"))
        repository.close()
    }

    @Test
    fun `legacy weather keys migrate out of default prefs`() {
        defaults.edit()
            .putString("weather_current_type", WeatherType.SNOW.name)
            .putLong("weather_sunrise", 1_718_424_000L)
            .putString("weather_raw_json", "{\"legacy\":true}")
            .putBoolean("pref_debug_enabled", true)
            .commit()

        assertEquals("{\"legacy\":true}", WeatherRepository.getRawJson(context))
        assertEquals(WeatherType.SNOW, WeatherRepository.getWeatherType(context))
        assertEquals(1_718_424_000L, WeatherRepository.getSunrise(context))
        assertFalse(defaults.contains("weather_current_type"))
        assertFalse(defaults.contains("weather_sunrise"))
        assertFalse(defaults.contains("weather_raw_json"))
        assertTrue(defaults.contains("pref_debug_enabled"))
        assertEquals(1_718_424_000L, weatherPrefs.getLong("weather_sunrise", -1L))
    }

    @Test
    fun `updateWeather clears stale temperature on NaN and stores explicit latitude`() {
        WeatherRepository.updateWeather(
            context, WeatherType.CLOUDY, 1_718_424_000L, 1_718_474_400L, 3f,
            extendedData = WeatherRepository.ExtendedWeatherData(temperatureCelsius = 12.5f),
            latitude = 48.14
        )
        assertEquals(12.5f, WeatherRepository.getExtendedData(context).temperatureCelsius, 0.1f)
        assertEquals(48.14, WeatherRepository.getInferredLatitude(context)!!, 0.01)

        WeatherRepository.updateWeather(
            context, WeatherType.CLEAR, 1_718_424_000L, 1_718_474_400L, 1f,
            extendedData = WeatherRepository.ExtendedWeatherData(temperatureCelsius = Float.NaN)
        )
        assertFalse(weatherPrefs.contains("weather_temperature"))
        assertTrue(WeatherRepository.getExtendedData(context).temperatureCelsius.isNaN())
    }
}
