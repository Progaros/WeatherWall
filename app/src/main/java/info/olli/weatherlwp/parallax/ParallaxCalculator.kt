package info.olli.weatherlwp.parallax

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Calculates parallax offset values using a position-based (tilt-following) system.
 * 
 * Key behavior:
 * - Scene offset follows the device tilt angle
 * - Holding the phone at an angle keeps the scene offset stable
 * - Smooth interpolation (lerp) toward target for natural feel
 * - Optional return-to-center when tilt is near zero (controlled by returnSpeed)
 * - tan() perspective curve gives natural depth perception
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
        /** How fast position approaches target (per frame, 0-1).
         * High value = more responsive. Direct mode (no lerp) used for low smoothing.
         */
        private const val POSITION_LERP_RATE = 0.95f
        
        /** Maximum tilt angle for tan() perspective curve (18 degrees in radians) */
        private const val MAX_TILT_ANGLE = 0.31415927f
        
        /** Precomputed 1/tan(MAX_TILT_ANGLE) for normalizing tan() output */
        private val TAN_NORMALIZER = 1f / tan(MAX_TILT_ANGLE)

        /** Ignore tiny tilt noise so return-to-center can engage when device is effectively still. */
        private const val TILT_NOISE_DEADZONE = 0.06f

        /** Stillness detection: tilt magnitude threshold below which phone is "still". */
        private const val STILL_TILT_EPSILON = 0.08f

        /** Stillness detection: tilt velocity threshold (change per frame). */
        private const val STILL_VELOCITY_EPSILON = 0.015f

        /** Stillness detection: ms the phone must remain still before recentering engages. */
        private const val STILL_HOLD_MS = 400L

        /** Damping rate for return-to-center (higher = faster return). */
        private const val RECENTER_DAMPING = 2.5f
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

    // Previous tilt values for velocity estimation
    private var prevTiltX = 0f
    private var prevTiltY = 0f

    // Accumulated time (ms) since the phone was last "not still"
    private var stillTimerMs = 0L
    
    // User-configurable settings - defaults match pref_effects.xml
    // These are overwritten by loadPrefs() but provide sane defaults before settings load
    var strength = 0.075f               // Default: 25% on log scale → ~0.075
    var maxOffsetPx = 130f              // Legacy: used when maxOffsetPercent is 0
    var maxOffsetPercent = 20f          // Default: 20% of screen height (increased for more movement)
    var smoothing = 0.134f              // Default: 30 → 0.02 + 0.3*0.38 = 0.134
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
    private var canvasWidth = referenceHeight * 0.5625f
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
        canvasWidth = width.coerceAtLeast(1f)
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
     * Uses a nonlinear curve: layers further away shift more, creating natural depth.
     */
    private fun movementMultiplier(depthFactor: Float): Float {
        val d = depthFactor.coerceIn(0f, 1f)
        val foregroundHeavy = d          // Model A: foreground moves most
        val backgroundHeavy = 1f - d      // Model B: background moves most
        val mixed = foregroundHeavy + (backgroundHeavy - foregroundHeavy) * perspective
        return mixed * depthSeparation
    }
    
    /**
     * Apply tan() perspective curve to a normalized position value.
     * Converts linear tilt into perspective-correct displacement:
     * - Near center: nearly linear (natural small movements)
     * - Near edges: accelerates (like looking through a window at an angle)
     * 
     * @param normalizedPos Position in -1..1 range
     * @return Perspective-corrected position in -1..1 range
     */
    private fun applyTanCurve(normalizedPos: Float): Float {
        val clamped = normalizedPos.coerceIn(-1f, 1f)
        return tan(clamped * MAX_TILT_ANGLE) * TAN_NORMALIZER
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
        val dt = deltaTimeMs / 1000f  // Convert to seconds for dt-based damping
        val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps for legacy lerp rates
        
        // Get latest tilt from sensor
        val tilt = pendingTilt.get()
        val filteredTiltX = if (kotlin.math.abs(tilt.x) < TILT_NOISE_DEADZONE) 0f else tilt.x
        val filteredTiltY = if (kotlin.math.abs(tilt.y) < TILT_NOISE_DEADZONE) 0f else tilt.y
        val tiltMagnitude = kotlin.math.sqrt(filteredTiltX * filteredTiltX + filteredTiltY * filteredTiltY)

        // Estimate tilt velocity (change since last frame)
        val tiltVelocityX = filteredTiltX - prevTiltX
        val tiltVelocityY = filteredTiltY - prevTiltY
        val tiltVelocity = kotlin.math.sqrt(tiltVelocityX * tiltVelocityX + tiltVelocityY * tiltVelocityY)
        prevTiltX = filteredTiltX
        prevTiltY = filteredTiltY

        // Stillness detection: phone must be below both magnitude and velocity thresholds
        val isStill = tiltMagnitude < STILL_TILT_EPSILON &&
                tiltVelocity < STILL_VELOCITY_EPSILON
        if (isStill) {
            stillTimerMs += deltaTimeMs
        } else {
            stillTimerMs = 0L
        }
        val shouldRecenter = isStill && stillTimerMs > STILL_HOLD_MS && returnSpeed > 0.001f
        
        // Calculate effective max offset based on canvas height percentage
        val effectiveMaxOffset = if (maxOffsetPercent > 0) {
            canvasHeight * (maxOffsetPercent / 100f)
        } else {
            maxOffsetPx * scaleFactor
        }
        
        // Calculate target position from tilt
        val (horizontalComp, verticalComp) = if (canvasWidth >= canvasHeight) {
            1f to aspectRatioCompensation
        } else {
            aspectRatioCompensation to 1f
        }

        val rawTargetX = filteredTiltX * effectiveMaxOffset * strength * horizontalComp
        val rawTargetY = filteredTiltY * effectiveMaxOffset * strength * verticalComp

        // Soft-limit the target using tanh-based radial saturation
        // This replaces the hard circular clamp, giving smooth deceleration near edges
        val xLimit = effectiveMaxOffset * horizontalComp
        val yLimit = effectiveMaxOffset * verticalComp
        targetX = softLimit(rawTargetX, xLimit)
        targetY = softLimit(rawTargetY, yLimit)
        
        // Calculate edge easing multiplier based on current position
        val edgeEasingMultiplier = if (edgeEasing > 0.01f) {
            calculateEdgeEasingMultiplier(xLimit, yLimit)
        } else {
            1f
        }

        if (shouldRecenter) {
            // Return-to-center: dt-based exponential damping toward (0, 0)
            val recenterRate = RECENTER_DAMPING * returnSpeed
            val t = 1f - exp(-recenterRate * dt)
            positionX += (0f - positionX) * t
            positionY += (0f - positionY) * t
        } else {
            // Normal tilt tracking
            val returnThreshold = 0.15f + returnSpeed * 0.35f
            val returnBlend = (1f - tiltMagnitude / returnThreshold).coerceIn(0f, 1f)

            val tiltLerp = if (smoothing <= 0.02f) {
                1.0f
            } else {
                val baseLerpRate = POSITION_LERP_RATE * (1f - smoothing * 0.5f) * deltaScale
                baseLerpRate.coerceIn(0.1f, 0.98f)
            }

            val returnLerp = if (returnSpeed <= 0.001f) {
                0f
            } else {
                (returnSpeed * 0.5f * deltaScale).coerceAtMost(0.9f)
            }

            val baseLerp = tiltLerp * (1f - returnBlend) + returnLerp * returnBlend

            val effectiveLerpRate = if (edgeEasing > 0.01f && isMovingAwayFromCenter(targetX, targetY)) {
                if (returnSpeedUnlocked && returnBlend > 0.5f) baseLerp else baseLerp * edgeEasingMultiplier
            } else {
                baseLerp
            }

            if (effectiveLerpRate >= 1.0f) {
                positionX = targetX
                positionY = targetY
            } else if (effectiveLerpRate > 0f) {
                positionX += (targetX - positionX) * effectiveLerpRate
                positionY += (targetY - positionY) * effectiveLerpRate
            }
        }
        
        // Snap to target when very close (prevents tiny oscillations)
        if (kotlin.math.abs(targetX - positionX) < 0.1f) positionX = targetX
        if (kotlin.math.abs(targetY - positionY) < 0.1f) positionY = targetY
        // Also snap to zero when recentering is nearly complete
        if (shouldRecenter) {
            if (kotlin.math.abs(positionX) < 0.1f) positionX = 0f
            if (kotlin.math.abs(positionY) < 0.1f) positionY = 0f
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
     * Applies tan() perspective curve for natural depth perception,
     * then scales by depth-dependent movement multiplier.
     * 
     * @param depthFactor Layer depth: 0.0 = background, 1.0 = foreground
     * @return (x, y) offset to apply to this layer
     */
    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        val mult = movementMultiplier(depthFactor)
        return Pair(curvedOffset(positionX, mult), curvedOffset(positionY, mult))
    }
    
    /**
     * Get X offset for a specific depth.
     * Applies tan() perspective curve for natural depth perception.
     */
    fun getOffsetXForDepth(depthFactor: Float): Float {
        return curvedOffset(positionX, movementMultiplier(depthFactor))
    }
    
    /**
     * Get Y offset for a specific depth.
     * Applies tan() perspective curve for natural depth perception.
     */
    fun getOffsetYForDepth(depthFactor: Float): Float {
        return curvedOffset(positionY, movementMultiplier(depthFactor))
    }

    /**
     * Apply perspective curve to a position component and scale by depth multiplier.
     */
    private fun curvedOffset(position: Float, mult: Float): Float {
        val effectiveMaxOffset = getEffectiveMaxOffset()
        val normalized = if (effectiveMaxOffset > 0) position / effectiveMaxOffset else 0f
        return applyTanCurve(normalized) * effectiveMaxOffset * mult
    }
    
    /** Get the effective max offset in pixels based on current settings. */
    private fun getEffectiveMaxOffset(): Float {
        return if (maxOffsetPercent > 0) {
            canvasHeight * (maxOffsetPercent / 100f)
        } else {
            maxOffsetPx * scaleFactor
        }
    }
    
    /**
     * Reset parallax to center immediately.
     */
    fun reset() {
        positionX = 0f
        positionY = 0f
        targetX = 0f
        targetY = 0f
        prevTiltX = 0f
        prevTiltY = 0f
        stillTimerMs = 0L
        pendingTilt.set(TiltPair(0f, 0f))
    }

    /**
     * Soft-limit a value using tanh-based saturation.
     * Smoothly decelerates toward max instead of hard-clamping.
     * Output is always in (-max, +max) and monotonic.
     */
    fun softLimit(value: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return max * tanh(value / max)
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
        
        // Smoothstep-style deceleration near edges avoids abrupt stop/jump feeling.
        val shapedDistance = normalizedDistance.pow(1f + edgeEasing * 1.5f)
        val smoothstep = shapedDistance * shapedDistance * (3f - 2f * shapedDistance)
        val multiplier = 1f - smoothstep

        // Keep some minimum movement even at very high tilt to avoid visual "sticking".
        return multiplier.coerceIn(0.15f, 1f)
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
