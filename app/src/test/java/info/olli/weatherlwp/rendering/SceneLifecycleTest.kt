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
}