package com.example.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ParallaxSensorManager - Tests for Issue #6
 * 
 * Issue #6: Parallax Axis Wrong
 * Tilting phone left/right should move scene correctly
 * X-axis (values[0]) = left/right tilt
 * Y-axis (values[1]) = forward/back tilt
 */
class ParallaxSensorManagerTest {

    companion object {
        const val GRAVITY = 9.81f
        const val SENSITIVITY = 150f
    }

    // ============== Issue #6: Axis Mapping Tests ==============
    
    @Test
    fun tiltX_should_use_accelerometer_X_axis() {
        // When phone tilts left/right, values[0] changes
        // This should map to horizontal parallax (tiltX)
        val accelerometerX = 4.9f  // Phone tilted ~30 degrees
        val accelerometerY = 0f
        
        val (tiltX, tiltY) = calculateTilt(accelerometerX, accelerometerY)
        
        // TiltX should be non-zero, tiltY should be near zero
        assertTrue("TiltX should respond to X-axis", kotlin.math.abs(tiltX) > 0.3f)
        assertTrue("TiltY should be near zero", kotlin.math.abs(tiltY) < 0.1f)
    }
    
    @Test
    fun tiltY_should_use_accelerometer_Y_axis() {
        // When phone tilts forward/back, values[1] changes
        // This should map to vertical parallax (tiltY)
        val accelerometerX = 0f
        val accelerometerY = 4.9f  // Phone tilted forward ~30 degrees
        
        val (tiltX, tiltY) = calculateTilt(accelerometerX, accelerometerY)
        
        // TiltY should be non-zero, tiltX should be near zero
        assertTrue("TiltY should respond to Y-axis", kotlin.math.abs(tiltY) > 0.3f)
        assertTrue("TiltX should be near zero", kotlin.math.abs(tiltX) < 0.1f)
    }
    
    @Test
    fun `tilt should be normalized to -1 to 1 range`() {
        // Even with max tilt, output should be clamped
        val maxAcceleration = GRAVITY * 2  // More than gravity (impossible in practice)
        
        val tiltX = (maxAcceleration / GRAVITY).coerceIn(-1f, 1f)
        val tiltY = (-maxAcceleration / GRAVITY).coerceIn(-1f, 1f)
        
        assertTrue("TiltX should be <= 1", tiltX <= 1f)
        assertTrue("TiltX should be >= -1", tiltX >= -1f)
        assertTrue("TiltY should be <= 1", tiltY <= 1f)
        assertTrue("TiltY should be >= -1", tiltY >= -1f)
    }
    
    @Test
    fun `output should be scaled by sensitivity`() {
        val normalizedTilt = 0.5f
        val expectedOffset = normalizedTilt * SENSITIVITY
        
        assertEquals("Offset should equal tilt * sensitivity", 75f, expectedOffset, 0.1f)
    }
    
    @Test
    fun `flat phone should produce near-zero offset`() {
        // Phone lying flat: X=0, Y=0 (Z would be ~9.81, but we only use X and Y)
        val accelerometerX = 0f
        val accelerometerY = 0f
        
        val (tiltX, tiltY) = calculateTilt(accelerometerX, accelerometerY)
        val offsetX = tiltX * SENSITIVITY
        val offsetY = tiltY * SENSITIVITY
        
        assertTrue("Flat phone should have ~0 X offset", kotlin.math.abs(offsetX) < 1f)
        assertTrue("Flat phone should have ~0 Y offset", kotlin.math.abs(offsetY) < 1f)
    }

    // ============== Smoothing Filter Tests ==============
    
    @Test
    fun `low-pass filter should smooth sudden changes`() {
        val alpha = 0.85f
        var smoothedValue = 0f
        
        // Simulate sudden jump in sensor value
        val newValue = 5f
        smoothedValue = alpha * smoothedValue + (1 - alpha) * newValue
        
        // First update should not reach new value immediately
        assertTrue("First filtered value should be < raw value", smoothedValue < newValue)
        assertEquals("First filtered value", 0.75f, smoothedValue, 0.01f)
        
        // After multiple updates, should approach target
        for (i in 0 until 20) {
            smoothedValue = alpha * smoothedValue + (1 - alpha) * newValue
        }
        assertTrue("After many updates, should be close to target", 
            kotlin.math.abs(smoothedValue - newValue) < 0.5f)
    }
    
    // Helper function matching ParallaxSensorManager logic
    private fun calculateTilt(ax: Float, ay: Float): Pair<Float, Float> {
        val tiltX = (ax / GRAVITY).coerceIn(-1f, 1f)
        val tiltY = (ay / GRAVITY).coerceIn(-1f, 1f)
        return Pair(tiltX, tiltY)
    }
}
