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
        /** How quickly velocity input is integrated into position (reduced from 3 to 1.2) */
        private const val VELOCITY_SCALE = 1.2f
        
        /** How fast position drifts back to center when idle (per frame) */
        private const val CENTER_DRIFT_RATE = 0.015f
        
        /** Frames without input before starting center drift */
        private const val IDLE_FRAMES_BEFORE_DRIFT = 30
    }
    
    // Current parallax offset position (pixels)
    // @Volatile for thread safety: sensor thread writes, render thread reads
    @Volatile private var positionX = 0f
    @Volatile private var positionY = 0f
    
    // Smoothed velocity values (for smooth movement)
    @Volatile private var smoothedVelocityX = 0f
    @Volatile private var smoothedVelocityY = 0f
    
    // Accumulated velocity input this frame - protected by velocityLock
    private var pendingVelocityX = 0f
    private var pendingVelocityY = 0f
    private val velocityLock = Any()  // Synchronization lock for pending velocity
    
    // Previous raw sensor values for low-pass filtering
    private var lastRawVelocityX = 0f
    private var lastRawVelocityY = 0f
    
    /** Low-pass filter alpha for raw sensor input (0.7 = 70% new, 30% previous) */
    private val sensorFilterAlpha = 0.7f
    
    // Frame counter for idle detection
    @Volatile private var idleFrameCount = 0
    
    // User-configurable settings
    var strength = 1.0f                 // Sensitivity multiplier
    var maxOffsetPx = 80f               // Maximum offset in any direction (reduced from 100)
    var centerDriftRate = CENTER_DRIFT_RATE  // How fast to return to center
    var smoothing = 0.4f                // Velocity smoothing factor (0.4 = 40% filter)
    var depthSeparation = 0.25f         // How much depth affects movement (reduced from 0.5)
    
    /**
     * Update from sensor input.
     * Thread-safe: can be called from sensor callback thread.
     * Applies low-pass filter to raw sensor data to reduce jitter.
     * @param velocityX Change in X tilt (-1 to 1, where magnitude = speed)
     * @param velocityY Change in Y tilt (-1 to 1, where magnitude = speed)
     */
    fun updateFromSensor(velocityX: Float, velocityY: Float) {
        // Low-pass filter on raw sensor input
        val filteredX = velocityX * sensorFilterAlpha + lastRawVelocityX * (1f - sensorFilterAlpha)
        val filteredY = velocityY * sensorFilterAlpha + lastRawVelocityY * (1f - sensorFilterAlpha)
        lastRawVelocityX = filteredX
        lastRawVelocityY = filteredY
        
        // Synchronize to prevent race with update() reading pending values
        synchronized(velocityLock) {
            pendingVelocityX += filteredX
            pendingVelocityY += filteredY
        }
        
        // Reset idle counter if there's any movement
        if (filteredX != 0f || filteredY != 0f) {
            idleFrameCount = 0
        }
    }
    
    /**
     * Update parallax state each frame.
     * Call this every frame before rendering.
     */
    fun update(deltaTimeMs: Long = 16L) {
        val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps
        
        // Atomically read and clear pending velocity
        val currentPendingX: Float
        val currentPendingY: Float
        synchronized(velocityLock) {
            currentPendingX = pendingVelocityX
            currentPendingY = pendingVelocityY
            pendingVelocityX = 0f
            pendingVelocityY = 0f
        }
        
        // Smoothing: lerp smoothed velocity toward pending velocity
        // Higher smoothing = more filtering = smoother but slower response
        // Fixed formula: smoothing is the filter strength (0 = instant, 0.95 = very smooth)
        val filterStrength = smoothing.coerceIn(0f, 0.95f)
        smoothedVelocityX = smoothedVelocityX * filterStrength + currentPendingX * (1f - filterStrength)
        smoothedVelocityY = smoothedVelocityY * filterStrength + currentPendingY * (1f - filterStrength)
        
        // Apply smoothed velocity to position
        val velocityMagnitude = kotlin.math.sqrt(
            smoothedVelocityX * smoothedVelocityX + smoothedVelocityY * smoothedVelocityY
        )
        
        if (velocityMagnitude > 0.001f) {
            // Edge damping: reduce velocity as we approach the edges
            // This prevents jerky behavior at the boundaries
            val edgeDistX = 1f - (kotlin.math.abs(positionX) / maxOffsetPx).coerceIn(0f, 1f)
            val edgeDistY = 1f - (kotlin.math.abs(positionY) / maxOffsetPx).coerceIn(0f, 1f)
            // Use quadratic falloff for smoother edge approach
            val edgeDampX = edgeDistX * edgeDistX * 0.7f + 0.3f  // 30% minimum at edge
            val edgeDampY = edgeDistY * edgeDistY * 0.7f + 0.3f
            
            // Move position based on smoothed velocity with edge damping
            val dx = smoothedVelocityX * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale * edgeDampX
            val dy = smoothedVelocityY * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale * edgeDampY
            
            positionX += dx
            positionY += dy
            
            // Clamp to bounds
            positionX = positionX.coerceIn(-maxOffsetPx, maxOffsetPx)
            positionY = positionY.coerceIn(-maxOffsetPx, maxOffsetPx)
            
            // Fix boundary momentum: clear velocity when hitting edge in same direction
            // This allows instant reversal when tilting back from edge
            if (positionX == maxOffsetPx && smoothedVelocityX > 0f) {
                smoothedVelocityX = 0f
            }
            if (positionX == -maxOffsetPx && smoothedVelocityX < 0f) {
                smoothedVelocityX = 0f
            }
            if (positionY == maxOffsetPx && smoothedVelocityY > 0f) {
                smoothedVelocityY = 0f
            }
            if (positionY == -maxOffsetPx && smoothedVelocityY < 0f) {
                smoothedVelocityY = 0f
            }
        }
        
        // Increment idle counter
        idleFrameCount++
        
        // Drift back to center when idle - ALWAYS apply some recentering force
        // Even with minimal sensor input, gradually recenter to handle miscalibration
        val isIdle = idleFrameCount > IDLE_FRAMES_BEFORE_DRIFT
        val isLowInput = velocityMagnitude < 0.01f  // Nearly stationary input
        
        if (isIdle || isLowInput) {
            // Use stronger drift when truly idle
            val effectiveDriftRate = if (isIdle) centerDriftRate else centerDriftRate * 0.3f
            val driftAmount = effectiveDriftRate * deltaScale
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
     * - depthSeparation=0: all layers move the same amount (flat)
     * - depthSeparation=1: background barely moves, foreground moves full amount (max 3D)
     * 
     * Formula: effectiveDepth = depthFactor * depthSeparation
     * - Background (depthFactor=0) moves 0%
     * - Foreground (depthFactor=1) moves depthSeparation * 100%
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        // Simplified depth formula: foreground moves more, background moves less
        val effectiveDepth = depthFactor * depthSeparation
        return Pair(positionX * effectiveDepth, positionY * effectiveDepth)
    }
    
    /**
     * Get X offset for a specific depth.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float {
        val effectiveDepth = depthFactor * depthSeparation
        return positionX * effectiveDepth
    }
    
    /**
     * Get Y offset for a specific depth.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float {
        val effectiveDepth = depthFactor * depthSeparation
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
        lastRawVelocityX = 0f
        lastRawVelocityY = 0f
        synchronized(velocityLock) {
            pendingVelocityX = 0f
            pendingVelocityY = 0f
        }
        idleFrameCount = 0
    }
}
