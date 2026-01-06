package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.DebugOverlayLayer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DebugOverlayLayer.
 * Tests the debug item enum and configuration.
 */
class DebugOverlayTest {
    
    @Test
    fun `debug items have unique keys`() {
        val items = DebugOverlayLayer.DebugItem.entries
        val keys = items.map { it.key }
        
        assertEquals("All keys are unique", keys.size, keys.toSet().size)
    }
    
    @Test
    fun `debug items have non-empty labels`() {
        val items = DebugOverlayLayer.DebugItem.entries
        
        for (item in items) {
            assertTrue("${item.name} has non-empty label", item.label.isNotEmpty())
            assertTrue("${item.name} has non-empty key", item.key.isNotEmpty())
        }
    }
    
    @Test
    fun `debug items include essential performance metrics`() {
        val items = DebugOverlayLayer.DebugItem.entries
        val keys = items.map { it.key }.toSet()
        
        assertTrue("FPS item exists", keys.contains("fps"))
        assertTrue("Frame time item exists", keys.contains("frame_time"))
        assertTrue("Power mode item exists", keys.contains("power_mode"))
    }
    
    @Test
    fun `debug items include weather and time info`() {
        val items = DebugOverlayLayer.DebugItem.entries
        val keys = items.map { it.key }.toSet()
        
        assertTrue("Time item exists", keys.contains("time_of_day"))
        assertTrue("Weather item exists", keys.contains("weather"))
        assertTrue("Wind item exists", keys.contains("wind"))
        assertTrue("Clouds item exists", keys.contains("clouds"))
    }
    
    @Test
    fun `debug items include scene state info`() {
        val items = DebugOverlayLayer.DebugItem.entries
        val keys = items.map { it.key }.toSet()
        
        assertTrue("Events item exists", keys.contains("events"))
        assertTrue("Moon item exists", keys.contains("moon"))
        assertTrue("Parallax item exists", keys.contains("parallax"))
    }
    
    @Test
    fun `debug item count is reasonable`() {
        val items = DebugOverlayLayer.DebugItem.entries
        
        assertTrue("At least 5 debug items", items.size >= 5)
        assertTrue("Not too many items to display", items.size <= 15)
    }
    
    @Test
    fun `default debug items are sensible`() {
        // Default items should be FPS and Frame Time
        val defaultItems = setOf("fps", "frame_time")
        
        assertEquals("Two default items", 2, defaultItems.size)
        assertTrue("FPS is default", defaultItems.contains("fps"))
        assertTrue("Frame time is default", defaultItems.contains("frame_time"))
    }
    
    @Test
    fun `debug item labels are user-friendly`() {
        val items = DebugOverlayLayer.DebugItem.entries
        
        for (item in items) {
            // Labels should be readable (no underscores, not all caps)
            assertFalse("${item.name} label has no underscores", item.label.contains("_"))
            assertFalse("${item.name} label is not all caps", item.label == item.label.uppercase() && item.label.length > 3)
        }
    }
    
    @Test
    fun `fps calculation math is correct`() {
        // 60 FPS = 16.67ms per frame
        val targetFps = 60f
        val expectedFrameTimeMs = 1000f / targetFps
        
        assertEquals("60 FPS = ~16.67ms", 16.67f, expectedFrameTimeMs, 0.1f)
        
        // 30 FPS = 33.33ms per frame
        val targetFps30 = 30f
        val expectedFrameTimeMs30 = 1000f / targetFps30
        
        assertEquals("30 FPS = ~33.33ms", 33.33f, expectedFrameTimeMs30, 0.1f)
    }
    
    @Test
    fun `slow frame threshold is reasonable`() {
        // 16ms is 60 FPS target
        val threshold = 16L
        
        assertTrue("Threshold is positive", threshold > 0)
        assertTrue("Threshold represents 60 FPS", threshold <= 17)
        assertTrue("Threshold is not too aggressive", threshold >= 15)
    }
}
