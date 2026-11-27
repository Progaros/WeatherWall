package info.olli.weatherlwp.rendering

import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.weather.WeatherRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SceneLifecycleTest {
    private val sensor = mock(ParallaxSensorManager::class.java)
    private val scene = ParallaxScene(RuntimeEnvironment.getApplication(), sensor, WeatherRepository)

    private fun settings(parallax: Boolean, shake: Boolean) = SimulationSettings.fromSnapshot(mapOf(
        "pref_parallax_enabled" to parallax,
        "pref_shake_fireworks_enabled" to shake
    ))

    @Test
    fun `hidden settings reload does not start sensors`() {
        try {
            scene.start()
            scene.stop()
            clearInvocations(sensor)
            scene.reloadSettings(settings(true, true))
            verify(sensor, never()).start()
        } finally {
            scene.destroy()
        }
    }

    @Test
    fun `visible scene with all sensor features disabled stays stopped`() {
        try {
            scene.reloadSettings(settings(false, false))
            clearInvocations(sensor)
            scene.start()
            verify(sensor, never()).start()
        } finally {
            scene.destroy()
        }
    }

    @Test
    fun `shake can activate sensors without parallax`() {
        try {
            scene.reloadSettings(settings(false, true))
            clearInvocations(sensor)
            scene.start()
            verify(sensor).start()
        } finally {
            scene.destroy()
        }
    }

    @Test
    fun `simulation state remains readable after render for Material You colors`() {
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(120, 200, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            scene.render(canvas, 16L)
            org.junit.Assert.assertNotNull(
                "getSimulationState() must stay populated after render() for WeatherWallpaper.onComputeColors()",
                scene.getSimulationState()
            )
            bitmap.recycle()
        } finally {
            scene.destroy()
        }
    }

    @Test
    fun `post-rain rainbow triggers on daytime sunbreak and fades out`() {
        val seasonal = SeasonalEvents(RuntimeEnvironment.getApplication())
        val rainDay = info.olli.weatherlwp.testutil.stateAt(14f).copy(weather = info.olli.weatherlwp.weather.WeatherType.RAIN)
        val clearDay = rainDay.copy(weather = info.olli.weatherlwp.weather.WeatherType.PARTLY_CLOUDY)
        val clearNight = info.olli.weatherlwp.testutil.stateAt(23f).copy(weather = info.olli.weatherlwp.weather.WeatherType.CLEAR)

        seasonal.update(rainDay, 16L)
        org.junit.Assert.assertEquals(0f, seasonal.postRainRainbowAlpha(), 0.001f)

        // Transition from rain to sunbreak during daytime triggers rainbow
        seasonal.update(clearDay, 16L)
        seasonal.update(clearDay, 5_000L)
        org.junit.Assert.assertEquals(1f, seasonal.postRainRainbowAlpha(), 0.05f)

        // Fades out by the end of its duration
        seasonal.update(clearDay, SeasonalEvents.POST_RAIN_RAINBOW_DURATION_MS)
        org.junit.Assert.assertEquals(0f, seasonal.postRainRainbowAlpha(), 0.001f)

        // Does not trigger at night
        seasonal.update(rainDay.copy(isNight = true, timeOfDayHours = 23f), 16L)
        seasonal.update(clearNight, 5_000L)
        org.junit.Assert.assertEquals(0f, seasonal.postRainRainbowAlpha(), 0.001f)
    }
}