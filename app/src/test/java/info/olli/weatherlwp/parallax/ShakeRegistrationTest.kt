package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShakeRegistrationTest {
    private val sensorManager = mock(SensorManager::class.java)
    private val rotation = mock(Sensor::class.java)
    private val accelerometer = mock(Sensor::class.java)
    private val gyroscope = mock(Sensor::class.java)

    private fun startedManager(): ParallaxSensorManager {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)).thenReturn(rotation)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accelerometer)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyroscope)
        `when`(sensorManager.registerListener(any(SensorEventListener::class.java), any(Sensor::class.java), anyInt()))
            .thenReturn(true)
        return ParallaxSensorManager(context).apply {
            setShakeSettings(enabled = true)
            start()
            clearInvocations(sensorManager)
        }
    }

    @Test
    fun `tuning shake while enabled keeps registrations`() {
        val manager = startedManager()
        manager.setShakeSettings(enabled = true, sensitivity = 0.9f, unlockDelayMs = 1000)
        verify(sensorManager, never()).unregisterListener(any(SensorEventListener::class.java), any(Sensor::class.java))
        verify(sensorManager, never()).registerListener(any(SensorEventListener::class.java), any(Sensor::class.java), anyInt())
    }

    @Test
    fun `toggling shake updates registrations`() {
        val manager = startedManager()
        manager.setShakeSettings(enabled = false)
        verify(sensorManager).unregisterListener(manager, accelerometer)
        verify(sensorManager).unregisterListener(manager, gyroscope)
        clearInvocations(sensorManager)
        manager.setShakeSettings(enabled = true)
        verify(sensorManager).registerListener(manager, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        verify(sensorManager).registerListener(manager, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }
}
