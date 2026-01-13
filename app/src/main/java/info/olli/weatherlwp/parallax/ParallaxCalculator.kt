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
        /** How fast position approaches target (per frame, 0-1)
         * Reference app uses direct values (no lerping) with tan() transformation
         * For compatibility with smoothing slider, we lerp but support instant mode
         */
        private const val POSITION_LERP_RATE = 0.9f
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
    var strength = 0.075f               // Default: 25% on log scale → ~0.075
    var maxOffsetPx = 130f              // Legacy: used when maxOffsetPercent is 0
    var maxOffsetPercent = 20f          // Default: 20% of screen height (increased for more movement)
    var smoothing = 0f                  // Default: 0 = direct/instant (reference app behavior)
    var depthSeparation = 1.32f         // Default: (33/100)*4.0 = 1.32 (pref_parallax_depth=33)
    
    // Axis inversion settings - for correcting inverted parallax directions
    var invertX = false                 // Default: pref_parallax_invert_x=false
    var invertY = false                 // Default: pref_parallax_invert_y=false
    
    // Aspect ratio compensation: adjusts horizontal movement relative to vertical
    // 1.0 = same movement for both axes (default with height-based percentage)
    // >1.0 = more horizontal movement, <1.0 = less horizontal movement
    var aspectRatioCompensation = 1.0f  // Default: pref_parallax_aspect_ratio=100 (100/100=1.0)
    
    /**
     * Edge easing curve: controls how movement slows near edges.
     * 0.0 = linear (no easing, hard edges)
     * 0.5 = moderate easing (smooth deceleration near edges)
     * 1.0 = extreme easing (only moves freely in center, steep slowdown near edges)
     * Uses quadratic falloff: movement multiplier = 1 - (distance^(1+edgeEasing*3))
     */
    var edgeEasing = 0.0f               // Default: 0 = linear (no easing)
    
    /**
     * Return speed unlocked: when true, return to center uses full speed regardless of position.
     * When false, return speed is also affected by edge easing (slower near edges).
     * Default true = return to center is always responsive.
     */
    var returnSpeedUnlocked = true      // Default: true = fast return to center
    
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
     * Return speed: controls how fast the scene re-centers when the device is flat.
     * 0.0 = no return (holds position when tilt is released)
     * 0.5 = normal return speed
     * 1.0 = instant return
     * Only applies when tilt magnitude is low (near zero).
     */
    var returnSpeed = 0.15f             // Default: moderate pull (pref_parallax_return_speed=15)
    
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
     * - returnSpeed applies a continuous force pulling position toward center
     * - At 0: position fully follows tilt (no return)
     * - At 1: strong dampening, scene resists tilt and drifts toward center
     * - Works at all tilt angles, not just when phone is flat
     * 
     * Edge easing behavior:
     * - Movement slows down when approaching edges (away from center)
     * - Moving toward center is always at normal speed
     * - edgeEasing=0: linear movement (no slowdown)
     * - edgeEasing=1: extreme slowdown near edges, only center moves freely
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
        
        // Determine interpolation speed based on state
        // If tilt is low (device flat), we use "Return Speed" to control recentering
        val isReturning = tiltMagnitude < 0.1f
        
        // Calculate edge easing multiplier based on current position
        // Only applies when moving AWAY from center (not when returning)
        val edgeEasingMultiplier = if (edgeEasing > 0.01f && !isReturning) {
            calculateEdgeEasingMultiplier(xLimit, yLimit)
        } else {
            1f
        }
        
        val effectiveLerpRate = if (isReturning) {
            if (returnSpeed <= 0.001f) {
                // returnSpeed=0: Hold position (freeze) when flat
                0f
            } else {
                // Use returnSpeed for lerp rate (0.001 to 0.5)
                // returnSpeed=1.0 -> 0.5 (Very fast)
                // returnSpeed=0.1 -> 0.05 (Slow)
                val baseLerp = (returnSpeed * 0.5f * deltaScale).coerceAtMost(0.9f)
                // Apply edge easing to return speed only if not unlocked
                if (returnSpeedUnlocked) baseLerp else baseLerp * edgeEasingMultiplier
            }
        } else {
            // Smoothing=0 means instant/direct response (matches reference app)
            // Smoothing>0 adds progressive lerp-based smoothing
            val baseLerp = if (smoothing <= 0.01f) {
                // Direct mode: instant response, no lerping (reference app behavior)
                1.0f
            } else {
                // Standard smoothing when active (tilting)
                val baseLerpRate = POSITION_LERP_RATE * (1f - smoothing * 0.6f) * deltaScale
                baseLerpRate.coerceAtMost(0.95f)
            }
            // Apply edge easing - only slow down when moving away from center
            val movingAwayFromCenter = isMovingAwayFromCenter(targetX, targetY)
            if (movingAwayFromCenter) baseLerp * edgeEasingMultiplier else baseLerp
        }
        
        // Apply interpolation
        if (effectiveLerpRate >= 1.0f) {
            // Direct/instant mode - no lerping
            positionX = targetX
            positionY = targetY
        } else if (effectiveLerpRate > 0f) {
            positionX += (targetX - positionX) * effectiveLerpRate
            positionY += (targetY - positionY) * effectiveLerpRate
        }
        
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
    
    /**
     * Calculate edge easing multiplier based on current position.
     * Returns a value between 0 and 1 that slows movement near edges.
     * 
     * Uses power curve: multiplier = (1 - normalizedDistance)^exponent
     * where exponent = 1 + edgeEasing * 3 (range 1-4)
     * 
     * At edgeEasing=0: exponent=1, linear (no easing)
     * At edgeEasing=0.5: exponent=2.5, moderate easing
     * At edgeEasing=1: exponent=4, extreme easing (steep slowdown near edges)
     */
    private fun calculateEdgeEasingMultiplier(xLimit: Float, yLimit: Float): Float {
        if (edgeEasing <= 0.01f) return 1f
        
        // Calculate normalized distance from center (0=center, 1=edge)
        val normalizedX = if (xLimit > 0) kotlin.math.abs(positionX) / xLimit else 0f
        val normalizedY = if (yLimit > 0) kotlin.math.abs(positionY) / yLimit else 0f
        val normalizedDistance = kotlin.math.sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
            .coerceIn(0f, 1f)
        
        // Apply power curve: near center (0) = full speed, near edge (1) = slow
        // exponent ranges from 1 (linear) to 4 (very steep at edges)
        val exponent = 1f + edgeEasing * 3f
        val base = (1f - normalizedDistance).toDouble()
        val multiplier = Math.pow(base, exponent.toDouble()).toFloat()
        
        // Clamp to reasonable minimum to prevent complete freeze
        return multiplier.coerceIn(0.05f, 1f)
    }
    
    /**
     * Check if the parallax is moving away from center (position magnitude increasing).
     * Movement toward center should not be slowed by edge easing.
     */
    private fun isMovingAwayFromCenter(newTargetX: Float, newTargetY: Float): Boolean {
        val currentMagnitude = kotlin.math.sqrt(positionX * positionX + positionY * positionY)
        val targetMagnitude = kotlin.math.sqrt(newTargetX * newTargetX + newTargetY * newTargetY)
        return targetMagnitude > currentMagnitude
    }
}
