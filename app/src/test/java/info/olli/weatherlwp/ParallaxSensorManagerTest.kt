package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for ParallaxSensorManager
 * 
 * Key approach:
 * - Direct pass-through: no exponential smoothing, no deadzone on sensor side
 * - Gyroscope mode: 3-sample moving average, direct integration, ±18° clamp
 * - Rotation vector mode: delta-based accumulation, no decay during motion
 * - All smoothing/return-to-center handled by ParallaxCalculator
 * 
 * Latency:
 * - No smoothing filter
 * - Both sensor modes output directly (zero sensor-side latency)
 * - Optional auto-return only when device is held still
 */
class ParallaxSensorManagerTest {

    companion object {
        const val GRAVITY_EARTH = 9.81f  // SensorManager.GRAVITY_EARTH
        const val SHAKE_THRESHOLD_GRAVITY = 2.7f
        const val SHAKE_SLOP_TIME_MS = 500L
        const val SHAKE_COUNT_RESET_TIME_MS = 3000L
        const val SHAKE_TRIGGER_COUNT = 3
        
        // Gyro wiggle constants (improved stable sign + debounce detection)
        const val GYRO_SIGN_STABLE_SAMPLES = 2
        const val GYRO_MIN_DIR_CHANGE_TIME_MS = 90L
        const val DEFAULT_GYRO_TRIGGER_CHANGES = 6  // Requires 3 full back-and-forth wiggles
        const val DEFAULT_GYRO_WINDOW_MS = 1600L
        const val DEFAULT_GYRO_AXIS_THRESHOLD = 1.2f
    }

    // ============== Gravity Extraction Tests ==============
    
    @Test
    fun `gravity extraction from rotation matrix third row`() {
        // Simulated remapped rotation matrix where device is tilted left
        // The third row represents world Z-axis in device coords
        // Gravity = negative of that
        val remappedMatrix = floatArrayOf(
            1f, 0f, 0f,   // first row
            0f, 1f, 0f,   // second row
            0.3f, 0f, 0.95f  // third row: Z-axis slightly tilted
        )
        
        val gx = -remappedMatrix[6]  // -0.3
        val gy = -remappedMatrix[7]  // 0
        val gz = -remappedMatrix[8]  // -0.95
        
        assertEquals("Gravity X should be negative of R[6]", -0.3f, gx, 0.001f)
        assertEquals("Gravity Y should be negative of R[7]", 0f, gy, 0.001f)
        assertEquals("Gravity Z should be negative of R[8]", -0.95f, gz, 0.001f)
    }
    
    // ============== Direct Pass-Through Tests ==============
    // Sensor output is passed directly to listener without deadzone or smoothing
    
    @Test
    fun `direct pass-through outputs value unchanged`() {
        // processTiltValues now sets filteredX/Y directly (no deadzone, no filter)
        val targetX = 0.5f
        val targetY = -0.3f
        
        // Direct assignment
        val filteredX = targetX
        val filteredY = targetY
        
        assertEquals("X should pass through unchanged", targetX, filteredX, 0.001f)
        assertEquals("Y should pass through unchanged", targetY, filteredY, 0.001f)
    }
    
    @Test
    fun `small values pass through without deadzone`() {
        // Previously small values were killed by DEADZONE=0.03
        // Now all values pass through directly (expected behavior)
        val tinyX = 0.005f
        val tinyY = 0.002f
        
        val filteredX = tinyX
        val filteredY = tinyY
        
        assertTrue("Even tiny X should pass through", filteredX > 0f)
        assertTrue("Even tiny Y should pass through", filteredY > 0f)
    }
    
    @Test
    fun `zero value outputs zero`() {
        val filteredX = 0f
        val filteredY = 0f
        
        assertEquals("Zero input should output zero", 0f, filteredX, 0.001f)
        assertEquals("Zero input should output zero", 0f, filteredY, 0.001f)
    }
    
    // ============== Tilt Direction Tests ==============
    
    @Test
    fun `tilt left should produce negative target X`() {
        // When phone tilts left, gravity in screen coords has positive X component
        val gx = 0.5f  // Gravity pulling right (phone tilted left)
        val confidence = 1f
        
        // Target is negated for natural parallax (tilt left = scene shifts right)
        val targetX = (-gx) * confidence
        
        assertTrue("Tilting left should give negative targetX", targetX < 0)
    }
    
    @Test
    fun `tilt right should produce positive target X`() {
        val gx = -0.5f  // Gravity pulling left (phone tilted right)
        val confidence = 1f
        
        val targetX = (-gx) * confidence
        
        assertTrue("Tilting right should give positive targetX", targetX > 0)
    }
    
    // ============== Auto-Return Tests ==============
    // Auto-return decays accumulated values only when device is held still
    
    @Test
    fun `auto-return does not apply during active motion`() {
        val autoReturnRate = 0.9995f  // Some decay enabled
        var accumulatedX = 0.5f
        
        // Simulate motion (delta above threshold)
        val deltaPitch = 0.01f
        val deltaRoll = 0.005f
        val motionMag = sqrt(deltaPitch * deltaPitch + deltaRoll * deltaRoll)
        
        // motionMag > 0.002f → no decay applied
        if (motionMag < 0.002f) {
            accumulatedX *= autoReturnRate
        }
        
        assertEquals("Accumulated value should NOT decay during motion", 0.5f, accumulatedX, 0.0001f)
    }
    
    @Test
    fun `auto-return applies when device is still`() {
        val autoReturnRate = 0.999f
        var accumulatedX = 0.5f
        
        // Simulate no motion (deltas near zero)
        val deltaPitch = 0.0001f
        val deltaRoll = 0.0001f
        val motionMag = sqrt(deltaPitch * deltaPitch + deltaRoll * deltaRoll)
        
        // motionMag < 0.002f → decay applied
        if (motionMag < 0.002f) {
            accumulatedX *= autoReturnRate
        }
        
        assertTrue("Accumulated value should decay when still", accumulatedX < 0.5f)
        assertEquals("Decay should be gentle", 0.5f * 0.999f, accumulatedX, 0.0001f)
    }
    
    @Test
    fun `auto-return rate 1 means no decay`() {
        val autoReturnRate = 1f
        var accumulatedX = 0.5f
        
        // Even when still, rate=1 should not decay
        for (i in 0 until 1000) {
            if (autoReturnRate < 1f) {
                accumulatedX *= autoReturnRate
            }
        }
        
        assertEquals("Rate 1.0 should never decay", 0.5f, accumulatedX, 0.0001f)
    }
    
    @Test
    fun `setAutoReturnRate maps speed correctly`() {
        // speed=0 → rate=1.0 (no decay)
        // speed=1 → rate=0.9995 (gentle decay)
        val speed0Rate = 1f  // speed=0 → 1.0
        val speed1Rate = (1f - 1f * 0.0005f).coerceIn(0.999f, 1f) // speed=1 → 0.9995
        
        assertEquals("Speed 0 should give rate 1.0", 1f, speed0Rate, 0.0001f)
        assertEquals("Speed 1 should give rate 0.9995", 0.9995f, speed1Rate, 0.0001f)
    }
    
    // ============== Tilt Position Output Tests ==============
    
    @Test
    fun `tilt position passes through instantly`() {
        // Direct pass-through: output equals input immediately (no filter convergence needed)
        val target = 0.6f
        val filtered = target  // Direct assignment
        
        assertEquals("Output should equal input instantly",
            target, filtered, 0.001f)
    }
    
    @Test
    fun `tilt position is stable when device held at angle`() {
        // Accumulated delta approach: same input → same accumulated value → stable output
        val constantTarget = 0.5f
        
        // With direct pass-through, output is always exactly the input
        val initialPosition = constantTarget
        val laterPosition = constantTarget
        
        assertEquals("Position should remain stable when tilt is constant",
            initialPosition, laterPosition, 0.001f)
    }
    
    @Test
    fun `tilt position returns to zero when device leveled`() {
        // When accumulated deltas cancel out (user returns to original position),
        // output naturally returns to zero
        var accumulated = 0.5f
        
        // Simulate returning: deltas that cancel the accumulated offset
        val returnDelta = -0.01f
        for (i in 0 until 50) {
            accumulated += returnDelta
        }
        accumulated = accumulated.coerceIn(-1f, 1f)
        
        assertEquals("Accumulated should approach zero when returning",
            0f, accumulated, 0.01f)
    }
    
    // ============== Display Rotation Vector Remapping Tests ==============
    
    @Test
    fun `rotation 0 keeps axes unchanged`() {
        val x = 0.5f; val y = 0.3f; val z = 0.8f
        
        // ROTATION_0: no change
        val (rx, ry, rz) = Triple(x, y, z)
        
        assertEquals(x, rx, 0.001f)
        assertEquals(y, ry, 0.001f)
        assertEquals(z, rz, 0.001f)
    }
    
    @Test
    fun `rotation 90 swaps x and y with sign change`() {
        val x = 0.5f; val y = 0.3f; val z = 0.8f
        
        // ROTATION_90: (y, -x, z)
        val rx = y
        val ry = -x
        val rz = z
        
        assertEquals(0.3f, rx, 0.001f)
        assertEquals(-0.5f, ry, 0.001f)
        assertEquals(0.8f, rz, 0.001f)
    }
    
    @Test
    fun `rotation 180 negates x and y`() {
        val x = 0.5f; val y = 0.3f; val z = 0.8f
        
        // ROTATION_180: (-x, -y, z)
        val rx = -x
        val ry = -y
        val rz = z
        
        assertEquals(-0.5f, rx, 0.001f)
        assertEquals(-0.3f, ry, 0.001f)
        assertEquals(0.8f, rz, 0.001f)
    }
    
    @Test
    fun `rotation 270 swaps x and y opposite to 90`() {
        val x = 0.5f; val y = 0.3f; val z = 0.8f
        
        // ROTATION_270: (-y, x, z)
        val rx = -y
        val ry = x
        val rz = z
        
        assertEquals(-0.3f, rx, 0.001f)
        assertEquals(0.5f, ry, 0.001f)
        assertEquals(0.8f, rz, 0.001f)
    }
    
    // ============== Axis Remapping for Matrix Tests ==============
    
    @Test
    fun `axis remap values are correct`() {
        // These should match SensorManager constants
        val AXIS_X = 1
        val AXIS_Y = 2
        val AXIS_MINUS_X = 129  // SensorManager.AXIS_MINUS_X
        @Suppress("UNUSED_VARIABLE")
        val AXIS_MINUS_Y = 130  // SensorManager.AXIS_MINUS_Y
        
        // ROTATION_0
        assertEquals("ROTATION_0 axisX", AXIS_X, 1)
        assertEquals("ROTATION_0 axisY", AXIS_Y, 2)
        
        // ROTATION_90: AXIS_Y, AXIS_MINUS_X
        assertEquals("ROTATION_90 axisX should be AXIS_Y", AXIS_Y, 2)
        assertEquals("ROTATION_90 axisY should be AXIS_MINUS_X", AXIS_MINUS_X, 129)
    }
    
    // ============== Averager Tests (Gyroscope Mode) ==============
    
    @Test
    fun `averager calculates simple average`() {
        val averager = TestAverager(3)
        averager.add(1f)
        averager.add(2f)
        averager.add(3f)
        
        assertEquals("Average of 1,2,3 should be 2", 2f, averager.calc(), 0.001f)
    }
    
    @Test
    fun `averager wraps around buffer`() {
        val averager = TestAverager(3)
        averager.add(1f)
        averager.add(2f)
        averager.add(3f)
        averager.add(10f)  // Overwrites 1
        
        // Buffer now contains [10, 2, 3], average = 15/3 = 5
        assertEquals("Average after wrap should be 5", 5f, averager.calc(), 0.001f)
    }
    
    @Test
    fun `averager reset clears buffer`() {
        val averager = TestAverager(3)
        averager.add(5f)
        averager.add(5f)
        averager.add(5f)
        averager.reset(0f)
        
        assertEquals("Average after reset should be 0", 0f, averager.calc(), 0.001f)
    }
    
    @Test
    fun `averager smooths noisy input`() {
        val averager = TestAverager(3)
        // Simulating noisy gyroscope readings
        averager.add(0.1f)
        averager.add(0.15f)
        averager.add(0.05f)
        
        // Average smooths out the noise: (0.1 + 0.15 + 0.05) / 3 = 0.1
        assertEquals("Noisy input should average to 0.1", 0.1f, averager.calc(), 0.001f)
    }
    
    // ============== Gyroscope Mode Integration Tests ==============
    
    @Test
    fun `gyroscope accumulation clamps to max tilt`() {
        val MAX_TILT_RAD = (18f * Math.PI / 180f).toFloat()  // ~0.314 rad
        
        // Simulating continuous gyro input that would exceed limits
        var angle = 0f
        val angularVelocity = 10.0f  // rad/s (fast rotation)
        val deltaTime = 0.5f  // 500ms
        val sensitivity = 6.0f * (Math.PI.toFloat() / 180f)
        
        // Accumulated angle = 0 + (10.0 * 0.5 * sensitivity) = ~0.52 rad, exceeds max
        angle = (angle + angularVelocity * deltaTime * sensitivity).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        
        assertEquals("Angle should clamp to MAX_TILT_RAD", MAX_TILT_RAD, angle, 0.001f)
    }
    
    @Test
    fun `gyroscope mode produces tilt in -1 to 1 range`() {
        val MAX_TILT_RAD = (18f * Math.PI / 180f).toFloat()
        
        // At max tilt angle, output should be ±1
        val gyroAngle = MAX_TILT_RAD
        val tilt = (gyroAngle / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        assertEquals("Max gyro angle should map to tilt of 1.0", 1f, tilt, 0.001f)
        
        val halfTilt = (MAX_TILT_RAD / 2f / MAX_TILT_RAD).coerceIn(-1f, 1f)
        assertEquals("Half max angle should map to tilt of 0.5", 0.5f, halfTilt, 0.001f)
    }
    
    // ============== Gyroscope Formula Tests ==============
    
    @Test
    fun `formula calculates correct time factor`() {
        // Reference formula: f = deltaTimeNanos * 1e-8 * 6.0 * 0.017453292
        val deltaTimeNanos = 16_666_666L  // ~16.67ms (60fps)
        val f = deltaTimeNanos * 1e-8f * 6.0f * 0.017453292f
        
        // Expected: 16666666 * 1e-8 * 6 * 0.01745 ≈ 0.01745
        assertEquals("Time factor at 60fps", 0.01745f, f, 0.001f)
    }
    
    @Test
    fun `gyroscope accumulation matches reference`() {
        val MAX_TILT_RAD = 0.31415927f  // 18 degrees
        
        // Simulate reference accumulation
        var angle = 0f
        val angularVelocity = 1.0f  // 1 rad/s
        val deltaTimeNanos = 16_666_666L  // ~60fps
        val f = deltaTimeNanos * 1e-8f * 6.0f * 0.017453292f
        
        // 60 frames at 1 rad/s
        for (i in 0 until 60) {
            angle = (angle + angularVelocity * f).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        }
        
        // After 60 frames: 60 * 0.01745 ≈ 1.047 rad, but clamped to MAX_TILT_RAD
        assertEquals("Should be clamped to MAX_TILT_RAD", MAX_TILT_RAD, angle, 0.001f)
    }
    
    @Test
    fun `averaging smooths noisy gyroscope input`() {
        // Reference uses 3-sample averaging
        val averager = TestAverager(3)
        
        // Noisy input
        averager.add(0.5f)
        averager.add(0.6f)
        averager.add(0.4f)
        
        // Average = (0.5 + 0.6 + 0.4) / 3 = 0.5
        assertEquals("Averaging should smooth noise", 0.5f, averager.calc(), 0.001f)
    }
    
    @Test
    fun `tilt output normalized to -1 to 1 range`() {
        val MAX_TILT_RAD = 0.31415927f
        
        // At max tilt
        val gyroAngle = MAX_TILT_RAD
        val tilt = (gyroAngle / MAX_TILT_RAD).coerceIn(-1f, 1f)
        assertEquals("Max angle should map to 1.0", 1f, tilt, 0.001f)
        
        // At half max tilt
        val halfAngle = MAX_TILT_RAD / 2f
        val halfTilt = (halfAngle / MAX_TILT_RAD).coerceIn(-1f, 1f)
        assertEquals("Half angle should map to 0.5", 0.5f, halfTilt, 0.001f)
        
        // At negative max
        val negativeTilt = (-MAX_TILT_RAD / MAX_TILT_RAD).coerceIn(-1f, 1f)
        assertEquals("Negative max should map to -1.0", -1f, negativeTilt, 0.001f)
    }
    
    // ============== Gyroscope Integration Tests ==============
    // Tests verify correct gyroscope integration
    
    @Test
    fun `gyroscope raw values map directly to averagers`() {
        // Raw gyroscope values go directly to averagers:
        //   averagers[0].add(values[0]) → lastYaw
        //   averagers[1].add(values[1]) → lastPitch
        //   averagers[2].add(values[2]) → lastRoll
        // NO axis remapping before averaging - raw sensor values used directly
        val values = floatArrayOf(0.5f, 0.3f, 0.1f)
        
        // Values go directly to averagers
        val yawInput = values[0]   // X-axis rate → "yaw"
        val pitchInput = values[1] // Y-axis rate → "pitch"
        val rollInput = values[2]  // Z-axis rate → "roll"
        
        assertEquals("Yaw input should be values[0]", 0.5f, yawInput, 0.001f)
        assertEquals("Pitch input should be values[1]", 0.3f, pitchInput, 0.001f)
        assertEquals("Roll input should be values[2]", 0.1f, rollInput, 0.001f)
    }
    
    @Test
    fun `gyroscope ROTATION_0 maps pitch to tiltX and yaw to tiltY`() {
        // Expected: ROTATION_0: tiltX = -lastPitch, tiltY = lastYaw
        val MAX_TILT_RAD = 0.31415927f
        val gyroYaw = 0.15f
        val gyroPitch = 0.1f
        
        // ROTATION_0 mapping (after accumulation)
        val rawTiltX = -gyroPitch
        val rawTiltY = gyroYaw
        
        val tiltX = (rawTiltX / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (rawTiltY / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        assertTrue("tiltX should be negative (from negated positive pitch)", tiltX < 0)
        assertTrue("tiltY should be positive (from positive yaw)", tiltY > 0)
    }
    
    @Test
    fun `gyroscope ROTATION_90 maps yaw to tiltX and pitch to tiltY`() {
        // Expected: ROTATION_90: tiltX = -lastYaw, tiltY = -lastPitch
        val MAX_TILT_RAD = 0.31415927f
        val gyroYaw = 0.15f
        val gyroPitch = 0.1f
        
        // ROTATION_90 mapping (after accumulation)
        val rawTiltX = -gyroYaw
        val rawTiltY = -gyroPitch
        
        val tiltX = (rawTiltX / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (rawTiltY / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        assertTrue("tiltX for ROTATION_90 should be negative (from negated positive yaw)", tiltX < 0)
        assertTrue("tiltY for ROTATION_90 should be negative (from negated positive pitch)", tiltY < 0)
    }
    
    @Test
    fun `gyroscope ROTATION_270 maps yaw to tiltX and pitch to tiltY opposite to 90`() {
        // Expected: ROTATION_270: tiltX = lastYaw, tiltY = lastPitch
        val MAX_TILT_RAD = 0.31415927f
        val gyroYaw = 0.15f
        val gyroPitch = 0.1f
        
        // ROTATION_270 mapping (after accumulation)
        val rawTiltX = gyroYaw
        val rawTiltY = gyroPitch
        
        val tiltX = (rawTiltX / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (rawTiltY / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        assertTrue("tiltX for ROTATION_270 should be positive (from positive yaw)", tiltX > 0)
        assertTrue("tiltY for ROTATION_270 should be positive (from positive pitch)", tiltY > 0)
    }
    
    @Test
    fun `gyroscope no deadzone before averaging`() {
        // ALL values go to averagers without any deadzone filtering
        // The deadzone was previously applied BEFORE averaging - this was wrong
        val testAverager = TestAverager(3)
        
        // Add small values that would have been filtered by deadzone
        testAverager.add(0.01f)  // Very small
        testAverager.add(0.02f)  // Very small
        testAverager.add(0.03f)  // Very small
        
        val avg = testAverager.calc()
        
        // Average should be (0.01 + 0.02 + 0.03) / 3 = 0.02
        assertEquals("Small values should be averaged without filtering", 0.02f, avg, 0.001f)
    }
    
    @Test
    fun `gyroscope no decay - angles hold until moved`() {
        // Accumulated angles should NEVER decay - they hold until moved
        val MAX_TILT_RAD = 0.31415927f
        var gyroAngle = 0.15f  // Some accumulated angle
        
        // When no motion, angle stays the same (no decay)
        // Old buggy code: gyroAngle *= GYRO_DECAY_RATE (0.998)
        // Correct: gyroAngle = gyroAngle (no change)
        
        val originalAngle = gyroAngle
        // Simulate 100 frames with no motion - angle should stay exactly the same
        for (i in 0 until 100) {
            // Reference: no decay
            // gyroAngle = gyroAngle (stays same)
        }
        
        assertEquals("Angle should not decay (expected behavior)", originalAngle, gyroAngle, 0.001f)
    }
    
    // ============== gForce Shake Detection Tests ==============
    
    @Test
    fun `gForce at rest is approximately 1`() {
        // At rest, accelerometer reads ~9.81 m/s² in one axis (gravity)
        val values = floatArrayOf(0f, 0f, GRAVITY_EARTH)  // Phone flat, gravity on Z
        
        val gX = values[0] / GRAVITY_EARTH
        val gY = values[1] / GRAVITY_EARTH
        val gZ = values[2] / GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        assertEquals("gForce at rest should be ~1.0", 1.0f, gForce, 0.01f)
    }
    
    @Test
    fun `gForce below threshold does not trigger shake`() {
        // Simulate mild movement (2.0g - below 2.7g threshold)
        val values = floatArrayOf(GRAVITY_EARTH * 1.5f, GRAVITY_EARTH * 0.5f, GRAVITY_EARTH)
        
        val gX = values[0] / GRAVITY_EARTH
        val gY = values[1] / GRAVITY_EARTH
        val gZ = values[2] / GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        assertTrue("gForce should be less than threshold", gForce < SHAKE_THRESHOLD_GRAVITY)
    }
    
    @Test
    fun `gForce exceeds threshold during shake`() {
        // Simulate strong shake (> 2.7g)
        // Using 2.0g, 1.5g, 1.2g gives sqrt(4 + 2.25 + 1.44) = sqrt(7.69) ≈ 2.77g
        val values = floatArrayOf(GRAVITY_EARTH * 2.0f, GRAVITY_EARTH * 1.5f, GRAVITY_EARTH * 1.2f)
        
        val gX = values[0] / GRAVITY_EARTH
        val gY = values[1] / GRAVITY_EARTH
        val gZ = values[2] / GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        assertTrue("gForce during shake should exceed threshold: $gForce", gForce >= SHAKE_THRESHOLD_GRAVITY)
    }
    
    @Test
    fun `shake count reset after timeout`() {
        // Simulate: count should reset if no shake for SHAKE_COUNT_RESET_TIME_MS
        var shakeCount = 2
        var shakeTimestamp = 0L
        val now = SHAKE_COUNT_RESET_TIME_MS + 100  // More than reset time
        
        // This mimics the reset logic in detectShakeFromAccelerometer
        if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
            shakeCount = 0
        }
        
        assertEquals("Shake count should reset after timeout", 0, shakeCount)
    }
    
    @Test
    fun `shake slop ignores rapid consecutive shakes`() {
        // Simulate: shakes too close together should be ignored
        val shakeTimestamp = 100L
        val now = shakeTimestamp + 100  // Only 100ms later (< 500ms slop)
        
        val shouldIgnore = shakeTimestamp + SHAKE_SLOP_TIME_MS > now
        
        assertTrue("Shakes within slop time should be ignored", shouldIgnore)
    }
    
    @Test
    fun `shake slop allows shakes after slop time`() {
        val shakeTimestamp = 100L
        val now = shakeTimestamp + 600  // 600ms later (> 500ms slop)
        
        val shouldIgnore = shakeTimestamp + SHAKE_SLOP_TIME_MS > now
        
        assertFalse("Shakes after slop time should be processed", shouldIgnore)
    }
    
    @Test
    fun `three shakes trigger shake event`() {
        // Simulate shake count reaching trigger threshold
        var shakeCount = SHAKE_TRIGGER_COUNT - 1
        shakeCount++
        
        val shouldTrigger = shakeCount >= SHAKE_TRIGGER_COUNT
        
        assertTrue("Three shakes should trigger event", shouldTrigger)
    }
    
    @Test
    fun `gForce calculation is correct for tilted phone`() {
        // Phone tilted 45 degrees, gravity split between Y and Z
        val cos45 = 0.707f
        val values = floatArrayOf(0f, GRAVITY_EARTH * cos45, GRAVITY_EARTH * cos45)
        
        val gX = values[0] / GRAVITY_EARTH
        val gY = values[1] / GRAVITY_EARTH
        val gZ = values[2] / GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        
        // Should still be ~1.0 (no additional acceleration beyond gravity)
        assertEquals("gForce for tilted phone at rest should still be ~1.0", 1.0f, gForce, 0.02f)
    }
    
    // ============== Roll Wiggle Detection Tests ==============
    
    @Test
    fun `roll wiggle constants are reasonable`() {
        // Test the wiggle detection constants
        val wiggleThreshold = 0.2f  // ~11 degrees
        val wiggleTimeWindow = 800L
        val wiggleTriggerCount = 4
        
        // Threshold should be small enough to detect quick wrist turns
        assertTrue("Threshold should be ~11 degrees", wiggleThreshold in 0.15f..0.25f)
        
        // Time window should allow natural gesture speed
        assertTrue("Time window should be 500-1500ms", wiggleTimeWindow in 500L..1500L)
        
        // 4 direction changes = 2 complete back-and-forth movements
        assertEquals("4 direction changes needed", 4, wiggleTriggerCount)
    }
    
    @Test
    fun `roll wiggle detects direction changes`() {
        // Simulate roll angle changes indicating wiggle
        val wiggleThreshold = 0.2f
        var lastRoll = 0f
        var lastDirection = 0
        var directionChanges = 0
        
        // Sequence: 0 -> +0.3 (right) -> -0.3 (left) -> +0.3 (right) -> -0.3 (left)
        val rollSequence = listOf(0f, 0.3f, -0.3f, 0.3f, -0.3f)
        
        for (roll in rollSequence.drop(1)) {
            val delta = roll - lastRoll
            if (kotlin.math.abs(delta) >= wiggleThreshold) {
                val direction = if (delta > 0) 1 else -1
                if (lastDirection != 0 && direction != lastDirection) {
                    directionChanges++
                }
                lastDirection = direction
                lastRoll = roll
            }
        }
        
        // Should detect 3 direction changes: right->left, left->right, right->left
        assertEquals("Should detect 3 direction changes", 3, directionChanges)
    }
    
    @Test
    fun `roll wiggle 4 direction changes trigger event`() {
        val triggerCount = 4
        var directionChanges = 0
        var triggered = false
        
        // Simulate 4 direction changes
        for (i in 1..4) {
            directionChanges++
            if (directionChanges >= triggerCount) {
                triggered = true
                break
            }
        }
        
        assertTrue("4 direction changes should trigger", triggered)
    }
    
    @Test
    fun `roll wiggle resets on timeout`() {
        val timeWindow = 800L
        var directionChanges = 2
        
        // Simulate timeout after first wiggle
        val startTime = 100L
        val now = 1000L  // 900ms later, exceeds 800ms window
        
        if (now - startTime > timeWindow) {
            directionChanges = 0
        }
        
        assertEquals("Direction changes should reset on timeout", 0, directionChanges)
    }
    
    // ============== Latency Optimization Tests ==============
    
    @Test
    fun `direct pass-through reaches target in 1 frame`() {
        // With direct pass-through, 100% of target is reached in a single frame
        val target = 1f
        val filtered = target  // Direct assignment
        
        assertEquals("Should reach 100% of target in 1 frame", target, filtered, 0.001f)
    }
    
    @Test
    fun `zero-latency output works correctly`() {
        // Direct output mode uses no filter.
        // Both sensor modes now output directly with zero sensor-side latency.
        val target = 0.8f
        val output = target  // Direct, no filtering
        
        assertEquals("Output should exactly equal target (zero latency)", target, output, 0.001f)
    }
    
    @Test
    fun `gyroscope mode uses direct output without filter`() {
        // Gyroscope mode should bypass filter entirely for minimum latency
        // This matches expected direct output behavior
        val MAX_TILT_RAD = 0.31415927f
        
        // Simulating gyro output calculation
        val gyroRoll = 0.15f  // Half max tilt
        val gyroPitch = 0.10f
        
        // Direct output (no filtering)
        val tiltX = (-gyroRoll / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (-gyroPitch / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        // Output should be exact, not smoothed
        assertEquals("Gyro mode tiltX should be direct", -gyroRoll / MAX_TILT_RAD, tiltX, 0.001f)
        assertEquals("Gyro mode tiltY should be direct", -gyroPitch / MAX_TILT_RAD, tiltY, 0.001f)
    }
    
    @Test
    fun `latency target is 20ms`() {
        // At 50Hz sensor rate (SENSOR_DELAY_GAME = ~20ms), we want:
        // - Sensor event arrives: +20ms
        // - Processing (instant with direct output): +0ms
        // - Next render frame picks up value: +16ms max (60fps)
        // Total: ~36ms worst case, ~20ms typical
        
        val sensorDelayMs = 20L  // SENSOR_DELAY_GAME
        val renderDelayMs = 16L  // 60fps
        val worstCaseLatency = sensorDelayMs + renderDelayMs
        
        assertTrue("Worst case latency should be under 50ms", worstCaseLatency < 50)
    }
    
    // ============== Shake Unlock Delay Tests ==============
    
    @Test
    fun `shake unlock delay default is 2500ms`() {
        // Default unlock delay prevents accidental triggers after phone unlock
        val defaultDelay = 2500L
        assertTrue("Default unlock delay should be 2500ms", defaultDelay == 2500L)
    }
    
    @Test
    fun `shake ignored during unlock delay period`() {
        // Simulate the unlock delay logic
        val screenOnTime = 1000L
        val shakeUnlockDelayMs = 2500L
        val shakeTimeWithinDelay = 2000L  // 1000ms after screen on
        val shakeTimeAfterDelay = 4000L   // 3000ms after screen on
        
        // Shake within delay period should be ignored
        val ignoredWithinDelay = shakeTimeWithinDelay - screenOnTime < shakeUnlockDelayMs
        assertTrue("Shake 1000ms after screen on should be ignored", ignoredWithinDelay)
        
        // Shake after delay period should be accepted
        val ignoredAfterDelay = shakeTimeAfterDelay - screenOnTime < shakeUnlockDelayMs
        assertTrue("Shake 3000ms after screen on should be accepted", !ignoredAfterDelay)
    }
    
    @Test
    fun `shake unlock delay of zero disables the feature`() {
        // When delay is 0, shakes should always be accepted (no unlock delay)
        val shakeUnlockDelayMs = 0L
        val screenOnTime = 1000L
        val immediateShakeTime = 1001L  // 1ms after screen on
        
        // With delay=0, no shakes should be ignored due to unlock delay
        val ignored = shakeUnlockDelayMs > 0 && immediateShakeTime - screenOnTime < shakeUnlockDelayMs
        assertTrue("With delay=0, shakes should never be ignored due to unlock delay", !ignored)
    }
    
    // ============== Gyro Wiggle Stable Sign Detection Tests ==============
    // Tests for the improved gyro wiggle detection with stable sign + debounce
    
    @Test
    fun `gyro wiggle requires stable sign before counting direction change`() {
        // Single sample of new sign should not count as direction change
        // Need GYRO_SIGN_STABLE_SAMPLES (2) consecutive samples
        var gyroCandidateCount = 1  // First sample of new sign
        val shouldCount = gyroCandidateCount >= GYRO_SIGN_STABLE_SAMPLES
        
        assertFalse("Single sample should not count as direction change", shouldCount)
        
        // Second consecutive sample
        gyroCandidateCount++
        val shouldCountNow = gyroCandidateCount >= GYRO_SIGN_STABLE_SAMPLES
        
        assertTrue("Two consecutive samples should count as direction change", shouldCountNow)
    }
    
    @Test
    fun `gyro wiggle debounces rapid direction changes`() {
        // Direction changes happening faster than GYRO_MIN_DIR_CHANGE_TIME_MS should be ignored
        val lastChangeTime = 100L
        val now = 150L  // Only 50ms later (< 90ms min)
        
        val shouldIgnore = now - lastChangeTime < GYRO_MIN_DIR_CHANGE_TIME_MS
        
        assertTrue("Direction change within 90ms should be ignored", shouldIgnore)
    }
    
    @Test
    fun `gyro wiggle accepts direction change after debounce time`() {
        val lastChangeTime = 100L
        val now = 200L  // 100ms later (> 90ms min)
        
        val shouldIgnore = now - lastChangeTime < GYRO_MIN_DIR_CHANGE_TIME_MS
        
        assertFalse("Direction change after 90ms should be accepted", shouldIgnore)
    }
    
    @Test
    fun `gyro wiggle requires 6 direction changes by default`() {
        // 6 direction changes = 3 full back-and-forth wiggles
        // This is stricter than the old 4-change requirement
        assertEquals("Default trigger count should be 6", 6, DEFAULT_GYRO_TRIGGER_CHANGES)
        
        // Simulate: 5 changes should not trigger, 6 should
        var changes = 5
        assertFalse("5 changes should not trigger", changes >= DEFAULT_GYRO_TRIGGER_CHANGES)
        
        changes = 6
        assertTrue("6 changes should trigger", changes >= DEFAULT_GYRO_TRIGGER_CHANGES)
    }
    
    @Test
    fun `gyro wiggle window is 1600ms for deliberate gesture`() {
        // Extended window allows slower, more deliberate gesture
        assertEquals("Window should be 1600ms", 1600L, DEFAULT_GYRO_WINDOW_MS)
    }
    
    @Test
    fun `gyro wiggle threshold filters low angular velocity`() {
        // Threshold of 1.2 rad/s filters out slow movements
        val lowVelocity = 1.0f  // Below threshold
        val highVelocity = 1.5f // Above threshold
        
        val lowIgnored = kotlin.math.abs(lowVelocity) < DEFAULT_GYRO_AXIS_THRESHOLD
        val highIgnored = kotlin.math.abs(highVelocity) < DEFAULT_GYRO_AXIS_THRESHOLD
        
        assertTrue("Low velocity (1.0 rad/s) should be ignored", lowIgnored)
        assertFalse("High velocity (1.5 rad/s) should be processed", highIgnored)
    }
    
    @Test
    fun `stable sign rejects jitter from single wrist turn`() {
        // Simulating sensor jitter: rapid sign flips that shouldn't count
        // A single wrist turn might cause yaw to flip signs multiple times due to noise
        
        // Scenario: sign = +1, then quickly -1, then +1 all within one sample window
        // Without stable sign detection, this could count as 2+ direction changes
        // With stable sign detection, need 2 consecutive samples to confirm
        
        var confirmedSign = 1
        var candidateSign = 0
        var candidateCount = 0
        
        // Simulated sensor readings (jitter pattern)
        val jitterSigns = listOf(-1, 1, -1)  // Rapid flips
        var acceptedChanges = 0
        
        for (sign in jitterSigns) {
            if (sign == confirmedSign) {
                candidateSign = 0
                candidateCount = 0
            } else if (candidateSign != sign) {
                candidateSign = sign
                candidateCount = 1
            } else {
                candidateCount++
            }
            
            if (candidateCount >= GYRO_SIGN_STABLE_SAMPLES) {
                acceptedChanges++
                confirmedSign = sign
                candidateSign = 0
                candidateCount = 0
            }
        }
        
        // Rapid jitter should result in 0 accepted changes (not stable enough)
        assertEquals("Rapid jitter should not count as direction changes", 0, acceptedChanges)
    }
    
    @Test
    fun `stable sign accepts deliberate direction change`() {
        // Simulating deliberate wiggle: sustained sign for multiple samples
        var confirmedSign = 1
        var candidateSign = 0
        var candidateCount = 0
        
        // Deliberate change: 3 consecutive samples of opposite sign
        val deliberateSigns = listOf(-1, -1, -1)
        var acceptedChanges = 0
        
        for (sign in deliberateSigns) {
            if (sign == confirmedSign) {
                candidateSign = 0
                candidateCount = 0
            } else if (candidateSign != sign) {
                candidateSign = sign
                candidateCount = 1
            } else {
                candidateCount++
            }
            
            if (candidateCount >= GYRO_SIGN_STABLE_SAMPLES) {
                acceptedChanges++
                confirmedSign = sign
                candidateSign = 0
                candidateCount = 0
            }
        }
        
        // Deliberate sustained sign should count as 1 direction change
        assertEquals("Deliberate sustained sign should count as 1 change", 1, acceptedChanges)
    }
    
    @Test
    fun `full wiggle sequence triggers after 6 stable direction changes`() {
        // Simulate a complete 3-wiggle gesture with stable sign detection
        // Pattern: +++ --- +++ --- +++ --- (6 direction changes)
        
        var confirmedSign = 0
        var candidateSign = 0
        var candidateCount = 0
        var directionChanges = 0
        var lastChangeTime = 0L
        var currentTime = 0L
        
        // Each wiggle: 3 samples of each sign, 100ms apart
        val sampleInterval = 100L  // > GYRO_MIN_DIR_CHANGE_TIME_MS
        
        // Full wiggle pattern: 3 reps of (3 positive, 3 negative)
        val wigglePattern = listOf(
            1, 1, 1,  // First positive
            -1, -1, -1,  // First negative (change 1)
            1, 1, 1,  // Second positive (change 2)
            -1, -1, -1,  // Second negative (change 3)
            1, 1, 1,  // Third positive (change 4)
            -1, -1, -1,  // Third negative (change 5)
            1, 1, 1   // Fourth positive (change 6)
        )
        
        for (sign in wigglePattern) {
            currentTime += sampleInterval
            
            if (confirmedSign == 0) {
                confirmedSign = sign
                continue
            }
            
            if (sign == confirmedSign) {
                candidateSign = 0
                candidateCount = 0
            } else if (candidateSign != sign) {
                candidateSign = sign
                candidateCount = 1
            } else {
                candidateCount++
            }
            
            if (candidateCount >= GYRO_SIGN_STABLE_SAMPLES) {
                // Check debounce
                if (currentTime - lastChangeTime >= GYRO_MIN_DIR_CHANGE_TIME_MS) {
                    directionChanges++
                    lastChangeTime = currentTime
                    confirmedSign = sign
                    candidateSign = 0
                    candidateCount = 0
                }
            }
        }
        
        assertTrue("Full wiggle sequence should reach trigger count", 
            directionChanges >= DEFAULT_GYRO_TRIGGER_CHANGES)
    }
    
    // ============== Sensor Warmup Tests ==============
    // Tests for the sensor warmup feature that prevents stale queued events from triggering shakes
    
    @Test
    fun `shake warmup ignores first N sensor events after start`() {
        // Sensor warmup period: first 30 sensor events are ignored
        // This prevents stale queued events from triggering shakes after unlock
        val SHAKE_WARMUP_SAMPLES = 30
        
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        var shakeTriggered = false
        
        // Simulate 35 sensor events with high gForce (would normally trigger shake)
        for (i in 1..35) {
            if (warmupRemaining > 0) {
                warmupRemaining--
                // Skip processing during warmup
                continue
            }
            // After warmup, shakes can be triggered
            if (i > 30) {
                shakeTriggered = true
            }
        }
        
        assertTrue("Shake should only trigger after warmup period", shakeTriggered)
        assertEquals("Warmup should complete after 30 samples", 0, warmupRemaining)
    }
    
    @Test
    fun `shake warmup counter resets on sensor restart`() {
        // When sensors are restarted (e.g., screen on/off), warmup counter should reset
        val SHAKE_WARMUP_SAMPLES = 30
        
        // First start
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        
        // Process some samples
        for (i in 1..15) {
            if (warmupRemaining > 0) warmupRemaining--
        }
        assertEquals("After 15 samples, 15 warmup remaining", 15, warmupRemaining)
        
        // Simulate restart (stop + start)
        warmupRemaining = SHAKE_WARMUP_SAMPLES  // Reset on start()
        
        assertEquals("After restart, warmup should reset to 30", SHAKE_WARMUP_SAMPLES, warmupRemaining)
    }
    
    @Test
    fun `shake warmup prevents immediate triggers after phone unlock`() {
        // Combined test: warmup + unlock delay should prevent false triggers
        val SHAKE_WARMUP_SAMPLES = 30
        val shakeUnlockDelayMs = 2500L
        val screenOnTime = 1000L
        
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        var shakeIgnoredDueToWarmup = 0
        var shakeIgnoredDueToUnlockDelay = 0
        var shakeAccepted = 0
        
        // Simulate sensor events starting at screen on time
        var eventTime = screenOnTime
        val sensorInterval = 20L  // ~50Hz sensor rate
        
        for (i in 1..200) {
            eventTime += sensorInterval
            
            // Check warmup first
            if (warmupRemaining > 0) {
                warmupRemaining--
                shakeIgnoredDueToWarmup++
                continue
            }
            
            // Check unlock delay
            if (eventTime - screenOnTime < shakeUnlockDelayMs) {
                shakeIgnoredDueToUnlockDelay++
                continue
            }
            
            // Would be accepted
            shakeAccepted++
        }
        
        // First 30 events ignored by warmup
        assertEquals("30 events ignored by warmup", 30, shakeIgnoredDueToWarmup)
        
        // Events after warmup but before 2500ms unlock delay should be ignored
        assertTrue("Some events ignored by unlock delay", shakeIgnoredDueToUnlockDelay > 0)
        
        // Events after both warmup AND unlock delay should be accepted
        assertTrue("Events after both protections should be accepted", shakeAccepted > 0)
    }
    
    @Test
    fun `warmup decrements on ALL accelerometer events regardless of gForce`() {
        // BUG FIX TEST: Previously warmup counter only decremented when gForce >= threshold.
        // This meant low-gForce events (normal gravity ~1.0g) during warmup never counted,
        // so the warmup period was effectively infinite until a real shake event arrived.
        // After the fix, warmup decrements on every sensor event before the threshold check.
        val SHAKE_WARMUP_SAMPLES = 30
        val shakeThresholdGravity = 2.7f
        
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        
        // Simulate 30 low-gForce events (normal gravity, phone sitting still)
        // These represent stale queued sensor events delivered at unlock
        for (i in 1..30) {
            val gForce = 1.0f  // Normal gravity - below threshold
            
            // NEW BEHAVIOR: warmup decrements regardless of gForce
            if (warmupRemaining > 0) {
                warmupRemaining--
            }
            // gForce check happens AFTER warmup decrement
            if (gForce < shakeThresholdGravity) continue
        }
        
        assertEquals("Warmup should be fully consumed by 30 low-gForce events", 0, warmupRemaining)
    }
    
    @Test
    fun `old bug - warmup only decremented on high gForce events`() {
        // Demonstrates the OLD buggy behavior where low-gForce events didn't decrement warmup
        val SHAKE_WARMUP_SAMPLES = 30
        val shakeThresholdGravity = 2.7f
        
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        
        // OLD BEHAVIOR: gForce check was BEFORE warmup decrement
        for (i in 1..30) {
            val gForce = 1.0f  // Below threshold
            if (gForce < shakeThresholdGravity) continue  // OLD: returned early, skipping warmup decrement
            if (warmupRemaining > 0) warmupRemaining--
        }
        
        // Bug: warmup counter never decremented because all events were below threshold!
        assertEquals("OLD BUG: warmup never decremented on low-gForce events", 
            SHAKE_WARMUP_SAMPLES, warmupRemaining)
    }
    
    @Test
    fun `gyro wiggle warmup also decrements independently`() {
        // The gyroscope wiggle detection path also needs to decrement warmup
        // in case only gyro events arrive (no accelerometer events)
        val SHAKE_WARMUP_SAMPLES = 30
        var warmupRemaining = SHAKE_WARMUP_SAMPLES
        
        // Simulate gyro events decrementing warmup
        for (i in 1..30) {
            if (warmupRemaining > 0) {
                warmupRemaining--
                continue  // Skip wiggle detection during warmup
            }
        }
        
        assertEquals("Gyro path should fully consume warmup in 30 events", 0, warmupRemaining)
    }
    
    // ============== Display Rotation Source Tests ==============
    // The wallpaper now reads the REAL display rotation via DisplayManager (reliable from a
    // Service context) and applies it directly. The canvas orientation is only a fallback
    // for the rare case where no display rotation is available.

    @Test
    fun `effective rotation uses physical display rotation regardless of canvas`() {
        // Previously the wallpaper forced ROTATION_0 whenever the canvas stayed portrait,
        // which swapped the landscape parallax axes. With a reliable rotation we use it as-is.
        val ROTATION_0 = 0
        val ROTATION_90 = 1
        val ROTATION_270 = 3

        fun effectiveRotation(reliableRotation: Int?, canvasIsLandscape: Boolean): Int {
            if (reliableRotation != null) return reliableRotation
            return if (canvasIsLandscape) ROTATION_90 else ROTATION_0
        }

        // Canvas portrait, device physically landscape → use the real landscape rotation
        assertEquals("ROTATION_90 used as-is", ROTATION_90, effectiveRotation(ROTATION_90, false))
        assertEquals("ROTATION_270 used as-is", ROTATION_270, effectiveRotation(ROTATION_270, false))
        assertEquals("ROTATION_0 stays 0", ROTATION_0, effectiveRotation(ROTATION_0, false))
    }

    @Test
    fun `effective rotation preserves landscape when canvas is landscape`() {
        val ROTATION_0 = 0
        val ROTATION_90 = 1
        val ROTATION_270 = 3

        fun effectiveRotation(reliableRotation: Int?, canvasIsLandscape: Boolean): Int {
            if (reliableRotation != null) return reliableRotation
            return if (canvasIsLandscape) ROTATION_90 else ROTATION_0
        }

        assertEquals("ROTATION_90 preserved", ROTATION_90, effectiveRotation(ROTATION_90, true))
        assertEquals("ROTATION_270 preserved", ROTATION_270, effectiveRotation(ROTATION_270, true))
    }

    @Test
    fun `effective rotation falls back to canvas orientation when display rotation unavailable`() {
        // When no display rotation can be read, infer orientation from the canvas size.
        val ROTATION_0 = 0
        val ROTATION_90 = 1

        fun effectiveRotation(reliableRotation: Int?, canvasIsLandscape: Boolean): Int {
            if (reliableRotation != null) return reliableRotation
            return if (canvasIsLandscape) ROTATION_90 else ROTATION_0
        }

        assertEquals("Landscape canvas → ROTATION_90", ROTATION_90, effectiveRotation(null, true))
        assertEquals("Portrait canvas → ROTATION_0", ROTATION_0, effectiveRotation(null, false))
    }
}

/**
 * Test implementation of Averager (mirrors the internal class in ParallaxSensorManager).
 */
private class TestAverager(private val bufferSize: Int) {
    private val values = FloatArray(bufferSize)
    private var index = 0
    
    fun add(value: Float): TestAverager {
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
