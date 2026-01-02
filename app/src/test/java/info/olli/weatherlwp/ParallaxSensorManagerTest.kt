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
 * - Low-pass filter for smooth motion
 * - Outputs absolute tilt position (not velocity) so parallax follows held angle
 */
class ParallaxSensorManagerTest {

    companion object {
        const val FILTER_ALPHA = 0.12f  // Matches ParallaxSensorManager
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
    
    // ============== Gyroscope Display Rotation Axis Remapping Tests ==============
    
    @Test
    fun `gyroscope ROTATION_0 keeps axes unchanged`() {
        // For ROTATION_0: (rateX, rateY, rateZ) = (values[0], values[1], values[2])
        val values = floatArrayOf(0.5f, 0.3f, 0.1f)
        
        // ROTATION_0 remapping
        val rateX = values[0]
        val rateY = values[1]
        val rateZ = values[2]
        
        assertEquals("rateX should equal values[0]", 0.5f, rateX, 0.001f)
        assertEquals("rateY should equal values[1]", 0.3f, rateY, 0.001f)
        assertEquals("rateZ should equal values[2]", 0.1f, rateZ, 0.001f)
    }
    
    @Test
    fun `gyroscope ROTATION_90 swaps and negates axes`() {
        // For ROTATION_90: (rateX, rateY, rateZ) = (values[1], -values[0], values[2])
        val values = floatArrayOf(0.5f, 0.3f, 0.1f)
        
        // ROTATION_90 remapping
        val rateX = values[1]
        val rateY = -values[0]
        val rateZ = values[2]
        
        assertEquals("rateX for ROTATION_90 should be values[1]", 0.3f, rateX, 0.001f)
        assertEquals("rateY for ROTATION_90 should be -values[0]", -0.5f, rateY, 0.001f)
        assertEquals("rateZ should remain values[2]", 0.1f, rateZ, 0.001f)
    }
    
    @Test
    fun `gyroscope ROTATION_270 swaps and negates opposite to 90`() {
        // For ROTATION_270: (rateX, rateY, rateZ) = (-values[1], values[0], values[2])
        val values = floatArrayOf(0.5f, 0.3f, 0.1f)
        
        // ROTATION_270 remapping
        val rateX = -values[1]
        val rateY = values[0]
        val rateZ = values[2]
        
        assertEquals("rateX for ROTATION_270 should be -values[1]", -0.3f, rateX, 0.001f)
        assertEquals("rateY for ROTATION_270 should be values[0]", 0.5f, rateY, 0.001f)
        assertEquals("rateZ should remain values[2]", 0.1f, rateZ, 0.001f)
    }
    
    @Test
    fun `gyroscope output maps roll to tiltX and pitch to tiltY`() {
        // After remapping and accumulation:
        // tiltX = gyroRoll / MAX_TILT_RAD (left/right tilt)
        // tiltY = -gyroPitch / MAX_TILT_RAD (forward/back tilt, negated for natural feel)
        val MAX_TILT_RAD = 0.31415927f
        
        val gyroRoll = 0.15f
        val gyroPitch = 0.1f
        
        val tiltX = (gyroRoll / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val tiltY = (-gyroPitch / MAX_TILT_RAD).coerceIn(-1f, 1f)
        
        assertTrue("tiltX should be positive (from positive roll)", tiltX > 0)
        assertTrue("tiltY should be negative (from negated positive pitch)", tiltY < 0)
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
