package info.olli.weatherlwp.simulation

import kotlin.random.Random

/**
 * Simulates wind dynamics: smooth strength transitions, direction smoothing,
 * and dynamic gusts with natural ramp-up/decay.
 *
 * Extracted from SimulationController (SRP) to enable independent testing.
 */
class WindSimulator {

    // Wind gust state
    private var nextGustTime = 0L
    private var gustDecay = 0f
    private var targetGust = 0f
    private var smoothedGust = 0f

    // Smooth wind transitions (prevent jumping when weather data changes)
    private var smoothedWindStrength = 0.5f
    private var smoothedWindDirection = 0f

    /**
     * Reset state after visibility changes to avoid stale gust timers.
     */
    fun reset() {
        nextGustTime = 0L
        targetGust = 0f
        smoothedGust = 0f
    }

    /**
     * Calculate dynamic wind gusts with smooth ramp-up and decay.
     * - Low wind: gentle gusts every 4-6 seconds
     * - High wind: strong gusts every 1-2 seconds
     * - Gusts ramp up gradually for natural feel
     */
    fun calculateWindGust(baseWind: Float, deltaMs: Long): Float {
        val currentMs = System.currentTimeMillis()
        // Cap deltaSec to 100ms to prevent explosion after visibility changes
        val deltaSec = (deltaMs / 1000f).coerceIn(0f, 0.1f)

        // No gusts in calm conditions - fixes "constant shaking" at low wind
        if (baseWind < 0.15f) {
            targetGust = 0f
            smoothedGust = (smoothedGust - deltaSec * 0.5f).coerceAtLeast(0f)
            return smoothedGust
        }

        // Decay target gust intensity (this is what we're ramping towards)
        targetGust = (targetGust - gustDecay * deltaSec).coerceIn(0f, 1f)

        // Check if it's time for a new gust
        if (currentMs >= nextGustTime) {
            // Gust interval: 1-2s at high wind, 4-7s at low wind
            val minInterval = (1000 + (1 - baseWind) * 3000).toLong()
            val maxInterval = (2000 + (1 - baseWind) * 5000).toLong()
            val interval = minInterval + (Random.nextDouble() * (maxInterval - minInterval)).toLong()
            nextGustTime = currentMs + interval

            // Gust intensity: proportional to base wind (no fixed floor)
            val minGust = baseWind * 0.5f
            val maxGust = baseWind * 0.3f + baseWind * 0.7f
            targetGust = minGust + Random.nextFloat() * (maxGust - minGust)

            // Decay rate: faster at higher wind (gusts are shorter but more frequent)
            gustDecay = 0.3f + baseWind * 0.5f
        }

        // Smooth the gust output: ramp up quickly (0.3s) but decay follows target
        val rampSpeed = 3.0f
        val diff = targetGust - smoothedGust
        if (diff > 0) {
            smoothedGust += diff * rampSpeed * deltaSec
            if (smoothedGust > targetGust) smoothedGust = targetGust
        } else {
            smoothedGust = targetGust
        }

        smoothedGust = smoothedGust.coerceIn(0f, 1f)
        return smoothedGust
    }

    /**
     * Smooth wind strength to prevent tree jumps when weather data updates.
     */
    fun smoothStrength(targetStrength: Float, deltaSec: Float): Float {
        val smoothFactor = 0.7f * deltaSec
        smoothedWindStrength += (targetStrength - smoothedWindStrength) * smoothFactor.coerceIn(0f, 1f)
        return smoothedWindStrength
    }

    /**
     * Smooth wind direction, handling 0/360 wraparound properly.
     */
    fun smoothDirection(targetDirection: Float, deltaSec: Float): Float {
        // Modulo normalization is O(1) regardless of input magnitude, so an out-of-range
        // targetDirection (e.g. from an untrusted broadcast) can never spin these loops.
        var delta = (targetDirection - smoothedWindDirection) % 360f
        if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f

        val smoothFactor = 0.5f * deltaSec
        smoothedWindDirection += delta * smoothFactor.coerceIn(0f, 1f)

        smoothedWindDirection = ((smoothedWindDirection % 360f) + 360f) % 360f

        return smoothedWindDirection
    }
}
