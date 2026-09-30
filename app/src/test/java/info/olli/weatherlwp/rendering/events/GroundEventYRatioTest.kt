package info.olli.weatherlwp.rendering.events

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The Y used to sort ground events against trees must come from the same height-scaled
 * traversal as draw(). Canvas isn't available here, so these check the scaling contract
 * via the x each event hands to the terrain provider.
 */
class GroundEventYRatioTest {

    private val ref = RenderConstants.REFERENCE_HEIGHT
    private val spawn = 50_000L

    private fun walkers(): List<RandomEvent> = listOf(
        FoxEvent(Random(1), spawn),
        RabbitEvent(Random(2), spawn),
        SquirrelEvent(Random(3), spawn),
        DeerEvent(Random(4), spawn),
        DuckEvent(Random(5), spawn)
    )

    private fun sampledX(event: RandomEvent, timeMs: Long, screenHeight: Float): Float {
        var x = Float.NaN
        event.getCurrentYRatio(timeMs, { eventX, _ -> x = eventX; 0.8f }, screenHeight)
        return x
    }

    @Test
    fun `two-arg overload is the reference height`() {
        val provider: EventTerrainProvider = { x, d -> 0.7f + x * 0.1f + d * 0.01f }
        for (event in walkers() + listOf(FrogEvent(Random(6), spawn), FishJumpEvent(Random(7), spawn))) {
            for (t in longArrayOf(0L, 1_000L, 4_000L, 9_000L)) {
                assertEquals(
                    event.getCurrentYRatio(spawn + t, provider, ref),
                    event.getCurrentYRatio(spawn + t, provider),
                    0f
                )
            }
        }
    }

    @Test
    fun `preview anchor x is the reference-height traversal`() {
        for (event in walkers()) {
            for (t in longArrayOf(0L, 2_500L, 7_000L)) {
                assertEquals(event.getPreviewAnchorXRatio(spawn + t), sampledX(event, spawn + t, ref), 0f)
            }
        }
    }

    @Test
    fun `sort position uses height-scaled traversal`() {
        val half = ref / 2f
        val t = 6_000L
        // Linear walkers (and the squirrel, which scales before pausing): half height at 2t == reference at t.
        for (event in walkers().filter { it !is DeerEvent }) {
            assertEquals(
                event.javaClass.simpleName,
                sampledX(event, spawn + t, ref),
                sampledX(event, spawn + 2 * t, half),
                0f
            )
            assertNotEquals(sampledX(event, spawn + 2 * t, ref), sampledX(event, spawn + 2 * t, half))
        }
    }

    @Test
    fun `deer sort position covers less ground on shorter screens`() {
        val deer = DeerEvent(Random(4), spawn)
        val start = sampledX(deer, spawn, ref)
        val t = spawn + 10_000L
        val fullDistance = abs(sampledX(deer, t, ref) - start)
        val halfDistance = abs(sampledX(deer, t, ref / 2f) - start)
        assertTrue(fullDistance > 0f)
        assertTrue(halfDistance < fullDistance)
    }

    @Test
    fun `frog and fish sort position does not depend on screen height`() {
        val provider: EventTerrainProvider = { x, _ -> 0.7f + x * 0.1f }
        for (event in listOf(FrogEvent(Random(6), spawn), FishJumpEvent(Random(7), spawn))) {
            val t = spawn + event.durationMs / 2
            assertEquals(event.getCurrentYRatio(t, provider, ref), event.getCurrentYRatio(t, provider, 700f), 0f)
        }
    }

    @Test
    fun `fireflies sort where the swarm is anchored`() {
        val fireflies = FireflyEvent(Random(8), spawn)
        assertEquals(
            fireflies.getPreviewAnchorYRatio(spawn + 1_000L, null),
            fireflies.getCurrentYRatio(spawn + 1_000L, null, 900f),
            0f
        )
    }
}
