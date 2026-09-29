package info.olli.weatherlwp.parallax

import kotlin.math.abs
import kotlin.math.max

/**
 * Detects a back-and-forth wiggle around the phone's long axis (earpiece to charging port),
 * i.e. gyroscope Y. That axis is fixed to the device, so display rotation does not matter.
 *
 * A reversal is counted when the Y rate crosses [rateThreshold] with the opposite sign of the
 * last counted swing, stays there for [stableSamples] samples, and Y clearly dominates X and Z.
 * The dominance check keeps pick-ups, pocketing and turning the phone from counting.
 */
class AxisWiggleDetector(
    var rateThreshold: Float = 1.5f,
    var requiredReversals: Int = 4,
    var windowMs: Long = 1500L,
    private val dominanceRatio: Float = 1.5f,
    private val stableSamples: Int = 2,
    private val minReversalIntervalMs: Long = 70L
) {
    private var confirmedSign = 0
    private var candidateSign = 0
    private var candidateCount = 0
    private var lastReversalMs = 0L
    private var firstReversalMs = 0L
    private var reversals = 0

    /** Number of reversals counted in the current attempt (for logging). */
    val currentReversals: Int get() = reversals

    fun reset() {
        confirmedSign = 0
        candidateSign = 0
        candidateCount = 0
        lastReversalMs = 0L
        firstReversalMs = 0L
        reversals = 0
    }

    /**
     * Feed one gyroscope sample (rad/s, device axes).
     * @return true exactly once when the gesture completes; state is then reset.
     */
    fun onSample(rateX: Float, rateY: Float, rateZ: Float, nowMs: Long): Boolean {
        if (reversals > 0 && nowMs - firstReversalMs > windowMs) reset()

        val absY = abs(rateY)
        if (absY < rateThreshold || absY < dominanceRatio * max(abs(rateX), abs(rateZ))) {
            candidateSign = 0
            candidateCount = 0
            return false
        }

        val sign = if (rateY > 0f) 1 else -1
        if (confirmedSign == 0) {
            confirmedSign = sign
            return false
        }
        if (sign == confirmedSign) {
            candidateSign = 0
            candidateCount = 0
            return false
        }

        if (sign != candidateSign) {
            candidateSign = sign
            candidateCount = 1
        } else {
            candidateCount++
        }
        if (candidateCount < stableSamples) return false
        if (nowMs - lastReversalMs < minReversalIntervalMs) return false

        confirmedSign = sign
        candidateSign = 0
        candidateCount = 0
        lastReversalMs = nowMs
        if (reversals == 0) firstReversalMs = nowMs
        reversals++

        if (reversals >= requiredReversals) {
            reset()
            return true
        }
        return false
    }
}
