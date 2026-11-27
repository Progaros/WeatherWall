package info.olli.weatherlwp.simulation

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for inHourWindow, used for golden/blue hour detection.
 * Boundary values are binary fractions (x.25, x.5, x.75) so wrapped bounds stay exact in Float.
 */
class HourWindowTest {

    @Test
    fun `normal window includes interior and both bounds`() {
        assertTrue(inHourWindow(6.5f, 6f, 6.75f))
        assertTrue("start inclusive", inHourWindow(6f, 6f, 6.75f))
        assertTrue("end inclusive", inHourWindow(6.75f, 6f, 6.75f))
        assertFalse(inHourWindow(5.75f, 6f, 6.75f))
        assertFalse(inHourWindow(7f, 6f, 6.75f))
        assertFalse(inHourWindow(18.5f, 6f, 6.75f))
    }

    @Test
    fun `normal windows match plain inclusive range`() {
        val windows = listOf(0f to 0.75f, 5.5f to 6f, 6f to 6.75f, 17.25f to 18f, 18f to 18.5f, 23f to 23.75f)
        for ((start, end) in windows) {
            var t = 0f
            while (t < 24f) {
                assertEquals("t=$t window=$start..$end", t in start..end, inHourWindow(t, start, end))
                t += 0.05f
            }
        }
    }

    @Test
    fun `window ending past midnight wraps to early morning`() {
        // Polar summer: sunset 23.75, blue hour runs to 24.25 (= 00:15)
        assertTrue(inHourWindow(23.9f, 23.75f, 24.25f))
        assertTrue(inHourWindow(0.1f, 23.75f, 24.25f))
        assertTrue("start inclusive", inHourWindow(23.75f, 23.75f, 24.25f))
        assertTrue("wrapped end inclusive", inHourWindow(0.25f, 23.75f, 24.25f))
        assertTrue("midnight", inHourWindow(0f, 23.75f, 24.25f))
        assertFalse(inHourWindow(0.5f, 23.75f, 24.25f))
        assertFalse(inHourWindow(23.5f, 23.75f, 24.25f))
        assertFalse(inHourWindow(12f, 23.75f, 24.25f))
    }

    @Test
    fun `task example sunset 23_8 blue hour includes 0_1`() {
        assertTrue(inHourWindow(0.1f, 23.8f, 23.8f + 0.5f))
    }

    @Test
    fun `window starting before midnight wraps to late evening`() {
        // Sunrise 0.25, blue hour starts at -0.5 (= 23:30)
        assertTrue(inHourWindow(23.75f, -0.5f, 0.25f))
        assertTrue(inHourWindow(0f, -0.5f, 0.25f))
        assertTrue("wrapped start inclusive", inHourWindow(23.5f, -0.5f, 0.25f))
        assertTrue("end inclusive", inHourWindow(0.25f, -0.5f, 0.25f))
        assertFalse(inHourWindow(23.25f, -0.5f, 0.25f))
        assertFalse(inHourWindow(0.5f, -0.5f, 0.25f))
        assertFalse(inHourWindow(12f, -0.5f, 0.25f))
    }

    @Test
    fun `window ending exactly at 24 includes midnight`() {
        assertTrue(inHourWindow(23.75f, 23.5f, 24f))
        assertTrue(inHourWindow(0f, 23.5f, 24f))
        assertFalse(inHourWindow(0.25f, 23.5f, 24f))
        assertFalse(inHourWindow(23.25f, 23.5f, 24f))
    }
}
