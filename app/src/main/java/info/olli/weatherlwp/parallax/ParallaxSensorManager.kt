package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
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
 * 1. Direct pass-through: no exponential smoothing, no deadzone on sensor side
 * 2. Gyroscope mode: 3-sample moving average, direct integration, ±18° clamp
 * 3. Rotation vector mode: delta-based accumulation, no decay
 * 4. All smoothing/return-to-center handled by ParallaxCalculator
 * 5. Auto-resets when display rotation changes
 */
class ParallaxSensorManager(private val context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        private const val FULL_TILT_RADIANS = (45f * PI / 180f).toFloat()
        
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
        
        // Sensor warmup: ignore first N sensor events after start() to prevent stale queued events
        // from triggering shakes immediately after unlock (in addition to time-based delay)
        private const val SHAKE_WARMUP_SAMPLES = 120
    }
    
    interface Listener {
        fun onTiltChanged(tiltX: Float, tiltY: Float)
    }
    
    interface ShakeListener {
        /**
         * Called on a hard shake or a quick wiggle around the phone's long axis.
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
    
    // Pose gain: reduces sensitivity when near vertical (gimbal-lock region)
    // 1.0 = flat (stable), 0.0 = vertical (unstable)
    private var poseGain = 1f

    // Auto-return rate: controls gentle return-to-center when device is held still.
    // 1.0 = no decay (holds position indefinitely)
    // <1.0 = gentle decay per sensor sample (only when device is not moving)
    // Set via setAutoReturnRate(). NOT applied during active tilting.
    private var autoReturnRate = 1f

    // Track whether the screen is facing down (used to conditionally flip Y)
    private var isScreenDown = false
    
    // Track display rotation for reset on rotation change
    private var lastDisplayRotation = Surface.ROTATION_0
    
    // Canvas orientation fallback: used ONLY when the real display rotation cannot be read.
    // The reliable rotation source is DisplayManager (see getCurrentDisplayRotation()); this
    // flag is a last-resort hint set via setCanvasOrientation().
    @Volatile private var canvasIsLandscape = false
    
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
    
    @Volatile private var listener: Listener? = null
    @Volatile private var shakeListener: ShakeListener? = null
    private var isStarted = false
    
    // Shake detection state - gForce-based approach
    private var shakeEnabled = true
    private var shakeTimestamp = 0L  // Last shake detection time
    private var shakeCount = 0
    private var lastShakeTime = 0L   // Last triggered shake event time (for cooldown)
    
    // Wiggle around the phone's long axis (earpiece to charging port)
    private val longAxisWiggle = AxisWiggleDetector()
    
    // Configurable thresholds (tuned by sensitivity)
    private var shakeThresholdGravity = SHAKE_THRESHOLD_GRAVITY
    private var shakeTriggerCount = SHAKE_TRIGGER_COUNT
    private var shakeSlopTimeMs = SHAKE_SLOP_TIME_MS
    
    // Shake unlock delay: prevents accidental shake triggers after screen on (phone unlock motion)
    private var shakeUnlockDelayMs = 2500L
    private var screenOnTime = 0L  // Timestamp when start() was called (screen became visible)
    
    // Sensor warmup: counts down sensor samples to ignore after start()
    // Prevents stale queued sensor events from triggering shakes immediately after unlock
    private var shakeWarmupSamplesRemaining = 0
    
    // Acceleration stability tracking: reject single-direction forces (pocket/desk)
    // A real shake oscillates, so we track recent high-g direction signs
    private var lastShakeSignX = 0
    private var lastShakeSignY = 0
    private var lastShakeSignZ = 0
    private var shakeDirectionChanges = 0
    private var lastHighGTime = 0L

    fun setListener(l: Listener) { listener = l }
    
    fun setShakeListener(l: ShakeListener?) { shakeListener = l }

    /**
     * Clear external callbacks to avoid retaining scene/service references after teardown.
     */
    fun clearCallbacks() {
        listener = null
        shakeListener = null
    }
    
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
     * @param unlockDelayMs Delay in ms after screen on before shake triggers are accepted
     */
    fun setShakeSettings(enabled: Boolean, sensitivity: Float = 0.5f, unlockDelayMs: Int = 500) {
        shakeEnabled = enabled
        shakeUnlockDelayMs = unlockDelayMs.toLong()
        
        // Map sensitivity to thresholds: higher sensitivity = lower thresholds, fewer required changes
        val s = sensitivity.coerceIn(0f, 1f)
        
        // Accelerometer: keep low sensitivity meaningfully strict for pocket/hand movement.
        shakeThresholdGravity = lerp(4.6f, 2.4f, s)
        shakeTriggerCount = if (s > 0.75f) 3 else if (s > 0.35f) 4 else 5
        shakeSlopTimeMs = if (s > 0.75f) 300L else if (s > 0.35f) 450L else 600L
        
        // Long-axis wiggle: sensitivity lowers the rotation speed and reversal count needed.
        longAxisWiggle.rateThreshold = lerp(3.0f, 1.2f, s)
        longAxisWiggle.requiredReversals = if (s > 0.66f) 3 else if (s > 0.33f) 4 else 5
        longAxisWiggle.windowMs = 1800L
        
        Log.d(TAG, "Shake settings: enabled=$enabled sens=$s gThresh=$shakeThresholdGravity wiggleRate=${longAxisWiggle.rateThreshold}")
        
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
        longAxisWiggle.reset()
        
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
            
            // Record screen on time for shake unlock delay
            // Prevents accidental shake triggers from phone unlock gesture
            screenOnTime = System.currentTimeMillis()
            
            // Initialize sensor warmup counter to ignore first N sensor events
            // This prevents stale queued events from triggering shakes immediately after unlock
            shakeWarmupSamplesRemaining = SHAKE_WARMUP_SAMPLES
            
            // Use SENSOR_DELAY_GAME (~20ms, 50Hz) for responsive parallax
            // Battery savings come from unregistering when not visible (done in stop())
            val sensorRate = SensorManager.SENSOR_DELAY_GAME
            val okActive = sensorManager.registerListener(this, activeSensor, sensorRate)
            Log.d(TAG, "Active sensor registered=$okActive type=${activeSensor?.type} name=${activeSensor?.name}")
            
            // Register shake detection sensors using the helper
            registerShakeSensors()
            
            isStarted = true
            Log.d(TAG, "Sensor started with mode: $parallaxMode, shakeEnabled=$shakeEnabled, unlockDelay=${shakeUnlockDelayMs}ms")
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
     * Set auto-return rate for rotation-vector / gravity modes.
     * Only applies when the device is held still (no active tilting).
     * @param speed 0.0 to 1.0 (0 = no return/holds forever, 1 = fast return)
     */
    fun setAutoReturnRate(speed: Float) {
        // Map user's return-speed 0..1 to per-sample rate:
        //   speed=0   → rate=1.0     (no decay, holds position)
        //   speed=0.15→ rate≈0.99994 (≈0.3%/sec at 50 Hz, very gentle)
        //   speed=1.0 → rate=0.9995  (≈2.5%/sec at 50 Hz, noticeable)
        autoReturnRate = if (speed <= 0.001f) 1f
            else (1f - speed * 0.0005f).coerceIn(0.999f, 1f)
    }

    fun isRunning(): Boolean = isStarted
    
    private fun getCurrentDisplayRotation(): Int {
        // Read the REAL display rotation. In a WallpaperService the engine runs on a
        // non-visual Service context, where Context.getDisplay() is unreliable (it throws
        // UnsupportedOperationException on Android R+, which the catch below turns into a
        // constant ROTATION_0). That made the wallpaper ignore device rotation entirely, so
        // in landscape the parallax axes were remapped as if still portrait (swapped /
        // "barely moves" tilt). DisplayManager.getDisplay(DEFAULT_DISPLAY) returns the actual
        // default-display rotation from ANY context, including a Service, and is reliable.
        val rotation = readDisplayRotation()
        if (rotation != null) return rotation
        // Fallback only when no display rotation is available: infer from the canvas
        // orientation that parallax offsets are actually applied to.
        return if (canvasIsLandscape) Surface.ROTATION_90 else Surface.ROTATION_0
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRotation(): Int? {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
                }
            display?.rotation
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Inform the sensor manager of the actual canvas orientation.
     * Call this when the canvas/surface size changes.
     *
     * This is now only a FALLBACK hint: the primary rotation source is the real display
     * rotation read via DisplayManager (reliable even from a WallpaperService context).
     * The canvas orientation is consulted only if that rotation is unavailable.
     */
    fun setCanvasOrientation(width: Float, height: Float) {
        canvasIsLandscape = width > height
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
     * Handle gyroscope sensor data.
     * 
     * Gyroscope approach:
     * - values[0] → yaw (Z-axis rotation rate - twisting phone)
     * - values[1] → pitch (X-axis rotation rate - tilting forward/back)
     * - values[2] → roll (Y-axis rotation rate - tilting left/right)
     * 
     * Accumulates RAW values without display rotation adjustment.
     * Display rotation is applied AFTER accumulation:
     * - ROTATION_0: tiltX = -pitch, tiltY = yaw
     * - ROTATION_90: tiltX = -yaw, tiltY = -pitch
     * - ROTATION_270: tiltX = yaw, tiltY = pitch
     * 
     * Algorithm:
     * 1. Add raw gyro values to 3-sample averagers (no deadzone before averaging)
     * 2. Formula: angle += avgRate * deltaTimeNanos * 1e-8 * 6 * DEG_TO_RAD
     * 3. Clamp each angle to ±18° (0.31415927 rad)
     * 4. No decay - angles hold their values until moved
     * 5. Display rotation applied AFTER accumulation when converting to tiltX/tiltY
     */
    private fun handleGyroscope(values: FloatArray, displayRotation: Int) {
        // Gyroscope axis mapping:
        // values[0] = rotation rate around X axis (pitch rate - tilting forward/back)
        // values[1] = rotation rate around Y axis (roll rate - tilting left/right)
        // values[2] = rotation rate around Z axis (yaw rate - twisting/rotating device)
        
        // Step 1: Add raw values to averagers (NO deadzone, NO display rotation adjustment)
        avgYaw.add(values[0])    // X-axis rate
        avgPitch.add(values[1])  // Y-axis rate
        avgRoll.add(values[2])   // Z-axis rate
        
        // Step 2: Calculate time delta
        val currentTime = System.nanoTime()
        val deltaTimeNanos = currentTime - lastGyroTime
        
        // Skip first sample or stale data (>100ms gap)
        if (lastGyroTime == 0L || deltaTimeNanos <= 0 || deltaTimeNanos > 100_000_000L) {
            lastGyroTime = currentTime
            return
        }
        
        // Step 3: Integration formula: f = deltaTimeNanos * 1e-8 * 6.0 * DEG_TO_RAD
        // DEG_TO_RAD = 0.017453292 = π/180
        val f = deltaTimeNanos * 1e-8f * 6.0f * 0.017453292f
        
        // Step 4: Accumulate averaged angular velocity into angles
        // No deadzone check, no decay - angles hold until moved
        // Hard clamp at physical tilt limit; smooth deceleration is applied
        // downstream in ParallaxCalculator.softLimit() to avoid double-tanh compression.
        gyroYaw = (gyroYaw + avgYaw.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        gyroPitch = (gyroPitch + avgPitch.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        gyroRoll = (gyroRoll + avgRoll.calc() * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        
        // Update time AFTER accumulation
        lastGyroTime = currentTime
        
        // Step 5: Apply display rotation to get tiltX/tiltY
        // ROTATION_0:   tiltX = -pitch, tiltY = yaw
        // ROTATION_90:  tiltX = -yaw,   tiltY = -pitch  
        // ROTATION_270: tiltX = yaw,    tiltY = pitch
        val rawTiltX: Float
        val rawTiltY: Float
        when (displayRotation) {
            Surface.ROTATION_90 -> { rawTiltX = -gyroYaw; rawTiltY = -gyroPitch }
            Surface.ROTATION_180 -> { rawTiltX = gyroPitch; rawTiltY = -gyroYaw }  // Interpolated from pattern
            Surface.ROTATION_270 -> { rawTiltX = gyroYaw; rawTiltY = gyroPitch }
            else -> { rawTiltX = -gyroPitch; rawTiltY = gyroYaw }  // ROTATION_0
        }
        
        // Convert angles from radians to normalized -1..1 range.
        // Hard clamp preserves full input range; ParallaxCalculator.softLimit()
        // is the single point of tanh saturation for the visual offset.
        val tiltX = (rawTiltX / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (rawTiltY / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        // Output directly - no additional filtering on sensor side
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
        val now = System.currentTimeMillis()
        
        // Sensor warmup: decrement on EVERY accelerometer event (not just high-gForce)
        // so stale queued events burn through the counter before real shakes are processed.
        if (shakeWarmupSamplesRemaining > 0) {
            shakeWarmupSamplesRemaining--
            return
        }
        
        val g = SensorManager.GRAVITY_EARTH
        val gX = values[0] / g
        val gY = values[1] / g
        val gZ = values[2] / g
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        // Only process if gForce exceeds threshold (tuned by sensitivity)
        if (gForce < shakeThresholdGravity) return
        
        // Check unlock delay: ignore shakes immediately after screen on
        // This prevents accidental triggers from phone unlock gesture
        if (shakeUnlockDelayMs > 0 && now - screenOnTime < shakeUnlockDelayMs) {
            Log.d(TAG, "Shake ignored: within unlock delay (${now - screenOnTime}ms < ${shakeUnlockDelayMs}ms)")
            return
        }
        
        // Check cooldown after a successful shake trigger
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
        
        // Oscillation check: real shakes alternate direction, pocket pulls / desk
        // vibrations tend to push in one direction. Track the sign of the dominant axis
        // and count reversals. Only allow shake counting after at least one reversal.
        val absX = kotlin.math.abs(gX)
        val absY = kotlin.math.abs(gY)
        val absZ = kotlin.math.abs(gZ)
        val signX = if (gX > 0) 1 else -1
        val signY = if (gY > 0) 1 else -1
        val signZ = if (gZ > 0) 1 else -1

        // Reset direction tracking if too much time between high-g events
        if (now - lastHighGTime > SHAKE_COUNT_RESET_TIME_MS) {
            shakeDirectionChanges = 0
            lastShakeSignX = 0
            lastShakeSignY = 0
            lastShakeSignZ = 0
            shakeCount = 0
        }
        lastHighGTime = now

        if (lastShakeSignX != 0) {
            // Count direction reversals on axes with significant force (> 0.5g)
            var reversals = 0
            if (absX > 0.5f && signX != lastShakeSignX) reversals++
            if (absY > 0.5f && signY != lastShakeSignY) reversals++
            if (absZ > 0.5f && signZ != lastShakeSignZ) reversals++
            if (reversals > 0) shakeDirectionChanges++
        }
        lastShakeSignX = signX
        lastShakeSignY = signY
        lastShakeSignZ = signZ

        // Require at least one direction reversal before counting toward trigger.
        // This filters single-push forces from pockets and desk vibrations.
        if (shakeDirectionChanges < 1) {
            Log.d(TAG, "Shake high-g but no oscillation yet (changes=$shakeDirectionChanges)")
            return
        }
        
        // Ignore shakes too close to each other (debounce)
        if (shakeTimestamp + shakeSlopTimeMs > now) return
        
        // Reset count if no shakes for a while
        if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
            shakeCount = 0
        }
        
        shakeTimestamp = now
        shakeCount++
        
        Log.d(TAG, "Shake count=$shakeCount gForce=${"%.2f".format(gForce)} dirChanges=$shakeDirectionChanges")
        
        if (shakeCount >= shakeTriggerCount) {
            shakeCount = 0
            shakeDirectionChanges = 0
            lastShakeTime = now
            Log.d(TAG, "Shake gesture detected! Triggering listener")
            shakeListener?.onShake()
        }
    }
    
    /**
     * Detect a wiggle around the phone's long axis (earpiece to charging port) from gyroscope Y.
     * @param values Gyroscope rates in rad/s around device X, Y, Z
     */
    private fun detectGyroWiggle(values: FloatArray) {
        val now = System.currentTimeMillis()

        // Sensor warmup: skip until warmup counter reaches zero.
        // Counter is decremented by detectShakeFromAccelerometer on every accel event.
        // Also decrement here as fallback in case gyro events arrive without accel events.
        if (shakeWarmupSamplesRemaining > 0) {
            shakeWarmupSamplesRemaining--
            return
        }
        
        // Unlock delay + cooldown
        if (shakeUnlockDelayMs > 0 && now - screenOnTime < shakeUnlockDelayMs) return
        if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return

        val before = longAxisWiggle.currentReversals
        val triggered = longAxisWiggle.onSample(values[0], values[1], values[2], now)
        if (!triggered && longAxisWiggle.currentReversals > before) {
            Log.d(TAG, "Long-axis wiggle reversal=${longAxisWiggle.currentReversals}/${longAxisWiggle.requiredReversals}")
        }
        if (triggered) {
            lastShakeTime = now
            Log.d(TAG, "Long-axis wiggle gesture detected! Triggering listener")
            shakeListener?.onShake()
        }
    }
    
    private fun handleRotationVector(values: FloatArray, displayRotation: Int) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        
        // Remap coordinate system based on display rotation
        // This ensures sensor data matches what the user sees on screen
        val axisX: Int
        val axisY: Int
        when (displayRotation) {
            Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
            else -> { axisX = SensorManager.AXIS_X; axisY = SensorManager.AXIS_Y }
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
        val gx: Float
        val gy: Float
        when (displayRotation) {
            Surface.ROTATION_90 -> { gx = values[1]; gy = -values[0] }
            Surface.ROTATION_180 -> { gx = -values[0]; gy = -values[1] }
            Surface.ROTATION_270 -> { gx = -values[1]; gy = values[0] }
            else -> { gx = values[0]; gy = values[1] }
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
        
        // Hard-clamp accumulated offset to ±1 range.
        // Smooth saturation is applied downstream in ParallaxCalculator.softLimit().
        accumulatedX = accumulatedX.coerceIn(-1f, 1f)
        accumulatedY = accumulatedY.coerceIn(-1f, 1f)
        
        // Auto-return: gentle decay ONLY when device is not actively moving.
        // During active tilting the accumulated values pass through unchanged
        // (zero-latency for responsive parallax).
        if (autoReturnRate < 1f) {
            val motionMag = sqrt(deltaPitch * deltaPitch + deltaRoll * deltaRoll)
            if (motionMag < 0.002f) {
                accumulatedX *= autoReturnRate
                accumulatedY *= autoReturnRate
            }
        }
        
        processTiltValues(accumulatedX, accumulatedY)
    }
    
    private fun processTiltValues(targetX: Float, targetY: Float) {
        // Direct pass-through: no deadzone, no exponential filter.
        // All smoothing/return-to-center is handled by ParallaxCalculator.
        filteredX = targetX
        filteredY = targetY

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
    private var count = 0
    
    fun add(value: Float): Averager {
        values[index] = value
        index = (index + 1) % bufferSize
        if (count < bufferSize) count++
        return this
    }
    
    fun calc(): Float {
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += values[i]
        return sum / count
    }
    
    fun reset(value: Float = 0f) {
        for (i in values.indices) values[i] = value
        index = 0
        count = bufferSize  // After reset with a value, buffer is considered full
    }
}
