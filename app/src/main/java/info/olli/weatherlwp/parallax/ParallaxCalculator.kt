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
        /** How fast position approaches target (per frame, 0-1) - increased for faster response */
        private const val POSITION_LERP_RATE = 0.35f
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
    var depthSeparation = 1.0f          // How much depth affects movement (1.0 = full effect)
    
    /**
     * Parallax perspective: controls which layers move more.
     * 0.0 = foreground moves most (head moves, window stays - current default)
     * 1.0 = background moves most (window moves, head stays)
     * This keeps the 3D "through a window" effect but shifts which plane feels anchored.
     */
    var perspective = 0.0f
    
    /**
     * Calculate movement multiplier for a given depth, accounting for perspective.
     * Blends between foreground-heavy (depth) and background-heavy (1-depth) based on perspective.
     */
    private fun movementMultiplier(depthFactor: Float): Float {
        val d = depthFactor.coerceIn(0f, 1f)
        val foregroundHeavy = d          // Model A: foreground moves most
        val backgroundHeavy = 1f - d      // Model B: background moves most
        val mixed = foregroundHeavy + (backgroundHeavy - foregroundHeavy) * perspective
        return mixed * depthSeparation
    }
    
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
        // At smoothing=0: full lerp rate for instant response
        // At smoothing=1: reduced lerp rate for smooth motion
        // Clamp to max 1.0 to prevent overshoot after stalls (e.g., GC, doze)
        val effectiveLerpRate = (POSITION_LERP_RATE * (1f - smoothing * 0.6f) * deltaScale).coerceAtMost(1f)
        
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
     * Uses perspective setting to control foreground vs background emphasis.
     * 
     * @param depthFactor Layer depth: 0.0 = background, 1.0 = foreground
     * @return (x, y) offset to apply to this layer
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        val mult = movementMultiplier(depthFactor)
        return Pair(positionX * mult, positionY * mult)
    }
    
    /**
     * Get X offset for a specific depth.
     * Uses perspective setting for "window" vs "head" movement feel.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float {
        return positionX * movementMultiplier(depthFactor)
    }
    
    /**
     * Get Y offset for a specific depth.
     * Uses perspective setting for "window" vs "head" movement feel.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float {
        return positionY * movementMultiplier(depthFactor)
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
