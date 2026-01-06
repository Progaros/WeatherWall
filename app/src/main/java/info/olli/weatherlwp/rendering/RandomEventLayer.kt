package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.events.*
import info.olli.weatherlwp.simulation.CloudDensity
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
 */
class RandomEventLayer(private val context: Context) : SceneLayer {
    
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
    
    private var eventsEnabled = true
    private var spawnChance = 0.001f
    
    /**
     * Hill height provider for ground events to follow terrain contour.
     * Set by TerrainLayer to provide hill Y at given X and depth.
     */
    var hillHeightProvider: HillHeightProvider? = null
    
    // Individual event toggles
    private var airplaneEnabled = true
    private var birdsEnabled = true
    private var eagleEnabled = true
    private var butterflyEnabled = true
    private var rabbitEnabled = true
    private var foxEnabled = true
    private var shootingStarEnabled = true
    private var firefliesEnabled = true
    private var owlEnabled = true
    private var balloonEnabled = true
    private var alienEnabled = true
    private var squirrelEnabled = true
    private var dragonflyEnabled = true
    private var snowflakeEnabled = true
    private var batEnabled = true
    private var deerEnabled = true
    
    // Current season for seasonal event restrictions
    private var currentSeason = SeasonHelper.getCurrentCalendarSeasonKey()
    
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
        // Calculate extended width for parallax content
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)
    }
    
    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!eventsEnabled || width <= 0) return
        
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
        
        // Check current season for seasonal restrictions
        val isSpring = currentSeason == "spring"
        val isSummer = currentSeason == "summer"
        val isAutumn = currentSeason == "autumn"
        val isWinter = currentSeason == "winter"
        val isWarmSeason = isSpring || isSummer
        
        // Day events (lambda takes spawnTime to create deterministic event)
        if (airplaneEnabled && !state.isNight) availableEvents.add { t -> AirplaneEvent(Random(t), t) }
        if (birdsEnabled && !state.isNight) availableEvents.add { t -> BirdFlockEvent(Random(t + 1), t) }
        if (eagleEnabled && !state.isNight) availableEvents.add { t -> EagleEvent(Random(t + 2), t) }
        
        // Butterflies: Spring & Summer only (warm weather insects)
        if (butterflyEnabled && !state.isNight && isWarmSeason) {
            availableEvents.add { t -> ButterflyEvent(Random(t + 3), t) }
        }
        
        // Dragonflies: Summer only (peak insect season)
        if (dragonflyEnabled && !state.isNight && isSummer) {
            availableEvents.add { t -> DragonflyEvent(Random(t + 11), t) }
        }
        
        val isRaining = state.weather == WeatherType.RAIN || state.weather == WeatherType.THUNDERSTORM || state.weather == WeatherType.SNOW
        if (balloonEnabled && !state.isNight && !isRaining) availableEvents.add { t -> HotAirBalloonEvent(Random(t + 4), t) }
        
        // Ground animals - some seasonal
        if (rabbitEnabled) availableEvents.add { t -> RabbitEvent(Random(t + 5), t) }
        if (foxEnabled) availableEvents.add { t -> FoxEvent(Random(t + 6), t) }
        
        // Squirrels: Autumn primarily (gathering nuts), but can appear year-round
        if (squirrelEnabled && (isAutumn || random.nextFloat() < 0.3f)) {
            availableEvents.add { t -> SquirrelEvent(Random(t + 12), t) }
        }
        
        // Night events
        val isClearEnough = state.cloudDensity == CloudDensity.LIGHT || state.cloudDensity == CloudDensity.MEDIUM
        if (shootingStarEnabled && state.isNight && isClearEnough) availableEvents.add { t -> ShootingStarEvent(Random(t + 7), t) }
        
        // Fireflies: Summer nights only (warm nights)
        if (firefliesEnabled && state.isNight && !isRaining && isSummer) {
            availableEvents.add { t -> FireflyEvent(Random(t + 8), t) }
        }
        
        if (owlEnabled && state.isNight) availableEvents.add { t -> OwlEvent(Random(t + 9), t) }
        
        // Decorative snowflakes: Winter only, when not already snowing
        if (snowflakeEnabled && isWinter && state.weather != WeatherType.SNOW && !state.isNight) {
            availableEvents.add { t -> SnowflakeEvent(Random(t + 13), t) }
        }
        
        // Bats: Night only, year-round
        if (batEnabled && state.isNight) {
            availableEvents.add { t -> BatEvent(Random(t + 14), t) }
        }
        
        // Deer: Autumn/Winter primarily (more visible in sparse foliage)
        if (deerEnabled && !isRaining && (isAutumn || isWinter || random.nextFloat() < 0.2f)) {
            availableEvents.add { t -> DeerEvent(Random(t + 15), t) }
        }
        
        // Rare alien event - night only, very low chance
        if (alienEnabled && state.isNight && random.nextFloat() < 0.05f) {
            availableEvents.add { t -> AlienEvent(Random(t + 10), t) }
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
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        for (event in currentEvents) {
            if (event.layer == EventLayer.GROUND && event.depthFactor < maxDepth) {
                try {
                    event.draw(canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height, 
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        if (event.layer == EventLayer.GROUND) hillHeightProvider else null)
                } catch (e: Exception) {
                    android.util.Log.e("RandomEventLayer", "Error drawing event ${event.javaClass.simpleName}", e)
                }
            }
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
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        for (event in currentEvents) {
            if (event.layer == EventLayer.GROUND && 
                event.depthFactor >= minDepth && event.depthFactor < maxDepth) {
                try {
                    event.draw(canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height, 
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        hillHeightProvider)
                } catch (e: Exception) {
                    android.util.Log.e("RandomEventLayer", "Error drawing event ${event.javaClass.simpleName}", e)
                }
            }
        }
    }
    
    /**
     * Get ground events in range with their Y positions for Y-based sorting with trees.
     * Returns list of (yRatio, drawLambda) pairs.
     */
    fun getGroundEventsInRangeWithY(canvas: Canvas, state: SimulationState, 
                                      parallaxCalculator: ParallaxCalculator,
                                      minDepth: Float, maxDepth: Float): List<Pair<Float, () -> Unit>> {
        if (width <= 0 || height <= 0 || !eventsEnabled) return emptyList()
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return emptyList()
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        val result = mutableListOf<Pair<Float, () -> Unit>>()
        
        for (event in currentEvents) {
            if (event.layer == EventLayer.GROUND && 
                event.depthFactor >= minDepth && event.depthFactor < maxDepth) {
                // Calculate the event's Y position using the same logic as draw()
                val elapsedMs = state.absoluteTimeMs - event.spawnTimeMs
                val eventY = event.getCurrentYRatio(state.absoluteTimeMs, hillHeightProvider)
                
                result.add(Pair(eventY) {
                    try {
                        event.draw(canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height, 
                            state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                            hillHeightProvider)
                    } catch (e: Exception) {
                        android.util.Log.e("RandomEventLayer", "Error drawing event ${event.javaClass.simpleName}", e)
                    }
                })
            }
        }
        
        return result
    }
    
    /**
     * Draw ground events with depthFactor >= the given threshold.
     * Used to draw foreground animals after all trees.
     * @param minDepth Events with depthFactor >= minDepth will be drawn
     */
    fun drawGroundEventsAfterDepth(canvas: Canvas, state: SimulationState, 
                                    parallaxCalculator: ParallaxCalculator, minDepth: Float) {
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        for (event in currentEvents) {
            if (event.layer == EventLayer.GROUND && event.depthFactor >= minDepth) {
                try {
                    event.draw(canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height, 
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        hillHeightProvider)
                } catch (e: Exception) {
                    android.util.Log.e("RandomEventLayer", "Error drawing event ${event.javaClass.simpleName}", e)
                }
            }
        }
    }
    
    private fun drawEventsByLayer(canvas: Canvas, state: SimulationState, 
                                   parallaxCalculator: ParallaxCalculator, targetLayer: EventLayer) {
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        for (event in currentEvents) {
            if (event.layer == targetLayer) {
                try {
                    // Pass extendedWidth so events can span the full parallax area
                    event.draw(canvas, paint, strokePaint, parallaxCalculator, extendedWidth, height, 
                        state.timeSeconds, ambientColor, extendedOffset, state.absoluteTimeMs,
                        if (targetLayer == EventLayer.GROUND) hillHeightProvider else null)
                } catch (e: Exception) {
                    // Log but don't crash - one bad event shouldn't break the layer
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
        
        val eventTypes = listOf<() -> RandomEvent>(
            { AirplaneEvent(random, seed) },
            { BirdFlockEvent(random, seed) },
            { EagleEvent(random, seed) },
            { ButterflyEvent(random, seed) },
            { RabbitEvent(random, seed) },
            { FoxEvent(random, seed) },
            { ShootingStarEvent(random, seed) },
            { FireflyEvent(random, seed) },
            { OwlEvent(random, seed) },
            { HotAirBalloonEvent(random, seed) },
            { AlienEvent(random, seed) },
            { SquirrelEvent(random, seed) },
            { DragonflyEvent(random, seed) },
            { SnowflakeEvent(random, seed) }
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
        
        val event: RandomEvent? = when (eventType) {
            "airplane" -> AirplaneEvent(random, seed)
            "birds" -> BirdFlockEvent(random, seed)
            "eagle" -> EagleEvent(random, seed)
            "butterfly" -> ButterflyEvent(random, seed)
            "rabbit" -> RabbitEvent(random, seed)
            "fox" -> FoxEvent(random, seed)
            "shootingstar" -> ShootingStarEvent(random, seed)
            "fireflies" -> FireflyEvent(random, seed)
            "owl" -> OwlEvent(random, seed)
            "balloon" -> HotAirBalloonEvent(random, seed)
            "alien" -> AlienEvent(random, seed)
            "squirrel" -> SquirrelEvent(random, seed)
            "dragonfly" -> DragonflyEvent(random, seed)
            "snowflake" -> SnowflakeEvent(random, seed)
            else -> null
        }
        
        event?.let {
            currentEvents.add(it)
            events = currentEvents.toList()
            android.util.Log.d("RandomEventLayer", "Spawned specific event: $eventType (seed: $seed)")
        }
    }
    
    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        eventsEnabled = prefs.getBoolean("pref_events_enabled", true)
        val chance = prefs.getInt("pref_event_frequency", 50)
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
        currentSeason = SeasonHelper.getEffectiveTerrainSeasonKey(prefs)
        
        airplaneEnabled = prefs.getBoolean("pref_event_airplane", true)
        birdsEnabled = prefs.getBoolean("pref_event_birds", true)
        eagleEnabled = prefs.getBoolean("pref_event_eagle", true)
        butterflyEnabled = prefs.getBoolean("pref_event_butterfly", true)
        rabbitEnabled = prefs.getBoolean("pref_event_rabbit", true)
        foxEnabled = prefs.getBoolean("pref_event_fox", true)
        shootingStarEnabled = prefs.getBoolean("pref_event_shootingstar", true)
        firefliesEnabled = prefs.getBoolean("pref_event_fireflies", true)
        owlEnabled = prefs.getBoolean("pref_event_owl", true)
        balloonEnabled = prefs.getBoolean("pref_event_balloon", true)
        alienEnabled = prefs.getBoolean("pref_event_alien", true)
        squirrelEnabled = prefs.getBoolean("pref_event_squirrel", true)
        dragonflyEnabled = prefs.getBoolean("pref_event_dragonfly", true)
        snowflakeEnabled = prefs.getBoolean("pref_event_snowflake", true)
        batEnabled = prefs.getBoolean("pref_event_bat", true)
        deerEnabled = prefs.getBoolean("pref_event_deer", true)
    }
    
    override fun destroy() {
        events = emptyList()
    }
}
