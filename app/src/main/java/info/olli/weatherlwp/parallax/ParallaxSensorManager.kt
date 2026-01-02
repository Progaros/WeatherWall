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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manages sensor input for parallax effect.
 * 
 * Uses TYPE_ROTATION_VECTOR (preferred) or TYPE_GAME_ROTATION_VECTOR for orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * KEY IMPLEMENTATION:
 * 1. Delta-based motion detection: responds to movement, not absolute position
 * 2. Works at ANY orientation (flat, tilted, vertical, upside-down)
 * 3. Accumulates motion with slow decay back to center
 * 4. Auto-resets when display rotation changes
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        private const val FILTER_ALPHA = 0.4f
        private const val FULL_TILT_RADIANS = (45f * PI / 180f).toFloat()
        private const val DEADZONE = 0.03f
        
        // Hysteresis thresholds for isScreenDown to prevent flip-flopping at vertical
        // When |z| is small (near vertical), we freeze the current state
        private const val SCREEN_DOWN_ON = -0.25f   // Enter "screen down" when z drops below this
        private const val SCREEN_DOWN_OFF = 0.25f   // Leave "screen down" when z rises above this
        private const val SCREEN_FLIP_FREEZE = 0.08f // Don't change state when |z| is this small
        
        // Shake detection constants
        private const val SHAKE_THRESHOLD_DEFAULT = 12f  // Minimum acceleration magnitude for shake
        private const val SHAKE_TIME_WINDOW_MS = 800L    // Time window to count shakes
        private const val SHAKE_COUNT_THRESHOLD = 3      // Number of direction reversals for trigger
        private const val SHAKE_COOLDOWN_MS = 1500L      // Cooldown after triggering shake event
    }
    
    interface Listener {
        fun onTiltChanged(tiltX: Float, tiltY: Float)
    }
    
    interface ShakeListener {
        /**
         * Called when a shake gesture is detected (3 quick back-and-forth shakes).
         */
        fun onShake()
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val activeSensor: Sensor? = rotationVector ?: gameRotationVector ?: gravitySensor ?: accelerometer
    
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)  // azimuth, pitch, roll

    private var filteredX = 0f
    private var filteredY = 0f
    
    // Delta-based motion detection: track previous angles instead of fixed baseline
    private var prevPitch = 0f
    private var prevRoll = 0f
    private var isFirstSample = true
    
    // Accumulated parallax offset (integrates delta motion)
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private val DECAY_RATE = 0.995f  // Slowly decay back to center
    
    // Pose gain: reduces sensitivity when near vertical (gimbal-lock region)
    // 1.0 = flat (stable), 0.0 = vertical (unstable)
    private var poseGain = 1f

    // Track whether the screen is facing down (used to conditionally flip Y)
    private var isScreenDown = false
    
    // Track display rotation for reset on rotation change
    private var lastDisplayRotation = Surface.ROTATION_0
    
    private var listener: Listener? = null
    private var shakeListener: ShakeListener? = null
    private var isStarted = false
    
    // Shake detection state
    private var shakeEnabled = true
    private var shakeThreshold = SHAKE_THRESHOLD_DEFAULT
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private var lastShakeDirectionX = 0  // -1, 0, or 1
    private var lastShakeDirectionY = 0
    private var shakeWindowStartTime = 0L
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var hasLastAccel = false

    fun setListener(l: Listener) { listener = l }
    
    fun setShakeListener(l: ShakeListener?) { shakeListener = l }
    
    /**
     * Configure shake detection sensitivity.
     * @param enabled Whether shake detection is enabled
     * @param sensitivity 0.0 to 1.0 where higher = more sensitive (requires less force)
     */
    fun setShakeSettings(enabled: Boolean, sensitivity: Float = 0.5f) {
        shakeEnabled = enabled
        // Map sensitivity 0-1 to threshold range: 20 (low sensitivity) to 5 (high sensitivity)
        shakeThreshold = 20f - (sensitivity.coerceIn(0f, 1f) * 15f)
    }

    fun start() {
        if (activeSensor == null) {
            Log.w(TAG, "No suitable sensor available for parallax")
            return
        }
        if (!isStarted) {
            filteredX = 0f
            filteredY = 0f
            prevPitch = 0f
            prevRoll = 0f
            isFirstSample = true
            accumulatedX = 0f
            accumulatedY = 0f
            lastDisplayRotation = getCurrentDisplayRotation()
            
            // Reset shake state
            shakeCount = 0
            hasLastAccel = false
            lastShakeTime = 0L
            
            // Use SENSOR_DELAY_FASTEST for smoothest parallax (matches 60 FPS target)
            // This gives ~5-10ms updates on most devices
            sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_FASTEST)
            
            // Also register accelerometer for shake detection if not already the active sensor
            if (shakeEnabled && accelerometer != null && accelerometer != activeSensor) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            }
            
            isStarted = true
            Log.d(TAG, "Sensor started with delta-based motion detection")
        }
    }

    fun stop() {
        if (isStarted) {
            sensorManager.unregisterListener(this)
            isStarted = false
            Log.d(TAG, "Sensor stopped")
        }
    }
    
    fun recalibrate() {
        // Reset delta tracking - next sample will be new reference
        isFirstSample = true
        accumulatedX = 0f
        accumulatedY = 0f
        filteredX = 0f
        filteredY = 0f
        Log.d(TAG, "Recalibration triggered (delta reset)")
    }
    
    fun isRunning(): Boolean = isStarted
    
    @Suppress("DEPRECATION")
    private fun getCurrentDisplayRotation(): Int {
        return windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            val displayRotation = getCurrentDisplayRotation()
            
            // Auto-recalibrate if display rotation changed
            if (displayRotation != lastDisplayRotation) {
                Log.d(TAG, "Display rotation changed: $lastDisplayRotation -> $displayRotation, recalibrating")
                lastDisplayRotation = displayRotation
                recalibrate()
                return  // Skip this sample, wait for fresh calibration
            }
            
            // Process shake detection from accelerometer
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && shakeEnabled) {
                detectShake(event.values)
            }

            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    handleRotationVector(event.values, displayRotation)
                }
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    handleGravityVector(event.values, displayRotation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor event: ${e.message}", e)
        }
    }
    
    /**
     * Detect shake gestures from accelerometer data.
     * Triggers ShakeListener.onShake() when 3 quick back-and-forth shakes are detected.
     */
    private fun detectShake(values: FloatArray) {
        val currentTime = System.currentTimeMillis()
        
        // Check cooldown
        if (currentTime - lastShakeTime < SHAKE_COOLDOWN_MS) {
            return
        }
        
        val x = values[0]
        val y = values[1]
        val z = values[2]
        
        if (!hasLastAccel) {
            lastAccelX = x
            lastAccelY = y
            lastAccelZ = z
            hasLastAccel = true
            return
        }
        
        // Calculate delta acceleration (jerk) in X and Y axes
        // Ignore Z (vertical) to focus on side-to-side shakes
        val deltaX = x - lastAccelX
        val deltaY = y - lastAccelY
        
        lastAccelX = x
        lastAccelY = y
        lastAccelZ = z
        
        // Check if delta exceeds threshold (significant acceleration change)
        val magnitude = sqrt(deltaX * deltaX + deltaY * deltaY)
        
        if (magnitude > shakeThreshold) {
            // Determine shake direction
            val dirX = when {
                deltaX > shakeThreshold * 0.7f -> 1
                deltaX < -shakeThreshold * 0.7f -> -1
                else -> 0
            }
            val dirY = when {
                deltaY > shakeThreshold * 0.7f -> 1
                deltaY < -shakeThreshold * 0.7f -> -1
                else -> 0
            }
            
            // Check for direction reversal (back-and-forth shake)
            val reversedX = lastShakeDirectionX != 0 && dirX != 0 && dirX != lastShakeDirectionX
            val reversedY = lastShakeDirectionY != 0 && dirY != 0 && dirY != lastShakeDirectionY
            
            if (reversedX || reversedY) {
                // Reset window if too much time has passed
                if (currentTime - shakeWindowStartTime > SHAKE_TIME_WINDOW_MS) {
                    shakeCount = 0
                    shakeWindowStartTime = currentTime
                }
                
                shakeCount++
                
                if (shakeCount >= SHAKE_COUNT_THRESHOLD) {
                    // Shake detected! Trigger listener and reset
                    Log.d(TAG, "Shake detected! Count=$shakeCount")
                    lastShakeTime = currentTime
                    shakeCount = 0
                    shakeListener?.onShake()
                }
            }
            
            lastShakeDirectionX = dirX
            lastShakeDirectionY = dirY
        }
    }
    
    private fun handleRotationVector(values: FloatArray, displayRotation: Int) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        
        // Remap coordinate system based on display rotation
        // This ensures sensor data matches what the user sees on screen
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        
        // Use Android's getOrientation() for proper pitch/roll extraction
        // This is the recommended approach after remapCoordinateSystem()
        // orientationAngles[0] = azimuth (rotation around Z axis)
        // orientationAngles[1] = pitch (rotation around X axis, -π to π)
        // orientationAngles[2] = roll (rotation around Y axis, -π/2 to π/2)
        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        
        val pitch = orientationAngles[1]  // Forward/backward tilt → Y parallax
        val roll = orientationAngles[2]   // Left/right tilt → X parallax
        
        // remappedMatrix[8] is the Z component of the device's Z-axis in world space.
        // When phone is flat, |Z[8]| ≈ 1. When phone is vertical (screen-facing), |Z[8]| ≈ 0.
        // Use hysteresis to prevent flip-flopping near vertical (gimbal lock region)
        val zCompRaw = remappedMatrix[8]
        updateScreenDownState(zCompRaw)
        val zComponent = abs(zCompRaw)
        poseGain = (zComponent / 0.3f).coerceIn(0f, 1f)  // Fade sensitivity below 0.3
        
        processOrientationAngles(pitch, roll)
    }
    
    private fun handleGravityVector(values: FloatArray, displayRotation: Int) {
        // Gravity/accelerometer sensors report values in the DEVICE coordinate system
        // (X right, Y up, Z out of screen in default orientation). We must remap them
        // based on displayRotation so the user sees consistent behavior in any rotation.
        val (gx, gy) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(values[1], -values[0])
            Surface.ROTATION_180 -> Pair(-values[0], -values[1])
            Surface.ROTATION_270 -> Pair(-values[1], values[0])
            else -> Pair(values[0], values[1])
        }
        val gz = values[2]
        
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 0.1f) return
        
        // Normalize for numerical stability
        val inv = 1f / norm
        val gxn = gx * inv
        val gyn = gy * inv
        val gzn = gz * inv
        
        // gzn is the normalized Z gravity component. Use hysteresis to prevent flip-flopping
        // at vertical, then use absolute for poseGain to reduce sensitivity near vertical.
        updateScreenDownState(-gzn)  // Negate because gravity Z sign is opposite to rotation matrix Z
        poseGain = (abs(gzn) / 0.3f).coerceIn(0f, 1f)  // Fade sensitivity below |gz| = 0.3
        
        // Correct formulas for pitch/roll from gravity:
        // When solving for angle about axis A, exclude A-component from denominator
        // Pitch = rotation about X axis → uses (Y,Z), denominator uses (X,Z) magnitude
        val pitch = atan2(-gyn, sqrt(gxn * gxn + gzn * gzn))   // [-π/2, +π/2]
        
        // Roll = rotation about Y axis → uses (X,Z), denominator uses (Y,Z) magnitude  
        val roll = atan2(gxn, sqrt(gyn * gyn + gzn * gzn))     // [-π/2, +π/2]
        
        processOrientationAngles(pitch, roll)
    }
    
    /**
     * Updates isScreenDown state with hysteresis to prevent flip-flopping near vertical.
     * @param z The Z component (positive = screen up, negative = screen down)
     */
    private fun updateScreenDownState(z: Float) {
        // Only change state when |z| is outside the freeze zone (prevents thrashing at vertical)
        if (abs(z) > SCREEN_FLIP_FREEZE) {
            if (!isScreenDown && z < SCREEN_DOWN_ON) {
                isScreenDown = true
            } else if (isScreenDown && z > SCREEN_DOWN_OFF) {
                isScreenDown = false
            }
        }
        // else: keep previous isScreenDown (frozen in ambiguous region)
    }
    
    private fun processOrientationAngles(pitch: Float, roll: Float) {
        // Delta-based motion detection: respond to movement, not absolute position
        // This works at ANY starting orientation (flat, tilted, vertical, upside-down)
        
        if (isFirstSample) {
            // First sample: just set reference, don't move yet
            prevPitch = pitch
            prevRoll = roll
            isFirstSample = false
            return
        }
        
        // Calculate motion since last frame
        var deltaPitch = pitch - prevPitch
        var deltaRoll = roll - prevRoll
        
        // Handle angle wraparound (if pitch/roll jumps from -π to +π)
        if (deltaPitch > PI.toFloat()) deltaPitch -= 2f * PI.toFloat()
        if (deltaPitch < -PI.toFloat()) deltaPitch += 2f * PI.toFloat()
        if (deltaRoll > PI.toFloat()) deltaRoll -= 2f * PI.toFloat()
        if (deltaRoll < -PI.toFloat()) deltaRoll += 2f * PI.toFloat()
        
        // Update reference for next frame
        prevPitch = pitch
        prevRoll = roll
        
        // Clamp delta to reasonable range (prevents huge jumps from sensor noise)
        deltaPitch = deltaPitch.coerceIn(-FULL_TILT_RADIANS * 0.5f, FULL_TILT_RADIANS * 0.5f)
        deltaRoll = deltaRoll.coerceIn(-FULL_TILT_RADIANS * 0.5f, FULL_TILT_RADIANS * 0.5f)
        
        // Apply pose gain: reduces sensitivity near vertical orientation (gimbal lock region)
        // poseGain is 0-1 where 0 = near vertical (unstable), 1 = flat (stable)
        deltaPitch *= poseGain
        deltaRoll *= poseGain
        
        // Accumulate motion into parallax offset (like moving a camera)
        // Sensitivity factor converts radians to parallax units
        val sensitivity = 2.0f  // Higher = more responsive
        accumulatedX += -deltaRoll * sensitivity
        accumulatedY += -deltaPitch * sensitivity
        
        // Clamp accumulated offset to ±1 range
        accumulatedX = accumulatedX.coerceIn(-1f, 1f)
        accumulatedY = accumulatedY.coerceIn(-1f, 1f)
        
        // Slow decay back to center when not moving (feels natural)
        accumulatedX *= DECAY_RATE
        accumulatedY *= DECAY_RATE
        
        processTiltValues(accumulatedX, accumulatedY)
    }
    
    private fun processTiltValues(targetX: Float, targetY: Float) {
        // Per-axis deadzone instead of magnitude-based
        // This prevents killing single-axis motion when combined magnitude is small
        val scaledX = if (abs(targetX) > DEADZONE) targetX else 0f
        val scaledY = if (abs(targetY) > DEADZONE) targetY else 0f
        
        filteredX += FILTER_ALPHA * (scaledX - filteredX)
        filteredY += FILTER_ALPHA * (scaledY - filteredY)

        // Conditionally flip vertical axis depending on whether the screen is down.
        // For screen-up we invert Y for consumers; when screen-down the pitch sign
        // relationship flips, so cancel the inversion.
        val outY = if (isScreenDown) filteredY else -filteredY
        listener?.onTiltChanged(filteredX, outY)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
