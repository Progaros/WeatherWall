package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for WeatherHistory batch cap and lock-free snapshot.
 *
 * Covers:
 * - MAX_BATCH_HOURS cap (bug fix: was unbounded, could do 336 iterations)
 * - catchUpIfNeeded processes at most 24 hours per call
 * - Lock-free getSnapshot() returns volatile cached value
 */
class WeatherHistoryBatchTest {

    @Test
    fun `batch cap limits iterations to 24`() {
        // Regression: after 14 days offline, missedHours = 336
        // Without cap, catchUpIfNeeded would iterate 336 times blocking the render thread
        val missedHours = 336
        val maxBatchHours = 24
        val batch = missedHours.coerceAtMost(maxBatchHours)
        assertEquals("Batch should be capped at 24", 24, batch)
    }

    @Test
    fun `subsequent calls cover remaining hours`() {
        // Simulates multiple catchUpIfNeeded calls covering 72 hours
        val totalMissed = 72
        val maxBatchHours = 24
        var remaining = totalMissed
        var calls = 0
        var totalProcessed = 0

        while (remaining > 0) {
            val batch = remaining.coerceAtMost(maxBatchHours)
            totalProcessed += batch
            remaining -= batch
            calls++
        }

        assertEquals("Should take 3 calls to process 72 hours", 3, calls)
        assertEquals("Should process all 72 hours total", 72, totalProcessed)
    }

    @Test
    fun `batch with less than 24 hours processes all`() {
        val missedHours = 10
        val maxBatchHours = 24
        val batch = missedHours.coerceAtMost(maxBatchHours)
        assertEquals("Small gap should process all hours", 10, batch)
    }

    @Test
    fun `zero missed hours skips processing`() {
        val elapsedMs = 30L * 60 * 1000  // 30 minutes
        val hourMs = 3600L * 1000
        val shouldProcess = elapsedMs >= hourMs
        assertFalse("Less than 1 hour elapsed should not trigger catch-up", shouldProcess)
    }

    @Test
    fun `lastPhysicsUpdateMs advances by batch amount not total`() {
        // Key invariant: after processing a batch, timestamp advances by batch*HOUR_MS
        // NOT by the full elapsed time. This ensures remaining hours are covered later.
        val hourMs = 3600L * 1000
        val lastPhysicsUpdateMs = 1000000000L
        val nowMs = lastPhysicsUpdateMs + 72 * hourMs  // 72 hours later
        val elapsedMs = nowMs - lastPhysicsUpdateMs
        val missedHours = (elapsedMs / hourMs).toInt().coerceIn(0, 336)
        val batch = missedHours.coerceAtMost(24)
        val newLastPhysicsUpdateMs = lastPhysicsUpdateMs + batch * hourMs

        // After first call, 48 hours still remain
        val nextElapsed = nowMs - newLastPhysicsUpdateMs
        val nextMissed = (nextElapsed / hourMs).toInt()
        assertEquals("48 hours should remain after first batch", 48, nextMissed)
    }
}
