package info.olli.weatherlwp.parallax

import info.olli.weatherlwp.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
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

        /** Enter stillness when tilt velocity (units/second) is below this. */
        private const val STILL_VELOCITY_ENTER = 0.72f

        /** Exit stillness when tilt velocity (units/second) exceeds this (hysteresis). */
        private const val STILL_VELOCITY_EXIT = 1.5f

        /** Milliseconds of continuous stillness before recenter engages. */
        private const val STILL_HOLD_MS = 500L

        /** Exponential damping rate for return-to-center when close (higher = faster). */
        private const val RECENTER_DAMPING_NEAR = 2.0f

        /** Exponential damping rate for return-to-center when far from center. */
        private const val RECENTER_DAMPING_FAR = 5.0f

        /** Snap position to zero when magnitude is below this (prevents oscillation). */
        private const val SNAP_EPSILON = 0.05f

        /**
         * Maximum tilt velocity in units/second that is integrated into position.
         * Rate-based rather than per-frame so a fast flick is not throttled harder at
         * 30 fps than at 60 fps (6.0/s == the previous 0.10 per frame at 60 fps).
         */
        private const val MAX_DELTA_RATE = 6.0f

        /** Per-frame delta deadzone: deltas smaller than this are zeroed to suppress sensor noise. */
        private const val NOISE_EPSILON = 0.0015f

        /** Only treat as sensor reset if delta exceeds this (much larger than any tilt). */
        private const val SENSOR_RESET_THRESHOLD = 0.6f

        /** Cap dt to prevent huge position jumps on app resume. */
        private const val MAX_DT_SECONDS = 0.1f

        /** Number of samples for weighted delta smoothing. */
        private const val DELTA_SMOOTHING_SAMPLES = 3

        /** Weights for weighted moving average: newest=0.75, mid=0.2, oldest=0.05. */
        private val SMOOTHING_WEIGHTS = floatArrayOf(0.75f, 0.2f, 0.05f)

        /**
         * Tilt velocity (units/second) at which smoothing relaxes to [MAX_RAW_WEIGHT].
         * Averaging costs ~1 frame of latency, which is only worth paying for the slow,
         * noisy movements it is meant to filter.
         */
        private const val SMOOTHING_BYPASS_RATE = 3.0f

        /** Upper bound on the unsmoothed share, so single-frame sensor spikes stay damped. */
        private const val MAX_RAW_WEIGHT = 0.8f

        /**
         * Maximum accumulated tilt velocity (units/second) consumed in one frame.
         * Rate-based for the same reason as [MAX_DELTA_RATE] (15.0/s == 0.25 at 60 fps).
         */
        private const val MAX_ACCUMULATED_RATE = 15.0f
    }

    // Lock-free tilt input (sensor thread → render thread)
    private data class TiltPair(val x: Float, val y: Float)
    private val pendingTilt = AtomicReference(TiltPair(0f, 0f))

    // Accumulated sensor deltas between frames (sensor thread → render thread).
    // Ensures frame-rate independent behavior even under variable pacing.
    private data class DeltaAccum(val dx: Float, val dy: Float, val count: Int)
    private val accumulatedDelta = AtomicReference(DeltaAccum(0f, 0f, 0))

    // Previous sensor tilt for computing deltas in updateFromSensor (sensor thread)
    @Volatile private var sensorPrevX = 0f
    @Volatile private var sensorPrevY = 0f
    @Volatile private var sensorHasPrevious = false

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

    // Moving-average delta smoothing buffers (reduces sensor jitter)
    private val deltaBufferX = FloatArray(DELTA_SMOOTHING_SAMPLES)
    private val deltaBufferY = FloatArray(DELTA_SMOOTHING_SAMPLES)
    private var deltaBufferIndex = 0
    private var deltaBufferCount = 0
    private var filteredDeltaX = 0f
    private var filteredDeltaY = 0f

    // Stillness detection with hysteresis
    private var isDeviceStill = false
    private var stillTimerMs = 0L
    
    // User-configurable settings - defaults match pref_effects.xml
    // These are overwritten by loadPrefs() but provide sane defaults before settings load
    var strength = 0.075f               // Default: 25% on log scale → ~0.075
    var maxOffsetPx = 130f              // Legacy: used when maxOffsetPercent is 0
    var maxOffsetPercent = 20f          // Default: 20% of screen height
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
    * 0.0 retains the tanh soft limit; 1.0 uses a gentler approach to the limit.
    * Both curves have unit slope at the center and remain radially bounded.
     */
    var edgeEasing = 0.0f
    
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
     *
     * Also accumulates inter-frame deltas so that multiple sensor events between
     * render frames are properly summed rather than only sampling the latest value.
     */
    fun updateFromSensor(tiltX: Float, tiltY: Float) {
        val adjustedX = if (invertX) -tiltX else tiltX
        val adjustedY = if (invertY) -tiltY else tiltY
        pendingTilt.set(TiltPair(adjustedX, adjustedY))

        // Accumulate delta from sensor thread for frame-rate independence
        if (sensorHasPrevious) {
            val dx = adjustedX - sensorPrevX
            val dy = adjustedY - sensorPrevY
            // CAS loop to safely accumulate
            var prev: DeltaAccum
            var next: DeltaAccum
            do {
                prev = accumulatedDelta.get()
                next = DeltaAccum(prev.dx + dx, prev.dy + dy, prev.count + 1)
            } while (!accumulatedDelta.compareAndSet(prev, next))
        }
        sensorPrevX = adjustedX
        sensorPrevY = adjustedY
        sensorHasPrevious = true
    }

    /**
     * Advance parallax state by one frame. Call once per frame on the render thread.
     *
     * All movement is delta-based: position changes by (filteredTilt − prevTilt)
     * scaled by sensitivity. No absolute tilt value ever maps to a visual offset.
     */
    fun update(deltaTimeMs: Long = 16L) {
        val dt = (deltaTimeMs.coerceAtLeast(1L) / 1000f).coerceAtMost(MAX_DT_SECONDS)

        // Consume accumulated sensor deltas (frame-rate independent).
        // If multiple sensor events arrived between frames, their sum is used.
        val accum = accumulatedDelta.getAndSet(DeltaAccum(0f, 0f, 0))

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

        // Use accumulated delta if sensor events arrived AND tilt is outside deadzone,
        // otherwise fallback to frame-based delta (works for tests and single-event-per-frame scenarios).
        val maxAccumulated = MAX_ACCUMULATED_RATE * dt
        val rawDeltaX: Float
        val rawDeltaY: Float
        if (accum.count > 0 && (filteredTiltX != 0f || filteredTiltY != 0f)) {
            val accumMag = kotlin.math.sqrt(accum.dx * accum.dx + accum.dy * accum.dy)
            // Check for sensor reset BEFORE clamping (huge jump = orientation change)
            if (accumMag > SENSOR_RESET_THRESHOLD) {
                resetDeltaSmoothing()
                accumulatedDelta.set(DeltaAccum(0f, 0f, 0))
                prevTiltX = filteredTiltX
                prevTiltY = filteredTiltY
                return
            } else if (accumMag > maxAccumulated) {
                // Clamp accumulated magnitude to prevent huge jumps from burst events
                val clamp = maxAccumulated / accumMag
                rawDeltaX = accum.dx * clamp
                rawDeltaY = accum.dy * clamp
            } else {
                rawDeltaX = accum.dx
                rawDeltaY = accum.dy
            }
        } else {
            rawDeltaX = filteredTiltX - prevTiltX
            rawDeltaY = filteredTiltY - prevTiltY
        }
        prevTiltX = filteredTiltX
        prevTiltY = filteredTiltY

        // Suppress micro-jitter: zero out deltas below noise floor
        var deltaTiltX = if (kotlin.math.abs(rawDeltaX) < NOISE_EPSILON) 0f else rawDeltaX
        var deltaTiltY = if (kotlin.math.abs(rawDeltaY) < NOISE_EPSILON) 0f else rawDeltaY

        val motionMagnitude = kotlin.math.sqrt(
            deltaTiltX * deltaTiltX + deltaTiltY * deltaTiltY
        )

        // Sensor reset (orientation change): reset baseline, no visual jump
        if (motionMagnitude > SENSOR_RESET_THRESHOLD) {
            resetDeltaSmoothing()
            accumulatedDelta.set(DeltaAccum(0f, 0f, 0))
            return
        }

        // Clamp large deltas instead of discarding them (preserves movement direction)
        val maxDelta = MAX_DELTA_RATE * dt
        if (motionMagnitude > maxDelta) {
            val clampScale = maxDelta / motionMagnitude
            deltaTiltX *= clampScale
            deltaTiltY *= clampScale
        }

        // Tilt velocity in units/second — frame-rate independent, so the same physical
        // motion behaves identically at 30, 60 and 120 fps.
        val motionRate = motionMagnitude / dt

        // Adaptive smoothing: the moving average costs ~1 frame of latency, so blend it
        // out as motion gets faster. Slow movement stays filtered, flicks stay responsive.
        smoothDelta(deltaTiltX, deltaTiltY)
        val rawWeight = (motionRate / SMOOTHING_BYPASS_RATE).coerceIn(0f, MAX_RAW_WEIGHT)
        deltaTiltX = filteredDeltaX + (deltaTiltX - filteredDeltaX) * rawWeight
        deltaTiltY = filteredDeltaY + (deltaTiltY - filteredDeltaY) * rawWeight

        // Stillness hysteresis
        if (isDeviceStill) {
            if (motionRate > STILL_VELOCITY_EXIT) {
                isDeviceStill = false
                stillTimerMs = 0L
            } else {
                stillTimerMs += deltaTimeMs
            }
        } else {
            if (motionRate < STILL_VELOCITY_ENTER) {
                isDeviceStill = true
                stillTimerMs += deltaTimeMs
            } else {
                stillTimerMs = 0L
            }
        }

        val shouldRecenter =
            isDeviceStill && stillTimerMs > STILL_HOLD_MS && returnSpeed > 0.001f

        // Effective max offset and aspect compensation.
        // Both are derived from the shared helpers so horizontal and vertical movement
        // stay consistent across portrait and landscape (see getEffectiveMaxOffset /
        // getAxisCompensation).
        val effectiveMaxOffset = getEffectiveMaxOffset()
        // Horizontal carries the user aspect-ratio knob; vertical is always 1f.
        val hComp = aspectRatioCompensation
        val vComp = 1f
        val sensitivityPx = effectiveMaxOffset * strength

        if (!shouldRecenter) {
            // ---- TRACKING ----
            // Direct delta integration: no smoothing on the input path.
            // Raw tilt delta → position change, immediate response.
            val moveX = deltaTiltX * sensitivityPx * hComp
            val moveY = deltaTiltY * sensitivityPx * vComp

            rawPositionX += moveX
            rawPositionY += moveY

            // Gentle pullback if raw position overshoots max by too much
            val maxDist = effectiveMaxOffset * kotlin.math.max(hComp, vComp)
            if (maxDist > 0f) {
                val rawDist = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
                if (rawDist > maxDist * 1.8f) {
                    val pullback = (1f - exp(-2f * dt)).coerceAtMost(1f)
                    val targetDist = maxDist * 1.4f
                    val scale = ((1f - pullback) + pullback * (targetDist / rawDist)).coerceAtMost(1f)
                    rawPositionX *= scale
                    rawPositionY *= scale
                }
            }
        } else {
            // ---- RECENTERING ----
            // Still integrate any incoming deltas so movement cancels recenter.
            val moveX = deltaTiltX * sensitivityPx * hComp
            val moveY = deltaTiltY * sensitivityPx * vComp
            rawPositionX += moveX
            rawPositionY += moveY

            // Adaptive recenter: fast when far, gentle when close (natural settling)
            val maxDist = effectiveMaxOffset * kotlin.math.max(hComp, vComp)
            val rawDist = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
            val distanceRatio = if (maxDist > 0f) (rawDist / maxDist).coerceIn(0f, 1f) else 0f
            val rate = (RECENTER_DAMPING_NEAR + distanceRatio * distanceRatio * (RECENTER_DAMPING_FAR - RECENTER_DAMPING_NEAR)) * returnSpeed
            val t = 1f - exp(-rate * dt)
            rawPositionX *= (1f - t)
            rawPositionY *= (1f - t)
        }

        // Compute display position from raw via soft-limit
        val maxDist = effectiveMaxOffset * kotlin.math.max(hComp, vComp)
        applyRadialSoftLimit(maxDist)

        // Debug validation: position must never exceed soft-limit bound
        if (BuildConfig.DEBUG) {
            val posMag = kotlin.math.sqrt(positionX * positionX + positionY * positionY)
            if (posMag > maxDist + 1f) {
                android.util.Log.w("ParallaxCalc",
                    "Position exceeded max: |pos|=$posMag > max=$maxDist")
            }
        }

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

    /**
     * True when sensor motion has arrived that no frame has integrated yet.
     *
     * Idle mode otherwise only wakes up once the visible offset has already moved,
     * which cannot happen until a frame renders — costing a full idle frame interval
     * of latency at the start of every movement.
     */
    fun hasPendingSensorMotion(): Boolean {
        val accum = accumulatedDelta.get()
        if (accum.count == 0) return false
        return kotlin.math.abs(accum.dx) > NOISE_EPSILON ||
            kotlin.math.abs(accum.dy) > NOISE_EPSILON
    }

    fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        // Delegates to the allocation-free scalar accessors. This convenience Pair
        // form is only used off the tight per-object loops (e.g. sky/stars once/frame).
        return Pair(getOffsetXForDepth(depthFactor), getOffsetYForDepth(depthFactor))
    }

    fun getOffsetXForDepth(depthFactor: Float): Float {
        // Allocation-free: called per cloud/hill/mountain/star every frame. Horizontal
        // axis carries the user aspect-ratio compensation.
        val mult = movementMultiplier(depthFactor)
        val axisMax = getEffectiveMaxOffset() * aspectRatioCompensation
        val maxOffset = axisMax * mult
        return curvedOffset(positionX, mult, axisMax).coerceIn(-maxOffset, maxOffset)
    }

    fun getOffsetYForDepth(depthFactor: Float): Float {
        // Allocation-free: vertical axis compensation is always 1f (see getMaxOffsetForDepth).
        val mult = movementMultiplier(depthFactor)
        val axisMax = getEffectiveMaxOffset()
        val maxOffset = axisMax * mult
        return curvedOffset(positionY, mult, axisMax).coerceIn(-maxOffset, maxOffset)
    }

    /**
     * Conservative max per-axis offset for a layer depth.
     * Used by render layers to compute overscan that is guaranteed to cover the viewport.
     * Vertical compensation is always 1f; horizontal carries [aspectRatioCompensation].
     */
    fun getMaxOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
        val effectiveMaxOffset = getEffectiveMaxOffset()
        val mult = movementMultiplier(depthFactor)
        return Pair(effectiveMaxOffset * aspectRatioCompensation * mult, effectiveMaxOffset * mult)
    }

    /** Reset visual offset to center AND clear input baseline. */
    fun reset() {
        rawPositionX = 0f
        rawPositionY = 0f
        positionX = 0f
        positionY = 0f
        prevTiltX = 0f
        prevTiltY = 0f
        hasPreviousTilt = false
        resetDeltaSmoothing()
        isDeviceStill = false
        stillTimerMs = 0L
        pendingTilt.set(TiltPair(0f, 0f))
        accumulatedDelta.set(DeltaAccum(0f, 0f, 0))
        sensorPrevX = 0f
        sensorPrevY = 0f
        sensorHasPrevious = false
    }

    /**
     * Reset only the input baseline without zeroing the visual offset.
     * Call after sensor recalibration or orientation change to prevent a
     * large delta on the next frame.
     */
    fun resetInputBaseline() {
        hasPreviousTilt = false
        resetDeltaSmoothing()
        accumulatedDelta.set(DeltaAccum(0f, 0f, 0))
        sensorPrevX = 0f
        sensorPrevY = 0f
        sensorHasPrevious = false
    }

    /** Tanh soft-limit (public for testing). */
    fun softLimit(value: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return max * tanh(value / max)
    }

    // ---------- Internal helpers ----------

    /** Reset the moving-average smoothing buffers. */
    private fun resetDeltaSmoothing() {
        deltaBufferX.fill(0f)
        deltaBufferY.fill(0f)
        deltaBufferIndex = 0
        deltaBufferCount = 0
        filteredDeltaX = 0f
        filteredDeltaY = 0f
    }

    /**
     * Insert a delta sample into the ring buffer and compute a weighted average.
     * Weights: newest=0.75, previous=0.2, oldest=0.05 — reduces latency vs uniform average.
     * Result is stored in [filteredDeltaX] / [filteredDeltaY] to avoid allocation.
     */
    private fun smoothDelta(dx: Float, dy: Float) {
        deltaBufferX[deltaBufferIndex] = dx
        deltaBufferY[deltaBufferIndex] = dy
        deltaBufferIndex = (deltaBufferIndex + 1) % DELTA_SMOOTHING_SAMPLES
        if (deltaBufferCount < DELTA_SMOOTHING_SAMPLES) deltaBufferCount++

        if (deltaBufferCount < DELTA_SMOOTHING_SAMPLES) {
            // Not enough samples yet — use uniform average for stability
            var sumX = 0f
            var sumY = 0f
            for (i in 0 until deltaBufferCount) {
                sumX += deltaBufferX[i]
                sumY += deltaBufferY[i]
            }
            filteredDeltaX = sumX / deltaBufferCount
            filteredDeltaY = sumY / deltaBufferCount
        } else {
            // Full buffer — weighted average (newest=0.7, mid=0.2, oldest=0.1)
            // deltaBufferIndex points to next-write slot, so newest is at index-1
            var sumX = 0f
            var sumY = 0f
            for (w in 0 until DELTA_SMOOTHING_SAMPLES) {
                // w=0 → newest (weight 0.6), w=1 → previous (0.3), w=2 → oldest (0.1)
                val bufIdx = (deltaBufferIndex - 1 - w + DELTA_SMOOTHING_SAMPLES) % DELTA_SMOOTHING_SAMPLES
                sumX += deltaBufferX[bufIdx] * SMOOTHING_WEIGHTS[w]
                sumY += deltaBufferY[bufIdx] * SMOOTHING_WEIGHTS[w]
            }
            filteredDeltaX = sumX
            filteredDeltaY = sumY
        }
    }

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

    private fun curvedOffset(position: Float, mult: Float, axisMax: Float): Float {
        val normalized = if (axisMax > 0) position / axisMax else 0f
        return applyTanCurve(normalized) * axisMax * mult
    }

    private fun getEffectiveMaxOffset(): Float {
        // Base the offset budget on the LONG edge so the movement magnitude does not
        // collapse when the device is rotated to landscape (where the height becomes the
        // short edge). In portrait the long edge is the height, so this is unchanged.
        return if (maxOffsetPercent > 0) {
            kotlin.math.max(canvasWidth, canvasHeight) * (maxOffsetPercent / 100f)
        } else {
            maxOffsetPx * scaleFactor
        }
    }

    // Per-axis compensation is intentionally implicit: horizontal uses the user
    // aspect-ratio knob ([aspectRatioCompensation]); vertical is always 1f. Because
    // [getEffectiveMaxOffset] is based on the long edge, a tilt produces the same pixel
    // offset on both axes regardless of orientation ("up/down barely moves in landscape"
    // is fixed). Callers inline these two factors to avoid per-frame Pair allocation.

    private fun applyRadialSoftLimit(maxOffset: Float) {
        if (maxOffset <= 0f) { positionX = 0f; positionY = 0f; return }
        val len = kotlin.math.sqrt(rawPositionX * rawPositionX + rawPositionY * rawPositionY)
        if (len <= 0f) { positionX = 0f; positionY = 0f; return }
        val normalized = len / maxOffset
        val baseLimit = tanh(normalized)
        val easedLimit = normalized / kotlin.math.sqrt(1f + normalized * normalized)
        val easing = edgeEasing.coerceIn(0f, 1f)
        val limitedLen = maxOffset * (baseLimit + (easedLimit - baseLimit) * easing)
        val scale = limitedLen / len
        positionX = rawPositionX * scale
        positionY = rawPositionY * scale
    }
}
