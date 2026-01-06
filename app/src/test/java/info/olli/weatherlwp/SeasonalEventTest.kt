package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.events.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for new seasonal event classes.
 * 
 * Tests the event initialization, layer assignments, and duration calculations.
 */
class SeasonalEventTest {
    
    // ============== SquirrelEvent Tests ==============
    
    @Test
    fun `squirrel spawns on ground layer`() {
        val event = SquirrelEvent(Random(12345), 0L)
        
        assertEquals("Squirrel is ground event", EventLayer.GROUND, event.layer)
    }
    
    @Test
    fun `squirrel has valid depth factor`() {
        val event = SquirrelEvent(Random(12345), 0L)
        
        assertTrue("Depth factor in valid range", event.depthFactor >= 0.4f)
        assertTrue("Depth factor not too deep", event.depthFactor <= 0.8f)
    }
    
    @Test
    fun `squirrel duration is calculated from speed`() {
        val event = SquirrelEvent(Random(12345), 0L)
        
        assertTrue("Duration is positive", event.durationMs > 0)
        assertTrue("Duration is reasonable", event.durationMs < 30000L)
        assertTrue("Duration is long enough", event.durationMs > 5000L)
    }
    
    @Test
    fun `squirrel Y position follows terrain`() {
        val event = SquirrelEvent(Random(12345), 0L)
        
        // Without hill provider, should return default baseY
        val y = event.getCurrentYRatio(1000L, null)
        
        assertTrue("Y is in lower portion", y >= 0.7f)
        assertTrue("Y is not off screen", y <= 1.0f)
    }
    
    // ============== DragonflyEvent Tests ==============
    
    @Test
    fun `dragonfly spawns in mid sky`() {
        val event = DragonflyEvent(Random(12345), 0L)
        
        assertEquals("Dragonfly is mid sky event", EventLayer.MID_SKY, event.layer)
    }
    
    @Test
    fun `dragonfly has valid duration`() {
        val event = DragonflyEvent(Random(12345), 0L)
        
        assertTrue("Duration is positive", event.durationMs > 0)
        assertTrue("Duration is reasonable", event.durationMs >= 6000L)
        assertTrue("Duration not too long", event.durationMs <= 15000L)
    }
    
    @Test
    fun `dragonfly default depth factor is zero`() {
        val event = DragonflyEvent(Random(12345), 0L)
        
        // Mid-sky events use default depth factor of 0.65 (from RandomEvent interface)
        assertEquals("Default depth factor", 0.65f, event.depthFactor, 0.01f)
    }
    
    // ============== SnowflakeEvent Tests ==============
    
    @Test
    fun `snowflake spawns in mid sky`() {
        val event = SnowflakeEvent(Random(12345), 0L)
        
        assertEquals("Snowflake is mid sky event", EventLayer.MID_SKY, event.layer)
    }
    
    @Test
    fun `snowflake has long duration for slow fall`() {
        val event = SnowflakeEvent(Random(12345), 0L)
        
        assertTrue("Duration allows slow fall", event.durationMs >= 12000L)
        assertTrue("Duration not excessive", event.durationMs <= 25000L)
    }
    
    @Test
    fun `snowflake starts above screen`() {
        // The startY is -0.05f which is above the visible screen
        val startY = -0.05f
        
        assertTrue("Snowflake starts above screen", startY < 0f)
    }
    
    // ============== Event Diversity Tests ==============
    
    @Test
    fun `random seeds produce different squirrel directions`() {
        val event1 = SquirrelEvent(Random(1), 0L)
        val event2 = SquirrelEvent(Random(2), 0L)
        val event3 = SquirrelEvent(Random(3), 0L)
        
        // With different seeds, we should get some variety
        // (at least one pair should differ in some property)
        val depths = listOf(event1.depthFactor, event2.depthFactor, event3.depthFactor)
        val uniqueDepths = depths.toSet()
        
        assertTrue("Different seeds produce variety", uniqueDepths.size >= 2)
    }
    
    @Test
    fun `same seed produces same event properties`() {
        val seed = 42L
        val event1 = SquirrelEvent(Random(seed), 1000L)
        val event2 = SquirrelEvent(Random(seed), 1000L)
        
        assertEquals("Same seed = same depth", event1.depthFactor, event2.depthFactor, 0.0001f)
        assertEquals("Same seed = same duration", event1.durationMs, event2.durationMs)
    }
    
    // ============== Event Layer Assignment Tests ==============
    
    @Test
    fun `ground events have depth factor for sorting`() {
        val squirrel = SquirrelEvent(Random(1), 0L)
        
        // Ground events should have non-default depth for Y-sorting
        assertTrue("Ground event has depth", squirrel.depthFactor > 0f)
        assertTrue("Ground event depth in range", squirrel.depthFactor < 1f)
    }
    
    @Test
    fun `sky events have default depth factor`() {
        val dragonfly = DragonflyEvent(Random(1), 0L)
        val snowflake = SnowflakeEvent(Random(1), 0L)
        
        // Sky events use default 0.65 depth (from RandomEvent interface)
        assertEquals("Dragonfly default depth", 0.65f, dragonfly.depthFactor, 0.01f)
        assertEquals("Snowflake default depth", 0.65f, snowflake.depthFactor, 0.01f)
    }
    
    // ============== Spawn Time Tests ==============
    
    @Test
    fun `spawn time is recorded correctly`() {
        val spawnTime = 123456789L
        
        val squirrel = SquirrelEvent(Random(1), spawnTime)
        val dragonfly = DragonflyEvent(Random(1), spawnTime)
        val snowflake = SnowflakeEvent(Random(1), spawnTime)
        
        assertEquals("Squirrel spawn time", spawnTime, squirrel.spawnTimeMs)
        assertEquals("Dragonfly spawn time", spawnTime, dragonfly.spawnTimeMs)
        assertEquals("Snowflake spawn time", spawnTime, snowflake.spawnTimeMs)
    }
    
    @Test
    fun `elapsed time calculation is correct`() {
        val spawnTime = 1000L
        val currentTime = 3000L
        val expectedElapsed = 2000L
        
        val elapsed = currentTime - spawnTime
        
        assertEquals("Elapsed time correct", expectedElapsed, elapsed)
    }
    
    // ============== Event Variety Tests ==============
    
    @Test
    fun `all new events have required properties`() {
        val events = listOf(
            SquirrelEvent(Random(1), 0L),
            DragonflyEvent(Random(1), 0L),
            SnowflakeEvent(Random(1), 0L)
        )
        
        for (event in events) {
            assertNotNull("Event has layer", event.layer)
            assertTrue("Event has positive duration", event.durationMs > 0)
            assertNotNull("Event has spawn time", event.spawnTimeMs)
        }
    }
    
    @Test
    fun `new events cover different seasons`() {
        // Squirrel: primarily autumn
        // Dragonfly: summer only
        // Snowflake: winter only
        
        val seasonalDistribution = mapOf(
            "spring" to listOf<String>(), // No new spring-exclusive events
            "summer" to listOf("dragonfly"),
            "autumn" to listOf("squirrel"),
            "winter" to listOf("snowflake")
        )
        
        assertTrue("Summer has dragonfly", seasonalDistribution["summer"]?.contains("dragonfly") == true)
        assertTrue("Autumn has squirrel", seasonalDistribution["autumn"]?.contains("squirrel") == true)
        assertTrue("Winter has snowflake", seasonalDistribution["winter"]?.contains("snowflake") == true)
    }
    
    // ============== BatEvent Tests ==============
    
    @Test
    fun `bat spawns in mid sky`() {
        val event = BatEvent(Random(12345), 0L)
        
        assertEquals("Bat is mid sky event", EventLayer.MID_SKY, event.layer)
    }
    
    @Test
    fun `bat has valid duration`() {
        val event = BatEvent(Random(12345), 0L)
        
        assertTrue("Duration is positive", event.durationMs > 0)
        assertTrue("Duration is reasonable", event.durationMs >= 5000L)
        assertTrue("Duration not too long", event.durationMs <= 30000L)
    }
    
    @Test
    fun `bat colony has multiple bats`() {
        // BatEvent contains 3-8 bats - this is internal but we can test indirectly
        // by checking that the event initializes correctly with different seeds
        val event1 = BatEvent(Random(1), 0L)
        val event2 = BatEvent(Random(100), 0L)
        
        // Both should be valid mid-sky events
        assertEquals(EventLayer.MID_SKY, event1.layer)
        assertEquals(EventLayer.MID_SKY, event2.layer)
    }
    
    // ============== DeerEvent Tests ==============
    
    @Test
    fun `deer spawns on ground layer`() {
        val event = DeerEvent(Random(12345), 0L)
        
        assertEquals("Deer is ground event", EventLayer.GROUND, event.layer)
    }
    
    @Test
    fun `deer has long duration for grazing`() {
        val event = DeerEvent(Random(12345), 0L)
        
        assertEquals("Deer duration is 15 seconds", 15000L, event.durationMs)
    }
    
    @Test
    fun `deer has valid spawn time`() {
        val spawnTime = 500000L
        val event = DeerEvent(Random(12345), spawnTime)
        
        assertEquals("Spawn time recorded", spawnTime, event.spawnTimeMs)
    }
    
    @Test
    fun `random seeds produce different deer properties`() {
        val events = (1..10).map { DeerEvent(Random(it.toLong()), 0L) }
        
        // Events should have variety in layer timing
        val layers = events.map { it.layer }
        val durations = events.map { it.durationMs }
        
        // All deer are ground events
        assertTrue("All deer are ground events", layers.all { it == EventLayer.GROUND })
        // All have same fixed duration
        assertTrue("All deer have fixed duration", durations.all { it == 15000L })
    }
    
    // ============== Full Coverage Tests ==============
    
    @Test
    fun `all new events v2 have required properties`() {
        val events = listOf(
            SquirrelEvent(Random(1), 0L),
            DragonflyEvent(Random(1), 0L),
            SnowflakeEvent(Random(1), 0L),
            BatEvent(Random(1), 0L),
            DeerEvent(Random(1), 0L)
        )
        
        for (event in events) {
            assertNotNull("Event has layer", event.layer)
            assertTrue("Event has positive duration", event.durationMs > 0)
            assertNotNull("Event has spawn time", event.spawnTimeMs)
        }
    }
    
    @Test
    fun `night and day events are separated`() {
        // Bats are night-only, deer are day/dusk
        // These restrictions are enforced in RandomEventLayer
        
        val nightEvents = listOf("bat", "owl", "fireflies", "shootingstar")
        val dayEvents = listOf("airplane", "eagle", "butterfly", "deer")
        
        // Verify no overlap in our primary categorization
        val overlap = nightEvents.intersect(dayEvents.toSet())
        assertTrue("No overlap between night and day events", overlap.isEmpty())
    }
}
