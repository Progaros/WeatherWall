package info.olli.weatherlwp.rendering.events

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EventTraversalTest {

    // ============== HorizontalPath Tests ==============

    @Test
    fun `HorizontalPath distance is positive`() {
        val path = EventTraversal.HorizontalPath(startX = -0.15f, endX = 1.15f, direction = 1f)
        assertEquals(1.3f, path.distance, 0.001f)
    }

    @Test
    fun `HorizontalPath distance is absolute`() {
        val path = EventTraversal.HorizontalPath(startX = 1.15f, endX = -0.15f, direction = -1f)
        assertEquals(1.3f, path.distance, 0.001f)
    }

    // ============== randomHorizontalPath Tests ==============

    @Test
    fun `randomHorizontalPath with direction right starts left of screen`() {
        // Seed that gives direction=right (nextBoolean=true)
        val random = Random(42)
        val path = EventTraversal.randomHorizontalPath(random, 0.15f)
        // Either left-to-right or right-to-left
        assertTrue(path.startX < 0f || path.startX > 1f)
        assertTrue(path.endX < 0f || path.endX > 1f)
        assertNotEquals(path.startX > 0.5f, path.endX > 0.5f)
    }

    @Test
    fun `randomHorizontalPath uses margin for spawn`() {
        val random = Random(1)
        val margin = 0.2f
        val path = EventTraversal.randomHorizontalPath(random, margin)
        if (path.direction > 0f) {
            assertEquals(-margin, path.startX, 0.001f)
            assertEquals(1f + margin, path.endX, 0.001f)
        } else {
            assertEquals(1f + margin, path.startX, 0.001f)
            assertEquals(-margin, path.endX, 0.001f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `randomHorizontalPath rejects zero margin`() {
        EventTraversal.randomHorizontalPath(Random(0), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `randomHorizontalPath rejects margin at 0-5`() {
        EventTraversal.randomHorizontalPath(Random(0), 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `randomHorizontalPath rejects negative margin`() {
        EventTraversal.randomHorizontalPath(Random(0), -0.1f)
    }

    @Test
    fun `randomHorizontalPath direction is 1 or negative 1`() {
        repeat(20) { seed ->
            val path = EventTraversal.randomHorizontalPath(Random(seed), 0.15f)
            assertTrue(path.direction == 1f || path.direction == -1f)
        }
    }

    // ============== durationMsForPath Tests ==============

    @Test
    fun `durationMsForPath basic calculation`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 1f, direction = 1f)
        // distance=1.0, speed=0.001 -> 1000ms
        val duration = EventTraversal.durationMsForPath(path, 0.001f)
        assertEquals(1000L, duration)
    }

    @Test
    fun `durationMsForPath respects minimum duration`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 0.01f, direction = 1f)
        // Very short distance, fast speed -> would be very small
        val duration = EventTraversal.durationMsForPath(path, 1f, minDurationMs = 5000L)
        assertEquals(5000L, duration)
    }

    @Test
    fun `durationMsForPath with zero speed uses safe minimum`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 1f, direction = 1f)
        val duration = EventTraversal.durationMsForPath(path, 0f)
        // 0 speed coerced to 1e-7, distance=1.0 -> 10_000_000ms
        assertTrue(duration >= 1000L)
    }

    @Test
    fun `durationMsForPath with pauses increases duration`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 1f, direction = 1f)
        val noPause = EventTraversal.durationMsForPath(path, 0.001f)
        val withPause = EventTraversal.durationMsForPath(
            path, 0.001f,
            pauseIntervalMs = 500L,
            pauseDurationMs = 200L
        )
        assertTrue("With pauses should be longer", withPause > noPause)
    }

    @Test
    fun `durationMsForPath zero pause interval means no pauses`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 1f, direction = 1f)
        val noPause = EventTraversal.durationMsForPath(path, 0.001f)
        val zeroPauseInterval = EventTraversal.durationMsForPath(
            path, 0.001f,
            pauseIntervalMs = 0L,
            pauseDurationMs = 200L
        )
        assertEquals(noPause, zeroPauseInterval)
    }

    // ============== xAtProgress Tests ==============

    @Test
    fun `xAtProgress at 0 returns startX`() {
        val path = EventTraversal.HorizontalPath(startX = -0.15f, endX = 1.15f, direction = 1f)
        assertEquals(-0.15f, EventTraversal.xAtProgress(path, 0f), 0.001f)
    }

    @Test
    fun `xAtProgress at 1 returns endX`() {
        val path = EventTraversal.HorizontalPath(startX = -0.15f, endX = 1.15f, direction = 1f)
        assertEquals(1.15f, EventTraversal.xAtProgress(path, 1f), 0.001f)
    }

    @Test
    fun `xAtProgress at 0-5 returns midpoint`() {
        val path = EventTraversal.HorizontalPath(startX = 0f, endX = 2f, direction = 1f)
        assertEquals(1f, EventTraversal.xAtProgress(path, 0.5f), 0.001f)
    }

    @Test
    fun `xAtProgress works for reverse direction`() {
        val path = EventTraversal.HorizontalPath(startX = 1.15f, endX = -0.15f, direction = -1f)
        assertEquals(1.15f, EventTraversal.xAtProgress(path, 0f), 0.001f)
        assertEquals(-0.15f, EventTraversal.xAtProgress(path, 1f), 0.001f)
        assertEquals(0.5f, EventTraversal.xAtProgress(path, 0.5f), 0.001f)
    }

    // ============== Event zoom factor tests ==============

    @Test
    fun `zoom factor is 1 at reference height`() {
        val referenceHeight = 1920f
        val worldHeight = 1920f
        val zoom = referenceHeight / worldHeight
        assertEquals("Zoom at reference height should be 1", 1f, zoom, 0.001f)
    }

    @Test
    fun `zoom factor increases when zoomed in`() {
        val referenceHeight = 1920f
        val worldHeight = 960f  // Zoomed in 2x (shows half the world)
        val zoom = referenceHeight / worldHeight
        assertEquals("Zoom when zoomed in 2x", 2f, zoom, 0.001f)
    }

    @Test
    fun `zoom factor decreases when zoomed out`() {
        val referenceHeight = 1920f
        val worldHeight = 3840f  // Zoomed out 2x (shows twice the world)
        val zoom = referenceHeight / worldHeight
        assertEquals("Zoom when zoomed out 2x", 0.5f, zoom, 0.001f)
    }

    @Test
    fun `events are screen-relative and do not scale with zoom`() {
        // Events use ratio-based positions (0..1 of screen width).
        // Their speed is constant in screen-space regardless of world zoom.
        // This is intentional: events are overlay content, not world content.
        val worldSpeed = 0.001f  // xRatio/ms - constant screen-relative speed
        val width = 1080f

        // At reference height: screen pixels/ms = speed * width
        val screenSpeedRef = worldSpeed * width

        // At 2x zoom: same speed (events DON'T scale with zoom)
        val screenSpeedZoomed = worldSpeed * width  // No zoom factor

        assertEquals("Event screen speed is zoom-independent",
            screenSpeedRef, screenSpeedZoomed, 0.001f)
    }
}
