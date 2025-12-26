package com.example.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class ParallaxSensorManager(context: Context) : SensorEventListener {
    interface Listener {
        fun onOffsetChanged(x: Float, y: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var ax = 0f
    private var ay = 0f
    private val alpha = 0.85f

    private var listener: Listener? = null

    fun setListener(l: Listener) { listener = l }

    fun start() {
        // SENSOR_DELAY_UI: guter Kompromiss (Battery/UX) für Wallpaper
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        ax = alpha * ax + (1 - alpha) * event.values[0]
        ay = alpha * ay + (1 - alpha) * event.values[1]

        val tiltX = (ay / 9.81f).coerceIn(-1f, 1f)
        val tiltY = (ax / 9.81f).coerceIn(-1f, 1f)

        val sensitivityPx = 50f
        listener?.onOffsetChanged(tiltX * sensitivityPx, tiltY * sensitivityPx)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
