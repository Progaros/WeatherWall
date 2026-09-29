package info.olli.weatherlwp

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.rendering.ParallaxScene
import info.olli.weatherlwp.settings.SettingsResetManager
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Regenerates docs/screenshots/Screenshot_N.png through the real ParallaxScene with the app's
 * default settings. Skipped unless run with -Dreadme.screenshots=true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReadmeScreenshotTest {

    private data class Shot(
        val file: String,
        val minutes: Int,
        val weather: WeatherType,
        val cloudCover: Int,
        val precip: Int,
        val season: String,
        val extraPrefs: Map<String, Any> = emptyMap()
    )

    private val shots = listOf(
        Shot("Screenshot_1.png", 5 * 60 + 50, WeatherType.CLOUDY, 70, 0, "spring"),          // Dawn clouds
        Shot("Screenshot_2.png", 13 * 60, WeatherType.PARTLY_CLOUDY, 20, 0, "summer"),       // Clear day
        Shot("Screenshot_3.png", 11 * 60, WeatherType.SNOW, 90, 90, "winter", mapOf(         // Snowy day
            "pref_sim_weather_history" to true,
            "pref_sim_snow_level" to 80
        )),
        Shot("Screenshot_4.png", 19 * 60 + 40, WeatherType.RAIN, 85, 85, "autumn"),          // Rainy dusk
        Shot("Screenshot_5.png", 23 * 60, WeatherType.CLEAR, 5, 0, "summer", mapOf(         // Night sky
            "pref_moon_phase" to 40
        ))
    )

    @Test
    fun `render README screenshots`() {
        assumeTrue("pass -Dreadme.screenshots=true to regenerate", System.getProperty("readme.screenshots") == "true")

        // Gradle runs unit tests from the app module directory.
        val outDir = File("../docs/screenshots").apply { mkdirs() }
        val context = RuntimeEnvironment.getApplication()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        for (shot in shots) {
            prefs.edit().clear().commit()
            SettingsResetManager.ensureDefaults(context)
            val editor = prefs.edit()
                .putBoolean("pref_debug_enabled", true)
                .putInt("pref_manual_time", shot.minutes)
                .putString("pref_terrain_season", shot.season)
            shot.extraPrefs.forEach { (key, value) ->
                when (value) {
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()

            // Same path as a Breezy Weather broadcast, so clouds and precipitation follow the data.
            WeatherRepository.updateWeather(
                context, shot.weather, sunrise = -1L, sunset = -1L, windSpeed = 3f,
                extendedData = WeatherRepository.ExtendedWeatherData(
                    precipProbability = shot.precip,
                    cloudCover = shot.cloudCover,
                    humidity = 60,
                    visibility = 10_000,
                    moonPhase = 144
                )
            )

            val scene = ParallaxScene(context, ParallaxSensorManager(context), WeatherRepository)
            scene.reloadSettings(SimulationSettings.fromSnapshot(prefs.all))

            // 1080x2340 (19.5:9). Real time between frames lets the async cloud bitmaps finish
            // and weather particles spread across the screen.
            val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            repeat(90) {
                scene.render(canvas, 33L)
                Thread.sleep(33L)
            }
            scene.destroy()

            File(outDir, shot.file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
