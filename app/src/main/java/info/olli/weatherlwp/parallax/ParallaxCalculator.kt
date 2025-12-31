package info.olli.weatherlwp.parallax

import java.util.concurrent.atomic.AtomicReference

/**
 * Calculates parallax offset values using a position-based (tilt-following) system.
 * 
 * Key behavior:
 * - Scene offset follows the device tilt angle
 * - Holding the phone at an angle keeps the scene offset stable
 * - Smooth interpolation (lerp) toward target for natural feel
 * - No drift back to center - offset stays where the tilt puts it
 * 
 * Performance optimized:
 * - Uses AtomicReference for lock-free thread safety
 * - Minimizes allocations in update loop
 * 
 * Usage:
 * 1. Call updateFromSensor(tiltX, tiltY) when sensor data arrives
 * 2. Call update() each frame to smoothly interpolate toward target
 * 3. Use getOffsetForDepth(depth) to get parallax for a specific layer
 */
class ParallaxCalculator {
    companion object {
        /** How fast position approaches target (per frame, 0-1) */
        private const val POSITION_LERP_RATE = 0.08f
    }
    
    // Tilt holder for atomic operations (lock-free)
    private data class TiltPair(val x: Float, val y: Float)
    private val pendingTilt = AtomicReference(TiltPair(0f, 0f))
    
    // Current parallax offset position (pixels) - only modified on render thread
    private var positionX = 0f
    private var positionY = 0f
    
    // Target position based on sensor tilt
    private var targetX = 0f
    private var targetY = 0f
    
    // User-configurable settings
    var strength = 1.0f                 // Sensitivity multiplier
    var maxOffsetPx = 80f               // Maximum offset in any direction
    var smoothing = 0.4f                // Position smoothing factor (higher = smoother but slower)
    var depthSeparation = 0.25f         // How much depth affects movement
    
    /**
     * Update from sensor input.
     * Thread-safe: can be called from sensor callback thread.
     * Uses lock-free atomic operations for minimal overhead.
     * @param tiltX Current tilt in X axis (-1 to 1, absolute position)
     * @param tiltY Current tilt in Y axis (-1 to 1, absolute position)
     */
    fun updateFromSensor(tiltX: Float, tiltY: Float) {
        pendingTilt.set(TiltPair(tiltX, tiltY))
    }
    
    /**
     * Update parallax state each frame.
     * Call this every frame before rendering.
     */
    fun update(deltaTimeMs: Long = 16L) {
        val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps
        
        // Get latest tilt from sensor
        val tilt = pendingTilt.get()
        
        // Calculate target position from tilt
        // Tilt values are -1 to 1, map to pixel offset
        targetX = tilt.x * maxOffsetPx * strength
        targetY = tilt.y * maxOffsetPx * strength
        
        // Clamp target to bounds
        targetX = targetX.coerceIn(-maxOffsetPx, maxOffsetPx)
        targetY = targetY.coerceIn(-maxOffsetPx, maxOffsetPx)
        
        // Calculate effective lerp rate based on smoothing setting
        // Higher smoothing = slower response (smaller lerp rate)
        val effectiveLerpRate = POSITION_LERP_RATE * (1f - smoothing * 0.8f) * deltaScale
        
        // Smoothly interpolate position toward target
        positionX += (targetX - positionX) * effectiveLerpRate
        positionY += (targetY - positionY) * effectiveLerpRate
        
        // Snap to target when very close (prevents tiny oscillations)
        if (kotlin.math.abs(targetX - positionX) < 0.1f) positionX = targetX
        if (kotlin.math.abs(targetY - positionY) < 0.1f) positionY = targetY
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
        targetX = 0f
        targetY = 0f
        pendingTilt.set(TiltPair(0f, 0f))
    }
}
