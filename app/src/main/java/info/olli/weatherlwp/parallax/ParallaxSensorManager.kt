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
        
        // Shake detection constants - gForce-based approach (proven reliable)
        // This matches common "shake detector" implementations that work on real devices
        private const val SHAKE_THRESHOLD_GRAVITY = 2.7f  // gForce threshold (~2.7g during shake)
        private const val SHAKE_SLOP_TIME_MS = 500L       // Ignore shakes too close together
        private const val SHAKE_COUNT_RESET_TIME_MS = 3000L  // Reset count if no shakes for this long
        private const val SHAKE_TRIGGER_COUNT = 3          // Number of shakes to trigger event
        private const val SHAKE_COOLDOWN_MS = 1000L        // Cooldown after triggering shake event
        
        // Roll wiggle detection (quick wrist turns around arm axis)
        // This is an alternative trigger for shake - fast roll direction changes
        private const val WIGGLE_ROLL_THRESHOLD_RAD = 0.2f  // Minimum roll angle change (~11 degrees)
        private const val WIGGLE_TIME_WINDOW_MS = 800L     // Time window to detect wiggles
        private const val WIGGLE_TRIGGER_COUNT = 4         // Direction changes to trigger (4 = 2 back-and-forth)
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
    
    /**
     * Parallax sensor mode - determines which algorithm is used.
     */
    enum class ParallaxMode {
        /** Our implementation: Delta-based with rotation vector (works at any orientation) */
        ROTATION_VECTOR,
        /** Gyroscope with averaging (simpler, very smooth) */
        GYROSCOPE
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // Available sensors
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    
    // Active sensor depends on mode
    private var activeSensor: Sensor? = rotationVector ?: gameRotationVector ?: gravitySensor ?: accelerometer
    
    // Parallax mode - can be changed at runtime
    private var parallaxMode = ParallaxMode.ROTATION_VECTOR
    
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
    
    // ==== Gyroscope mode state approach) ====
    // Simple averager buffers (3 samples each)
    private val avgYaw = Averager(3)
    private val avgPitch = Averager(3)
    private val avgRoll = Averager(3)
    
    // Accumulated angles from gyroscope (radians, clamped to ±18°)
    private var gyroYaw = 0f
    private var gyroPitch = 0f
    private var gyroRoll = 0f
    private var lastGyroTime = 0L
    
    // Maximum tilt angle in radians (18 degrees: 0.31415927)
    private val MAX_TILT_RAD = (18f * PI / 180f).toFloat()
    
    private var listener: Listener? = null
    private var shakeListener: ShakeListener? = null
    private var isStarted = false
    
    // Shake detection state - gForce-based approach
    private var shakeEnabled = true
    private var shakeTimestamp = 0L  // Last shake detection time
    private var shakeCount = 0
    private var lastShakeTime = 0L   // Last triggered shake event time (for cooldown)
    
    // Roll wiggle detection state - detects quick wrist turns
    private var lastRollAngle = 0f
    private var rollDirectionChanges = 0
    private var lastRollDirection = 0  // -1=left, 0=none, 1=right
    private var rollWiggleStartTime = 0L

    fun setListener(l: Listener) { listener = l }
    
    fun setShakeListener(l: ShakeListener?) { shakeListener = l }
    
    /**
     * Set the parallax sensor mode.
     * @param mode ROTATION_VECTOR (our implementation) or GYROSCOPE
     */
    fun setParallaxMode(mode: ParallaxMode) {
        if (mode != parallaxMode) {
            val wasStarted = isStarted
            if (wasStarted) stop()
            parallaxMode = mode
            if (wasStarted) start()
        }
    }
    
    fun getParallaxMode(): ParallaxMode = parallaxMode
    
    /**
     * Configure shake detection.
     * @param enabled Whether shake detection is enabled
     * @param sensitivity Not used - gForce threshold is fixed at 2.7g for reliability
     */
    fun setShakeSettings(enabled: Boolean, sensitivity: Float = 0.5f) {
        shakeEnabled = enabled
        
        // If manager is already started, re-register shake sensors to handle runtime toggle
        if (isStarted) {
            // Unregister accelerometer if it's not the active parallax sensor
            accelerometer?.let { if (it != activeSensor) sensorManager.unregisterListener(this, it) }
            
            // Re-register if shake is now enabled
            registerShakeSensors()
            
            Log.d(TAG, "Shake settings updated at runtime: enabled=$enabled")
        }
    }
    
    /**
     * Register shake detection sensors.
     * Uses only accelerometer with gForce detection (proven reliable across devices).
     */
    private fun registerShakeSensors() {
        if (!shakeEnabled) {
            return
        }
        
        // Reset shake state
        shakeTimestamp = 0L
        shakeCount = 0
        
        // ALWAYS register accelerometer for shake - gForce detection is proven reliable
        // We don't use LINEAR_ACCELERATION anymore as it's often too smoothed on some devices
        val okAccel = if (accelerometer != null && accelerometer != activeSensor) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelerometer == activeSensor) {
            true  // Already registered as active sensor
        } else {
            false
        }
        
        Log.d(TAG, "Shake sensor registered: accel=$okAccel")
    }

    fun start() {
        // Select sensor based on mode
        activeSensor = when (parallaxMode) {
            ParallaxMode.GYROSCOPE -> gyroscope ?: rotationVector ?: gameRotationVector ?: gravitySensor
            ParallaxMode.ROTATION_VECTOR -> rotationVector ?: gameRotationVector ?: gravitySensor ?: accelerometer
        }
        
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
            
            // Reset gyroscope state
            gyroYaw = 0f
            gyroPitch = 0f
            gyroRoll = 0f
            avgYaw.reset()
            avgPitch.reset()
            avgRoll.reset()
            lastGyroTime = System.nanoTime()
            
            // Reset shake state
            shakeCount = 0
            lastShakeTime = 0L
            shakeTimestamp = 0L
            
            // Use SENSOR_DELAY_GAME for both modes (~50Hz) - good balance between responsiveness and battery
            // SENSOR_DELAY_FASTEST (100-500Hz) causes significant battery drain
            val sensorRate = SensorManager.SENSOR_DELAY_GAME
            val okActive = sensorManager.registerListener(this, activeSensor, sensorRate)
            Log.d(TAG, "Active sensor registered=$okActive type=${activeSensor?.type} name=${activeSensor?.name}")
            
            // Register shake detection sensors using the helper
            registerShakeSensors()
            
            isStarted = true
            Log.d(TAG, "Sensor started with mode: $parallaxMode, shakeEnabled=$shakeEnabled")
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
        // Reset gyroscope state
        gyroYaw = 0f
        gyroPitch = 0f
        gyroRoll = 0f
        avgYaw.reset()
        avgPitch.reset()
        avgRoll.reset()
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
            
            // Process shake detection from accelerometer using gForce method
            // This runs unconditionally on every accelerometer event when shake is enabled
            // (proven reliable approach - no dependency on LINEAR_ACCELERATION sensor)
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && shakeEnabled) {
                detectShakeFromAccelerometer(event.values)
                
                // Only use accelerometer for parallax if it's the active sensor (fallback)
                if (activeSensor?.type != Sensor.TYPE_ACCELEROMETER) {
                    return
                }
            }

            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    handleGyroscope(event.values, displayRotation)
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    handleRotationVector(event.values, displayRotation)
                }
                Sensor.TYPE_GRAVITY -> {
                    handleGravityVector(event.values, displayRotation)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // Only reach here if accelerometer is the active sensor (line 201 check passed)
                    handleGravityVector(event.values, displayRotation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor event: ${e.message}", e)
        }
    }
    
    /**
     * Handle gyroscope sensor data (reference app approach).
     * 
     * Reference app RotationManager.java:
     * - values[0] → lastYaw (Z-axis rotation rate - twisting phone)
     * - values[1] → lastPitch (X-axis rotation rate - tilting forward/back)
     * - values[2] → lastRoll (Y-axis rotation rate - tilting left/right)
     * 
     * Reference app accumulates RAW values without display rotation adjustment.
     * Display rotation is applied AFTER accumulation in ForestRenderer:
     * - ROTATION_0: tiltX = -lastPitch, tiltY = lastYaw
     * - ROTATION_90: tiltX = -lastYaw, tiltY = -lastPitch
     * - ROTATION_270: tiltX = lastYaw, tiltY = lastPitch
     * 
     * Uses 3-sample averaging, formula: angle += avgRate * deltaTimeNanos * 1e-8 * 6 * DEG_TO_RAD
     * Clamps to ±18° (0.31415927 rad)
     */
    private fun handleGyroscope(values: FloatArray, displayRotation: Int) {
        // Gyroscope reports angular velocity in rad/s around device axes
        // values[0] = rotation rate around X axis (pitch rate)
        // values[1] = rotation rate around Y axis (roll rate)
        // values[2] = rotation rate around Z axis (yaw rate)
        
        // Remap based on display rotation (same approach as v1.4.6 that worked)
        val (rateX, rateY, rateZ) = when (displayRotation) {
            Surface.ROTATION_90 -> Triple(values[1], -values[0], values[2])
            Surface.ROTATION_180 -> Triple(-values[0], -values[1], values[2])
            Surface.ROTATION_270 -> Triple(-values[1], values[0], values[2])
            else -> Triple(values[0], values[1], values[2])
        }
        
        // Add remapped values to averagers for smoothing
        avgPitch.add(rateX)
        avgRoll.add(rateY)
        avgYaw.add(rateZ)
        
        // Calculate time delta
        val currentTime = System.nanoTime()
        val deltaTimeNanos = currentTime - lastGyroTime
        
        // Skip first sample or stale data
        if (deltaTimeNanos <= 0 || deltaTimeNanos > 100_000_000L) {
            lastGyroTime = currentTime
            return
        }
        
        // Reference formula: f = deltaTimeNanos * 1e-8 * 6.0 * DEG_TO_RAD
        val f = deltaTimeNanos * 1e-8f * 6.0f * 0.017453292f
        
        // Accumulate averaged angular velocity into angle
        gyroPitch = (gyroPitch + avgPitch.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        gyroRoll = (gyroRoll + avgRoll.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        gyroYaw = (gyroYaw + avgYaw.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        
        // Update time AFTER accumulation
        lastGyroTime = currentTime
        
        // Convert gyro angles to tilt output (-1 to 1 range)
        // Roll = left/right tilt → tiltX, Pitch = forward/back tilt → tiltY
        // Note: gyroRoll is negated to match rotation vector behavior (invert horizontal for gyro)
        val tiltX = (-gyroRoll / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (-gyroPitch / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        // Apply low-pass filter for extra smoothness
        filteredX += FILTER_ALPHA * (tiltX - filteredX)
        filteredY += FILTER_ALPHA * (tiltY - filteredY)
        
        listener?.onTiltChanged(filteredX, filteredY)
    }
    
    /**
     * Detect shake from accelerometer using gForce method.
     * This is the proven reliable approach used by most working shake detectors.
     * 
     * At rest, gForce ≈ 1.0 (gravity only)
     * During shake, gForce exceeds 2.0+ (additional acceleration)
     * Threshold of 2.7g filters out normal movements while detecting real shakes.
     */
    private fun detectShakeFromAccelerometer(values: FloatArray) {
        val g = SensorManager.GRAVITY_EARTH
        val gX = values[0] / g
        val gY = values[1] / g
        val gZ = values[2] / g
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        // Only process if gForce exceeds threshold (2.7g = significant shake)
        if (gForce < SHAKE_THRESHOLD_GRAVITY) return
        
        val now = System.currentTimeMillis()
        
        // Check cooldown after a successful shake trigger
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
        
        // Ignore shakes too close to each other (debounce)
        if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) return
        
        // Reset count if no shakes for a while
        if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
            shakeCount = 0
        }
        
        shakeTimestamp = now
        shakeCount++
        
        Log.d(TAG, "Shake count=$shakeCount gForce=${"%.2f".format(gForce)}")
        
        if (shakeCount >= SHAKE_TRIGGER_COUNT) {
            shakeCount = 0
            lastShakeTime = now
            Log.d(TAG, "Shake gesture detected! Triggering listener")
            shakeListener?.onShake()
        }
    }
    
    /**
     * Detect roll wiggle gesture - quick wrist turns around the arm axis.
     * This is an alternative to shake for users who prefer a more subtle gesture.
     * 
     * Detects rapid direction changes in roll angle (left-right turns).
     * 4 direction changes (2 complete back-and-forth movements) trigger the event.
     */
    private fun detectRollWiggle(currentRoll: Float) {
        val now = System.currentTimeMillis()
        
        // Check cooldown after a successful shake trigger (shared with shake)
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
        
        val rollDelta = currentRoll - lastRollAngle
        
        // Only process if roll changed enough to be intentional
        if (abs(rollDelta) < WIGGLE_ROLL_THRESHOLD_RAD) return
        
        // Determine direction: positive = right, negative = left
        val direction = if (rollDelta > 0) 1 else -1
        
        // Check if direction changed from last movement
        if (lastRollDirection != 0 && direction != lastRollDirection) {
            // Direction changed! This is a wiggle
            rollDirectionChanges++
            Log.d(TAG, "Roll wiggle direction change=$rollDirectionChanges, delta=${"%.2f".format(rollDelta)}")
        }
        
        lastRollDirection = direction
        lastRollAngle = currentRoll
        
        // Reset if too much time passed since first wiggle
        if (rollDirectionChanges > 0 && rollWiggleStartTime > 0 && 
            now - rollWiggleStartTime > WIGGLE_TIME_WINDOW_MS) {
            rollDirectionChanges = 0
            rollWiggleStartTime = 0L
            Log.d(TAG, "Roll wiggle reset - timeout")
        }
        
        // Start timing on first direction change
        if (rollDirectionChanges == 1 && rollWiggleStartTime == 0L) {
            rollWiggleStartTime = now
        }
        
        // Trigger shake event on enough direction changes
        if (rollDirectionChanges >= WIGGLE_TRIGGER_COUNT) {
            rollDirectionChanges = 0
            rollWiggleStartTime = 0L
            lastRollDirection = 0
            lastShakeTime = now  // Use shared cooldown
            Log.d(TAG, "Roll wiggle gesture detected! Triggering listener")
            shakeListener?.onShake()
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
            lastRollAngle = roll  // Initialize roll wiggle detection
            isFirstSample = false
            return
        }
        
        // Detect roll wiggle (quick wrist turns) for shake alternative
        if (shakeEnabled) {
            detectRollWiggle(roll)
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

/**
 * Simple circular buffer averager.
 * Computes running average of last N values for smooth sensor output.
 */
internal class Averager(private val bufferSize: Int) {
    private val values = FloatArray(bufferSize)
    private var index = 0
    
    fun add(value: Float): Averager {
        values[index] = value
        index = (index + 1) % bufferSize
        return this
    }
    
    fun calc(): Float {
        var sum = 0f
        for (v in values) sum += v
        return sum / bufferSize
    }
    
    fun reset(value: Float = 0f) {
        for (i in values.indices) values[i] = value
    }
}
