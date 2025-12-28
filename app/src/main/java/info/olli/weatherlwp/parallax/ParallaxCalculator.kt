package info.olli.weatherlwp.parallax

/**
 * Calculates parallax offset values using a velocity-based system.
 * 
 * Key behavior:
 * - Scene starts at center (0,0)
 * - Sensor provides VELOCITY (change), which moves the scene position
 * - Position is clamped to maxOffsetPx in any direction
 * - When at edge and moving further in same direction: no movement
 * - When at edge and moving opposite direction: immediate response
 * - Gradual drift back to center when no movement detected
 * 
 * Usage:
 * 1. Call updateFromSensor(velocityX, velocityY) when sensor data arrives
 * 2. Call update() each frame to apply position updates and center drift
 * 3. Use getOffsetForDepth(depth) to get parallax for a specific layer
 */
class ParallaxCalculator {
    companion object {
        /** How quickly velocity input is integrated into position */
        private const val VELOCITY_SCALE = 3f
        
        /** How fast position drifts back to center when idle (per frame) */
        private const val CENTER_DRIFT_RATE = 0.02f
        
        /** Frames without input before starting center drift */
        private const val IDLE_FRAMES_BEFORE_DRIFT = 30
    }
    
    // Current parallax offset position (pixels)
    private var positionX = 0f
    private var positionY = 0f
    
    // Smoothed velocity values (for smooth movement)
    private var smoothedVelocityX = 0f
    private var smoothedVelocityY = 0f
    
    // Accumulated velocity input this frame
    private var pendingVelocityX = 0f
    private var pendingVelocityY = 0f
    
    // Frame counter for idle detection
    private var idleFrameCount = 0
    
    // User-configurable settings
    var strength = 1.0f                 // Sensitivity multiplier
    var maxOffsetPx = 100f              // Maximum offset in any direction
    var centerDriftRate = CENTER_DRIFT_RATE  // How fast to return to center
    var smoothing = 0.3f                // Velocity smoothing factor (0=instant, 1=very smooth)
    var depthSeparation = 0.5f          // How much depth affects movement (0=all same, 1=max difference)
    
    /**
     * Update from sensor input.
     * @param velocityX Change in X tilt (-1 to 1, where magnitude = speed)
     * @param velocityY Change in Y tilt (-1 to 1, where magnitude = speed)
     */
    fun updateFromSensor(velocityX: Float, velocityY: Float) {
        // Accumulate velocity (will be applied in update())
        pendingVelocityX += velocityX
        pendingVelocityY += velocityY
        
        // Reset idle counter if there's any movement
        if (velocityX != 0f || velocityY != 0f) {
            idleFrameCount = 0
        }
    }
    
    /**
     * Update parallax state each frame.
     * Call this every frame before rendering.
     */
    fun update(deltaTimeMs: Long = 16L) {
        val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps
        
        // Smoothing: lerp raw velocity to smoothed velocity
        // Higher smoothing = slower response, smoother movement
        val smoothFactor = 1f - smoothing.coerceIn(0f, 0.99f)  // 0.01 to 1.0
        smoothedVelocityX = smoothedVelocityX + (pendingVelocityX - smoothedVelocityX) * smoothFactor
        smoothedVelocityY = smoothedVelocityY + (pendingVelocityY - smoothedVelocityY) * smoothFactor
        
        // Apply smoothed velocity to position
        val velocityMagnitude = kotlin.math.sqrt(
            smoothedVelocityX * smoothedVelocityX + smoothedVelocityY * smoothedVelocityY
        )
        
        if (velocityMagnitude > 0.001f) {
            // Move position based on smoothed velocity
            val dx = smoothedVelocityX * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale
            val dy = smoothedVelocityY * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale
            positionX += dx
            positionY += dy
            
            // Clamp to bounds
            positionX = positionX.coerceIn(-maxOffsetPx, maxOffsetPx)
            positionY = positionY.coerceIn(-maxOffsetPx, maxOffsetPx)
        }
        
        // Clear pending velocity for next frame (but keep smoothed for decay)
        pendingVelocityX = 0f
        pendingVelocityY = 0f
        
        // Increment idle counter
        idleFrameCount++
        
        // Drift back to center when idle
        if (idleFrameCount > IDLE_FRAMES_BEFORE_DRIFT) {
            val driftAmount = centerDriftRate * deltaScale
            positionX *= (1f - driftAmount)
            positionY *= (1f - driftAmount)
            
            // Snap to zero when very close
            if (kotlin.math.abs(positionX) < 0.5f) positionX = 0f
            if (kotlin.math.abs(positionY) < 0.5f) positionY = 0f
        }
    }
    
    /**
     * Get the current parallax offset as (x, y) pair.
     */
    fun getOffset(): Pair<Float, Float> = Pair(positionX, positionY)
    
    /**
     * Get the current X offset.
     */
    fun getOffsetX(): Float = positionX
    
    /**
     * Get the current Y offset.
     */
    fun getOffsetY(): Float = positionY
    
    /**
     * Get parallax offset for a specific layer depth.
     * 
     * @param depthFactor Layer depth: 0.0 = background (moves least), 1.0 = foreground (moves most)
     * @return (x, y) offset to apply to this layer
     * 
     * Depth separation controls how much the layers differ:
     * - depthSeparation=0: all layers move the same amount
     * - depthSeparation=1: background barely moves, foreground moves full amount
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        // Apply depth separation: mix between uniform movement (all same) and depth-based
        // minScale = 1 - depthSeparation (how much background moves)
        val minScale = 1f - depthSeparation
        val effectiveDepth = minScale + depthFactor * depthSeparation
        return Pair(positionX * effectiveDepth, positionY * effectiveDepth)
    }
    
    /**
     * Get X offset for a specific depth.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float {
        val minScale = 1f - depthSeparation
        val effectiveDepth = minScale + depthFactor * depthSeparation
        return positionX * effectiveDepth
    }
    
    /**
     * Get Y offset for a specific depth.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float {
        val minScale = 1f - depthSeparation
        val effectiveDepth = minScale + depthFactor * depthSeparation
        return positionY * effectiveDepth
    }
    
    /**
     * Reset parallax to center immediately.
     */
    fun reset() {
        positionX = 0f
        positionY = 0f
        smoothedVelocityX = 0f
        smoothedVelocityY = 0f
        pendingVelocityX = 0f
        pendingVelocityY = 0f
        idleFrameCount = 0
    }
}
