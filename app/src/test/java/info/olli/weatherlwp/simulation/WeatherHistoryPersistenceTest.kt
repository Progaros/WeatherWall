package info.olli.weatherlwp.simulation

import android.content.Context
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherHistoryPersistenceTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val prefs = context.getSharedPreferences("weather_history", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `hourly levels and partial day survive recreation`() {
        val original = WeatherHistory(context)
        original.recordCurrentWeather(WeatherType.SNOW, 1f)
        val recorded = original.getSnapshot()

        val restored = WeatherHistory(context).getSnapshot()

        assertEquals(0.06f, recorded.snowDepth, 0.0001f)
        assertEquals(recorded.snowDepth, restored.snowDepth, 0f)
        assertEquals(recorded.riverLevel, restored.riverLevel, 0f)
        assertEquals(1f, restored.totalSnowToday, 0f)
    }

    @Test
    fun `restored partial day is flushed on the next day`() {
        prefs.edit()
            .putFloat("snow_depth", 0f)
            .putInt("today_day_id", 1)
            .putFloat("today_rain_hours", 2.5f)
            .putInt("last_record_hour", 3)
            .putLong("last_physics_update_ms", System.currentTimeMillis())
            .commit()

        val history = WeatherHistory(context)
        history.recordCurrentWeather(WeatherType.CLEAR, 0f)

        val flushed = history.getHistory().single()
        assertEquals(1, flushed.dayId)
        assertEquals(2.5f, flushed.rainHours, 0f)
    }

    @Test
    fun `restart after midnight flushes yesterday and applies pending catch-up`() {
        val cal = java.util.Calendar.getInstance()
        val today = ((cal.timeInMillis + cal.get(java.util.Calendar.ZONE_OFFSET) +
            cal.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L).toInt()
        WeatherRepository.updateWeather(
            context, WeatherType.SNOW, -1L, -1L,
            extendedData = WeatherRepository.ExtendedWeatherData(temperatureCelsius = -5f)
        )
        prefs.edit()
            .putFloat("snow_depth", 0.5f)
            .putFloat("river_level", 0f)
            .putInt("today_day_id", today - 1)
            .putFloat("today_snow_hours", 3f)
            .putInt("last_record_hour", 22)
            .putLong("last_physics_update_ms", System.currentTimeMillis() - 4 * 3_600_000L - 60_000L)
            .putString("last_weather_type", WeatherType.SNOW.name)
            .putFloat("last_temp_celsius", -5f)
            .putFloat("last_precip_prob", 1f)
            .commit()

        val history = WeatherHistory(context)
        history.recordCurrentWeather(WeatherType.SNOW, 1f)

        val yesterday = history.getHistory().single()
        assertEquals(today - 1, yesterday.dayId)
        assertEquals(3f, yesterday.snowHours, 0f)
        // Four catch-up hours plus the current hour of snow at -5 °C
        assertEquals(0.8f, history.getSnapshot().snowDepth, 0.0001f)
        assertEquals(1f, history.getSnapshot().totalSnowToday, 0f)

        val restored = WeatherHistory(context)
        assertEquals(history.getSnapshot(), restored.getSnapshot())
        assertEquals(history.getHistory(), restored.getHistory())
    }

    @Test
    fun `scenes share one history owner`() {
        assertSame(WeatherHistory.shared(context), WeatherHistory.shared(context.applicationContext))
    }
}
