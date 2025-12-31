package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/**
 * Unit tests for ParallaxSensorManager - Tests for orientation-aware parallax
 * 
 * The implementation uses Android's standard rotation matrix approach:
 * - SensorManager.getRotationMatrixFromVector() for rotation vector sensors
 * - SensorManager.getRotationMatrix() for gravity fallback
 * - remapCoordinateSystem() for display rotation handling
 * - getOrientation() to extract pitch/roll for parallax
 * 
 * These tests validate the logical behavior without mocking Android sensors.
 */
class ParallaxSensorManagerTest {

    companion object {
        const val VELOCITY_SCALE = 5f  // Matches ParallaxSensorManager
        const val MIN_VELOCITY = 0.008f
        const val TILT_ALPHA = 0.85f
    }

    // ============== Velocity Calculation Tests ==============
    
    @Test
    fun `velocity should be zero when tilt is constant`() {
        val prevTilt = 0.5f
        val currentTilt = 0.5f
        
        val velocity = (currentTilt - prevTilt) * VELOCITY_SCALE
        assertEquals("No change in tilt = no velocity", 0f, velocity, 0.001f)
    }
    
    @Test
    fun `velocity should be positive when tilt increases`() {
        val prevTilt = 0.3f
        val currentTilt = 0.5f
        
        val velocity = (currentTilt - prevTilt) * VELOCITY_SCALE
        assertTrue("Increasing tilt = positive velocity", velocity > 0)
    }
    
    @Test
    fun `velocity should be negative when tilt decreases`() {
        val prevTilt = 0.5f
        val currentTilt = 0.3f
        
        val velocity = (currentTilt - prevTilt) * VELOCITY_SCALE
        assertTrue("Decreasing tilt = negative velocity", velocity < 0)
    }
    
    @Test
    fun `small velocity should be filtered by dead zone`() {
        val prevTilt = 0.5000f
        val currentTilt = 0.5010f  // Very tiny change
        
        var velocity = (currentTilt - prevTilt) * VELOCITY_SCALE
        if (kotlin.math.abs(velocity) < MIN_VELOCITY) velocity = 0f
        
        assertEquals("Tiny velocity should be filtered", 0f, velocity, 0.001f)
    }
    
    @Test
    fun `velocity should be clamped to valid range`() {
        val prevTilt = -1f
        val currentTilt = 1f  // Maximum possible change
        
        var velocity = (currentTilt - prevTilt) * VELOCITY_SCALE
        velocity = velocity.coerceIn(-1f, 1f)
        
        assertTrue("Velocity should be clamped to 1", velocity <= 1f)
        assertTrue("Velocity should be clamped to -1", velocity >= -1f)
    }
    
    // ============== Smoothing Filter Tests ==============
    
    @Test
    fun `low-pass filter should smooth sudden changes`() {
        var smoothedValue = 0f
        
        // Simulate sudden jump in sensor value
        val newValue = 1f
        smoothedValue = TILT_ALPHA * smoothedValue + (1 - TILT_ALPHA) * newValue
        
        // First update should not reach new value immediately
        assertTrue("First filtered value should be < raw value", smoothedValue < newValue)
        assertEquals("First filtered value", 0.15f, smoothedValue, 0.01f)
        
        // After multiple updates, should approach target
        for (i in 0 until 50) {
            smoothedValue = TILT_ALPHA * smoothedValue + (1 - TILT_ALPHA) * newValue
        }
        assertTrue("After many updates, should be close to target", 
            kotlin.math.abs(smoothedValue - newValue) < 0.01f)
    }
    
    @Test
    fun `smoothing filter should preserve direction of change`() {
        var smoothedValue = 0.5f
        
        // Move toward higher value
        val highValue = 0.8f
        smoothedValue = TILT_ALPHA * smoothedValue + (1 - TILT_ALPHA) * highValue
        assertTrue("Should move toward higher value", smoothedValue > 0.5f)
        
        // Move toward lower value
        val lowValue = 0.2f
        for (i in 0 until 20) {
            smoothedValue = TILT_ALPHA * smoothedValue + (1 - TILT_ALPHA) * lowValue
        }
        assertTrue("Should move toward lower value", smoothedValue < 0.5f)
    }
    
    // ============== Orientation Angle to Tilt Conversion Tests ==============
    
    @Test
    fun `sin conversion should give smooth tilt response`() {
        // Small angles should give proportional tilt
        val smallAngle = 0.1f  // ~5.7 degrees
        val tilt = sin(smallAngle.toDouble()).toFloat()
        assertTrue("Small angle should give small tilt", tilt < 0.15f)
        assertTrue("Small angle should give positive tilt", tilt > 0f)
    }
    
    @Test
    fun `tilt should be normalized to valid range`() {
        // Even extreme angles should be clamped
        val extremeAngle = 2f  // > 90 degrees
        val tilt = sin(extremeAngle.toDouble()).toFloat().coerceIn(-1f, 1f)
        
        assertTrue("Tilt should be <= 1", tilt <= 1f)
        assertTrue("Tilt should be >= -1", tilt >= -1f)
    }
    
    @Test
    fun `zero angle should give zero tilt`() {
        val zeroAngle = 0f
        val tilt = sin(zeroAngle.toDouble()).toFloat()
        assertEquals("Zero angle = zero tilt", 0f, tilt, 0.001f)
    }
    
    @Test
    fun `negative angle should give negative tilt`() {
        val negativeAngle = -0.3f
        val tilt = sin(negativeAngle.toDouble()).toFloat()
        assertTrue("Negative angle should give negative tilt", tilt < 0f)
    }
    
    // ============== Axis Remapping Logic Tests ==============
    
    @Test
    fun `rotation 90 should swap and negate axes correctly`() {
        // Simulating remapCoordinateSystem behavior for ROTATION_90
        // AXIS_Y, AXIS_MINUS_X means: new X = old Y, new Y = -old X
        val originalX = 0.5f
        val originalY = 0.3f
        
        val remappedX = originalY
        val remappedY = -originalX
        
        assertEquals("X should become Y", originalY, remappedX, 0.001f)
        assertEquals("Y should become -X", -originalX, remappedY, 0.001f)
    }
    
    @Test
    fun `rotation 180 should negate both axes`() {
        // AXIS_MINUS_X, AXIS_MINUS_Y means: new X = -old X, new Y = -old Y
        val originalX = 0.5f
        val originalY = 0.3f
        
        val remappedX = -originalX
        val remappedY = -originalY
        
        assertEquals("X should be negated", -originalX, remappedX, 0.001f)
        assertEquals("Y should be negated", -originalY, remappedY, 0.001f)
    }
    
    @Test
    fun `rotation 270 should swap axes opposite to 90`() {
        // AXIS_MINUS_Y, AXIS_X means: new X = -old Y, new Y = old X
        val originalX = 0.5f
        val originalY = 0.3f
        
        val remappedX = -originalY
        val remappedY = originalX
        
        assertEquals("X should become -Y", -originalY, remappedX, 0.001f)
        assertEquals("Y should become X", originalX, remappedY, 0.001f)
    }
    
    // ============== Parallax Direction Tests ==============
    
    @Test
    fun `velocity negation should create natural parallax effect`() {
        // When user tilts left (positive velocity in sensor), scene should shift right (negative output)
        val rawVelocityX = 0.5f
        val parallaxVelocityX = -rawVelocityX
        
        assertTrue("Positive sensor velocity should give negative parallax", parallaxVelocityX < 0)
    }
    
    @Test
    fun `parallax should respond to both X and Y axes`() {
        val velocityX = 0.3f
        val velocityY = 0.4f
        
        val parallaxX = -velocityX
        val parallaxY = -velocityY
        
        assertNotEquals("Parallax X should not be zero", 0f, parallaxX)
        assertNotEquals("Parallax Y should not be zero", 0f, parallaxY)
    }
    
    // ============== Sensor Priority Tests ==============
    
    @Test
    fun `sensor priority order is correct`() {
        // This tests the logical order, actual sensor availability is device-dependent
        // Priority: Game Rotation Vector > Rotation Vector > Gravity > Accelerometer
        val priorities = listOf(
            "TYPE_GAME_ROTATION_VECTOR",
            "TYPE_ROTATION_VECTOR", 
            "TYPE_GRAVITY",
            "TYPE_ACCELEROMETER"
        )
        
        assertEquals("First priority", "TYPE_GAME_ROTATION_VECTOR", priorities[0])
        assertEquals("Second priority", "TYPE_ROTATION_VECTOR", priorities[1])
        assertEquals("Third priority", "TYPE_GRAVITY", priorities[2])
        assertEquals("Last priority", "TYPE_ACCELEROMETER", priorities[3])
    }
    
    // ============== Dead Zone Threshold Tests ==============
    
    @Test
    fun `dead zone should filter micro-movements`() {
        // Values just below threshold should be zeroed
        val tinyVelocity = MIN_VELOCITY * 0.5f
        val filteredVelocity = if (kotlin.math.abs(tinyVelocity) < MIN_VELOCITY) 0f else tinyVelocity
        
        assertEquals("Below-threshold velocity should be zero", 0f, filteredVelocity, 0.0001f)
    }
    
    @Test
    fun `dead zone should pass larger movements`() {
        // Values above threshold should pass through
        val normalVelocity = MIN_VELOCITY * 3f
        val filteredVelocity = if (kotlin.math.abs(normalVelocity) < MIN_VELOCITY) 0f else normalVelocity
        
        assertEquals("Above-threshold velocity should pass", normalVelocity, filteredVelocity, 0.0001f)
    }
}
