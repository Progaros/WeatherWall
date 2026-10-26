package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Layer-level depth snapping uses [hillInfoWorldProvider] (world-space) for
 * terrain-following of ground events (e.g. animals walking along hills).
 */
class RandomEventLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.RANDOM_EVENTS
    
    // Thread-safe event list using volatile + immutable list pattern
    @Volatile private var events: List<RandomEvent> = emptyList()
    
    companion object {
        private const val TAG = "RandomEventLayer"
        private const val EVENT_ERROR_LOG_INTERVAL_MS = 5000L
        private const val PERSEID_SEED_OFFSET = 7777L
    }

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
    
    // Extended canvas dimensions for height-based field scaling
    private var extendedWidth = 0f
    private var extendedOffset = 0f
    private var extendedOffsetY = 0f
    
    // Ratio of visible viewport to extended world width (1.0 landscape, <1.0 portrait)
    private var viewportRatio = 1.0f

    /** Convert an event's normalized x (in extended field space) to screen pixels. */
    fun eventXToScreenX(x: Float): Float = x * extendedWidth - extendedOffset

    // Cached per-frame ambient color (computed in update, used by multiple draw methods)
    private var cachedAmbientColor = 0
    private var eventsEnabled = true
    private var spawnChance = 0.001f
    
    /**
     * Terrain Y provider for ground events to follow the hill contour.
     * Set by ParallaxScene, which maps the event's field-normalized x to world space.
     */
    var eventTerrainProvider: EventTerrainProvider? = null

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
                resetEventPaints()
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
                    eventTerrainProvider
                )
            } catch (e: Exception) {
                logEventDrawError(currentEvent, e)
            }
        }
    }

    // Draw failures repeat every frame, so log at most one per interval and count the rest.
    private var lastEventErrorLogMs = 0L
    private var suppressedEventErrors = 0

    private fun logEventDrawError(event: RandomEvent, e: Exception) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastEventErrorLogMs >= EVENT_ERROR_LOG_INTERVAL_MS) {
            val suffix = if (suppressedEventErrors > 0) " (+$suppressedEventErrors suppressed)" else ""
            lastEventErrorLogMs = now
            suppressedEventErrors = 0
            android.util.Log.e(TAG, "Error drawing event ${event.javaClass.simpleName}$suffix", e)
        } else {
            suppressedEventErrors++
        }
    }

    /**
     * Restore the shared [paint] and [strokePaint] to their baseline configuration
     * before each event draws. Events mutate these shared instances (style, strokeWidth,
     * shader, color); resetting first prevents state from one event leaking into the next.
     */
    private fun resetEventPaints() {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 2f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
    }

    private fun ensureGroundEntryPoolSize(required: Int) {
        while (groundEventEntryPool.size < required) {
            groundEventEntryPool.add(GroundEventDrawEntryImpl())
        }
    }
    
    init {
        reloadSettings(SimulationSettings.fromSnapshot(emptyMap<String, Any?>()))
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
        // Height-based field scaling: extendedWidth scales with height so that events
        // spread uniformly in both X and Y. Uses the same field as SkyViewportMapper.
        extendedWidth = SkyViewportMapper.fieldWidth(w, h)
        extendedOffset = SkyViewportMapper.fieldOffset(w, h)
        extendedOffsetY = SkyViewportMapper.fieldOffsetY(w, h)
        viewportRatio = w / extendedWidth

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
            // Events get proportionally longer lifetime at smaller screen heights
            // so their height-scaled traversal can complete before expiry.
            // Cap to prevent active-event explosion at very small heights.
            val scaledDurationMs = (event.durationMs / EventTraversal.heightScale(height)).toLong()
                .coerceAtMost(event.durationMs * 3L)
            val isStillAlive = (state.absoluteTimeMs - event.spawnTimeMs) < scaledDurationMs
            if (!isStillAlive) {
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

            // Annual Perseids: extra meteors at a rate following the real activity profile.
            if (perseidsEnabled && state.isNight && !isPrecipitating(state) &&
                (state.cloudDensity == CloudDensity.LIGHT || state.cloudDensity == CloudDensity.MEDIUM)
            ) {
                val chance = perseidActivity(state.absoluteTimeMs) * (spawnChance * 1.5f).coerceAtMost(0.05f)
                val showerRandom = Random(spawnSeed + PERSEID_SEED_OFFSET)
                val count = currentEvents?.size ?: events.size
                if (showerRandom.nextFloat() < chance && count < 8) {
                    if (currentEvents == null) {
                        currentEvents = events.toMutableList()
                    }
                    currentEvents.add(ShootingStarEvent.perseid(showerRandom, currentBucket * 500))
                    needsListUpdate = true
                }
            }
        }
        
        // Only update the volatile reference if something changed
        if (needsListUpdate && currentEvents != null) {
            events = currentEvents.toList()
        }
    }
    
    private var lastSpawnBucket = 0L

    private var perseidsEnabled = true
    private var cachedPerseidActivity = 0f
    private var cachedPerseidActivityMs = Long.MIN_VALUE / 2

    /** Shower activity changes over days; refresh once a minute. */
    private fun perseidActivity(nowMs: Long): Float {
        if (kotlin.math.abs(nowMs - cachedPerseidActivityMs) >= 60_000L) {
            cachedPerseidActivity = MeteorShowers.perseidActivity(nowMs)
            cachedPerseidActivityMs = nowMs
        }
        return cachedPerseidActivity
    }

    private fun isPrecipitating(state: SimulationState): Boolean =
        state.weather == WeatherType.RAIN || state.weather == WeatherType.THUNDERSTORM ||
            state.weather == WeatherType.SNOW || state.weather == WeatherType.HAIL

    /**
     * Lets active star-field events (falling stars) modify the sky's star field.
     * Resets and fills [overlay]; returns false when no such event is running.
     */
    fun applyStarFieldEffects(overlay: StarFieldOverlay, absoluteTimeMs: Long): Boolean {
        if (!eventsEnabled) return false
        var any = false
        for (event in events) {
            if (event !is StarFieldEffect || event.spawnTimeMs > absoluteTimeMs) continue
            if (!any) {
                overlay.reset()
                any = true
            }
            event.applyTo(overlay, absoluteTimeMs)
        }
        return any
    }
    
    private fun spawnRandomEvent(state: SimulationState, currentEvents: MutableList<RandomEvent>, random: Random, spawnTime: Long) {
        val conditions = EventSpawnConditions(
            isNight = state.isNight,
            isDusk = state.isDusk,
            season = currentSeason,
            weather = state.weather,
            cloudDensity = state.cloudDensity
        )
        // Registry order and the enabled-first short-circuit fix which conditions draw from [random].
        val availableEvents = ArrayList<EventSpec>(EventSpec.entries.size)
        for (spec in EventSpec.entries) {
            if (spec.id in enabledEvents && spec.canSpawn(conditions, random)) {
                availableEvents.add(spec)
            }
        }
        
        if (availableEvents.isEmpty()) return
        
        // Use deterministic selection based on spawnTime
        val selectionRandom = Random(spawnTime + 99999)
        val spec = availableEvents[selectionRandom.nextInt(availableEvents.size)]
        currentEvents.add(spec.createForSpawn(spawnTime, viewportRatio))
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
     * Draw all ground events.
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
            if (event.layer == EventLayer.GROUND && event.spawnTimeMs <= groundDrawAbsoluteTimeMs) {
                // Snap event's depthFactor to the actual hill it visually sits on
                val effectiveDepth = hillInfoWorldProvider?.invoke(0f, event.depthFactor)
                    ?: event.depthFactor
                if (effectiveDepth >= minDepth && effectiveDepth < maxDepth) {
                    // Get the event's current Y position for depth sorting
                    val eventY = event.getCurrentYRatio(groundDrawAbsoluteTimeMs, eventTerrainProvider, height)
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
            // Replayed manual spawns can be queued to start later.
            if (event.spawnTimeMs > state.absoluteTimeMs) continue
            // Drawn by SkyLayer as part of the star field.
            if (event is StarFieldEffect) continue
            if (predicate(event)) {
                try {
                    // Events use screen-relative ratio-based positions and speeds.
                    // This is intentional: events are overlay content whose apparent speed
                    // is independent of world zoom. No canvas scaling is applied.
                    // Sky events get extendedOffsetY for uniform vertical field scaling;
                    // ground events get 0 since eventTerrainProvider returns screen-space ratios.
                    val offsetY = if (event.layer == EventLayer.GROUND) 0f else extendedOffsetY
                    resetEventPaints()
                    event.draw(
                        canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height,
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        if (event.layer == EventLayer.GROUND) eventTerrainProvider else null,
                        offsetY
                    )
                } catch (e: Exception) {
                    logEventDrawError(event, e)
                }
            }
        }
    }
    
    /**
     * Spawn a specific event type by id (manual trigger from settings).
     * @param seed Seeds the event's randomness; the same seed gives the same event in preview and wallpaper.
     * @param spawnTimeMs When the event starts (absolute time).
     */
    fun spawnSpecificEvent(eventType: String, seed: Long, spawnTimeMs: Long = seed) {
        val event = EventSpec.fromId(eventType)?.create(Random(seed), spawnTimeMs, viewportRatio) ?: return
        events = events + event
        android.util.Log.d("RandomEventLayer", "Spawned specific event: $eventType (seed: $seed)")
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
        for (spec in EventSpec.entries) {
            if (settings.getBoolean(spec.prefKey, true)) {
                enabledEvents.add(spec.id)
            }
        }
        perseidsEnabled = EventSpec.PERSEIDS.id in enabledEvents
    }
    
    override fun destroy() {
        events = emptyList()
    }
}
