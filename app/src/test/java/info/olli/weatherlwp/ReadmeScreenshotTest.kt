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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Regenerates docs/screenshots/Screenshot_N.gif: the real ParallaxScene with the app's default
 * settings, tilted in a slow circle through the same input the gyroscope drives.
 * Skipped unless run with -Dreadme.screenshots=true.
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
        val seed: Int,
        val windSpeed: Float,
        val extraPrefs: Map<String, Any> = emptyMap()
    )

    // Each shot gets its own landscape seed (terrain, mountains, clouds) and wind, so the GIFs differ.
    private val shots = listOf(
        Shot("Screenshot_1.gif", 5 * 60 + 50, WeatherType.CLOUDY, 70, 0, "spring", 208, 2f),          // Dawn clouds
        Shot("Screenshot_2.gif", 13 * 60, WeatherType.PARTLY_CLOUDY, 20, 0, "summer", 42, 4f),        // Clear day
        Shot("Screenshot_3.gif", 11 * 60, WeatherType.SNOW, 90, 90, "winter", 517, 1.5f, mapOf(      // Snowy day
            "pref_sim_weather_history" to true,
            "pref_sim_snow_level" to 80
        )),
        Shot("Screenshot_4.gif", 19 * 60 + 40, WeatherType.RAIN, 85, 85, "autumn", 733, 7f),          // Rainy dusk
        Shot("Screenshot_5.gif", 23 * 60, WeatherType.CLEAR, 5, 0, "summer", 96, 3f, mapOf(           // Night sky
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
                .putInt("pref_random_seed", shot.seed)
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
                context, shot.weather, sunrise = -1L, sunset = -1L, windSpeed = shot.windSpeed,
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

            // One warm-up orbit so the smoothed offset is on its steady path, then capture the
            // next orbit so the GIF loops seamlessly. The orbit speed stays above the
            // "device is still" threshold, so return-to-center never kicks in.
            val frames = mutableListOf<IntArray>()
            for (i in 0 until FRAMES_PER_ORBIT * 2) {
                val angle = 2.0 * PI * i / FRAMES_PER_ORBIT
                scene.onTiltChanged((TILT * cos(angle)).toFloat(), (TILT * sin(angle)).toFloat())
                scene.render(canvas, 33L)
                Thread.sleep(33L)
                if (i >= FRAMES_PER_ORBIT) frames.add(downscale(bitmap))
            }
            scene.destroy()
            bitmap.recycle()

            GifWriter.write(File(outDir, shot.file), GIF_WIDTH, GIF_HEIGHT, frames, GIF_DELAY_CS)
        }
    }

    private fun downscale(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, GIF_WIDTH, GIF_HEIGHT, true)
        val pixels = IntArray(GIF_WIDTH * GIF_HEIGHT)
        small.getPixels(pixels, 0, GIF_WIDTH, 0, 0, GIF_WIDTH, GIF_HEIGHT)
        small.recycle()
        return pixels
    }

    private companion object {
        const val GIF_WIDTH = 360
        const val GIF_HEIGHT = 780
        const val FRAMES_PER_ORBIT = 60
        const val GIF_DELAY_CS = 5
        const val TILT = 0.6
    }
}
