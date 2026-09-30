package info.olli.weatherlwp.simulation

import android.content.Context
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
    fun `scenes share one history owner`() {
        assertSame(WeatherHistory.shared(context), WeatherHistory.shared(context.applicationContext))
    }
}
