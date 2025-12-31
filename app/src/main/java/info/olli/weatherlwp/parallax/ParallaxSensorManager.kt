package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Manages sensor input for parallax effect using Android's recommended rotation matrix approach.
 * 
 * Uses TYPE_GAME_ROTATION_VECTOR (preferred) or TYPE_ROTATION_VECTOR for stable orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * Uses Android's standard approach:
 * 1. Get rotation matrix from sensor
 * 2. Remap coordinate system based on display rotation
 * 3. Extract orientation (azimuth, pitch, roll) 
 * 4. Convert pitch/roll to parallax velocity
 * 
 * Key behavior:
 * - Tilt phone left = scene shifts right (revealing left side)
 * - Tilt phone right = scene shifts left (revealing right side)
 * - Works consistently in any device orientation (upright, flat, angled)
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        
        // Smoothing for tilt values (higher = snappier response)
        private const val TILT_ALPHA = 0.85f
        
        // Sensitivity multiplier for velocity
        private const val VELOCITY_SCALE = 5f
        
        // Minimum movement threshold (dead zone for tiny jitters)
        private const val MIN_VELOCITY = 0.008f
        
        // Minimum time between sensor event processing (ms) - throttle to ~30Hz max
        private const val MIN_EVENT_INTERVAL_MS = 33L
    }
    
    interface Listener {
        /**
         * Called with velocity (change) in X and Y.
         * Values are normalized: -1 to 1 where magnitude indicates speed of tilt change.
         */
        fun onOffsetChanged(velocityX: Float, velocityY: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // Sensor priority: Game Rotation Vector > Rotation Vector > Gravity
    // Game Rotation Vector is ideal for parallax (no magnetometer, no drift issues for relative motion)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val activeSensor: Sensor? = gameRotationVector ?: rotationVector ?: gravitySensor ?: accelerometer
    private val useRotationVector: Boolean = (gameRotationVector != null || rotationVector != null)

    // Rotation matrix and orientation arrays (reused to avoid allocations)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // For gravity fallback
    private val gravityValues = floatArrayOf(0f, 0f, 9.81f)

    // Current smoothed tilt values (in normalized range -1 to 1)
    private var currentTiltX = 0f  // Left/right tilt (from roll)
    private var currentTiltY = 0f  // Forward/backward tilt (from pitch)
    
    // Previous smoothed values (for computing velocity)
    private var prevTiltX = 0f
    private var prevTiltY = 0f
    
    // Throttling: track last processed event time
    private var lastEventTimeMs = 0L
    
    private var initialized = false
    private var listener: Listener? = null
    private var isStarted = false

    fun setListener(l: Listener) { listener = l }

    fun start() {
        if (activeSensor == null) {
            Log.w(TAG, "No suitable sensor available for parallax")
            return
        }
        if (!isStarted) {
            initialized = false
            lastEventTimeMs = 0L
            currentTiltX = 0f
            currentTiltY = 0f
            prevTiltX = 0f
            prevTiltY = 0f
            
            val sensorName = when {
                gameRotationVector != null -> "game rotation vector"
                rotationVector != null -> "rotation vector"
                gravitySensor != null -> "gravity"
                else -> "accelerometer (fallback)"
            }
            
            sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_UI)
            isStarted = true
            Log.d(TAG, "Sensor started using $sensorName")
        }
    }

    fun stop() {
        if (isStarted) {
            sensorManager.unregisterListener(this)
            isStarted = false
            Log.d(TAG, "Sensor stopped")
        }
    }
    
    fun isRunning(): Boolean = isStarted

    override fun onSensorChanged(event: SensorEvent) {
        // Throttle: skip if events are coming too fast
        val now = System.currentTimeMillis()
        if (now - lastEventTimeMs < MIN_EVENT_INTERVAL_MS) return
        lastEventTimeMs = now

        val (tiltX, tiltY) = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                calculateTiltFromRotationVector(event.values)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                calculateTiltFromGravity(event.values)
            }
            else -> return
        }
        
        // Low-pass filter for smoothing
        currentTiltX = TILT_ALPHA * currentTiltX + (1 - TILT_ALPHA) * tiltX
        currentTiltY = TILT_ALPHA * currentTiltY + (1 - TILT_ALPHA) * tiltY
        
        if (!initialized) {
            // First reading - just store, no velocity yet
            prevTiltX = currentTiltX
            prevTiltY = currentTiltY
            initialized = true
            return
        }
        
        // Compute velocity (change since last frame)
        var velocityX = (currentTiltX - prevTiltX) * VELOCITY_SCALE
        var velocityY = (currentTiltY - prevTiltY) * VELOCITY_SCALE
        
        // Apply dead zone for tiny movements
        if (kotlin.math.abs(velocityX) < MIN_VELOCITY) velocityX = 0f
        if (kotlin.math.abs(velocityY) < MIN_VELOCITY) velocityY = 0f
        
        // Clamp to -1..1
        velocityX = velocityX.coerceIn(-1f, 1f)
        velocityY = velocityY.coerceIn(-1f, 1f)
        
        // Store current as previous for next frame
        prevTiltX = currentTiltX
        prevTiltY = currentTiltY

        // Send velocity to listener
        // Negate for natural parallax: tilt direction reveals opposite side
        listener?.onOffsetChanged(-velocityX, -velocityY)
    }
    
    /**
     * Calculate tilt from rotation vector using Android's standard rotation matrix approach.
     * This is the recommended method for reliable orientation across all device positions.
     */
    private fun calculateTiltFromRotationVector(rotationVectorValues: FloatArray): Pair<Float, Float> {
        // Get rotation matrix from rotation vector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValues)
        
        // Remap coordinate system based on current display rotation
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val success = remapMatrixForDisplayRotation(rotation)
        
        if (!success) {
            return Pair(0f, 0f)
        }
        
        // Get orientation angles from remapped matrix
        // orientationAngles[0] = azimuth (rotation around Z axis)
        // orientationAngles[1] = pitch (rotation around X axis) - forward/back tilt
        // orientationAngles[2] = roll (rotation around Y axis) - left/right tilt
        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        
        // Convert radians to normalized tilt (-1..1)
        // sin() gives natural response curve, already in -1..1 range for normal tilts
        val tiltX = kotlin.math.sin(orientationAngles[2].toDouble()).toFloat().coerceIn(-1f, 1f) // roll
        val tiltY = kotlin.math.sin(orientationAngles[1].toDouble()).toFloat().coerceIn(-1f, 1f) // pitch
        
        return Pair(tiltX, tiltY)
    }
    
    /**
     * Fallback: Calculate tilt from gravity vector using rotation matrix.
     * Less accurate than rotation vector but works when rotation sensors unavailable.
     */
    private fun calculateTiltFromGravity(gravityVectorValues: FloatArray): Pair<Float, Float> {
        // Copy to our array
        gravityValues[0] = gravityVectorValues[0]
        gravityValues[1] = gravityVectorValues[1]
        gravityValues[2] = gravityVectorValues[2]
        
        // Get rotation matrix from gravity (without magnetic field, less accurate but functional)
        // Pass null for geomagnetic - this gives us a matrix aligned with gravity only
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, null)
        
        if (!success) {
            // Fallback to simple calculation when matrix fails (device near gimbal lock)
            return calculateSimpleTiltFromGravity(gravityVectorValues)
        }
        
        // Remap for display rotation
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapSuccess = remapMatrixForDisplayRotation(rotation)
        
        if (!remapSuccess) {
            return Pair(0f, 0f)
        }
        
        // Get orientation
        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        
        val tiltX = kotlin.math.sin(orientationAngles[2].toDouble()).toFloat().coerceIn(-1f, 1f)
        val tiltY = kotlin.math.sin(orientationAngles[1].toDouble()).toFloat().coerceIn(-1f, 1f)
        
        return Pair(tiltX, tiltY)
    }
    
    /**
     * Simple gravity-based tilt calculation as last-resort fallback.
     * Used when getRotationMatrix fails (near gimbal lock positions).
     */
    private fun calculateSimpleTiltFromGravity(g: FloatArray): Pair<Float, Float> {
        val gMag = kotlin.math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gMag < 0.1f) return Pair(0f, 0f)
        
        // Normalize
        val nx = g[0] / gMag
        val ny = g[1] / gMag
        
        // Simple: use normalized X and Y directly, adjusted for display rotation
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        
        var tiltX = nx.coerceIn(-1f, 1f)
        var tiltY = ny.coerceIn(-1f, 1f)
        
        // Adjust for screen rotation
        when (rotation) {
            Surface.ROTATION_90 -> {
                val temp = tiltX
                tiltX = tiltY
                tiltY = -temp
            }
            Surface.ROTATION_180 -> {
                tiltX = -tiltX
                tiltY = -tiltY
            }
            Surface.ROTATION_270 -> {
                val temp = tiltX
                tiltX = -tiltY
                tiltY = temp
            }
        }
        
        return Pair(tiltX, tiltY)
    }
    
    /**
     * Remap rotation matrix for current display rotation.
     * This ensures sensor coordinates match screen coordinates.
     */
    private fun remapMatrixForDisplayRotation(displayRotation: Int): Boolean {
        // Define axis mapping based on display rotation
        // These mappings transform from device coordinates to screen coordinates
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_0 -> {
                // Portrait (natural orientation for most phones)
                SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            Surface.ROTATION_90 -> {
                // Landscape, rotated 90° counter-clockwise (home button on right)
                SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_180 -> {
                // Upside down portrait
                SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            }
            Surface.ROTATION_270 -> {
                // Landscape, rotated 90° clockwise (home button on left)
                SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            }
            else -> {
                SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
        }
        
        return SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
