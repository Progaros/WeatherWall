package info.olli.weatherlwp.parallax

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Fully relative (delta-based) parallax calculator.
 *
 * Core principle: parallax position changes only by per-frame tilt delta.
 * No absolute tilt value ever maps directly to a visual offset.
 *
 *   position += smoothedDelta * sensitivity
 *   position  = softLimit(position)
 *   if still → position damped toward zero
 *
 * This eliminates all jump classes caused by target-rebasing, origin-tracking,
 * or stale absolute-tilt targets after return-to-center.
 *
 * Thread safety:
 * - [updateFromSensor] may be called from any thread (lock-free AtomicReference).
 * - [update] must be called on the render thread only.
 *
 * Usage:
 * 1. Call updateFromSensor(tiltX, tiltY) when sensor data arrives
 * 2. Call update() each frame before rendering
 * 3. Use getOffsetForDepth(depth) to get parallax for a specific layer
 */
class ParallaxCalculator {
    companion object {
        /** Maximum tilt angle for tan() perspective curve (18 degrees in radians). */
        private const val MAX_TILT_ANGLE = 0.31415927f

        /** Precomputed 1/tan(MAX_TILT_ANGLE) for normalizing tan() output. */
        private val TAN_NORMALIZER = 1f / tan(MAX_TILT_ANGLE)

        /** Ignore tiny tilt noise so return-to-center can engage on a still device. */
        private const val TILT_NOISE_DEADZONE = 0.06f

        /** Enter stillness when per-frame delta magnitude is below this. */
        private const val STILL_VELOCITY_ENTER = 0.012f

        /** Exit stillness when per-frame delta magnitude exceeds this (hysteresis). */
        private const val STILL_VELOCITY_EXIT = 0.025f

        /** Milliseconds of continuous stillness before recenter engages. */
        private const val STILL_HOLD_MS = 400L

        /** Exponential damping rate for return-to-center (higher = faster). */
        private const val RECENTER_DAMPING = 2.5f

        /** Snap position to zero when magnitude is below this (prevents oscillation). */
        private const val SNAP_EPSILON = 0.1f

        /** Discard frame as sensor reset / orientation change if delta exceeds this. */
        private const val JUMP_THRESHOLD = 0.3f

        /** Cap dt to prevent huge position jumps on app resume. */
        private const val MAX_DT_SECONDS = 0.1f
    }

    // Lock-free tilt input (sensor thread → render thread)
    private data class TiltPair(val x: Float, val y: Float)
    private val pendingTilt = AtomicReference(TiltPair(0f, 0f))

    // Raw accumulated offset (unlimited, render thread only)
    private var rawPositionX = 0f
    private var rawPositionY = 0f

    // Display offset in pixels (soft-limited from raw, render thread only)
    private var positionX = 0f
    private var positionY = 0f

    // Previous filtered tilt for computing per-frame delta
    private var prevTiltX = 0f
    private var prevTiltY = 0f
    private var hasPreviousTilt = false

    // Smoothed tilt delta (exponential filter, controlled by [smoothing])
    private var smoothedDeltaX = 0f
    private var smoothedDeltaY = 0f

    // Stillness detection with hysteresis
    private var isDeviceStill = false
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
    var returnSpeed = 0f                // Default: off (production sets from pref_parallax_return_speed)
    
    /**
     * Parallax perspective: controls which layers move more.
     * 0.0 = foreground moves most (head moves, window stays)
     * 1.0 = background moves most (window moves, head stays - default)
     * This keeps the 3D "through a window" effect but shifts which plane feels anchored.
     */
    var perspective = 1.0f              // Default: pref_parallax_perspective=100
    
    /**
     * Store latest sensor tilt. Thread-safe (lock-free atomic).
     * Values are absolute tilt in -1..1; the calculator computes deltas internally.
     */
    fun updateFromSensor(tiltX: Float, tiltY: Float) {
        val adjustedX = if (invertX) -tiltX else tiltX
        val adjustedY = if (invertY) -tiltY else tiltY
        pendingTilt.set(TiltPair(adjustedX, adjustedY))
    }

    /**
     * Advance parallax state by one frame. Call once per frame on the render thread.
     *
     * All movement is delta-based: position changes by (filteredTilt − prevTilt)
     * scaled by sensitivity. No absolute tilt value ever maps to a visual offset.
     */
    fun update(deltaTimeMs: Long = 16L) {
        val dt = (deltaTimeMs.coerceAtLeast(1L) / 1000f).coerceAtMost(MAX_DT_SECONDS)

        // Read latest tilt and apply deadzone
        val tilt = pendingTilt.get()
        val filteredTiltX = if (kotlin.math.abs(tilt.x) < TILT_NOISE_DEADZONE) 0f else tilt.x
        val filteredTiltY = if (kotlin.math.abs(tilt.y) < TILT_NOISE_DEADZONE) 0f else tilt.y

        // First frame after reset: set baseline, do not move
        if (!hasPreviousTilt) {
            prevTiltX = filteredTiltX
            prevTiltY = filteredTiltY
            hasPreviousTilt = true
            return
        }

        // Per-frame tilt delta
        val deltaTiltX = filteredTiltX - prevTiltX
        val deltaTiltY = filteredTiltY - prevTiltY
        prevTiltX = filteredTiltX
        prevTiltY = filteredTiltY

        val motionMagnitude = kotlin.math.sqrt(
            deltaTiltX * deltaTiltX + deltaTiltY * deltaTiltY
        )

        // Discard huge jumps (sensor reset, orientation change)
        if (motionMagnitude > JUMP_THRESHOLD) {
            smoothedDeltaX = 0f
            smoothedDeltaY = 0f
            return
        }

        // Stillness hysteresis
        if (isDeviceStill) {
            if (motionMagnitude > STILL_VELOCITY_EXIT) {
                isDeviceStill = false
                stillTimerMs = 0L
            } else {
                stillTimerMs += deltaTimeMs
            }
        } else {
            if (motionMagnitude < STILL_VELOCITY_ENTER) {
                isDeviceStill = true
                stillTimerMs += deltaTimeMs
            } else {
                stillTimerMs = 0L
            }
        }

        val shouldRecenter =
            isDeviceStill && stillTimerMs > STILL_HOLD_MS && returnSpeed > 0.001f

        // Effective max offset and aspect compensation
        val effectiveMaxOffset = if (maxOffsetPercent > 0) {
            canvasHeight * (maxOffsetPercent / 100f)
        } else {
            maxOffsetPx * scaleFactor
        }
        val (hComp, vComp) = if (canvasWidth >= canvasHeight) {
            1f to aspectRatioCompensation
        } else {
            aspectRatioCompensation to 1f
        }
        val sensitivityPx = effectiveMaxOffset * strength

        if (!shouldRecenter) {
            // ---- TRACKING ----
            // Smooth the delta (exponential low-pass filter)
            if (smoothing <= 0.02f) {
                smoothedDeltaX = deltaTiltX
                smoothedDeltaY = deltaTiltY
            } else {
                val lambda = (1f - smoothing * 0.8f).coerceIn(0.05f, 1f) * 60f
                val alpha = 1f - exp(-lambda * dt)
                smoothedDeltaX += (deltaTiltX - smoothedDeltaX) * alpha
                smoothedDeltaY += (deltaTiltY - smoothedDeltaY) * alpha
            }

            var moveX = smoothedDeltaX * sensitivityPx * hComp
            var moveY = smoothedDeltaY * sensitivityPx * vComp

            // Edge easing: slow outward movement near edges
            if (edgeEasing > 0.01f && isMovingAwayFromCenter(moveX, moveY)) {
                val xLimit = effectiveMaxOffset * hComp
                val yLimit = effectiveMaxOffset * vComp
                val mult = calculateEdgeEasingMultiplier(xLimit, yLimit)
                moveX *= mult
                moveY *= mult
            }

            rawPositionX += moveX
            rawPositionY += moveY
        } else {
            // ---- RECENTERING ----
            val rate = RECENTER_DAMPING * returnSpeed
            val t = 1f - exp(-rate * dt)
            rawPositionX -= rawPositionX * t
            rawPositionY -= rawPositionY * t
            // Drain smoothed delta so tracking resumes cleanly
            smoothedDeltaX = 0f
            smoothedDeltaY = 0f
        }

        // Compute display position from raw via soft-limit
        val maxDist = effectiveMaxOffset * kotlin.math.max(hComp, vComp)
        applyRadialSoftLimit(maxDist)

        // Snap to zero
        val rawLen = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
        if (rawLen < SNAP_EPSILON) {
            rawPositionX = 0f
            rawPositionY = 0f
            positionX = 0f
            positionY = 0f
        }
    }

    // ---------- Public query API ----------

    fun getOffset(): Pair<Float, Float> = Pair(positionX, positionY)
    fun getOffsetX(): Float = positionX
    fun getOffsetY(): Float = positionY

    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        val mult = movementMultiplier(depthFactor)
        return Pair(curvedOffset(positionX, mult), curvedOffset(positionY, mult))
    }

    fun getOffsetXForDepth(depthFactor: Float): Float =
        curvedOffset(positionX, movementMultiplier(depthFactor))

    fun getOffsetYForDepth(depthFactor: Float): Float =
        curvedOffset(positionY, movementMultiplier(depthFactor))

    /** Reset visual offset to center AND clear input baseline. */
    fun reset() {
        rawPositionX = 0f
        rawPositionY = 0f
        positionX = 0f
        positionY = 0f
        prevTiltX = 0f
        prevTiltY = 0f
        hasPreviousTilt = false
        smoothedDeltaX = 0f
        smoothedDeltaY = 0f
        isDeviceStill = false
        stillTimerMs = 0L
        pendingTilt.set(TiltPair(0f, 0f))
    }

    /**
     * Reset only the input baseline without zeroing the visual offset.
     * Call after sensor recalibration or orientation change to prevent a
     * large delta on the next frame.
     */
    fun resetInputBaseline() {
        hasPreviousTilt = false
        smoothedDeltaX = 0f
        smoothedDeltaY = 0f
    }

    /** Tanh soft-limit (public for testing). */
    fun softLimit(value: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return max * tanh(value / max)
    }

    // ---------- Internal helpers ----------

    private fun movementMultiplier(depthFactor: Float): Float {
        val d = depthFactor.coerceIn(0f, 1f)
        val foregroundHeavy = d
        val backgroundHeavy = 1f - d
        val mixed = foregroundHeavy + (backgroundHeavy - foregroundHeavy) * perspective
        return mixed * depthSeparation
    }

    private fun applyTanCurve(normalizedPos: Float): Float {
        val clamped = normalizedPos.coerceIn(-1f, 1f)
        return tan(clamped * MAX_TILT_ANGLE) * TAN_NORMALIZER
    }

    private fun curvedOffset(position: Float, mult: Float): Float {
        val effectiveMaxOffset = getEffectiveMaxOffset()
        val normalized = if (effectiveMaxOffset > 0) position / effectiveMaxOffset else 0f
        return applyTanCurve(normalized) * effectiveMaxOffset * mult
    }

    private fun getEffectiveMaxOffset(): Float {
        return if (maxOffsetPercent > 0) {
            canvasHeight * (maxOffsetPercent / 100f)
        } else {
            maxOffsetPx * scaleFactor
        }
    }

    private fun applyRadialSoftLimit(maxOffset: Float) {
        if (maxOffset <= 0f) { positionX = 0f; positionY = 0f; return }
        val len = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
        if (len <= 0f) { positionX = 0f; positionY = 0f; return }
        val limitedLen = maxOffset * tanh(len / maxOffset)
        val scale = limitedLen / len
        positionX = rawPositionX * scale
        positionY = rawPositionY * scale
    }

    private fun calculateEdgeEasingMultiplier(xLimit: Float, yLimit: Float): Float {
        if (edgeEasing <= 0.01f) return 1f
        val normalizedX = if (xLimit > 0) kotlin.math.abs(positionX) / xLimit else 0f
        val normalizedY = if (yLimit > 0) kotlin.math.abs(positionY) / yLimit else 0f
        val normalizedDistance = kotlin.math.sqrt(
            normalizedX * normalizedX + normalizedY * normalizedY
        ).coerceIn(0f, 1f)
        val shapedDistance = normalizedDistance.pow(1f + edgeEasing * 1.5f)
        val smoothstep = shapedDistance * shapedDistance * (3f - 2f * shapedDistance)
        val multiplier = 1f - smoothstep
        return multiplier.coerceIn(0.15f, 1f)
    }

    private fun isMovingAwayFromCenter(moveX: Float, moveY: Float): Boolean {
        val currentMag = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
        val newMag = kotlin.math.sqrt(
            (rawPositionX + moveX) * (rawPositionX + moveX) +
                (rawPositionY + moveY) * (rawPositionY + moveY)
        )
        return newMag > currentMag
    }
}
