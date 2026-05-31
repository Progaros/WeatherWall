package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression test for DebugTracer.disable() recording "Session ended" before disabling.
 */
class DebugTracerTest {

    @Test
    fun `disable includes session ended in report`() {
        DebugTracer.enable()
        DebugTracer.log("Test", "some event")
        val report = DebugTracer.disable()
        assertTrue(
            "Report should contain 'Session ended'",
            report.contains("Session ended")
        )
    }

    @Test
    fun `disable actually disables tracing`() {
        DebugTracer.enable()
        assertTrue(DebugTracer.isEnabled())
        DebugTracer.disable()
        assertFalse(DebugTracer.isEnabled())
    }

    @Test
    fun `enable includes session started in report`() {
        DebugTracer.enable()
        val report = DebugTracer.disable()
        assertTrue(
            "Report should contain 'Session started'",
            report.contains("Session started")
        )
    }
}
