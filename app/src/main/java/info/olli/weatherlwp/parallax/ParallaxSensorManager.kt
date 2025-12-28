package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Manages accelerometer input for parallax effect.
 * 
 * This implementation tracks VELOCITY (change in tilt) rather than absolute position.
 * 
 * Key behavior:
 * - Reports the change in tilt since last reading
 * - Positive change = tilting in that direction
 * - Zero change = holding steady (no parallax movement)
 * - Works at any phone orientation (no baseline needed for velocity)
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        
        // Smoothing for raw sensor input (higher = snappier response)
        private const val SENSOR_ALPHA = 0.8f
        
        // Sensitivity multiplier for velocity (reduced from 15 to 8 to prevent double scaling)
        private const val VELOCITY_SCALE = 8f
        
        // Minimum movement threshold (stricter dead zone for tiny jitters)
        private const val MIN_VELOCITY = 0.01f
    }
    
    interface Listener {
        /**
         * Called with velocity (change) in X and Y.
         * Values are normalized: -1 to 1 where magnitude indicates speed of tilt change.
         */
        fun onOffsetChanged(velocityX: Float, velocityY: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Current smoothed sensor values
    private var currentX = 0f
    private var currentY = 0f
    
    // Previous smoothed values (for computing velocity)
    private var prevX = 0f
    private var prevY = 0f
    
    private var initialized = false
    private var listener: Listener? = null
    private var isStarted = false

    fun setListener(l: Listener) { listener = l }

    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer sensor available")
            return
        }
        if (!isStarted) {
            initialized = false
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
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

        val rawX = event.values[0]
        val rawY = event.values[1]
        
        // Low-pass filter for smoothing
        currentX = SENSOR_ALPHA * currentX + (1 - SENSOR_ALPHA) * rawX
        currentY = SENSOR_ALPHA * currentY + (1 - SENSOR_ALPHA) * rawY
        
        if (!initialized) {
            // First reading - just store, no velocity yet
            prevX = currentX
            prevY = currentY
            initialized = true
            return
        }
        
        // Compute velocity (change since last frame)
        var velocityX = (currentX - prevX) * VELOCITY_SCALE
        var velocityY = (currentY - prevY) * VELOCITY_SCALE
        
        // Apply dead zone for tiny movements
        if (kotlin.math.abs(velocityX) < MIN_VELOCITY) velocityX = 0f
        if (kotlin.math.abs(velocityY) < MIN_VELOCITY) velocityY = 0f
        
        // Clamp to -1..1
        velocityX = velocityX.coerceIn(-1f, 1f)
        velocityY = velocityY.coerceIn(-1f, 1f)
        
        // Store current as previous for next frame
        prevX = currentX
        prevY = currentY

        // Send velocity to listener
        listener?.onOffsetChanged(velocityX, velocityY)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
