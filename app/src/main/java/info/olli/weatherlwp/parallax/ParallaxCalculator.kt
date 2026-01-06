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
    var strength = 3.0f                 // Default: pref_parallax_strength=3
    var maxOffsetPx = 130f              // Legacy: used when maxOffsetPercent is 0
    var maxOffsetPercent = 15f          // Default: 15% of screen height (increased for more movement)
    var smoothing = 0.134f              // Default: 0.02 + (30/100)*0.38 (pref_parallax_smoothing=30)
    var depthSeparation = 1.32f         // Default: (33/100)*4.0 = 1.32 (pref_parallax_depth=33)
    
    // Axis inversion settings - for correcting inverted parallax directions
    var invertX = false                 // Default: pref_parallax_invert_x=false
    var invertY = false                 // Default: pref_parallax_invert_y=false
    
    // Aspect ratio compensation: adjusts horizontal movement relative to vertical
    // 1.0 = same movement for both axes (default with height-based percentage)
    // >1.0 = more horizontal movement, <1.0 = less horizontal movement
    var aspectRatioCompensation = 1.0f  // Default: pref_parallax_aspect_ratio=100 (100/100=1.0)
    
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
    @Suppress("UNUSED_PARAMETER")
    fun setCanvasSize(width: Float, height: Float) {
        // width kept for API consistency, height used for scaling
        canvasHeight = height.coerceAtLeast(1f)
    }
    
    /**
     * Return speed: how fast position drifts back to center when tilt is zero.
     * 0.0 = no return (scene stays where it is)
     * 0.5 = slow constant return
     * 1.0 = fast constant return
     * Uses logarithmic scale for natural feel.
     * Maps from pref setting: 0=no return, 1-99=slow to fast, 100=instant
     */
    var returnSpeed = 0.15f             // Default: slow return (pref_parallax_return_speed=15)
    
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
        // Apply axis inversion if enabled
        val adjustedX = if (invertX) -tiltX else tiltX
        val adjustedY = if (invertY) -tiltY else tiltY
        pendingTilt.set(TiltPair(adjustedX, adjustedY))
    }
    
    /**
     * Update parallax state each frame.
     * Call this every frame before rendering.
     * 
     * Return-to-center behavior:
     * - returnSpeed controls constant movement toward center (0 = no return, 1 = fast return)
     * - Movement is proportional to distance from center (slower when close)
     * - When tilt is active, position follows tilt; when tilt is released, position drifts to center
     */
    fun update(deltaTimeMs: Long = 16L) {
        val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps
        
        // Get latest tilt from sensor
        val tilt = pendingTilt.get()
        val tiltMagnitude = kotlin.math.sqrt(tilt.x * tilt.x + tilt.y * tilt.y)
        
        // Calculate effective max offset based on canvas height percentage
        // This ensures movement is proportional to screen size (e.g., 10% = 192px on 1920p screen)
        val effectiveMaxOffset = if (maxOffsetPercent > 0) {
            canvasHeight * (maxOffsetPercent / 100f)
        } else {
            // Fallback to legacy pixel-based offset if percentage not set
            maxOffsetPx * scaleFactor
        }
        
        // Calculate target position from tilt (where tilt alone would put us)
        val tiltTargetX = tilt.x * effectiveMaxOffset * strength * aspectRatioCompensation
        val tiltTargetY = tilt.y * effectiveMaxOffset * strength
        
        // Clamp to circular bounds instead of square (prevents corner stretching)
        // This gives consistent maximum movement in all directions
        val xLimit = effectiveMaxOffset * aspectRatioCompensation
        val yLimit = effectiveMaxOffset
        
        // Normalize to unit circle bounds then scale back
        val targetMagnitude = kotlin.math.sqrt(
            (tiltTargetX / xLimit) * (tiltTargetX / xLimit) + 
            (tiltTargetY / yLimit) * (tiltTargetY / yLimit)
        )
        
        if (targetMagnitude > 1f) {
            // Scale down proportionally to fit within circle
            val scale = 1f / targetMagnitude
            targetX = tiltTargetX * scale
            targetY = tiltTargetY * scale
        } else {
            targetX = tiltTargetX
            targetY = tiltTargetY
        }
        
        // Apply return-to-center force when tilt is near zero
        // This creates a smooth drift toward center, controlled by returnSpeed
        // returnSpeed: 0.0 = no return (position holds), 1.0 = fast smooth return
        var returnLerpBoost = 1f  // Multiplier for lerp rate during return
        if (tiltMagnitude < RETURN_TILT_THRESHOLD && returnSpeed > 0.001f) {
            // Set target to center
            targetX = 0f
            targetY = 0f
            
            // Boost the lerp rate based on returnSpeed for faster but still smooth return
            // returnSpeed=0.15 (default): boost=1.5x → ~1.5s to center
            // returnSpeed=0.50: boost=4x → ~0.4s to center
            // returnSpeed=1.00: boost=8x → ~0.2s to center (still smooth)
            returnLerpBoost = 1f + returnSpeed * 7f
        } else if (tiltMagnitude < RETURN_TILT_THRESHOLD && returnSpeed <= 0.001f) {
            // returnSpeed = 0: Hold current position (no return to center)
            targetX = positionX
            targetY = positionY
        }
        
        // Calculate effective lerp rate based on smoothing setting
        // Higher smoothing = slower response (smaller lerp rate)
        // Apply returnLerpBoost when returning to center
        val baseLerpRate = POSITION_LERP_RATE * (1f - smoothing * 0.6f) * deltaScale
        val effectiveLerpRate = (baseLerpRate * returnLerpBoost).coerceAtMost(0.9f)  // Cap at 0.9 for smooth animation
        
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
