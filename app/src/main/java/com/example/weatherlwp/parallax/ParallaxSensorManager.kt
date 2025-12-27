package com.example.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
    }
    
    interface Listener {
        fun onOffsetChanged(x: Float, y: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var ax = 0f
    private var ay = 0f
    private val alpha = 0.85f  // Smoothing factor

    private var listener: Listener? = null
    private var isStarted = false

    fun setListener(l: Listener) { listener = l }

    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer sensor available")
            return
        }
        if (!isStarted) {
            // Use SENSOR_DELAY_UI (60ms) instead of SENSOR_DELAY_GAME (20ms)
            // for better battery life - parallax doesn't need high-frequency updates
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            isStarted = true
            Log.d(TAG, "Sensor started")
        }
    }

    fun stop() {
        if (isStarted) {
            sensorManager.unregisterListener(this)
            isStarted = false
            Log.d(TAG, "Sensor stopped")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Low-pass filter for smooth movement
        ax = alpha * ax + (1 - alpha) * event.values[0]
        ay = alpha * ay + (1 - alpha) * event.values[1]

        // Normalize to -1..1 range (assuming max tilt at ~9.81 m/s²)
        // X-axis (values[0]) = left/right tilt, Y-axis (values[1]) = forward/back tilt
        val tiltX = (ax / 9.81f).coerceIn(-1f, 1f)
        val tiltY = (ay / 9.81f).coerceIn(-1f, 1f)

        // Increased sensitivity for more noticeable parallax effect
        val sensitivityPx = 150f
        listener?.onOffsetChanged(tiltX * sensitivityPx, tiltY * sensitivityPx)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
