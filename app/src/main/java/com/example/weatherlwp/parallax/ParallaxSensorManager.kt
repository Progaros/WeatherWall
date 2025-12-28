package com.example.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Manages accelerometer input for parallax effect.
 * 
 * Uses adaptive calibration to work at any phone orientation:
 * - Continuously tracks a moving average baseline
 * - Parallax responds to tilts relative to this baseline
 * - This means parallax works whether phone is flat, upright, or any angle
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        
        // Smoothing for raw sensor input
        private const val SENSOR_ALPHA = 0.85f
        
        // Baseline adaptation rate - slower = more stable, faster = more responsive
        // 0.01 means baseline adjusts slowly to where user holds the phone
        private const val BASELINE_ADAPT_RATE = 0.01f
        
        // Minimum tilt angle (in g-force) before parallax responds
        // This creates a small "dead zone" at center for stability
        private const val DEAD_ZONE = 0.3f
        
        // Sensitivity - higher = more parallax from less tilt
        private const val SENSITIVITY = 3.5f
    }
    
    interface Listener {
        fun onOffsetChanged(x: Float, y: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Smoothed raw sensor values
    private var ax = 0f
    private var ay = 0f
    
    // Adaptive baseline - tracks where "center" is
    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineInitialized = false

    private var listener: Listener? = null
    private var isStarted = false

    fun setListener(l: Listener) { listener = l }

    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer sensor available")
            return
        }
        if (!isStarted) {
            // Reset baseline on start
            baselineInitialized = false
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
        ax = SENSOR_ALPHA * ax + (1 - SENSOR_ALPHA) * event.values[0]
        ay = SENSOR_ALPHA * ay + (1 - SENSOR_ALPHA) * event.values[1]
        
        // Initialize baseline to current position on first reading
        if (!baselineInitialized) {
            baselineX = ax
            baselineY = ay
            baselineInitialized = true
            Log.d(TAG, "Baseline initialized: x=$baselineX, y=$baselineY")
        }
        
        // Slowly adapt baseline toward current position
        // This allows parallax to work at any phone orientation
        // The baseline drifts toward where the user holds the phone
        baselineX += (ax - baselineX) * BASELINE_ADAPT_RATE
        baselineY += (ay - baselineY) * BASELINE_ADAPT_RATE
        
        // Calculate tilt relative to adaptive baseline
        var deltaX = ax - baselineX
        var deltaY = ay - baselineY
        
        // Apply dead zone for stability at center
        if (kotlin.math.abs(deltaX) < DEAD_ZONE) deltaX = 0f
        if (kotlin.math.abs(deltaY) < DEAD_ZONE) deltaY = 0f
        
        // Apply sensitivity and normalize to -1..1 range
        val tiltX = (deltaX * SENSITIVITY / 9.8f).coerceIn(-1f, 1f)
        val tiltY = (deltaY * SENSITIVITY / 9.8f).coerceIn(-1f, 1f)

        // Send normalized values (-1 to 1) to the listener
        listener?.onOffsetChanged(tiltX, tiltY)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
