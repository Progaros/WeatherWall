package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.util.Log
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.DebugTracer
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.simulation.SimulationController
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherRepository

/**
 * Main scene renderer that coordinates all layers.
 * 
 * Architecture:
 * - Manages SimulationController for consistent state
 * - Manages ParallaxCalculator for sensor input and smoothing
 * - Manages a list of SceneLayers (Sky, Mountains, Terrain, Clouds)
 * - Handles Weather and Seasonal overlays
 * - Coordinates settings updates
 */
class ParallaxScene(
    private val context: Context,
    private val sensor: ParallaxSensorManager,
    private val weatherRepo: WeatherRepository,
    private val isPreview: Boolean = false  // True for MainActivity preview, false for live wallpaper
) : ParallaxSensorManager.Listener, ParallaxSensorManager.ShakeListener {

    companion object {
        private const val TAG = "ParallaxScene"
    }

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    // Trigger key suffixes based on context (preview vs wallpaper)
    private val triggerSuffix = if (isPreview) "_preview" else "_wallpaper"
    
    // Simulation Controller - single source of truth
    private val simulationController = SimulationController(context)
    
    // Parallax Calculator
    private val parallaxCalculator = ParallaxCalculator()
    
    // Layers
    private val layers = ArrayList<SceneLayer>()
    private val skyLayer = SkyLayer(context)
    private val mountainLayer = MountainLayer(context)
    private val cloudLayer = CloudLayer(context)
    private val terrainLayer = TerrainLayer(context)
    private val randomEventLayer = RandomEventLayer(context)
    
    // Overlays
    private val weatherLayer = WeatherLayer()
    private val seasonalEvents = SeasonalEvents(context)
    
    // State
    private var width = 0f
    private var height = 0f
    
    // Settings - defaults match pref_effects.xml
    private var parallaxStrength = 2.5f  // (25/50)^2 * 10 = 2.5 (pref_parallax_strength=25)
    private var parallaxMaxOffsetPx = 130f  // pref_parallax_max_offset=130
    private var weatherEnabled = true
    private var seasonalEnabled = true
    
    // Battery optimization: throttle SharedPreferences access for spawn triggers
    private var lastSpawnTriggerCheckMs = 0L
    private val SPAWN_TRIGGER_CHECK_INTERVAL_MS = 100L  // Only check every 100ms instead of every frame
    
    init {
        // Add layers in draw order (Background -> Foreground)
        layers.add(skyLayer)
        layers.add(cloudLayer) // Clouds behind mountains? Or in front? Usually behind or mixed.
                               // Original code had clouds behind mountains (layer 0.5).
                               // Let's put them behind mountains for now.
        layers.add(mountainLayer)
        layers.add(randomEventLayer)  // Ground animals and sky events (behind trees)
        layers.add(terrainLayer) // Hills and Trees (foreground)
        
        // Set up ground event drawer for proper depth integration
        // This allows animals to appear behind/in front of trees based on their Y position
        terrainLayer.groundEventDrawer = object : TerrainLayer.GroundEventDrawer {
            override fun drawInRange(minDepth: Float, maxDepth: Float) {
                randomEventLayer.drawGroundEventsInRange(
                    lastCanvas ?: return,
                    lastState ?: return,
                    parallaxCalculator,
                    minDepth,
                    maxDepth
                )
            }
            
            override fun getEventsInRange(minDepth: Float, maxDepth: Float): List<Pair<Float, () -> Unit>> {
                return randomEventLayer.getGroundEventsInRangeWithY(
                    lastCanvas ?: return emptyList(),
                    lastState ?: return emptyList(),
                    parallaxCalculator,
                    minDepth,
                    maxDepth
                )
            }
        }
        
        // Set up hill height provider so ground events can follow terrain contour
        randomEventLayer.hillHeightProvider = { xRatio, depthFactor ->
            terrainLayer.getHillYAtPosition(xRatio, depthFactor)
        }
        
        // Load settings BEFORE enabling sensor to ensure correct initial strength
        loadPrefs()
        sensor.setListener(this)
        sensor.setShakeListener(this)
    }
    
    // Temporary references for ground event drawer callback
    private var lastCanvas: Canvas? = null
    private var lastState: SimulationState? = null

    fun start() {
        sensor.start()
        // Reset timing to avoid delta spikes after visibility changes
        simulationController.resetTiming()
    }

    fun stop() {
        sensor.stop()
    }

    override fun onTiltChanged(tiltX: Float, tiltY: Float) {
        // Pass absolute tilt position to calculator
        parallaxCalculator.updateFromSensor(tiltX, tiltY)
    }
    
    override fun onShake() {
        // Trigger fireworks when shake gesture is detected
        if (seasonalEvents.isShakeFireworksEnabled()) {
            Log.d(TAG, "Shake detected - triggering fireworks")
            seasonalEvents.triggerShakeFireworks()
        }
    }

    fun render(canvas: Canvas, deltaTimeMs: Long = 16L) {
        DebugTracer.traceStart("render")
        try {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            
            if (w <= 0 || h <= 0) return
            
            // Handle resize
            if (w != width || h != height) {
                width = w
                height = h
                resizeLayers(w, h)
                simulationController.resize(w, h)
                // Update parallax calculator with canvas size for proportional scaling
                parallaxCalculator.setCanvasSize(w, h)
            }
            
            // Get current simulation state (single source of truth)
            val state = simulationController.getState()
            
            // Store for ground event drawer callback
            lastCanvas = canvas
            lastState = state
            
            // Check for manual spawn trigger from settings
            // Battery optimization: Only check SharedPreferences every 100ms instead of every frame
            val currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs - lastSpawnTriggerCheckMs >= SPAWN_TRIGGER_CHECK_INTERVAL_MS) {
                lastSpawnTriggerCheckMs = currentTimeMs
                
                // Use context-specific keys (preview vs wallpaper) to avoid race conditions
                // Each context reads and clears only its own key, but uses same seed for deterministic events
                val spawnTriggerKey = "trigger_spawn_event_time$triggerSuffix"
                val spawnTriggerTime = prefs.getLong(spawnTriggerKey, 0L)
                val timeSinceSpawnTrigger = currentTimeMs - spawnTriggerTime
                if (spawnTriggerTime > 0 && timeSinceSpawnTrigger < 10000) {
                    // Clear only our own key to prevent duplicate spawns
                    prefs.edit().putLong(spawnTriggerKey, 0L).apply()
                    // Use trigger time as seed for deterministic event spawning
                    randomEventLayer.debugSpawnEvent(spawnTriggerTime)
                    Log.d(TAG, "Manual spawn trigger received [${if (isPreview) "preview" else "wallpaper"}] (delay: ${timeSinceSpawnTrigger}ms)")
                }
                
                // Check for specific event spawn trigger from individual toggles
                val specificEventKey = "trigger_spawn_specific_event$triggerSuffix"
                val specificEventTimeKey = "trigger_spawn_specific_event_time$triggerSuffix"
                val specificEventTime = prefs.getLong(specificEventTimeKey, 0L)
                val specificEvent = prefs.getString(specificEventKey, null)
                val timeSinceSpecificTrigger = currentTimeMs - specificEventTime
                if (specificEvent != null && specificEventTime > 0 && timeSinceSpecificTrigger < 10000) {
                    prefs.edit()
                        .remove(specificEventKey)
                        .putLong(specificEventTimeKey, 0L)
                        .apply()  // Use apply() to avoid blocking render thread
                    // Use trigger time as seed for deterministic event spawning
                    randomEventLayer.spawnSpecificEvent(specificEvent, specificEventTime)
                    Log.d(TAG, "Specific spawn trigger received [${if (isPreview) "preview" else "wallpaper"}]: $specificEvent (delay: ${timeSinceSpecificTrigger}ms)")
                }
            }
            
            // Update parallax physics with real frame delta for accurate smoothing
            parallaxCalculator.update(deltaTimeMs)
            
            // Update layers with real delta time
            for (layer in layers) {
                layer.update(state, deltaTimeMs)
            }
            weatherLayer.update(state, deltaTimeMs)
            
            // Draw layers in proper order with events at correct depths:
            // 1. Sky (gradient)
            // 2. Clouds 
            // 3. FAR SKY events (shooting stars, balloons, UFOs) - behind mountains
            // 4. Seasonal: rainbow (St. Patrick's) - behind mountains
            // 5. Mountains
            // 6. MID SKY events (birds, planes, eagles) - in front of mountains
            // 7. Seasonal: fireworks (New Year, Chinese New Year) - behind terrain/hills
            // 8. Terrain (hills + trees + ground animals interleaved by depth)
            for (layer in layers) {
                try {
                    // Skip randomEventLayer in normal iteration - ground events drawn by terrain callback
                    if (layer === randomEventLayer) continue
                    
                    // Before drawing mountainLayer, draw far sky events and behind-mountain seasonal effects
                    if (layer === mountainLayer) {
                        randomEventLayer.drawFarSkyEvents(canvas, state, parallaxCalculator)
                        // Draw seasonal background elements that should appear behind mountains (e.g. rainbow)
                        seasonalEvents.renderBehindMountains(canvas, seasonalEnabled)
                    }
                    
                    // After drawing mountainLayer, draw mid sky events (birds, planes, eagles)
                    // and fireworks (which launch from behind hills but in front of mountains)
                    if (layer === mountainLayer) {
                        layer.draw(canvas, state, parallaxCalculator)
                        randomEventLayer.drawMidSkyEvents(canvas, state, parallaxCalculator)
                        // Draw fireworks behind terrain/hills but in front of mountains
                        seasonalEvents.renderBehindTerrain(canvas, seasonalEnabled)
                        continue  // Skip the layer.draw below since we already drew it
                    }
                    
                    // Terrain layer draws hills, trees, and ground animals interleaved by depth
                    layer.draw(canvas, state, parallaxCalculator)
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing layer ${layer.javaClass.simpleName}", e)
                }
            }
            
            // Draw Weather Overlay (only if enabled)
            if (weatherEnabled) {
                try {
                    weatherLayer.draw(canvas, state, parallaxCalculator)
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing weather", e)
                }
            }
            
            // Draw Seasonal Events (Christmas Star only - in front of everything)
            // Note: Fireworks are rendered via renderBehindTerrain() to appear behind hills
            if (seasonalEnabled) {
                try {
                    seasonalEvents.render(canvas, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing seasonal", e)
                }
            }
            
            DebugTracer.traceEnd("render")
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering scene", e)
        }
    }
    
    private fun resizeLayers(w: Float, h: Float) {
        for (layer in layers) {
            layer.resize(w, h)
        }
        weatherLayer.resize(w, h)
    }

    fun loadPrefs() {
        try {
            val parallaxEnabled = prefs.getBoolean("pref_parallax_enabled", true)
            
            // Sensor mode: rotation_vector (our robust implementation) or gyroscope
            val sensorMode = prefs.getString("pref_parallax_sensor_mode", "rotation_vector") ?: "rotation_vector"
            val parallaxMode = when (sensorMode) {
                "gyroscope" -> info.olli.weatherlwp.parallax.ParallaxSensorManager.ParallaxMode.GYROSCOPE
                else -> info.olli.weatherlwp.parallax.ParallaxSensorManager.ParallaxMode.ROTATION_VECTOR
            }
            sensor.setParallaxMode(parallaxMode)
            
            // Start/stop sensor based on parallax enabled state
            // This saves significant battery/CPU when parallax is disabled
            if (parallaxEnabled) {
                if (!sensor.isRunning()) {
                    sensor.start()
                }
            } else {
                if (sensor.isRunning()) {
                    sensor.stop()
                    parallaxCalculator.reset()  // Reset to center when disabled
                }
            }
            
            // Input sensitivity: slider 0-50 with QUADRATIC curve
            // 0 = off, 25 = normal (2.5x), 50 = high (10x)
            // Quadratic: (val/50)^2 * 10 gives 0->0, 25->2.5, 50->10
            val sensitivityVal = prefs.getInt("pref_parallax_strength", 25).coerceIn(0, 50)
            val normalized = sensitivityVal / 50f
            val rawStrength = normalized * normalized * 10f  // Quadratic: 25->2.5x, 50->10x
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset slider: 20..200 px (default 130)
            parallaxMaxOffsetPx = (prefs.getInt("pref_parallax_max_offset", 130)).toFloat().coerceIn(20f, 200f)
            
            // Smoothing slider: map 1..100 -> 0.02f..0.40f (wider range)
            // 1 = nearly instant, 50 = moderate smoothing, 100 = floaty
            val smoothingVal = prefs.getInt("pref_parallax_smoothing", 30).coerceIn(1, 100)
            val smoothingFactor = 0.02f + (smoothingVal / 100f) * 0.38f
            
            // Depth separation slider: map 0..100 -> 0f..4.0f (default 33 = ~1.3x effect)
            val depthVal = prefs.getInt("pref_parallax_depth", 33).coerceIn(0, 100)
            val depthSeparation = (depthVal / 100f) * 4.0f
            
            // Perspective slider: map 0..100 -> 0f..1f (default 100 = background moves)
            // 0 = foreground moves most (head tilts, window stays)
            // 100 = background moves most (window shifts, head stays)
            val perspectiveVal = prefs.getInt("pref_parallax_perspective", 100).coerceIn(0, 100)
            val perspective = perspectiveVal / 100f
            
            // Return speed slider: map 1..100 -> 0f..1f (default 10 = fast return)
            // 1 = instant return to center, 50 = medium, 100 = never return (stays offset)
            val returnSpeedVal = prefs.getInt("pref_parallax_return_speed", 10).coerceIn(1, 100)
            val returnSpeed = (returnSpeedVal - 1) / 99f  // 1->0, 100->1
            
            // Axis inversion settings (for correcting inverted movement direction)
            val invertX = prefs.getBoolean("pref_parallax_invert_x", false)
            val invertY = prefs.getBoolean("pref_parallax_invert_y", false)
            
            // Aspect ratio compensation: map 50..150 -> 0.5..1.5 (default 100 = 1:1 ratio)
            // Adjusts horizontal movement relative to vertical for 1:1 pixel offset
            val aspectRatioVal = prefs.getInt("pref_parallax_aspect_ratio", 100).coerceIn(50, 150)
            val aspectRatioCompensation = aspectRatioVal / 100f
            
            // Apply to parallax calculator
            parallaxCalculator.strength = parallaxStrength
            parallaxCalculator.maxOffsetPx = parallaxMaxOffsetPx
            parallaxCalculator.smoothing = smoothingFactor
            parallaxCalculator.depthSeparation = depthSeparation
            parallaxCalculator.perspective = perspective
            parallaxCalculator.returnSpeed = returnSpeed
            parallaxCalculator.invertX = invertX
            parallaxCalculator.invertY = invertY
            parallaxCalculator.aspectRatioCompensation = aspectRatioCompensation
            
            // Weather and seasonal toggles
            weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
            seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
            
            // Reload simulation controller settings
            simulationController.reloadSettings()
            
            // Reload layer settings
            for (layer in layers) {
                layer.reloadSettings()
            }
            weatherLayer.reloadSettings()
            seasonalEvents.loadSettings()
            
            // Configure shake detection based on settings
            val shakeEnabled = prefs.getBoolean("pref_shake_fireworks_enabled", true)
            val shakeSensitivity = prefs.getInt("pref_shake_sensitivity", 50) / 100f
            sensor.setShakeSettings(shakeEnabled, shakeSensitivity)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prefs", e)
        }
    }
    
    fun destroy() {
        for (layer in layers) {
            layer.destroy()
        }
        weatherLayer.destroy()
    }
    
    /**
     * Debug method to spawn a random event immediately.
     */
    fun debugSpawnEvent() {
        randomEventLayer.debugSpawnEvent()
    }
}
