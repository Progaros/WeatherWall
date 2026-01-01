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
        // Increased to 0.5 for more responsive parallax - single stage of smoothing
        private const val FILTER_ALPHA = 0.5f
        
        // Deadzone threshold for flat device (prevents noise when tilt is tiny)
        // Reduced from 0.06 to 0.02 (~1.8°) for better sensitivity to small movements
        private const val DEADZONE = 0.02f
    }
    
    // Pre-allocated fields to avoid Pair/Triple allocations in hot path
    private var remappedTiltX = 0f
    private var remappedTiltY = 0f
    private var remappedVectorX = 0f
    private var remappedVectorY = 0f
    private var remappedVectorZ = 0f
    
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
            
            sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)
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
     * 
     * IMPORTANT: Using 45° (PI/4) as the reference angle, not 90°.
     * This means full parallax offset is reached at 45° tilt, allowing responsive
     * movement even when holding the phone upright. Tilt beyond 45° still works
     * (saturates at ±1.0).
     */
    private fun handleRotationVectorQuaternion(values: FloatArray, displayRotation: Int, dt: Float) {
        // Rotation vector provides quaternion components
        val qx = values[0]
        val qy = values[1]
        val qz = values[2]
        // Safe qw computation: clamp BEFORE sqrt to prevent NaN from floating-point errors
        val qw = if (values.size >= 4) {
            values[3]
        } else {
            sqrt((1f - qx*qx - qy*qy - qz*qz).coerceIn(0f, 1f))
        }
        
        // Extract pitch (tilt forward/back) from quaternion
        // Pitch: rotation around device X-axis
        val sinPitch = 2.0f * (qw * qx + qy * qz)
        val cosPitch = 1.0f - 2.0f * (qx * qx + qy * qy)
        val pitch = atan2(sinPitch, cosPitch)
        
        // Extract roll (tilt left/right) from quaternion
        // Roll: rotation around device Y-axis  
        val sinRoll = 2.0f * (qw * qy - qz * qx)
        val roll = asin(sinRoll.coerceIn(-1f, 1f))
        
        // Normalize to -1..1 range using 45° (PI/4) as full-scale reference
        // This makes parallax more responsive - 45° tilt = full offset
        // Beyond 45° still works but saturates at ±1.0
        // NEGATE to match gravity fallback convention (tilt right -> scene moves left)
        val rawTiltX = (-(roll) / (PI / 4)).toFloat().coerceIn(-1f, 1f)
        val rawTiltY = (-(pitch) / (PI / 4)).toFloat().coerceIn(-1f, 1f)
        
        // Apply display rotation remapping to convert device coords to screen coords
        remapTiltForDisplay(rawTiltX, rawTiltY, displayRotation)
        
        processTiltValues(remappedTiltX, remappedTiltY, dt)
    }
    
    /**
     * Handle gravity/accelerometer sensor by remapping the vector for display rotation.
     */
    private fun handleGravityVector(values: FloatArray, displayRotation: Int, dt: Float) {
        // Remap gravity vector for display rotation
        remapVectorForDisplay(values, displayRotation)
        val gx = remappedVectorX
        val gy = remappedVectorY
        val gz = remappedVectorZ
        
        // Normalize to ~1g for consistent scaling
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 0.1f) return
        
        val ngx = gx / norm
        val ngy = gy / norm
        
        // Pass normalized gravity directly - let processTiltValues handle deadzone
        // (Removes double-deadzone attenuation for consistent feel vs quaternion path)
        val targetX = -ngx
        val targetY = -ngy
        
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
     * Stores result in remappedTiltX/remappedTiltY to avoid allocations.
     */
    private fun remapTiltForDisplay(tiltX: Float, tiltY: Float, displayRotation: Int) {
        when (displayRotation) {
            Surface.ROTATION_0 -> { remappedTiltX = tiltX; remappedTiltY = tiltY }
            Surface.ROTATION_90 -> { remappedTiltX = tiltY; remappedTiltY = -tiltX }
            Surface.ROTATION_180 -> { remappedTiltX = -tiltX; remappedTiltY = -tiltY }
            Surface.ROTATION_270 -> { remappedTiltX = -tiltY; remappedTiltY = tiltX }
            else -> { remappedTiltX = tiltX; remappedTiltY = tiltY }
        }
    }
    
    /**
     * Remap a gravity vector for display rotation.
     * Stores result in remappedVectorX/Y/Z to avoid allocations.
     */
    private fun remapVectorForDisplay(v: FloatArray, displayRotation: Int) {
        val x = v[0]
        val y = v[1]
        val z = v[2]
        
        when (displayRotation) {
            Surface.ROTATION_0 -> { remappedVectorX = x; remappedVectorY = y; remappedVectorZ = z }
            Surface.ROTATION_90 -> { remappedVectorX = y; remappedVectorY = -x; remappedVectorZ = z }
            Surface.ROTATION_180 -> { remappedVectorX = -x; remappedVectorY = -y; remappedVectorZ = z }
            Surface.ROTATION_270 -> { remappedVectorX = -y; remappedVectorY = x; remappedVectorZ = z }
            else -> { remappedVectorX = x; remappedVectorY = y; remappedVectorZ = z }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
