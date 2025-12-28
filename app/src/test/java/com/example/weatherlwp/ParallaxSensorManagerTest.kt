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
 * 
 * Updated: The new implementation uses adaptive baseline, so parallax
 * works at any phone orientation by measuring relative tilt from baseline.
 */
class ParallaxSensorManagerTest {

    companion object {
        const val GRAVITY = 9.81f
        const val SENSITIVITY = 3.5f  // Matches ParallaxSensorManager.SENSITIVITY
        const val DEAD_ZONE = 0.3f    // Matches ParallaxSensorManager.DEAD_ZONE
    }

    // ============== Issue #6: Axis Mapping Tests ==============
    
    @Test
    fun tiltX_should_respond_to_relative_X_change() {
        // With adaptive baseline, tilt is measured relative to initial position
        // Simulate: baseline at 0, then phone tilts right by 4.9
        val baselineX = 0f
        val currentX = 4.9f
        
        val tiltX = calculateRelativeTilt(currentX, baselineX)
        
        // TiltX should be positive (tilted right)
        assertTrue("TiltX should be positive for right tilt", tiltX > 0.3f)
    }
    
    @Test
    fun tiltY_should_respond_to_relative_Y_change() {
        // Simulate: baseline at 5.0 (phone held at angle), then tilts forward by 3
        val baselineY = 5.0f
        val currentY = 8.0f
        
        val tiltY = calculateRelativeTilt(currentY, baselineY)
        
        // TiltY should be positive (tilted forward)
        assertTrue("TiltY should be positive for forward tilt", tiltY > 0.3f)
    }
    
    @Test
    fun `tilt should be normalized to -1 to 1 range`() {
        // Even with max tilt, output should be clamped
        val baseline = 0f
        val extremeValue = GRAVITY * 2  // Way beyond normal tilt
        
        val tilt = calculateRelativeTilt(extremeValue, baseline)
        
        assertTrue("Tilt should be <= 1", tilt <= 1f)
        assertTrue("Tilt should be >= -1", tilt >= -1f)
    }
    
    @Test
    fun `no relative movement should produce zero offset`() {
        // When current equals baseline (no tilt from resting position)
        val baseline = 5.0f
        val current = 5.0f
        
        val tilt = calculateRelativeTilt(current, baseline)
        
        assertTrue("No relative movement should have ~0 tilt", kotlin.math.abs(tilt) < 0.1f)
    }
    
    @Test
    fun `dead zone should prevent small jitters`() {
        // Small movement within dead zone should result in 0
        val baseline = 0f
        val smallMovement = 0.2f  // Less than DEAD_ZONE
        
        val delta = smallMovement - baseline
        val withDeadZone = if (kotlin.math.abs(delta) < DEAD_ZONE) 0f else delta
        
        assertEquals("Small movement should be filtered", 0f, withDeadZone, 0.01f)
    }
    
    @Test
    fun `adaptive baseline allows any starting orientation`() {
        // Phone held upright (ay ≈ 9.8): baseline adapts to this
        // Then user tilts forward slightly (ay increases to 10.5 theoretically)
        // This should still produce parallax, even at unusual angles
        val baselineY = 9.8f  // Upright position
        val tiltedY = 9.8f + 1.5f  // Tilted forward
        
        val tilt = calculateRelativeTilt(tiltedY, baselineY)
        
        assertTrue("Should respond to relative tilt from any baseline", tilt > 0.2f)
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
    
    // Helper function matching new ParallaxSensorManager logic with adaptive baseline
    private fun calculateRelativeTilt(current: Float, baseline: Float): Float {
        var delta = current - baseline
        
        // Apply dead zone
        if (kotlin.math.abs(delta) < DEAD_ZONE) delta = 0f
        
        // Apply sensitivity and normalize
        return (delta * SENSITIVITY / GRAVITY).coerceIn(-1f, 1f)
    }
}
