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
        // Near-direct pass-through for minimal latency (reference app uses no filter)
        // Higher = faster response (1.0 = instant, 0.4 = old sluggish value)
        private const val FILTER_ALPHA = 0.85f
        private const val FULL_TILT_RADIANS = (45f * PI / 180f).toFloat()
        private const val DEADZONE = 0.03f
        
        // Gyroscope drift prevention: ignore very small angular velocities
        // Typical gyro noise is ~0.01 rad/s, so 0.03 rad/s (~1.7°/s) filters noise
        private const val GYRO_DEADZONE_RADS = 0.03f
        // Gyro accumulated angle decay (prevents drift buildup)
        private const val GYRO_DECAY_RATE = 0.998f  // Slowly decay to center
        
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
        
        // Default gyro wiggle thresholds (tuned via sensitivity)
        private const val DEFAULT_GYRO_AXIS_THRESHOLD = 1.3f  // rad/s angular velocity threshold
        private const val DEFAULT_GYRO_WINDOW_MS = 900L       // Time window for wiggle detection
        private const val DEFAULT_GYRO_TRIGGER_CHANGES = 4    // Direction changes to trigger
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
    
    // Decay rate controls how fast the scene returns to center after movement stops
    // Default 0.995f gives a slow, natural return
    // Modified by setReturnSpeed() - range 0.9f (fast) to 1.0f (no return)
    private var decayRate = 0.995f
    
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
    
    // Gyroscope wiggle detection state - detects wrist wiggle via angular velocity
    private var gyroLastSign = 0
    private var gyroSignChanges = 0
    private var gyroWiggleStart = 0L
    
    // Configurable thresholds (tuned by sensitivity)
    private var shakeThresholdGravity = SHAKE_THRESHOLD_GRAVITY
    private var shakeTriggerCount = SHAKE_TRIGGER_COUNT
    private var shakeSlopTimeMs = SHAKE_SLOP_TIME_MS
    private var gyroAxisThreshold = DEFAULT_GYRO_AXIS_THRESHOLD
    private var gyroWindowMs = DEFAULT_GYRO_WINDOW_MS
    private var gyroTriggerChanges = DEFAULT_GYRO_TRIGGER_CHANGES

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
     * @param sensitivity 0.0 = hard shake only, 1.0 = sensitive wiggle detection
     */
    fun setShakeSettings(enabled: Boolean, sensitivity: Float = 0.5f) {
        shakeEnabled = enabled
        
        // Map sensitivity to thresholds: higher sensitivity = lower thresholds, fewer required changes
        val s = sensitivity.coerceIn(0f, 1f)
        
        // Accelerometer: high sensitivity => lower g-force threshold, fewer shakes needed
        shakeThresholdGravity = lerp(2.9f, 1.8f, s)
        shakeTriggerCount = if (s > 0.7f) 2 else 3
        shakeSlopTimeMs = if (s > 0.7f) 250L else 400L
        
        // Gyroscope wiggle: high sensitivity => lower angular velocity threshold
        // At default 0.5: threshold = 0.55 rad/s (~31 deg/s), 4 direction changes in 1000ms
        // Range: 0.8 rad/s (46 deg/s) at low sensitivity to 0.3 rad/s (17 deg/s) at high
        gyroAxisThreshold = lerp(0.8f, 0.3f, s)
        gyroWindowMs = if (s > 0.5f) 1200L else 1000L
        gyroTriggerChanges = if (s > 0.7f) 3 else 4
        
        Log.d(TAG, "Shake settings: enabled=$enabled sens=$s gThresh=$shakeThresholdGravity gyroThresh=$gyroAxisThreshold")
        
        // If manager is already started, re-register shake sensors to handle runtime toggle
        if (isStarted) {
            // Unregister sensors if not the active parallax sensor
            accelerometer?.let { if (it != activeSensor) sensorManager.unregisterListener(this, it) }
            gyroscope?.let { if (it != activeSensor) sensorManager.unregisterListener(this, it) }
            
            // Re-register if shake is now enabled
            registerShakeSensors()
            
            Log.d(TAG, "Shake settings updated at runtime: enabled=$enabled")
        }
    }
    
    /** Linear interpolation between a and b */
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    
    /**
     * Register shake detection sensors.
     * Uses accelerometer with gForce detection and gyroscope for wiggle detection.
     */
    private fun registerShakeSensors() {
        if (!shakeEnabled) {
            return
        }
        
        // Reset shake state
        shakeTimestamp = 0L
        shakeCount = 0
        
        // Reset gyro wiggle state
        gyroLastSign = 0
        gyroSignChanges = 0
        gyroWiggleStart = 0L
        
        // Use SENSOR_DELAY_GAME for shake detection (responsive wiggle detection)
        val sensorRate = SensorManager.SENSOR_DELAY_GAME
        
        // ALWAYS register accelerometer for shake - gForce detection is proven reliable
        // We don't use LINEAR_ACCELERATION anymore as it's often too smoothed on some devices
        val okAccel = if (accelerometer != null && accelerometer != activeSensor) {
            sensorManager.registerListener(this, accelerometer, sensorRate)
        } else if (accelerometer == activeSensor) {
            true  // Already registered as active sensor
        } else {
            false
        }
        
        // Register gyroscope for wiggle detection (even when rotation vector is active for parallax)
        val okGyro = if (gyroscope != null && gyroscope != activeSensor) {
            sensorManager.registerListener(this, gyroscope, sensorRate)
        } else if (gyroscope == activeSensor) {
            true  // Already registered as active sensor
        } else {
            false
        }
        
        Log.d(TAG, "Shake sensors registered: accel=$okAccel gyro=$okGyro")
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
            
            // Use SENSOR_DELAY_GAME (~20ms, 50Hz) for responsive parallax
            // Battery savings come from unregistering when not visible (done in stop())
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
    
    /**
     * Update return speed based on user preference.
     * @param speed 0.0 to 1.0 (0=no return, 1=fast return)
     */
    fun setReturnSpeed(speed: Float) {
        // Map 0..1 to decay rate 1.0..0.9
        // speed=0 -> rate=1.0 (no decay, holds position)
        // speed=0.5 -> rate=0.995 (default slow decay)
        // speed=1.0 -> rate=0.9 (fast decay)
        
        // Use exponential curve for better feel?
        // Let's stick to mapped linear for predictability
        // 0.0 -> 1.0
        // 1.0 -> 0.9
        decayRate = (1.0f - (speed * 0.1f)).coerceIn(0.9f, 1.0f)
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
            
            // Process gyro wiggle detection when gyroscope events come in
            // This runs when shake is enabled, even if gyro is not the active parallax sensor
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE && shakeEnabled) {
                detectGyroWiggle(event.values)
                
                // Only use gyroscope for parallax if it's the active sensor
                if (activeSensor?.type != Sensor.TYPE_GYROSCOPE) {
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
        
        // Apply dead zone to filter sensor noise (prevents jitter buildup on flat surfaces)
        val filteredRateX = if (abs(rateX) < GYRO_DEADZONE_RADS) 0f else rateX
        val filteredRateY = if (abs(rateY) < GYRO_DEADZONE_RADS) 0f else rateY
        val filteredRateZ = if (abs(rateZ) < GYRO_DEADZONE_RADS) 0f else rateZ
        
        // Add filtered values to averagers for smoothing
        avgPitch.add(filteredRateX)
        avgRoll.add(filteredRateY)
        avgYaw.add(filteredRateZ)
        
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
        
        // Get averaged rates
        val avgPitchRate = avgPitch.calc()
        val avgRollRate = avgRoll.calc()
        
        // Accumulate averaged angular velocity into angle (only if above noise floor)
        if (abs(avgPitchRate) >= GYRO_DEADZONE_RADS) {
            gyroPitch = (gyroPitch + avgPitchRate * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        } else {
            // Decay toward zero when no significant motion (prevents drift buildup)
            gyroPitch *= GYRO_DECAY_RATE
        }
        
        if (abs(avgRollRate) >= GYRO_DEADZONE_RADS) {
            gyroRoll = (gyroRoll + avgRollRate * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        } else {
            gyroRoll *= GYRO_DECAY_RATE
        }
        
        gyroYaw = (gyroYaw + avgYaw.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        
        // Update time AFTER accumulation
        lastGyroTime = currentTime
        
        // Convert gyro angles to tilt output (-1 to 1 range)
        // Roll = left/right tilt → tiltX, Pitch = forward/back tilt → tiltY
        // Note: gyroRoll is negated to match rotation vector behavior (invert horizontal for gyro)
        val tiltX = (-gyroRoll / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (-gyroPitch / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        // Gyroscope mode: Output directly without additional filtering
        // The 3-sample averaging already provides sufficient smoothing
        // Reference app (RotationManager.java) uses this exact approach for ~20ms latency
        filteredX = tiltX
        filteredY = tiltY
        
        listener?.onTiltChanged(filteredX, filteredY)
    }
    
    /**
     * Detect shake from accelerometer using gForce method.
     * This is the proven reliable approach used by most working shake detectors.
     * 
     * At rest, gForce ≈ 1.0 (gravity only)
     * During shake, gForce exceeds 2.0+ (additional acceleration)
     * Threshold is tuned by sensitivity setting.
     */
    private fun detectShakeFromAccelerometer(values: FloatArray) {
        val g = SensorManager.GRAVITY_EARTH
        val gX = values[0] / g
        val gY = values[1] / g
        val gZ = values[2] / g
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        // Only process if gForce exceeds threshold (tuned by sensitivity)
        if (gForce < shakeThresholdGravity) return
        
        val now = System.currentTimeMillis()
        
        // Check cooldown after a successful shake trigger
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
        
        // Ignore shakes too close to each other (debounce)
        if (shakeTimestamp + shakeSlopTimeMs > now) return
        
        // Reset count if no shakes for a while
        if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
            shakeCount = 0
        }
        
        shakeTimestamp = now
        shakeCount++
        
        Log.d(TAG, "Shake count=$shakeCount gForce=${"%.2f".format(gForce)}")
        
        if (shakeCount >= shakeTriggerCount) {
            shakeCount = 0
            lastShakeTime = now
            Log.d(TAG, "Shake gesture detected! Triggering listener")
            shakeListener?.onShake()
        }
    }
    
    /**
     * Detect wrist wiggle from gyroscope angular velocity.
     * This is the recommended rocket trigger for "wiggle in hand" gesture.
     * 
     * Uses yaw (Z-axis) angular velocity sign changes to detect quick wrist turns.
     * Works better than accelerometer gForce for subtle hand movements.
     * 
     * @param values Gyroscope sensor values [x=pitch rate, y=roll rate, z=yaw rate] in rad/s
     */
    private fun detectGyroWiggle(values: FloatArray) {
        val now = System.currentTimeMillis()
        
        // Check cooldown after a successful shake trigger (shared with other detectors)
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
        
        // Use yaw (Z-axis) for wrist wiggle detection - rotates when you twist your wrist
        val yawRate = values[2]
        
        // Ignore small movements below threshold
        if (abs(yawRate) < gyroAxisThreshold) return
        
        // Determine direction: positive = right, negative = left
        val sign = if (yawRate > 0) 1 else -1
        
        // Check if direction changed from last movement
        if (gyroLastSign != 0 && sign != gyroLastSign) {
            // Direction changed! This is a wiggle
            if (gyroSignChanges == 0) {
                gyroWiggleStart = now
            }
            gyroSignChanges++
            
            Log.d(TAG, "Gyro wiggle change=$gyroSignChanges yawRate=${"%.2f".format(yawRate)}")
            
            // Check if enough changes within time window
            if (now - gyroWiggleStart <= gyroWindowMs && gyroSignChanges >= gyroTriggerChanges) {
                gyroSignChanges = 0
                gyroWiggleStart = 0L
                gyroLastSign = 0
                lastShakeTime = now
                Log.d(TAG, "Gyro wiggle gesture detected! Triggering listener")
                shakeListener?.onShake()
                return
            }
        }
        
        // Reset if too much time passed since first wiggle
        if (gyroSignChanges > 0 && now - gyroWiggleStart > gyroWindowMs) {
            gyroSignChanges = 0
            gyroWiggleStart = 0L
            gyroLastSign = 0
            Log.d(TAG, "Gyro wiggle reset - timeout")
        } else {
            gyroLastSign = sign
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
        accumulatedX *= decayRate
        accumulatedY *= decayRate
        
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
