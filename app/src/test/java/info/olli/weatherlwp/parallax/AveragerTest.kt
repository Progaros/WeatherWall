package info.olli.weatherlwp.parallax

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the Averager warm-up fix.
 *
 * Covers:
 * - During warm-up (< bufferSize samples), average uses actual count not bufferSize
 * - After buffer is full, normal averaging resumes
 * - reset() marks buffer as full
 */
class AveragerTest {

    @Test
    fun `single sample returns that sample`() {
        val avg = Averager(5)
        avg.add(10f)
        assertEquals("Single sample should return itself", 10f, avg.calc(), 0.001f)
    }

    @Test
    fun `two samples returns their mean`() {
        val avg = Averager(5)
        avg.add(10f)
        avg.add(20f)
        assertEquals("Two samples should average to 15", 15f, avg.calc(), 0.001f)
    }

    @Test
    fun `full buffer averages all values`() {
        val avg = Averager(3)
        avg.add(10f)
        avg.add(20f)
        avg.add(30f)
        assertEquals("Full buffer should average to 20", 20f, avg.calc(), 0.001f)
    }

    @Test
    fun `overflow wraps correctly`() {
        val avg = Averager(3)
        avg.add(10f)
        avg.add(20f)
        avg.add(30f)
        avg.add(40f)  // Overwrites first slot
        // Buffer: [40, 20, 30] — index wrapped
        assertEquals("After wrap, average of latest 3", 30f, avg.calc(), 0.001f)
    }

    @Test
    fun `reset fills buffer and uses full size`() {
        val avg = Averager(3)
        avg.reset(5f)
        assertEquals("Reset with 5 should return 5", 5f, avg.calc(), 0.001f)
    }

    @Test
    fun `empty averager returns zero`() {
        val avg = Averager(5)
        assertEquals("Empty averager should return 0", 0f, avg.calc(), 0.001f)
    }
}
