package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manages sensor input for parallax effect using QUATERNION-based tilt extraction.
 * 
 * Uses TYPE_GAME_ROTATION_VECTOR (preferred) or TYPE_ROTATION_VECTOR for stable orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * CRITICAL FIX (from ai.md analysis):
 * Instead of extracting gravity from rotation matrix rows (which breaks across display rotations),
 * we now extract pitch/roll directly from quaternions. Quaternions have no gimbal lock and
 * work identically at all four device rotations without any display rotation remapping.
 * 
 * Approach:
 * 1. Get quaternion from rotation vector sensor
 * 2. Extract pitch (tilt forward/back) and roll (tilt left/right) from quaternion
 * 3. These values are in world coordinates, independent of display rotation
 * 4. Use pitch/roll for parallax with deadzone for flat positions
 * 5. Output absolute tilt position (not velocity) so parallax follows held angle
 * 
 * Key behavior:
 * - Tilt phone left = scene shifts right (revealing left side)
 * - Tilt phone right = scene shifts left (revealing right side)
 * - Holding phone at angle keeps scene offset (no drift to center)
 * - Works consistently in any device orientation (upright, flat, angled, ANY rotation)
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        
        // Low-pass filter alpha (0..1, smaller = smoother but slower response)
        private const val FILTER_ALPHA = 0.12f
        
        // Deadzone threshold for flat device (prevents noise when tilt is tiny)
        private const val DEADZONE = 0.08f
    }
    
    interface Listener {
        /**
         * Called with absolute tilt position in X and Y.
         * Values are normalized: -1 to 1 where the value represents tilt angle.
         * Tilt left = negative X, tilt right = positive X.
         * Tilt forward = negative Y, tilt back = positive Y.
         */
        fun onTiltChanged(tiltX: Float, tiltY: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // Sensor priority: Game Rotation Vector > Rotation Vector > Gravity > Accelerometer
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val activeSensor: Sensor? = gameRotationVector ?: rotationVector ?: gravitySensor ?: accelerometer

    // Filtered tilt values (absolute position, -1 to 1)
    private var filteredX = 0f
    private var filteredY = 0f
    
    // Previous filtered values
    private var prevFilteredX = 0f
    private var prevFilteredY = 0f
    
    // Timestamp tracking
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
                gameRotationVector != null -> "game rotation vector (quaternion)"
                rotationVector != null -> "rotation vector (quaternion)"
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
                // Use quaternion-based extraction (works at ALL rotations!)
                handleRotationVectorQuaternion(event.values, displayRotation, dt)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                // Fallback: remap gravity vector for display rotation
                handleGravityVector(event.values, displayRotation, dt)
            }
        }
    }
    
    /**
     * Handle rotation vector sensor using QUATERNION-based pitch/roll extraction.
     * 
     * This approach extracts pitch and roll directly from the quaternion representation,
     * which works identically at ALL device rotations without remapping matrices.
     * The display rotation is still used to remap the final X/Y for screen coordinates.
     */
    private fun handleRotationVectorQuaternion(values: FloatArray, displayRotation: Int, dt: Float) {
        // Rotation vector provides quaternion components
        val qx = values[0]
        val qy = values[1]
        val qz = values[2]
        val qw = if (values.size >= 4) values[3] else sqrt(1f - qx*qx - qy*qy - qz*qz).coerceIn(0f, 1f)
        
        // Extract pitch (tilt forward/back) from quaternion
        // Pitch: rotation around device X-axis
        val sinPitch = 2.0f * (qw * qx + qy * qz)
        val cosPitch = 1.0f - 2.0f * (qx * qx + qy * qy)
        val pitch = atan2(sinPitch, cosPitch)
        
        // Extract roll (tilt left/right) from quaternion
        // Roll: rotation around device Y-axis  
        val sinRoll = 2.0f * (qw * qy - qz * qx)
        val roll = asin(sinRoll.coerceIn(-1f, 1f))
        
        // Normalize to -1..1 range (max tilt = 90 degrees = PI/2)
        // These are in device coordinates - pitch is forward/back, roll is left/right
        val rawTiltX = (roll / (PI / 2)).toFloat().coerceIn(-1f, 1f)
        val rawTiltY = (pitch / (PI / 2)).toFloat().coerceIn(-1f, 1f)
        
        // Apply display rotation remapping to convert device coords to screen coords
        val (tiltX, tiltY) = remapTiltForDisplay(rawTiltX, rawTiltY, displayRotation)
        
        processTiltValues(tiltX, tiltY, dt)
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
        
        // Compute horizontal magnitude (how much gravity is parallel to screen)
        val horizMag = sqrt(ngx * ngx + ngy * ngy)
        
        // Confidence factor: when device is nearly flat, horizMag is tiny and noisy.
        val confidence = ((horizMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        
        // Target tilt values (negate for natural parallax direction)
        val targetX = (-ngx) * confidence
        val targetY = (-ngy) * confidence
        
        processTiltValues(targetX, targetY, dt)
    }
    
    /**
     * Process tilt values with filtering and deadzone.
     */
    private fun processTiltValues(targetX: Float, targetY: Float, dt: Float) {
        // Compute tilt magnitude for deadzone
        val tiltMag = sqrt(targetX * targetX + targetY * targetY)
        
        // Apply deadzone
        val confidence = ((tiltMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        val scaledX = if (tiltMag > DEADZONE) targetX * confidence else 0f
        val scaledY = if (tiltMag > DEADZONE) targetY * confidence else 0f
        
        // Low-pass filter for smooth parallax motion
        filteredX += FILTER_ALPHA * (scaledX - filteredX)
        filteredY += FILTER_ALPHA * (scaledY - filteredY)
        
        if (!initialized) {
            prevFilteredX = filteredX
            prevFilteredY = filteredY
            initialized = true
            return
        }
        
        // Store for next frame
        prevFilteredX = filteredX
        prevFilteredY = filteredY
        
        // Send absolute tilt position to listener
        listener?.onTiltChanged(filteredX, filteredY)
    }
    
    /**
     * Remap tilt values for display rotation.
     * Converts device-coordinate tilt to screen-coordinate tilt.
     */
    private fun remapTiltForDisplay(tiltX: Float, tiltY: Float, displayRotation: Int): Pair<Float, Float> {
        return when (displayRotation) {
            Surface.ROTATION_0 -> Pair(tiltX, tiltY)
            Surface.ROTATION_90 -> Pair(tiltY, -tiltX)
            Surface.ROTATION_180 -> Pair(-tiltX, -tiltY)
            Surface.ROTATION_270 -> Pair(-tiltY, tiltX)
            else -> Pair(tiltX, tiltY)
        }
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
