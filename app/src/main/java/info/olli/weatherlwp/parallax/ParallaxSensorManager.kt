package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.sqrt

/**
 * Manages sensor input for parallax effect using gravity projection from rotation matrix.
 * 
 * Uses TYPE_GAME_ROTATION_VECTOR (preferred) or TYPE_ROTATION_VECTOR for stable orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * Key insight: Instead of using getOrientation() which produces Euler angles that suffer
 * from gimbal lock and direction flips, we extract the gravity vector directly from the
 * rotation matrix. The gravity direction in screen coordinates is always well-defined
 * and stable regardless of device orientation.
 * 
 * Approach:
 * 1. Get rotation matrix from sensor
 * 2. Remap coordinate system based on display rotation  
 * 3. Extract gravity as -[R[6], R[7], R[8]] (negative third row of remapped matrix)
 * 4. Use screen-parallel gravity components for parallax with deadzone for flat positions
 * 
 * Key behavior:
 * - Tilt phone left = scene shifts right (revealing left side)
 * - Tilt phone right = scene shifts left (revealing right side)
 * - Works consistently in any device orientation (upright, flat, angled)
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        
        // Low-pass filter alpha (0..1, smaller = smoother but slower response)
        private const val FILTER_ALPHA = 0.12f
        
        // Deadzone threshold for flat device (prevents noise when screen-parallel gravity is tiny)
        private const val DEADZONE = 0.08f
        
        // Sensitivity multiplier for velocity output
        private const val VELOCITY_SCALE = 8f
        
        // Minimum velocity threshold (filters micro-jitters)
        private const val MIN_VELOCITY = 0.005f
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
    
    // Sensor priority: Game Rotation Vector > Rotation Vector > Gravity > Accelerometer
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val activeSensor: Sensor? = gameRotationVector ?: rotationVector ?: gravitySensor ?: accelerometer

    // Rotation matrix arrays (reused to avoid allocations)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)

    // Filtered tilt values (absolute position, -1 to 1)
    private var filteredX = 0f
    private var filteredY = 0f
    
    // Previous filtered values for velocity calculation
    private var prevFilteredX = 0f
    private var prevFilteredY = 0f
    
    // Timestamp tracking for velocity calculation
    private var lastTimestampNs = 0L
    
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
            lastTimestampNs = 0L
            filteredX = 0f
            filteredY = 0f
            prevFilteredX = 0f
            prevFilteredY = 0f
            
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
        val now = event.timestamp
        val dt = if (lastTimestampNs != 0L) (now - lastTimestampNs) * 1e-9f else 0f
        lastTimestampNs = now
        
        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                handleRotationVector(event.values, displayRotation, dt)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                handleGravityVector(event.values, displayRotation, dt)
            }
        }
    }
    
    /**
     * Handle rotation vector sensor by extracting gravity from the rotation matrix.
     * This avoids Euler angle gimbal lock issues.
     */
    private fun handleRotationVector(values: FloatArray, displayRotation: Int, dt: Float) {
        // Get rotation matrix from rotation vector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        
        // Remap for display rotation
        val (axisX, axisY) = getRemapAxes(displayRotation)
        val success = SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        
        if (!success) return
        
        // Extract gravity from rotation matrix.
        // R transforms device coords to world coords where Z is up.
        // The third row of R is the world Z-axis in device coords.
        // Gravity (pointing down) is the negative of that: -R[6], -R[7], -R[8]
        val gx = -remappedMatrix[6]
        val gy = -remappedMatrix[7]
        val gz = -remappedMatrix[8]
        
        processGravityInScreenCoords(gx, gy, gz, dt)
    }
    
    /**
     * Handle gravity/accelerometer sensor by remapping the vector for display rotation.
     */
    private fun handleGravityVector(values: FloatArray, displayRotation: Int, dt: Float) {
        // Remap gravity vector for display rotation
        val (gx, gy, gz) = remapVectorForDisplay(values, displayRotation)
        
        // Normalize to ~1g for consistent scaling
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 0.1f) return
        
        val ngx = gx / norm
        val ngy = gy / norm
        val ngz = gz / norm
        
        processGravityInScreenCoords(ngx, ngy, ngz, dt)
    }
    
    /**
     * Process gravity vector in screen coordinates to produce parallax output.
     * 
     * gx: gravity component along screen X (positive = gravity pulling right)
     * gy: gravity component along screen Y (positive = gravity pulling up)
     * gz: gravity component along screen Z (positive = gravity pulling out of screen)
     */
    private fun processGravityInScreenCoords(gx: Float, gy: Float, gz: Float, dt: Float) {
        // Compute horizontal magnitude (how much gravity is parallel to screen)
        val horizMag = sqrt(gx * gx + gy * gy)
        
        // Confidence factor: when device is nearly flat, horizMag is tiny and noisy.
        // Scale down the effect near the deadzone to prevent jumpiness.
        val confidence = ((horizMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        
        // Target tilt values:
        // - Negate gx so that tilting left (gx positive in screen coords when phone tilts left)
        //   causes scene to shift right
        // - Apply confidence to reduce effect when device is flat
        val targetX = (-gx) * confidence
        val targetY = (-gy) * confidence
        
        // Low-pass filter for smooth parallax motion
        filteredX += FILTER_ALPHA * (targetX - filteredX)
        filteredY += FILTER_ALPHA * (targetY - filteredY)
        
        if (!initialized) {
            prevFilteredX = filteredX
            prevFilteredY = filteredY
            initialized = true
            return
        }
        
        // Compute velocity (change over time)
        var velocityX = if (dt > 0f) (filteredX - prevFilteredX) * VELOCITY_SCALE else 0f
        var velocityY = if (dt > 0f) (filteredY - prevFilteredY) * VELOCITY_SCALE else 0f
        
        // Apply dead zone for micro-jitters
        if (kotlin.math.abs(velocityX) < MIN_VELOCITY) velocityX = 0f
        if (kotlin.math.abs(velocityY) < MIN_VELOCITY) velocityY = 0f
        
        // Clamp to valid range
        velocityX = velocityX.coerceIn(-1f, 1f)
        velocityY = velocityY.coerceIn(-1f, 1f)
        
        // Store for next frame
        prevFilteredX = filteredX
        prevFilteredY = filteredY
        
        // Send to listener
        listener?.onOffsetChanged(velocityX, velocityY)
    }
    
    /**
     * Get axis remapping for display rotation.
     * These mappings transform device coordinates to screen coordinates.
     */
    private fun getRemapAxes(displayRotation: Int): Pair<Int, Int> = when (displayRotation) {
        Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    
    /**
     * Remap a gravity vector for display rotation.
     * Returns (gx, gy, gz) in screen coordinates.
     */
    private fun remapVectorForDisplay(v: FloatArray, displayRotation: Int): Triple<Float, Float, Float> {
        val x = v[0]
        val y = v[1]
        val z = v[2]
        
        return when (displayRotation) {
            Surface.ROTATION_0 -> Triple(x, y, z)
            Surface.ROTATION_90 -> Triple(y, -x, z)
            Surface.ROTATION_180 -> Triple(-x, -y, z)
            Surface.ROTATION_270 -> Triple(-y, x, z)
            else -> Triple(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
