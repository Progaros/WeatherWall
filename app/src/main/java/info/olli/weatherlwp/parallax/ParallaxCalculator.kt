package info.olli.weatherlwp.parallax

import java.util.concurrent.atomic.AtomicReference

/**
 * Calculates parallax offset values using a position-based (tilt-following) system.
 * 
 * Key behavior:
 * - Scene offset follows the device tilt angle
 * - Holding the phone at an angle keeps the scene offset stable
 * - Smooth interpolation (lerp) toward target for natural feel
 * - Optional return-to-center when tilt is near zero (controlled by returnSpeed)
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
        private const val POSITION_LERP_RATE = 0.5f
        
        /** Tilt magnitude below which return-to-center kicks in */
        private const val RETURN_TILT_THRESHOLD = 0.05f
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
    
    // User-configurable settings - defaults match pref_effects.xml
    // These are overwritten by loadPrefs() but provide sane defaults before settings load
    var strength = 2.5f                 // Default: (25/50)^2 * 10 = 2.5 (pref_parallax_strength=25)
    var maxOffsetPx = 130f              // Default: pref_parallax_max_offset=130
    var smoothing = 0.134f              // Default: 0.02 + (30/100)*0.38 (pref_parallax_smoothing=30)
    var depthSeparation = 1.32f         // Default: (33/100)*4.0 = 1.32 (pref_parallax_depth=33)
    
    /**
     * Reference height for scaling parallax offset.
     * Parallax offset is scaled relative to this height, so smaller canvases
     * (like preview) get proportionally smaller offsets.
     */
    private val referenceHeight = 1920f
    
    /**
     * Current canvas height for scaling. Set via setCanvasSize().
     */
    private var canvasHeight = referenceHeight
    
    /**
     * Scale factor computed from canvas height / reference height.
     * Ensures parallax looks the same proportionally regardless of canvas size.
     */
    private val scaleFactor: Float
        get() = (canvasHeight / referenceHeight).coerceIn(0.1f, 2f)
    
    /**
     * Update canvas dimensions for scaling parallax offset.
     * Call this when canvas size changes.
     */
    fun setCanvasSize(width: Float, height: Float) {
        canvasHeight = height.coerceAtLeast(1f)
    }
    
    /**
     * Return speed: how fast position drifts back to center when tilt is zero.
     * 0.0 = instant return, 0.5 = medium, 1.0 = never return (disabled)
     * Maps from pref setting: 1=instant (0.0), 50=medium (0.5), 100=slow (1.0)
     */
    var returnSpeed = 0.09f             // Default: (10-1)/99 = 0.09 (pref_parallax_return_speed=10)
    
    /**
     * Parallax perspective: controls which layers move more.
     * 0.0 = foreground moves most (head moves, window stays)
     * 1.0 = background moves most (window moves, head stays - default)
     * This keeps the 3D "through a window" effect but shifts which plane feels anchored.
     */
    var perspective = 1.0f              // Default: pref_parallax_perspective=100
    
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
        val tiltMagnitude = kotlin.math.sqrt(tilt.x * tilt.x + tilt.y * tilt.y)
        
        // Calculate effective max offset scaled to canvas size
        // This ensures smaller canvases (preview) have proportionally smaller offsets
        val effectiveMaxOffset = maxOffsetPx * scaleFactor
        
        // Calculate target position from tilt
        // Tilt values are -1 to 1, map to pixel offset
        targetX = tilt.x * effectiveMaxOffset * strength
        targetY = tilt.y * effectiveMaxOffset * strength
        
        // Clamp target to bounds (use scaled offset for consistent limits)
        targetX = targetX.coerceIn(-effectiveMaxOffset, effectiveMaxOffset)
        targetY = targetY.coerceIn(-effectiveMaxOffset, effectiveMaxOffset)
        
        // Apply return-to-center when tilt is near zero
        // returnSpeed: 0.0 = instant return, 1.0 = never return
        if (tiltMagnitude < RETURN_TILT_THRESHOLD && returnSpeed < 0.99f) {
            // Gradually pull target toward center
            // Lower returnSpeed = faster pull
            val returnRate = 1f - returnSpeed  // 0-1 where 1 is fastest
            val returnLerp = (returnRate * 0.3f * deltaScale).coerceAtMost(1f)
            targetX *= (1f - returnLerp)
            targetY *= (1f - returnLerp)
        }
        
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
