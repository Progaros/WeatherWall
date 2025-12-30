package info.olli.weatherlwp.parallax

import java.util.concurrent.atomic.AtomicReference

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
 * Performance optimized:
 * - Uses AtomicReference for lock-free thread safety
 * - Minimizes allocations in update loop
 * 
 * Usage:
 * 1. Call updateFromSensor(velocityX, velocityY) when sensor data arrives
 * 2. Call update() each frame to apply position updates and center drift
 * 3. Use getOffsetForDepth(depth) to get parallax for a specific layer
 */
class ParallaxCalculator {
    companion object {
        /** How quickly velocity input is integrated into position */
        private const val VELOCITY_SCALE = 1.2f
        
        /** How fast position drifts back to center when idle (per frame) */
        private const val CENTER_DRIFT_RATE = 0.015f
        
        /** Frames without input before starting center drift */
        private const val IDLE_FRAMES_BEFORE_DRIFT = 30
    }
    
    // Velocity holder for atomic operations (lock-free)
    private data class VelocityPair(val x: Float, val y: Float)
    private val pendingVelocity = AtomicReference(VelocityPair(0f, 0f))
    
    // Current parallax offset position (pixels) - only modified on render thread
    private var positionX = 0f
    private var positionY = 0f
    
    // Smoothed velocity values (for smooth movement)
    private var smoothedVelocityX = 0f
    private var smoothedVelocityY = 0f
    
    // Previous raw sensor values for low-pass filtering
    private var lastRawVelocityX = 0f
    private var lastRawVelocityY = 0f
    
    /** Low-pass filter alpha for raw sensor input (0.7 = 70% new, 30% previous) */
    private val sensorFilterAlpha = 0.7f
    
    // Frame counter for idle detection
    private var idleFrameCount = 0
    
    // User-configurable settings
    var strength = 1.0f                 // Sensitivity multiplier
    var maxOffsetPx = 80f               // Maximum offset in any direction
    var centerDriftRate = CENTER_DRIFT_RATE  // How fast to return to center
    var smoothing = 0.4f                // Velocity smoothing factor (0.4 = 40% filter)
    var depthSeparation = 0.25f         // How much depth affects movement
    
    /**
     * Update from sensor input.
     * Thread-safe: can be called from sensor callback thread.
     * Uses lock-free atomic operations for minimal overhead.
     * @param velocityX Change in X tilt (-1 to 1, where magnitude = speed)
     * @param velocityY Change in Y tilt (-1 to 1, where magnitude = speed)
     */
    fun updateFromSensor(velocityX: Float, velocityY: Float) {
        // Low-pass filter on raw sensor input
        val filteredX = velocityX * sensorFilterAlpha + lastRawVelocityX * (1f - sensorFilterAlpha)
        val filteredY = velocityY * sensorFilterAlpha + lastRawVelocityY * (1f - sensorFilterAlpha)
        lastRawVelocityX = filteredX
        lastRawVelocityY = filteredY
        
        // Lock-free accumulation using CAS loop
        while (true) {
            val current = pendingVelocity.get()
            val updated = VelocityPair(current.x + filteredX, current.y + filteredY)
            if (pendingVelocity.compareAndSet(current, updated)) break
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
        
        // Atomically read and clear pending velocity (lock-free)
        val pending = pendingVelocity.getAndSet(VelocityPair(0f, 0f))
        val currentPendingX = pending.x
        val currentPendingY = pending.y
        
        // Smoothing: lerp smoothed velocity toward pending velocity
        val filterStrength = smoothing.coerceIn(0f, 0.95f)
        smoothedVelocityX = smoothedVelocityX * filterStrength + currentPendingX * (1f - filterStrength)
        smoothedVelocityY = smoothedVelocityY * filterStrength + currentPendingY * (1f - filterStrength)
        
        // Fast magnitude check (avoid sqrt when possible)
        val velocityMagSq = smoothedVelocityX * smoothedVelocityX + smoothedVelocityY * smoothedVelocityY
        
        if (velocityMagSq > 0.000001f) {
            // Edge damping: reduce velocity as we approach the edges
            val edgeDistX = 1f - kotlin.math.abs(positionX) / maxOffsetPx
            val edgeDistY = 1f - kotlin.math.abs(positionY) / maxOffsetPx
            // Use quadratic falloff for smoother edge approach
            val edgeDampX = edgeDistX * edgeDistX * 0.7f + 0.3f  // 30% minimum at edge
            val edgeDampY = edgeDistY * edgeDistY * 0.7f + 0.3f
            
            // Move position based on smoothed velocity with edge damping
            val scale = VELOCITY_SCALE * strength * maxOffsetPx * deltaScale
            positionX += smoothedVelocityX * scale * edgeDampX
            positionY += smoothedVelocityY * scale * edgeDampY
            
            // Clamp to bounds
            if (positionX > maxOffsetPx) positionX = maxOffsetPx
            else if (positionX < -maxOffsetPx) positionX = -maxOffsetPx
            if (positionY > maxOffsetPx) positionY = maxOffsetPx
            else if (positionY < -maxOffsetPx) positionY = -maxOffsetPx
            
            // Fix boundary momentum: clear velocity when hitting edge in same direction
            if (positionX == maxOffsetPx && smoothedVelocityX > 0f) smoothedVelocityX = 0f
            if (positionX == -maxOffsetPx && smoothedVelocityX < 0f) smoothedVelocityX = 0f
            if (positionY == maxOffsetPx && smoothedVelocityY > 0f) smoothedVelocityY = 0f
            if (positionY == -maxOffsetPx && smoothedVelocityY < 0f) smoothedVelocityY = 0f
        }
        
        // Increment idle counter
        idleFrameCount++
        
        // Drift back to center when idle
        val isIdle = idleFrameCount > IDLE_FRAMES_BEFORE_DRIFT
        val isLowInput = velocityMagSq < 0.0001f  // Nearly stationary input
        
        if (isIdle || isLowInput) {
            val effectiveDriftRate = if (isIdle) centerDriftRate else centerDriftRate * 0.3f
            val driftMult = 1f - effectiveDriftRate * deltaScale
            positionX *= driftMult
            positionY *= driftMult
            
            // Snap to zero when very close
            if (positionX > -0.5f && positionX < 0.5f) positionX = 0f
            if (positionY > -0.5f && positionY < 0.5f) positionY = 0f
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
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        val effectiveDepth = depthFactor * depthSeparation
        return Pair(positionX * effectiveDepth, positionY * effectiveDepth)
    }
    
    /**
     * Get X offset for a specific depth.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float {
        return positionX * depthFactor * depthSeparation
    }
    
    /**
     * Get Y offset for a specific depth.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float {
        return positionY * depthFactor * depthSeparation
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
        pendingVelocity.set(VelocityPair(0f, 0f))
        idleFrameCount = 0
    }
}
