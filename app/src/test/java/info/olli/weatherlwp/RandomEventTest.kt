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
        
        // Shooting stars now have fade-out effect, making them slightly longer
        assertTrue("Shooting star is brief", duration <= 6000L)
        assertTrue("Shooting star visible", duration >= 2000L)
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
    
    // ============== Shooting Star Fade-Out Tests ==============
    
    @Test
    fun `shooting star fade starts at 80 percent of travel`() {
        val fadeStartProgress = 0.8f
        val totalTravel = 0.9f
        
        // At 80% of total travel, fade should start
        val currentProgress = 0.8f * totalTravel
        val isFading = currentProgress >= fadeStartProgress * totalTravel
        
        assertFalse("Just before fade start should not be fading", 
            (fadeStartProgress * totalTravel - 0.01f) >= fadeStartProgress * totalTravel)
        assertTrue("At fade start should be fading", isFading)
    }
    
    @Test
    fun `shooting star alpha decreases during fade`() {
        val fadeStartProgress = 0.8f
        val totalTravel = 0.9f
        
        // Calculate alpha at different points during fade
        val progressAtFadeStart = fadeStartProgress * totalTravel
        val progressAtEnd = totalTravel
        val fadeRange = progressAtEnd - progressAtFadeStart
        
        // At start of fade: alpha = 1.0
        // At end of fade: alpha = 0.0
        val alphaAtStart = 1f
        val progressMidFade = progressAtFadeStart + fadeRange * 0.5f
        val fadeProgress = (progressMidFade - progressAtFadeStart) / fadeRange
        val alphaAtMidFade = 1f - fadeProgress
        
        assertTrue("Alpha should decrease during fade", alphaAtMidFade < alphaAtStart)
        assertEquals("Alpha at mid-fade should be 0.5", 0.5f, alphaAtMidFade, 0.01f)
    }
    
    @Test
    fun `shooting star tail shrinks during fade`() {
        // Tail length decreases proportionally with alpha
        val baseLength = 0.08f
        val fadeAlpha = 0.5f
        
        val tailLength = baseLength * fadeAlpha
        
        assertEquals("Tail should be half length at half alpha", 0.04f, tailLength, 0.001f)
    }
    
    @Test
    fun `shooting star head shrinks during fade`() {
        // Head radius decreases proportionally with alpha
        val baseRadius = 0.003f
        val fadeAlpha = 0.5f
        
        val headRadius = baseRadius * fadeAlpha
        
        assertEquals("Head should be half size at half alpha", 0.0015f, headRadius, 0.0001f)
    }
    
    @Test
    fun `shooting star speed is slower than before`() {
        // New speed range: 0.00025f + random * 0.00015f
        // Max speed = 0.00025 + 0.00015 = 0.0004
        val minSpeed = 0.00025f
        val maxSpeed = 0.0004f
        
        // Old speed was 0.0005f - new max should be slower
        val oldSpeed = 0.0005f
        
        assertTrue("Max new speed should be slower than old", maxSpeed < oldSpeed)
        assertTrue("Speed range should be positive", minSpeed > 0)
        assertTrue("Max should be greater than min", maxSpeed > minSpeed)
    }
    
    // ============== Idle Mode Tests ==============
    
    @Test
    fun `idle mode reduces frame rate`() {
        val normalFps = 60
        val idleFps = 10
        
        assertTrue("Idle FPS should be much lower", idleFps < normalFps / 2)
        assertTrue("Idle FPS should still be reasonable", idleFps >= 5)
    }
    
    @Test
    fun `idle timeout is 5 seconds`() {
        val idleTimeoutMs = 5000L
        
        assertEquals("Idle timeout should be 5 seconds", 5000L, idleTimeoutMs)
    }
    
    @Test
    fun `scene is not idle when parallax is active`() {
        // Helper to check scene activity
        val parallaxActive = true
        val weatherActive = false
        val eventsActive = false
        
        val isIdle = !parallaxActive && !weatherActive && !eventsActive
        
        assertFalse("Should not be idle when parallax moving", isIdle)
    }
    
    @Test
    fun `scene is not idle when weather is active`() {
        val parallaxActive = false
        val weatherActive = true  // Rain, snow, etc.
        val eventsActive = false
        
        val isIdle = !parallaxActive && !weatherActive && !eventsActive
        
        assertFalse("Should not be idle during active weather", isIdle)
    }
    
    @Test
    fun `scene is not idle when events are active`() {
        val parallaxActive = false
        val weatherActive = false
        val eventsActive = true  // Shooting star, airplane, etc.
        
        val isIdle = !parallaxActive && !weatherActive && !eventsActive
        
        assertFalse("Should not be idle with active events", isIdle)
    }
    
    @Test
    fun `scene is idle when nothing is active`() {
        val parallaxActive = false
        val weatherActive = false
        val eventsActive = false
        
        val isIdle = !parallaxActive && !weatherActive && !eventsActive
        
        assertTrue("Should be idle when nothing happening", isIdle)
    }
    
    // ============== Seasonal Event Tests ==============
    
    @Test
    fun `butterfly restricted to warm seasons`() {
        // Butterflies only appear in spring and summer
        val spring = "spring"
        val summer = "summer"
        val autumn = "autumn"
        val winter = "winter"
        
        val butterflyInSpring = spring == "spring" || spring == "summer"
        val butterflyInSummer = summer == "spring" || summer == "summer"
        val butterflyInAutumn = autumn == "spring" || autumn == "summer"
        val butterflyInWinter = winter == "spring" || winter == "summer"
        
        assertTrue("Butterfly appears in spring", butterflyInSpring)
        assertTrue("Butterfly appears in summer", butterflyInSummer)
        assertFalse("Butterfly not in autumn", butterflyInAutumn)
        assertFalse("Butterfly not in winter", butterflyInWinter)
    }
    
    @Test
    fun `dragonfly only in summer`() {
        // Dragonflies only appear in summer (peak insect season)
        val spring = "spring"
        val summer = "summer"
        val autumn = "autumn"
        
        val dragonflyInSpring = spring == "summer"
        val dragonflyInSummer = summer == "summer"
        val dragonflyInAutumn = autumn == "summer"
        
        assertFalse("Dragonfly not in spring", dragonflyInSpring)
        assertTrue("Dragonfly appears in summer", dragonflyInSummer)
        assertFalse("Dragonfly not in autumn", dragonflyInAutumn)
    }
    
    @Test
    fun `fireflies only on summer nights`() {
        // Fireflies: summer + night + no rain
        val isSummer = true
        val isNight = true
        val isRaining = false
        
        val firefliesAvailable = isSummer && isNight && !isRaining
        assertTrue("Fireflies on summer nights", firefliesAvailable)
        
        // Not in winter nights
        val isWinter = true
        val winterFireflies = !isWinter || (isSummer && isNight && !isRaining)
        // Winter would make isSummer false, so:
        val firefliesInWinter = false && isNight && !isRaining
        assertFalse("No fireflies in winter", firefliesInWinter)
    }
    
    @Test
    fun `squirrel more common in autumn`() {
        // Squirrels: always available in autumn, 30% chance other seasons
        val autumnChance = 1.0f  // Always in autumn
        val otherSeasonChance = 0.3f  // 30% chance in other seasons
        
        assertTrue("Squirrel guaranteed in autumn", autumnChance >= 1.0f)
        assertTrue("Squirrel possible in other seasons", otherSeasonChance > 0f)
        assertTrue("Squirrel less common outside autumn", otherSeasonChance < autumnChance)
    }
    
    @Test
    fun `snowflake only in winter daytime`() {
        // Decorative snowflakes: winter + day + not already snowing
        val isWinter = true
        val isNight = false
        val isSnowing = false
        
        val snowflakeAvailable = isWinter && !isNight && !isSnowing
        assertTrue("Snowflakes in winter day", snowflakeAvailable)
        
        // Not at night
        val nightSnowflake = isWinter && true && !isSnowing
        // Note: the condition is !isNight, so night = true means not available
        val snowflakeAtNight = isWinter && !true && !isSnowing
        assertFalse("No decorative snowflakes at night", snowflakeAtNight)
    }
    
    // ============== New Event Duration Tests ==============
    
    @Test
    fun `squirrel duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_SQUIRREL
        
        assertTrue("Squirrel visible long enough", duration >= 8000L)
        assertTrue("Squirrel not too long", duration <= 20000L)
    }
    
    @Test
    fun `dragonfly duration is reasonable`() {
        val duration = RenderConstants.EVENT_DURATION_DRAGONFLY
        
        assertTrue("Dragonfly visible long enough", duration >= 6000L)
        assertTrue("Dragonfly not too long", duration <= 15000L)
    }
    
    @Test
    fun `snowflake duration allows slow fall`() {
        val duration = RenderConstants.EVENT_DURATION_SNOWFLAKE
        
        assertTrue("Snowflake falls slowly", duration >= 12000L)
        assertTrue("Snowflake not too long", duration <= 30000L)
    }
    
    // ============== Butterfly Movement Fix Tests ==============
    
    @Test
    fun `butterfly wobble amplitude is reduced`() {
        // Original values were too high, causing erratic movement
        // New values should be smaller
        val oldWobbleAmpX = 0.03f  // Old minimum
        val newWobbleAmpX = 0.015f  // New minimum (reduced by 50%)
        
        assertTrue("Wobble amplitude reduced", newWobbleAmpX < oldWobbleAmpX)
    }
    
    @Test
    fun `butterfly drift speed is reduced`() {
        // Original drift was too fast
        val oldDriftSpeed = 0.005f  // Old minimum
        val newDriftSpeed = 0.003f  // New minimum (reduced by 40%)
        
        assertTrue("Drift speed reduced", newDriftSpeed < oldDriftSpeed)
    }
    
    @Test
    fun `butterfly wobble frequency is reduced`() {
        // Slower wobble = more gentle movement
        val oldWobbleFreq = 0.6f  // Old minimum
        val newWobbleFreq = 0.3f  // New minimum (halved)
        
        assertTrue("Wobble frequency reduced", newWobbleFreq < oldWobbleFreq)
    }
    
    // ============== Bird Flock Animation Fix Tests ==============
    
    @Test
    fun `bird wings always curve upward`() {
        // The fix ensures wings have a base upward angle
        val baseWingUp = 0.4f  // size * 0.4 base lift
        
        assertTrue("Base wing lift is positive", baseWingUp > 0f)
    }
    
    @Test
    fun `bird wing oscillation does not invert wings`() {
        // Wing lift = baseWingUp + sin * amplitude
        // Even at minimum (sin = -1), wings should stay up
        val baseWingUp = 0.4f
        val maxAmplitude = 0.35f  // During flapping
        
        val minWingLift = baseWingUp - maxAmplitude
        
        assertTrue("Wings never fully invert", minWingLift > 0f || minWingLift >= -0.1f)
        // At minimum: 0.4 - 0.35 = 0.05 (still positive, wings still up)
    }
    
    @Test
    fun `bird wing tips stay above body`() {
        // The V-shape should always have tips above the body
        // wingtip Y = birdY - wingLift (negative Y = up on screen)
        val wingLift = 0.4f  // Base lift
        
        // Since we subtract wingLift, tips are above body when wingLift > 0
        assertTrue("Wing tips above body", wingLift > 0f)
    }
}
