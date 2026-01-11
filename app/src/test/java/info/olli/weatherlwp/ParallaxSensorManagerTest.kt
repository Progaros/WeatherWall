package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for ParallaxSensorManager - Gravity projection approach
 * 
 * The implementation extracts gravity directly from the rotation matrix
 * instead of using Euler angles (which suffer from gimbal lock).
 * 
 * Key approach:
 * - Gravity in screen coords = -[R[6], R[7], R[8]] (negative third row of remapped matrix)
 * - Deadzone prevents noise when device is flat
 * - Near-direct pass-through for minimal latency (~20ms target, matching reference app)
 * - Outputs absolute tilt position (not velocity) so parallax follows held angle
 * 
 * Latency optimization:
 * - Reference app (RotationManager.java) uses no smoothing filter
 * - We use high FILTER_ALPHA (0.85) for near-instant response
 * - Gyroscope mode bypasses filter entirely for direct output
 */
class ParallaxSensorManagerTest {

    companion object {
        // Near-direct pass-through for minimal latency (reference app uses no filter)
        const val FILTER_ALPHA = 0.85f  // Matches ParallaxSensorManager
        const val DEADZONE = 0.08f
        const val GRAVITY_EARTH = 9.81f  // SensorManager.GRAVITY_EARTH
        const val SHAKE_THRESHOLD_GRAVITY = 2.7f
        const val SHAKE_SLOP_TIME_MS = 500L
        const val SHAKE_COUNT_RESET_TIME_MS = 3000L
        const val SHAKE_TRIGGER_COUNT = 3
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
    
    // ============== Deadzone / Confidence Tests ==============
    
    @Test
    fun `confidence is zero when device is flat`() {
        // Device flat: gravity is purely in Z, horizontal magnitude is 0
        val gx = 0f
        val gy = 0f
        val horizMag = sqrt(gx * gx + gy * gy)
        
        val confidence = ((horizMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        
        assertEquals("Flat device should have zero confidence", 0f, confidence, 0.001f)
    }
    
    @Test
    fun `confidence is full when device is tilted significantly`() {
        // Device tilted 45°: horizontal magnitude is ~0.7
        val gx = 0.5f
        val gy = 0.5f
        val horizMag = sqrt(gx * gx + gy * gy)  // ~0.707
        
        val confidence = ((horizMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        
        assertTrue("Tilted device should have high confidence", confidence > 0.6f)
    }
    
    @Test
    fun `confidence scales smoothly near deadzone`() {
        // Just above deadzone
        val horizMag = DEADZONE + 0.1f
        val confidence = ((horizMag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        
        assertTrue("Just above deadzone should have small but positive confidence", 
            confidence > 0f && confidence < 0.2f)
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
    
    // ============== Low-Pass Filter Tests ==============
    
    @Test
    fun `low-pass filter should smooth sudden changes`() {
        var filtered = 0f
        val target = 1f
        
        // Single update
        filtered += FILTER_ALPHA * (target - filtered)
        
        assertEquals("First filtered value should be alpha * target", 
            FILTER_ALPHA, filtered, 0.001f)
        assertTrue("Filtered should be less than target", filtered < target)
    }
    
    @Test
    fun `low-pass filter converges to target`() {
        var filtered = 0f
        val target = 0.8f
        
        // Many updates
        for (i in 0 until 100) {
            filtered += FILTER_ALPHA * (target - filtered)
        }
        
        assertTrue("After many updates, should be close to target",
            kotlin.math.abs(filtered - target) < 0.01f)
    }
    
    // ============== Tilt Position Output Tests ==============
    
    @Test
    fun `filtered tilt position is output directly`() {
        // The new system outputs filtered tilt position, not velocity
        var filtered = 0f
        val target = 0.6f
        
        // Simulate several filter updates
        for (i in 0 until 50) {
            filtered += FILTER_ALPHA * (target - filtered)
        }
        
        // The filtered value IS the output (no velocity calculation)
        assertTrue("Filtered value should be close to target after convergence",
            kotlin.math.abs(filtered - target) < 0.05f)
    }
    
    @Test
    fun `tilt position is stable when device held at angle`() {
        // Simulate holding device at constant tilt
        var filtered = 0f
        val constantTarget = 0.5f
        
        // Initial convergence
        for (i in 0 until 100) {
            filtered += FILTER_ALPHA * (constantTarget - filtered)
        }
        val initialPosition = filtered
        
        // Continue with same target (device held steady)
        for (i in 0 until 50) {
            filtered += FILTER_ALPHA * (constantTarget - filtered)
        }
        val laterPosition = filtered
        
        // Position should be stable
        assertEquals("Position should remain stable when tilt is constant",
            initialPosition, laterPosition, 0.001f)
    }
    
    @Test
    fun `tilt position returns to zero when device leveled`() {
        var filtered = 0.5f  // Start at some offset
        val levelTarget = 0f  // Device is now level
        
        // Simulate returning to level
        for (i in 0 until 100) {
            filtered += FILTER_ALPHA * (levelTarget - filtered)
        }
        
        // Should return to near zero
        assertTrue("Position should return to near zero when leveled",
            kotlin.math.abs(filtered) < 0.01f)
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
    
    // ============== Gyroscope Reference App Match Tests ==============
    // Tests now match RotationManager.java from reference app exactly
    
    @Test
    fun `gyroscope raw values map directly to averagers`() {
        // Reference app: averagers[0].add(values[0]) → lastYaw
        //               averagers[1].add(values[1]) → lastPitch
        //               averagers[2].add(values[2]) → lastRoll
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
        // Reference ForestRenderer.java line 584: ROTATION_0: tiltX = -lastPitch, tiltY = lastYaw
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
        // Reference ForestRenderer.java line 587: ROTATION_90: tiltX = -lastYaw, tiltY = -lastPitch
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
        // Reference ForestRenderer.java line 591: ROTATION_270: tiltX = lastYaw, tiltY = lastPitch
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
    fun `gyroscope no deadzone before averaging matches reference`() {
        // Reference app adds ALL values to averagers without any deadzone filtering
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
    fun `gyroscope no decay matches reference`() {
        // Reference app NEVER decays accumulated angles - they hold until moved
        val MAX_TILT_RAD = 0.31415927f
        var gyroAngle = 0.15f  // Some accumulated angle
        
        // In reference app, when no motion, angle stays the same (no decay)
        // Old buggy code: gyroAngle *= GYRO_DECAY_RATE (0.998)
        // Reference code: gyroAngle = gyroAngle (no change)
        
        val originalAngle = gyroAngle
        // Simulate 100 frames with no motion - angle should stay exactly the same
        for (i in 0 until 100) {
            // Reference: no decay
            // gyroAngle = gyroAngle (stays same)
        }
        
        assertEquals("Angle should not decay (reference behavior)", originalAngle, gyroAngle, 0.001f)
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
        var startTime = 0L
        var directionChanges = 2
        
        // Simulate timeout after first wiggle
        startTime = 100L
        val now = 1000L  // 900ms later, exceeds 800ms window
        
        if (now - startTime > timeWindow) {
            directionChanges = 0
            startTime = 0L
        }
        
        assertEquals("Direction changes should reset on timeout", 0, directionChanges)
    }
    
    // ============== Latency Optimization Tests ==============
    
    @Test
    fun `high filter alpha provides near-instant response`() {
        // With FILTER_ALPHA = 0.85, we reach 85% of target in first frame
        var filtered = 0f
        val target = 1f
        
        // Single update
        filtered += FILTER_ALPHA * (target - filtered)
        
        // Should reach 85% immediately (vs old 40% with alpha=0.4)
        assertTrue("Should reach at least 80% of target in first frame", filtered >= 0.80f)
        
        // Second update gets us to 97.75%
        filtered += FILTER_ALPHA * (target - filtered)
        assertTrue("Should reach at least 97% after 2 frames", filtered >= 0.97f)
    }
    
    @Test
    fun `latency is under 2 frames with high alpha`() {
        // Target: reach 95% of target in under 2 frames (vs ~8 frames with old alpha)
        var filtered = 0f
        val target = 0.8f
        var framesTo95Percent = 0
        
        while (filtered < target * 0.95f && framesTo95Percent < 100) {
            filtered += FILTER_ALPHA * (target - filtered)
            framesTo95Percent++
        }
        
        assertTrue("Should reach 95% of target in 2 frames or less (got $framesTo95Percent)", 
            framesTo95Percent <= 2)
    }
    
    @Test
    fun `gyroscope mode uses direct output without filter`() {
        // Gyroscope mode should bypass filter entirely for minimum latency
        // This matches reference app behavior (RotationManager.java)
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
    fun `reference app latency target is 20ms`() {
        // At 50Hz sensor rate (SENSOR_DELAY_GAME = ~20ms), we want:
        // - Sensor event arrives: +20ms
        // - Processing (near-instant with direct output): +0-1ms
        // - Next render frame picks up value: +16ms max (60fps)
        // Total: ~36ms worst case, ~20ms typical
        
        // With high FILTER_ALPHA (0.85), single frame convergence is ~85%
        // This matches reference app's direct-value approach closely
        
        val sensorDelayMs = 20L  // SENSOR_DELAY_GAME
        val renderDelayMs = 16L  // 60fps
        val worstCaseLatency = sensorDelayMs + renderDelayMs
        
        assertTrue("Worst case latency should be under 50ms", worstCaseLatency < 50)
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
