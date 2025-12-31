package info.olli.weatherlwp

import info.olli.weatherlwp.parallax.ParallaxCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ParallaxCalculator
 * Tests parallax physics: strength, max offset, position-based tilt following
 */
class ParallaxCalculatorTest {

    private lateinit var calculator: ParallaxCalculator

    @Before
    fun setUp() {
        calculator = ParallaxCalculator()
    }

    // P1: Test position follows tilt and respects maxOffset
    @Test
    fun testPositionFollowsTiltWithinMaxOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Full tilt to the right
        calculator.updateFromSensor(1.0f, 0f)
        // Let it converge
        repeat(100) { calculator.update(16) }
        
        // Position should approach maxOffset * strength * tilt = 40 * 1.0 * 1.0 = 40
        val offset = calculator.getOffsetX()
        assertTrue("X offset should be near maxOffset", offset >= 35f && offset <= 40f)
    }

    // P2: Test max offset is respected with high strength
    @Test
    fun testMaxOffsetIsRespectedWithHighStrength() {
        calculator.strength = 2.0f  // High strength
        calculator.maxOffsetPx = 50f
        
        calculator.updateFromSensor(1.0f, 1.0f)
        // Run multiple updates to converge
        repeat(100) { calculator.update(16) }
        
        val offset = calculator.getOffset()
        // Target = 1.0 * 50 * 2.0 = 100, but clamped to 50
        assertTrue("X should be clamped to maxOffset", kotlin.math.abs(offset.first) <= 50f + 1f)
        assertTrue("Y should be clamped to maxOffset", kotlin.math.abs(offset.second) <= 50f + 1f)
    }

    // P3: Test vertical movement works
    @Test
    fun testVerticalParallaxWorks() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Full Y tilt, no X
        calculator.updateFromSensor(0f, 1.0f)
        repeat(50) { calculator.update(16) }
        
        assertTrue("Y offset should be positive for positive Y input", 
            calculator.getOffsetY() > 10f)  // Should have moved significantly
        assertEquals("X offset should be minimal for no X input", 
            0f, calculator.getOffsetX(), 1f)
    }

    // P4: Test position stays when tilt is held constant
    @Test
    fun testPositionStaysWithConstantTilt() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Set a tilt
        calculator.updateFromSensor(0.5f, 0f)
        repeat(50) { calculator.update(16) }
        val initialOffset = calculator.getOffsetX()
        
        // Continue with same tilt for more frames (no sensor change needed - same value)
        repeat(50) { calculator.update(16) }
        val laterOffset = calculator.getOffsetX()
        
        // Position should stay stable
        assertEquals("Position should remain stable when tilt is constant", 
            initialOffset, laterOffset, 1f)
    }

    // P5: Test returning to center when tilt goes to zero
    @Test
    fun testReturnsToZeroWhenTiltRemoved() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Move to offset
        calculator.updateFromSensor(1.0f, 0f)
        repeat(50) { calculator.update(16) }
        
        assertTrue("Should have moved away from center", calculator.getOffsetX() > 10f)
        
        // Remove tilt
        calculator.updateFromSensor(0f, 0f)
        repeat(100) { calculator.update(16) }
        
        // Should return to center
        assertTrue("Should return near center", kotlin.math.abs(calculator.getOffsetX()) < 5f)
    }

    // Test reset functionality
    @Test
    fun testResetClearsOffset() {
        calculator.updateFromSensor(1.0f, 1.0f)
        repeat(20) { calculator.update(16) }
        
        assertTrue("Should have offset before reset", calculator.getOffsetX() > 0f)
        
        calculator.reset()
        
        assertEquals(0f, calculator.getOffsetX(), 0.001f)
        assertEquals(0f, calculator.getOffsetY(), 0.001f)
    }

    // Test depth-based offset
    @Test
    fun testDepthBasedOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 100f
        calculator.depthSeparation = 1.0f  // Max separation for clear test
        
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        
        val baseOffset = calculator.getOffsetX()
        val halfDepthOffset = calculator.getOffsetXForDepth(0.5f)
        val fullDepthOffset = calculator.getOffsetXForDepth(1.0f)
        
        // With depthSeparation=1.0:
        // At depth 0.5: effectiveDepth = 0.5 * 1.0 = 0.5
        // At depth 1.0: effectiveDepth = 1.0 * 1.0 = 1.0
        assertEquals("Half depth should be half offset", 
            baseOffset * 0.5f, halfDepthOffset, 1f)
        assertEquals("Full depth should equal base offset", 
            baseOffset, fullDepthOffset, 1f)
    }

    // Test getOffsetForDepth returns correct pair
    @Test
    fun testGetOffsetForDepthReturnsPair() {
        calculator.depthSeparation = 1.0f  // Max separation for clear test
        
        calculator.updateFromSensor(0.5f, 0.8f)
        repeat(100) { calculator.update(16) }
        
        val offset = calculator.getOffsetForDepth(0.5f)
        
        // With depthSeparation=1.0: effectiveDepth = 0.5
        assertEquals(calculator.getOffsetX() * 0.5f, offset.first, 1f)
        assertEquals(calculator.getOffsetY() * 0.5f, offset.second, 1f)
    }

    // Test strength affects offset magnitude
    @Test
    fun testStrengthAffectsOffset() {
        calculator.maxOffsetPx = 40f
        
        // Low strength
        calculator.strength = 0.5f
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val lowStrengthOffset = calculator.getOffsetX()
        
        calculator.reset()
        
        // High strength (still clamped to maxOffset)
        calculator.strength = 1.5f
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val highStrengthOffset = calculator.getOffsetX()
        
        assertTrue("Higher strength should produce larger offset",
            highStrengthOffset > lowStrengthOffset)
    }

    // Test all four cardinal directions
    @Test
    fun testAllCardinalDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Right (positive X)
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        assertTrue("Right tilt should produce positive X offset", calculator.getOffsetX() > 30f)
        assertEquals("Right tilt should have minimal Y offset", 0f, calculator.getOffsetY(), 1f)
        
        calculator.reset()
        
        // Left (negative X)
        calculator.updateFromSensor(-1.0f, 0f)
        repeat(100) { calculator.update(16) }
        assertTrue("Left tilt should produce negative X offset", calculator.getOffsetX() < -30f)
        assertEquals("Left tilt should have minimal Y offset", 0f, calculator.getOffsetY(), 1f)
        
        calculator.reset()
        
        // Down (positive Y)
        calculator.updateFromSensor(0f, 1.0f)
        repeat(100) { calculator.update(16) }
        assertEquals("Down tilt should have minimal X offset", 0f, calculator.getOffsetX(), 1f)
        assertTrue("Down tilt should produce positive Y offset", calculator.getOffsetY() > 30f)
        
        calculator.reset()
        
        // Up (negative Y)
        calculator.updateFromSensor(0f, -1.0f)
        repeat(100) { calculator.update(16) }
        assertEquals("Up tilt should have minimal X offset", 0f, calculator.getOffsetX(), 1f)
        assertTrue("Up tilt should produce negative Y offset", calculator.getOffsetY() < -30f)
    }

    // Test diagonal directions
    @Test
    fun testDiagonalDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Top-right diagonal
        calculator.updateFromSensor(0.7f, -0.7f)
        repeat(100) { calculator.update(16) }
        assertTrue("Top-right should have positive X", calculator.getOffsetX() > 20f)
        assertTrue("Top-right should have negative Y", calculator.getOffsetY() < -20f)
        
        calculator.reset()
        
        // Bottom-left diagonal
        calculator.updateFromSensor(-0.7f, 0.7f)
        repeat(100) { calculator.update(16) }
        assertTrue("Bottom-left should have negative X", calculator.getOffsetX() < -20f)
        assertTrue("Bottom-left should have positive Y", calculator.getOffsetY() > 20f)
    }

    // Test smooth transition between directions
    @Test
    fun testSmoothTransitionBetweenDirections() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        calculator.smoothing = 0.4f  // Default smoothing
        
        // Start tilted right
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val rightOffset = calculator.getOffsetX()
        assertTrue("Should be at right", rightOffset > 30f)
        
        // Transition to left (tilt changes direction)
        calculator.updateFromSensor(-1.0f, 0f)
        
        // After just a few frames, should start moving left but not be there yet
        repeat(20) { calculator.update(16) }
        val transitioningOffset = calculator.getOffsetX()
        assertTrue("Should be transitioning (less than initial right)",
            transitioningOffset < rightOffset)
        
        // After more frames, should reach left
        repeat(100) { calculator.update(16) }
        val leftOffset = calculator.getOffsetX()
        assertTrue("Should reach left side", leftOffset < -30f)
    }

    // Test zero tilt returns to center
    @Test
    fun testZeroTiltReturnsToCenter() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Move to various positions, then return to center
        val testDirections = listOf(
            Pair(1.0f, 0f),    // Right
            Pair(-1.0f, 0f),   // Left
            Pair(0f, 1.0f),    // Down
            Pair(0f, -1.0f),   // Up
            Pair(0.7f, 0.7f),  // Bottom-right
            Pair(-0.7f, -0.7f) // Top-left
        )
        
        for (direction in testDirections) {
            calculator.updateFromSensor(direction.first, direction.second)
            repeat(50) { calculator.update(16) }
            
            // Should be away from center
            val offsetMagnitude = kotlin.math.sqrt(
                calculator.getOffsetX() * calculator.getOffsetX() + 
                calculator.getOffsetY() * calculator.getOffsetY()
            )
            assertTrue("Should have moved from center for ${direction}", offsetMagnitude > 10f)
            
            // Return to center
            calculator.updateFromSensor(0f, 0f)
            repeat(100) { calculator.update(16) }
            
            // Should be near center
            assertEquals("X should return to center", 0f, calculator.getOffsetX(), 2f)
            assertEquals("Y should return to center", 0f, calculator.getOffsetY(), 2f)
        }
    }

    // Test partial tilt produces proportional offset
    @Test
    fun testPartialTiltProducesProportionalOffset() {
        calculator.strength = 1.0f
        calculator.maxOffsetPx = 40f
        
        // Full tilt
        calculator.updateFromSensor(1.0f, 0f)
        repeat(100) { calculator.update(16) }
        val fullTiltOffset = calculator.getOffsetX()
        
        calculator.reset()
        
        // Half tilt
        calculator.updateFromSensor(0.5f, 0f)
        repeat(100) { calculator.update(16) }
        val halfTiltOffset = calculator.getOffsetX()
        
        // Half tilt should produce approximately half offset
        assertEquals("Half tilt should produce half offset",
            fullTiltOffset / 2f, halfTiltOffset, 3f)
    }
}
