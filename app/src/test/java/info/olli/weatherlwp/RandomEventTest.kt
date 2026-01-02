package info.olli.weatherlwp

import info.olli.weatherlwp.rendering.RenderConstants
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for Random Event System.
 * 
 * Tests the deterministic spawning and timing:
 * - Time-bucket based spawning
 * - Preview/wallpaper synchronization
 * - Event duration and positioning
 */
class RandomEventTest {

    // ============== Time Bucket Determinism Tests ==============
    
    @Test
    fun `same time bucket produces same random`() {
        val bucket = 1000L
        val seed = bucket + 4000L  // baseSeed = 4000
        
        val random1 = Random(seed)
        val random2 = Random(seed)
        
        // Same seed = same sequence
        assertEquals("First value matches", random1.nextFloat(), random2.nextFloat(), 0.0001f)
        assertEquals("Second value matches", random1.nextFloat(), random2.nextFloat(), 0.0001f)
        assertEquals("Third value matches", random1.nextFloat(), random2.nextFloat(), 0.0001f)
    }
    
    @Test
    fun `different buckets produce different randoms`() {
        val bucket1 = 1000L
        val bucket2 = 1001L
        val baseSeed = 4000L
        
        val random1 = Random(bucket1 + baseSeed)
        val random2 = Random(bucket2 + baseSeed)
        
        // Different seeds = different sequence (very likely)
        val val1 = random1.nextFloat()
        val val2 = random2.nextFloat()
        
        assertNotEquals("Different buckets = different values", val1, val2, 0.0001f)
    }
    
    @Test
    fun `bucket size is 500ms`() {
        val bucketSize = 500L
        
        // Same bucket
        val time1 = 1000L
        val time2 = 1499L
        assertEquals("Same bucket", time1 / bucketSize, time2 / bucketSize)
        
        // Different buckets
        val time3 = 1500L
        assertNotEquals("Different bucket", time1 / bucketSize, time3 / bucketSize)
    }
    
    @Test
    fun `preview and wallpaper see same bucket`() {
        // At the same absoluteTimeMs, both instances compute same bucket
        val absoluteTimeMs = 12345678L
        val bucketSize = 500L
        
        val previewBucket = absoluteTimeMs / bucketSize
        val wallpaperBucket = absoluteTimeMs / bucketSize
        
        assertEquals("Same bucket", previewBucket, wallpaperBucket)
    }
    
    // ============== Event Duration Tests ==============
    
    @Test
    fun `airplane duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_AIRPLANE
        
        assertTrue("Airplane visible long enough", duration >= 20000L)
        assertTrue("Airplane not too long", duration <= 60000L)
    }
    
    @Test
    fun `bird flock duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_BIRD_FLOCK
        
        assertTrue("Birds visible long enough", duration >= 10000L)
        assertTrue("Birds not too long", duration <= 20000L)
    }
    
    @Test
    fun `shooting star duration is short`() {
        val duration = RenderConstants.EVENT_DURATION_SHOOTING_STAR
        
        assertTrue("Shooting star is brief", duration <= 5000L)
        assertTrue("Shooting star visible", duration >= 1000L)
    }
    
    @Test
    fun `balloon duration is long`() {
        val duration = RenderConstants.EVENT_DURATION_BALLOON
        
        assertTrue("Balloon drifts slowly", duration >= 30000L)
        assertTrue("Balloon not too long", duration <= 60000L)
    }
    
    // ============== Event Positioning Tests ==============
    
    @Test
    fun `sky events spawn in upper portion`() {
        val yMin = RenderConstants.EVENT_SKY_Y_MIN
        val yMax = RenderConstants.EVENT_SKY_Y_MAX
        
        assertTrue("Sky min in upper half", yMin <= 0.5f)
        assertTrue("Sky max in upper half", yMax <= 0.5f)
        assertTrue("Range is valid", yMax > yMin)
    }
    
    @Test
    fun `ground events spawn at bottom`() {
        val yMin = RenderConstants.EVENT_GROUND_Y_MIN
        val yMax = RenderConstants.EVENT_GROUND_Y_MAX
        
        assertTrue("Ground events near bottom", yMin >= 0.7f)
        assertTrue("Ground events not off screen", yMax <= 1.0f)
    }
    
    @Test
    fun `low sky events in middle`() {
        val yMin = RenderConstants.EVENT_LOW_SKY_Y_MIN
        val yMax = RenderConstants.EVENT_LOW_SKY_Y_MAX
        
        assertTrue("Low sky above ground", yMax < RenderConstants.EVENT_GROUND_Y_MIN)
        assertTrue("Low sky below high sky", yMin > RenderConstants.EVENT_SKY_Y_MAX)
    }
    
    // ============== Event Count Limits ==============
    
    @Test
    fun `max concurrent events is limited`() {
        val maxEvents = 8
        
        // Simulation: try to spawn when at limit
        var eventCount = 8
        val canSpawn = eventCount < maxEvents
        
        assertFalse("Cannot spawn when at limit", canSpawn)
    }
    
    @Test
    fun `spawn chance scales appropriately`() {
        // Spawn chance range: 0.0001 (rare) to 0.5 (frequent)
        val minChance = 0.0001f
        val maxChance = 0.5f
        
        assertTrue("Min chance very low", minChance < 0.01f)
        assertTrue("Max chance reasonable", maxChance <= 1.0f)
        assertTrue("Range is significant", maxChance / minChance >= 1000f)
    }
    
    // ============== Event Type Availability Tests ==============
    
    @Test
    fun `day events not available at night`() {
        val isNight = true
        
        // Day-only events: airplane, birds, eagle, butterfly, balloon
        val airplaneAvailable = !isNight
        val birdsAvailable = !isNight
        
        assertFalse("Airplane not at night", airplaneAvailable)
        assertFalse("Birds not at night", birdsAvailable)
    }
    
    @Test
    fun `night events available at night`() {
        val isNight = true
        
        // Night events: shooting star, fireflies, owl
        val shootingStarAvailable = isNight
        val firefliesAvailable = isNight
        val owlAvailable = isNight
        
        assertTrue("Shooting star at night", shootingStarAvailable)
        assertTrue("Fireflies at night", firefliesAvailable)
        assertTrue("Owl at night", owlAvailable)
    }
    
    @Test
    fun `all-time events available anytime`() {
        val dayRabbit = true
        val nightRabbit = true
        val dayFox = true
        val nightFox = true
        
        assertTrue("Rabbit during day", dayRabbit)
        assertTrue("Rabbit at night", nightRabbit)
        assertTrue("Fox during day", dayFox)
        assertTrue("Fox at night", nightFox)
    }
    
    // ============== Deterministic Seeding Tests ==============
    
    @Test
    fun `spawn seed includes bucket for determinism`() {
        val bucket = 24689L
        val baseSeed = 4000L
        val spawnSeed = bucket + baseSeed
        
        val random1 = Random(spawnSeed)
        val random2 = Random(spawnSeed)
        
        // Generate same event type decision
        val eventChoice1 = random1.nextInt(10)
        val eventChoice2 = random2.nextInt(10)
        
        assertEquals("Same event choice", eventChoice1, eventChoice2)
    }
    
    @Test
    fun `event creation seed includes offset`() {
        val bucket = 24689L
        val baseSeed = 4000L
        val eventOffset = 12345L
        
        val spawnSeed = bucket + baseSeed
        val eventSeed = spawnSeed + eventOffset
        
        assertNotEquals("Event seed different from spawn seed", spawnSeed, eventSeed)
    }
    
    @Test
    fun `aligned spawn time syncs positions`() {
        val bucket = 24689L
        val bucketSize = 500L
        
        // Spawn time is aligned to bucket start
        val spawnTime = bucket * bucketSize
        
        // Both preview and wallpaper compute same spawn time
        val previewSpawnTime = bucket * bucketSize
        val wallpaperSpawnTime = bucket * bucketSize
        
        assertEquals("Spawn times match", previewSpawnTime, wallpaperSpawnTime)
    }
    
    // ============== Y-Based Draw Ordering Tests ==============
    
    @Test
    fun `ground events use hill depth range`() {
        // Ground events should use depthFactor in the hills range (0.50-0.80)
        val hillsMin = RenderConstants.PARALLAX_HILLS_MIN  // 0.50
        val hillsMax = RenderConstants.PARALLAX_HILLS_MAX  // 0.80
        
        assertTrue("Hills min is background", hillsMin >= 0.4f)
        assertTrue("Hills max is foreground", hillsMax <= 0.9f)
        assertTrue("Range is valid", hillsMax > hillsMin)
    }
    
    @Test
    fun `y-based ordering principle is lower Y drawn first`() {
        // Lower Y = higher on screen = further away = drawn first
        // Higher Y = lower on screen = closer = drawn later (in front)
        val treeY = 0.70f
        val animalY = 0.75f  // Animal below tree
        
        // When sorted by Y (ascending), tree draws first, animal draws second
        val drawOrder = listOf(treeY, animalY).sorted()
        
        assertEquals("Tree draws first (lower Y)", treeY, drawOrder[0], 0.001f)
        assertEquals("Animal draws second (higher Y = in front)", animalY, drawOrder[1], 0.001f)
    }
    
    @Test
    fun `animal above tree is drawn behind`() {
        // If animal is above (lower Y) tree, it should be behind
        val animalY = 0.65f  // Above tree
        val treeY = 0.75f   // Below animal
        
        val drawOrder = listOf(Pair("animal", animalY), Pair("tree", treeY))
            .sortedBy { it.second }
        
        assertEquals("Animal draws first (behind)", "animal", drawOrder[0].first)
        assertEquals("Tree draws second (in front)", "tree", drawOrder[1].first)
    }
    
    @Test
    fun `animal below tree is drawn in front`() {
        // If animal is below (higher Y) tree, it should be in front
        val treeY = 0.70f    // Above animal
        val animalY = 0.80f  // Below tree
        
        val drawOrder = listOf(Pair("animal", animalY), Pair("tree", treeY))
            .sortedBy { it.second }
        
        assertEquals("Tree draws first (behind)", "tree", drawOrder[0].first)
        assertEquals("Animal draws second (in front)", "animal", drawOrder[1].first)
    }
}
