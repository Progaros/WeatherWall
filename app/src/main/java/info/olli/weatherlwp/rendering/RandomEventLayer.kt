package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.events.*
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.SeasonHelper
import info.olli.weatherlwp.weather.WeatherType
import kotlin.math.pow
import kotlin.random.Random

/**
 * Layer that manages random events like birds, planes, and animals.
 * Events spawn randomly and update/draw until they leave the screen.
 *
 * Event classes are in the events/ package for better organization.
 *
 * ## Overlay content — screen-relative, not world content
 *
 * Random events are intentionally **screen-relative overlay effects**.
 * They are not deterministic world content and are not scaled by world zoom.
 * Their movement uses ratio-based screen positions (0..1) with absolute-time
 * traversal for visual consistency across zoom levels and orientations.
 *
 * Random usage in event spawning is acceptable because events are temporary
 * overlays — they do not need world-coordinate determinism.
 *
 * Layer-level depth snapping uses [hillInfoWorldProvider] (world-space, primary)
 * with fallback to the deprecated [hillInfoProvider] (xRatio-based) for
 * terrain-following of ground events (e.g. animals walking along hills).
 */
class RandomEventLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.RANDOM_EVENTS
    
    // Thread-safe event list using volatile + immutable list pattern
    @Volatile private var events: List<RandomEvent> = emptyList()
    
    // Use a fixed base seed - actual randomness comes from absoluteTimeMs
    private val baseSeed = 4000L
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private var width = 0f
    private var height = 0f
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f
    
    // Ratio of visible viewport to extended world width (1.0 landscape, <1.0 portrait)
    private var viewportRatio = 1.0f

    // Cached per-frame ambient color (computed in update, used by multiple draw methods)
    private var cachedAmbientColor = 0
    private var eventsEnabled = true
    private var spawnChance = 0.001f
    
    /**
     * Hill height provider for ground events to follow terrain contour.
     * Set by TerrainLayer to provide hill Y at given X and depth.
     */
    @Deprecated("Use hillHeightWorldProvider (worldX-based) instead")
    var hillHeightProvider: HillHeightProvider? = null

    /** World-space hill height provider for ground events. */
    var hillHeightWorldProvider: HillHeightWorldProvider? = null
    
    /**
     * Hill info provider that returns the actual hill depthFactor for a given requested depth.
     * Used to snap event depth to actual hill layers for correct draw ordering.
     */
    @Deprecated("Use hillInfoWorldProvider (worldX-based) instead")
    var hillInfoProvider: ((xRatio: Float, requestedDepthFactor: Float) -> Float)? = null

    /** World-space hill info provider. Returns actual hill depthFactor for given worldX and requested depth. */
    var hillInfoWorldProvider: ((worldX: Float, requestedDepthFactor: Float) -> Float)? = null
    
    // Reusable ground-event entries to avoid per-frame Pair/lambda allocation.
    private val groundEventResults = ArrayList<TerrainLayer.GroundEventDrawEntry>(32)
    private val groundEventEntryPool = ArrayList<GroundEventDrawEntryImpl>(32)
    private var lastGroundEntryCount = 0

    // Frame draw context consumed by pooled GroundEventDrawEntryImpl.draw().
    private var groundDrawCanvas: Canvas? = null
    private var groundDrawState: SimulationState? = null
    private var groundDrawParallaxCalculator: ParallaxCalculator? = null
    private var groundDrawAmbientColor = Color.WHITE
    private var groundDrawAbsoluteTimeMs = 0L
    
    // Individual event toggles
    private val enabledEvents = mutableSetOf<String>()
    
    // Current season for seasonal event restrictions
    private var currentSeason = SeasonHelper.getCurrentCalendarSeasonKey()

    private inner class GroundEventDrawEntryImpl : TerrainLayer.GroundEventDrawEntry {
        var event: RandomEvent? = null
        private var eventYRatio = 0f

        override val yRatio: Float
            get() = eventYRatio

        fun bind(boundEvent: RandomEvent, yRatioValue: Float) {
            event = boundEvent
            eventYRatio = yRatioValue
        }

        override fun draw() {
            val currentEvent = event ?: return
            val canvas = groundDrawCanvas ?: return
            val state = groundDrawState ?: return
            val parallaxCalculator = groundDrawParallaxCalculator ?: return

            try {
                currentEvent.draw(
                    canvas,
                    paint,
                    strokePaint,
                    parallaxCalculator,
                    extendedWidth,
                    height,
                    state.timeSeconds,
                    groundDrawAmbientColor,
                    extendedOffset,
                    groundDrawAbsoluteTimeMs,
                    @Suppress("DEPRECATION")
                    hillHeightProvider
                )
            } catch (e: Exception) {
                android.util.Log.e("RandomEventLayer", "Error drawing event ${currentEvent.javaClass.simpleName}", e)
            }
        }
    }

    private fun ensureGroundEntryPoolSize(required: Int) {
        while (groundEventEntryPool.size < required) {
            groundEventEntryPool.add(GroundEventDrawEntryImpl())
        }
    }
    
    init {
        reloadSettings()
    }
    
    /**
     * Check if there are any active events currently playing.
     * Used for idle mode battery optimization.
     */
    fun hasActiveEvents(): Boolean {
        return events.isNotEmpty()
    }
    
    /**
     * Get the number of active events.
     * Used for debug overlay display.
     */
    fun getActiveEventCount(): Int {
        return events.size
    }
    
    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        // extendedWidth/extendedOffset retained for legacy RandomEvent API compatibility
        extendedWidth = w
        extendedOffset = 0f
        viewportRatio = 1.0f

        // Clear frame-scoped draw context and pooled entry references on geometry changes.
        // This prevents stale canvas/state/event references from surviving rotations.
        groundDrawCanvas = null
        groundDrawState = null
        groundDrawParallaxCalculator = null
        groundDrawAmbientColor = Color.WHITE
        groundDrawAbsoluteTimeMs = 0L
        groundEventResults.clear()

        var i = 0
        while (i < lastGroundEntryCount && i < groundEventEntryPool.size) {
            groundEventEntryPool[i].event = null
            i++
        }
        lastGroundEntryCount = 0
    }
    
    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!eventsEnabled || width <= 0) return

        cachedAmbientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        
        // Track if we need to create a new list (only allocate when events actually change)
        var needsListUpdate = false
        var currentEvents: MutableList<RandomEvent>? = null
        
        // Update existing events and remove finished ones
        for (event in events) {
            if (!event.update(deltaTimeMs, state, width, height)) {
                // Event finished - need to remove it
                if (currentEvents == null) {
                    currentEvents = events.toMutableList()
                }
                currentEvents.remove(event)
                needsListUpdate = true
            }
        }
        
        // Spawn new events using time-bucket based determinism
        // Each 500ms time bucket has a deterministic spawn decision
        // Both preview and wallpaper checking the same bucket will get the same result
        val currentBucket = state.absoluteTimeMs / 500
        if (currentBucket != lastSpawnBucket) {
            lastSpawnBucket = currentBucket
            
            // Use the time bucket as seed - all instances get same random sequence
            val spawnSeed = currentBucket + baseSeed
            val spawnRandom = Random(spawnSeed)
            
            // Spawn chance is already scaled appropriately in reloadSettings()
            // 0.0001 (rare) to 0.5 (very frequent) per 500ms bucket check
            val eventCount = currentEvents?.size ?: events.size
            if (spawnRandom.nextFloat() < spawnChance && eventCount < 8) {
                // Use bucket + offset as seed for event creation (deterministic)
                val eventRandom = Random(spawnSeed + 12345)
                val spawnTime = currentBucket * 500  // Aligned spawn time for position sync
                if (currentEvents == null) {
                    currentEvents = events.toMutableList()
                }
                spawnRandomEvent(state, currentEvents, eventRandom, spawnTime)
                needsListUpdate = true
            }
        }
        
        // Only update the volatile reference if something changed
        if (needsListUpdate && currentEvents != null) {
            events = currentEvents.toList()
        }
    }
    
    private var lastSpawnBucket = 0L
    
    private fun spawnRandomEvent(state: SimulationState, currentEvents: MutableList<RandomEvent>, random: Random, spawnTime: Long) {
        val availableEvents = mutableListOf<(Long) -> RandomEvent?>()
        val vr = viewportRatio
        
        // Check current season for seasonal restrictions
        val isSpring = currentSeason == "spring"
        val isSummer = currentSeason == "summer"
        val isAutumn = currentSeason == "autumn"
        val isWinter = currentSeason == "winter"
        val isWarmSeason = isSpring || isSummer
        
        // Day events (lambda takes spawnTime to create deterministic event)
        if ("airplane" in enabledEvents && !state.isNight) availableEvents.add { t -> AirplaneEvent(Random(t), t) }
        if ("birds" in enabledEvents && !state.isNight) availableEvents.add { t -> BirdFlockEvent(Random(t + 1), t) }
        if ("eagle" in enabledEvents && !state.isNight) availableEvents.add { t -> EagleEvent(Random(t + 2), t) }
        
        // Butterflies: Spring & Summer only (warm weather insects)
        if ("butterfly" in enabledEvents && !state.isNight && isWarmSeason) {
            availableEvents.add { t -> ButterflyEvent(Random(t + 3), t, vr) }
        }
        
        // Dragonflies: Summer only (peak insect season)
        if ("dragonfly" in enabledEvents && !state.isNight && isSummer) {
            availableEvents.add { t -> DragonflyEvent(Random(t + 11), t, vr) }
        }
        
        val isRaining = state.weather == WeatherType.RAIN || state.weather == WeatherType.THUNDERSTORM || state.weather == WeatherType.SNOW
        if ("balloon" in enabledEvents && !state.isNight && !isRaining) availableEvents.add { t -> HotAirBalloonEvent(Random(t + 4), t) }
        
        // Ground animals - some seasonal
        if ("rabbit" in enabledEvents) availableEvents.add { t -> RabbitEvent(Random(t + 5), t, vr) }
        if ("fox" in enabledEvents) availableEvents.add { t -> FoxEvent(Random(t + 6), t, vr) }
        
        // Squirrels: Autumn primarily (gathering nuts), but can appear year-round
        if ("squirrel" in enabledEvents && (isAutumn || random.nextFloat() < 0.3f)) {
            availableEvents.add { t -> SquirrelEvent(Random(t + 12), t, vr) }
        }
        
        // Night events
        val isClearEnough = state.cloudDensity == CloudDensity.LIGHT || state.cloudDensity == CloudDensity.MEDIUM
        if ("shootingstar" in enabledEvents && state.isNight && isClearEnough) availableEvents.add { t -> ShootingStarEvent(Random(t + 7), t) }
        
        // Fireflies: Summer nights only (warm nights)
        if ("fireflies" in enabledEvents && state.isNight && !isRaining && isSummer) {
            availableEvents.add { t -> FireflyEvent(Random(t + 8), t) }
        }
        
        if ("owl" in enabledEvents && state.isNight) availableEvents.add { t -> OwlEvent(Random(t + 9), t) }
        
        // Decorative snowflakes: Winter only, when not already snowing
        if ("snowflake" in enabledEvents && isWinter && state.weather != WeatherType.SNOW && !state.isNight) {
            availableEvents.add { t -> SnowflakeEvent(Random(t + 13), t) }
        }
        
        // Bats: Night only, year-round
        if ("bat" in enabledEvents && state.isNight) {
            availableEvents.add { t -> BatEvent(Random(t + 14), t) }
        }
        
        // Deer: Autumn/Winter primarily (more visible in sparse foliage)
        if ("deer" in enabledEvents && !isRaining && (isAutumn || isWinter || random.nextFloat() < 0.2f)) {
            availableEvents.add { t -> DeerEvent(Random(t + 15), t, vr) }
        }
        
        // Rare alien event - night only, very low chance
        if ("alien" in enabledEvents && state.isNight && random.nextFloat() < 0.05f) {
            availableEvents.add { t -> AlienEvent(Random(t + 10), t) }
        }
        
        // Fish: Day or dusk, non-winter (jump in warm/mild weather)
        if ("fish" in enabledEvents && !state.isNight && !isWinter && !isRaining) {
            availableEvents.add { t -> FishJumpEvent(Random(t + 16), t) }
        }
        
        // Ducks: Day, year-round (avoid storms)
        if ("duck" in enabledEvents && !state.isNight && !isRaining) {
            availableEvents.add { t -> DuckEvent(Random(t + 17), t, vr) }
        }
        
        // Frogs: Evening/dusk/warm nights, spring/summer
        if ("frog" in enabledEvents && !isWinter && !isAutumn && (state.isDusk || state.isNight)) {
            availableEvents.add { t -> FrogEvent(Random(t + 18), t) }
        }
        
        if (availableEvents.isEmpty()) return
        
        // Use deterministic selection based on spawnTime
        val selectionRandom = Random(spawnTime + 99999)
        val event = availableEvents[selectionRandom.nextInt(availableEvents.size)](spawnTime)
        event?.let { currentEvents.add(it) }
    }
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Default draw method draws all ground events (for backward compatibility)
        drawGroundEvents(canvas, state, parallaxCalculator)
    }
    
    /**
     * Draw far sky events (shooting stars, balloons, UFOs).
     * Call this BEFORE drawing mountains.
     */
    fun drawFarSkyEvents(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        drawEventsByLayer(canvas, state, parallaxCalculator, EventLayer.FAR_SKY)
    }
    
    /**
     * Draw mid sky events (birds, airplanes, eagles).
     * Call this AFTER drawing mountains, BEFORE drawing terrain/hills.
     */
    fun drawMidSkyEvents(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        drawEventsByLayer(canvas, state, parallaxCalculator, EventLayer.MID_SKY)
    }
    
    /**
     * Draw all ground events (legacy method).
     */
    fun drawGroundEvents(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        drawEventsByLayer(canvas, state, parallaxCalculator, EventLayer.GROUND)
    }
    
    /**
     * Draw ground events with depthFactor less than the given threshold.
     * Used to draw background animals before drawing trees at that depth.
     * @param maxDepth Events with depthFactor < maxDepth will be drawn
     */
    fun drawGroundEventsBeforeDepth(canvas: Canvas, state: SimulationState, 
                                     parallaxCalculator: ParallaxCalculator, maxDepth: Float) {
        drawFilteredEvents(canvas, state, parallaxCalculator) { event ->
            event.layer == EventLayer.GROUND && event.depthFactor < maxDepth
        }
    }
    
    /**
     * Draw ground events with depthFactor in the given range.
     * Used to draw animals between tree layers.
     * @param minDepth Events with depthFactor >= minDepth will be drawn
     * @param maxDepth Events with depthFactor < maxDepth will be drawn
     */
    fun drawGroundEventsInRange(canvas: Canvas, state: SimulationState, 
                                 parallaxCalculator: ParallaxCalculator, 
                                 minDepth: Float, maxDepth: Float) {
        drawFilteredEvents(canvas, state, parallaxCalculator) { event ->
            if (event.layer != EventLayer.GROUND) return@drawFilteredEvents false
            val effectiveDepth = hillInfoWorldProvider?.invoke(0f, event.depthFactor)
                ?: @Suppress("DEPRECATION") hillInfoProvider?.invoke(0.5f, event.depthFactor)
                ?: event.depthFactor
            effectiveDepth >= minDepth && effectiveDepth < maxDepth
        }
    }
    
    /**
     * Get ground events in range with their Y positions for Y-based sorting with trees.
      * Returns reusable draw entries to keep the hot path allocation-free.
     */
    fun getGroundEventsInRangeWithY(canvas: Canvas, state: SimulationState, 
                                      parallaxCalculator: ParallaxCalculator,
                                   minDepth: Float, maxDepth: Float): List<TerrainLayer.GroundEventDrawEntry> {
        if (width <= 0 || height <= 0 || !eventsEnabled) return emptyList()
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return emptyList()
        
        val ambientColor = cachedAmbientColor
        
        val result = groundEventResults
        result.clear()

        // Bind frame context once for pooled draw entries.
        groundDrawCanvas = canvas
        groundDrawState = state
        groundDrawParallaxCalculator = parallaxCalculator
        groundDrawAmbientColor = ambientColor
        groundDrawAbsoluteTimeMs = state.absoluteTimeMs

        ensureGroundEntryPoolSize(currentEvents.size)
        result.ensureCapacity(currentEvents.size)
        var resultCount = 0
        
        for (event in currentEvents) {
            if (event.layer == EventLayer.GROUND) {
                // Snap event's depthFactor to the actual hill it visually sits on
                val effectiveDepth = hillInfoWorldProvider?.invoke(0f, event.depthFactor)
                    ?: @Suppress("DEPRECATION") hillInfoProvider?.invoke(0.5f, event.depthFactor)
                    ?: event.depthFactor
                if (effectiveDepth >= minDepth && effectiveDepth < maxDepth) {
                    // Get the event's current Y position for depth sorting
                    // Events internally use xRatio-based positions, so we pass the legacy
                    // hillHeightProvider here. Full event migration to worldX is a future task.
                    @Suppress("DEPRECATION")
                    val eventY = event.getCurrentYRatio(groundDrawAbsoluteTimeMs, hillHeightProvider)
                    val entry = groundEventEntryPool[resultCount]
                    entry.bind(event, eventY)
                    result.add(entry)
                    resultCount++
                }
            }
        }

        // Clear stale references in pooled entries from prior calls.
        var staleIndex = resultCount
        while (staleIndex < lastGroundEntryCount) {
            groundEventEntryPool[staleIndex].event = null
            staleIndex++
        }
        lastGroundEntryCount = resultCount
        
        return result
    }
    
    /**
     * Draw ground events with depthFactor >= the given threshold.
     * Used to draw foreground animals after all trees.
     * @param minDepth Events with depthFactor >= minDepth will be drawn
     */
    fun drawGroundEventsAfterDepth(canvas: Canvas, state: SimulationState, 
                                    parallaxCalculator: ParallaxCalculator, minDepth: Float) {
        drawFilteredEvents(canvas, state, parallaxCalculator) { event ->
            event.layer == EventLayer.GROUND && event.depthFactor >= minDepth
        }
    }
    
    private fun drawEventsByLayer(canvas: Canvas, state: SimulationState, 
                                   parallaxCalculator: ParallaxCalculator, targetLayer: EventLayer) {
        drawFilteredEvents(canvas, state, parallaxCalculator) { event ->
            event.layer == targetLayer
        }
    }

    /**
     * Shared event drawing logic: iterates events matching [predicate] and draws each
     * with error handling. Eliminates repeated try-catch iteration boilerplate.
     */
    private inline fun drawFilteredEvents(
        canvas: Canvas,
        state: SimulationState,
        parallaxCalculator: ParallaxCalculator,
        predicate: (RandomEvent) -> Boolean
    ) {
        if (width <= 0 || height <= 0 || !eventsEnabled) return

        val currentEvents = events
        if (currentEvents.isEmpty()) return

        val ambientColor = cachedAmbientColor

        for (event in currentEvents) {
            if (predicate(event)) {
                try {
                    // Events use screen-relative ratio-based positions and speeds.
                    // This is intentional: events are overlay content whose apparent speed
                    // is independent of world zoom. No canvas scaling is applied.
                    event.draw(
                        canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height,
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        @Suppress("DEPRECATION")
                        if (event.layer == EventLayer.GROUND) hillHeightProvider else null
                    )
                } catch (e: Exception) {
                    android.util.Log.e("RandomEventLayer", "Error drawing event ${event.javaClass.simpleName}", e)
                }
            }
        }
    }
    
    /**
     * Debug method to spawn a random event immediately.
     * @param seed Optional seed for deterministic spawning (both preview and wallpaper get same event)
     */
    fun debugSpawnEvent(seed: Long = System.currentTimeMillis()) {
        val currentEvents = events.toMutableList()
        val random = Random(seed)
        
        val vr = viewportRatio
        val eventTypes = listOf<() -> RandomEvent>(
            { AirplaneEvent(random, seed) },
            { BirdFlockEvent(random, seed) },
            { EagleEvent(random, seed) },
            { ButterflyEvent(random, seed, vr) },
            { RabbitEvent(random, seed, vr) },
            { FoxEvent(random, seed, vr) },
            { ShootingStarEvent(random, seed) },
            { FireflyEvent(random, seed) },
            { OwlEvent(random, seed) },
            { HotAirBalloonEvent(random, seed) },
            { AlienEvent(random, seed) },
            { SquirrelEvent(random, seed, vr) },
            { DragonflyEvent(random, seed, vr) },
            { SnowflakeEvent(random, seed) },
            { BatEvent(random, seed) },
            { DeerEvent(random, seed, vr) },
            { FishJumpEvent(random, seed) },
            { DuckEvent(random, seed, vr) },
            { FrogEvent(random, seed) }
        )
        
        val event = eventTypes[random.nextInt(eventTypes.size)]()
        currentEvents.add(event)
        events = currentEvents.toList()
        
        android.util.Log.d("RandomEventLayer", "Debug spawned: ${event.javaClass.simpleName} (seed: $seed)")
    }
    
    /**
     * Spawn a specific event type by name.
     * Used when toggling individual event switches in settings.
     * @param seed Optional seed for deterministic spawning (both preview and wallpaper get same event)
     */
    fun spawnSpecificEvent(eventType: String, seed: Long = System.currentTimeMillis()) {
        val currentEvents = events.toMutableList()
        val random = Random(seed)
        
        val vr = viewportRatio
        val event: RandomEvent? = when (eventType) {
            "airplane" -> AirplaneEvent(random, seed)
            "birds" -> BirdFlockEvent(random, seed)
            "eagle" -> EagleEvent(random, seed)
            "butterfly" -> ButterflyEvent(random, seed, vr)
            "rabbit" -> RabbitEvent(random, seed, vr)
            "fox" -> FoxEvent(random, seed, vr)
            "shootingstar" -> ShootingStarEvent(random, seed)
            "fireflies" -> FireflyEvent(random, seed)
            "owl" -> OwlEvent(random, seed)
            "balloon" -> HotAirBalloonEvent(random, seed)
            "alien" -> AlienEvent(random, seed)
            "squirrel" -> SquirrelEvent(random, seed, vr)
            "dragonfly" -> DragonflyEvent(random, seed, vr)
            "snowflake" -> SnowflakeEvent(random, seed)
            "bat" -> BatEvent(random, seed)
            "deer" -> DeerEvent(random, seed, vr)
            "fish" -> FishJumpEvent(random, seed)
            "duck" -> DuckEvent(random, seed, vr)
            "frog" -> FrogEvent(random, seed)
            else -> null
        }
        
        event?.let {
            currentEvents.add(it)
            events = currentEvents.toList()
            android.util.Log.d("RandomEventLayer", "Spawned specific event: $eventType (seed: $seed)")
        }
    }
    
    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        eventsEnabled = settings.getBoolean("pref_events_enabled", true)
        val chance = settings.getInt("pref_event_frequency", 50)
        // Use steep logarithmic-exponential scale for spawn chance per 500ms bucket
        // At 0: effectively 0 (disabled)
        // At 10: 0.0001 (extremely rare - one event per ~80 minutes)
        // At 50: ~0.02 (moderate - one event per ~25 seconds)
        // At 100: 0.8 (constant spawning - multiple per second capped by max 8)
        spawnChance = when {
            chance <= 0 -> 0f  // Disabled
            chance <= 10 -> 0.00001f * chance  // Very low: 0.00001 to 0.0001
            else -> {
                // Steep exponential: 0.0001 at 10, ~0.02 at 50, 0.8 at 100
                val normalized = (chance - 10) / 90f  // 0-1 range for 10-100
                0.0001f * (8000.0.pow(normalized.toDouble())).toFloat()
            }
        }.coerceAtMost(0.9f)

        // Update season for seasonal event restrictions
        currentSeason = SeasonHelper.getEffectiveTerrainSeasonKey(settings)

        enabledEvents.clear()
        val eventKeys = listOf(
            "airplane", "birds", "eagle", "butterfly", "rabbit", "fox",
            "shootingstar", "fireflies", "owl", "balloon", "alien", "squirrel",
            "dragonfly", "snowflake", "bat", "deer", "fish", "duck", "frog"
        )
        for (key in eventKeys) {
            if (settings.getBoolean("pref_event_$key", true)) {
                enabledEvents.add(key)
            }
        }
    }
    
    override fun destroy() {
        events = emptyList()
    }
}
