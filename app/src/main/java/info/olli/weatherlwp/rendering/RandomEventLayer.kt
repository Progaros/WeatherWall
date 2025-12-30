package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.events.*
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType
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
    private var eventsEnabled = true
    private var spawnChance = 0.001f
    private var lastSpawnCheck = 0L
    
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
    
    init {
        reloadSettings()
    }
    
    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }
    
    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!eventsEnabled || width <= 0) return
        
        // Update existing events and remove finished ones
        val currentEvents = events.toMutableList()
        val iterator = currentEvents.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (!event.update(deltaTimeMs, state, width, height)) {
                iterator.remove()
            }
        }
        
        // Spawn new events (throttled to avoid too many)
        // Use absoluteTimeMs for deterministic spawning across preview/wallpaper
        val spawnBucket = state.absoluteTimeMs / 100  // Check every 100ms bucket
        val spawnRandom = Random(baseSeed + spawnBucket)
        
        lastSpawnCheck += deltaTimeMs
        if (lastSpawnCheck > 100) {
            lastSpawnCheck = 0
            if (spawnRandom.nextFloat() < spawnChance * 6 && currentEvents.size < 5) {
                spawnRandomEvent(state, currentEvents, spawnRandom)
            }
        }
        
        events = currentEvents.toList()
    }
    
    private fun spawnRandomEvent(state: SimulationState, currentEvents: MutableList<RandomEvent>, random: Random) {
        val availableEvents = mutableListOf<() -> RandomEvent?>()
        
        // Day events
        if (airplaneEnabled && !state.isNight) availableEvents.add { AirplaneEvent(random) }
        if (birdsEnabled && !state.isNight) availableEvents.add { BirdFlockEvent(random) }
        if (eagleEnabled && !state.isNight) availableEvents.add { EagleEvent(random) }
        if (butterflyEnabled && !state.isNight) availableEvents.add { ButterflyEvent(random) }
        val isRaining = state.weather == WeatherType.RAIN || state.weather == WeatherType.THUNDERSTORM || state.weather == WeatherType.SNOW
        if (balloonEnabled && !state.isNight && !isRaining) availableEvents.add { HotAirBalloonEvent(random) }
        
        // Any time
        if (rabbitEnabled) availableEvents.add { RabbitEvent(random) }
        if (foxEnabled) availableEvents.add { FoxEvent(random) }
        
        // Night events
        val isClearEnough = state.cloudDensity == CloudDensity.LIGHT || state.cloudDensity == CloudDensity.MEDIUM
        if (shootingStarEnabled && state.isNight && isClearEnough) availableEvents.add { ShootingStarEvent(random) }
        if (firefliesEnabled && state.isNight && !isRaining) availableEvents.add { FireflyEvent(random) }
        if (owlEnabled && state.isNight) availableEvents.add { OwlEvent(random) }
        
        // Rare alien event - night only, very low chance
        if (alienEnabled && state.isNight && random.nextFloat() < 0.05f) {
            availableEvents.add { AlienEvent(random) }
        }
        
        if (availableEvents.isEmpty()) return
        
        val event = availableEvents[random.nextInt(availableEvents.size)]()
        event?.let { currentEvents.add(it) }
    }
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Default draw method draws ground events only (in front of terrain)
        drawEventsByLayer(canvas, state, parallaxCalculator, EventLayer.GROUND)
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
     * Draw ground events (foxes, rabbits, butterflies, fireflies, owls).
     * Call this AFTER drawing terrain/hills.
     */
    fun drawGroundEvents(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        drawEventsByLayer(canvas, state, parallaxCalculator, EventLayer.GROUND)
    }
    
    private fun drawEventsByLayer(canvas: Canvas, state: SimulationState, 
                                   parallaxCalculator: ParallaxCalculator, targetLayer: EventLayer) {
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk
        )
        
        for (event in currentEvents) {
            if (event.layer == targetLayer) {
                event.draw(canvas, paint, strokePaint, parallaxCalculator, width, height, 
                    state.timeSeconds, ambientColor)
            }
        }
    }
    
    /**
     * Debug method to spawn a random event immediately.
     */
    fun debugSpawnEvent() {
        val currentEvents = events.toMutableList()
        val random = Random(System.currentTimeMillis())
        
        val eventTypes = listOf<() -> RandomEvent>(
            { AirplaneEvent(random) },
            { BirdFlockEvent(random) },
            { EagleEvent(random) },
            { ButterflyEvent(random) },
            { RabbitEvent(random) },
            { FoxEvent(random) },
            { ShootingStarEvent(random) },
            { FireflyEvent(random) },
            { OwlEvent(random) },
            { HotAirBalloonEvent(random) },
            { AlienEvent(random) }
        )
        
        val event = eventTypes[random.nextInt(eventTypes.size)]()
        currentEvents.add(event)
        events = currentEvents.toList()
        
        android.util.Log.d("RandomEventLayer", "Debug spawned: ${event.javaClass.simpleName}")
    }
    
    /**
     * Spawn a specific event type by name.
     * Used when toggling individual event switches in settings.
     */
    fun spawnSpecificEvent(eventType: String) {
        val currentEvents = events.toMutableList()
        val random = Random(System.currentTimeMillis())
        
        val event: RandomEvent? = when (eventType) {
            "airplane" -> AirplaneEvent(random)
            "birds" -> BirdFlockEvent(random)
            "eagle" -> EagleEvent(random)
            "butterfly" -> ButterflyEvent(random)
            "rabbit" -> RabbitEvent(random)
            "fox" -> FoxEvent(random)
            "shootingstar" -> ShootingStarEvent(random)
            "fireflies" -> FireflyEvent(random)
            "owl" -> OwlEvent(random)
            "balloon" -> HotAirBalloonEvent(random)
            "alien" -> AlienEvent(random)
            else -> null
        }
        
        event?.let {
            currentEvents.add(it)
            events = currentEvents.toList()
            android.util.Log.d("RandomEventLayer", "Spawned specific event: $eventType")
        }
    }
    
    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        eventsEnabled = prefs.getBoolean("pref_events_enabled", true)
        val chance = prefs.getInt("pref_event_frequency", 50)
        spawnChance = 0.0002f + (chance / 100f) * 0.002f
        
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
    }
    
    override fun destroy() {
        events = emptyList()
    }
}
