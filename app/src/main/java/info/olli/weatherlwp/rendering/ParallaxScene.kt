package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.util.Log
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.DebugTracer
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.simulation.SimulationController
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherRepository
import kotlin.math.pow

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
    private val riverLayer = RiverLayer(context)
    private val lakeLayer = LakeLayer(context)
    private val randomEventLayer = RandomEventLayer(context)
    private val vignetteLayer = VignetteLayer(context)
    private val debugOverlayLayer = DebugOverlayLayer(context)
    
    // Overlays
    private val weatherLayer = WeatherLayer()
    
    // Seasonal events (SceneLayer, but rendered at specific z-depths)
    private val seasonalEvents = SeasonalEvents(context)
    
    // State
    private var width = 0f
    private var height = 0f
    
    // Settings - defaults match pref_effects.xml
    private var parallaxStrength = 0.075f  // Default: 25% on log scale → ~0.075
    private var parallaxMaxOffsetPx = 130f  // pref_parallax_max_offset=130
    private var weatherEnabled = true
    private var seasonalEnabled = true
    
    // Event-driven spawn trigger detection via SharedPreferences listener
    // Replaces 100ms polling — zero cost when no trigger fires
    @Volatile private var pendingSpawnTrigger = false
    @Volatile private var pendingSpecificTrigger = false
    
    private val spawnTriggerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            when {
                key.startsWith("trigger_spawn_event_time") && key.endsWith(triggerSuffix) -> {
                    pendingSpawnTrigger = true
                }
                key.startsWith("trigger_spawn_specific_event") && key.endsWith(triggerSuffix) -> {
                    pendingSpecificTrigger = true
                }
            }
        }
    }
    
    init {
        // Add layers in draw order (Background -> Foreground)
        layers.add(skyLayer)
        layers.add(cloudLayer) // Clouds behind mountains? Or in front? Usually behind or mixed.
                               // Original code had clouds behind mountains (layer 0.5).
                               // Let's put them behind mountains for now.
        layers.add(mountainLayer)
        layers.add(randomEventLayer)  // Ground animals and sky events (behind trees)
        layers.add(seasonalEvents)    // Seasonal effects (rendered at specific z-depths)
        layers.add(terrainLayer) // Hills and Trees (foreground)
        layers.add(riverLayer)   // River on top of terrain (draw via callback)
        layers.add(lakeLayer)    // Lake on terrain (draw via callback)
        
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
        
        // Wire river layer to terrain data so water flows through valleys
        riverLayer.terrainYProvider = { xRatio, depthFactor ->
            terrainLayer.getHillYAtPosition(xRatio, depthFactor)
        }
        riverLayer.hillDepthProvider = {
            terrainLayer.getHillDepthFactors()
        }
        
        // Integrate river drawing into terrain for correct z-ordering:
        // River is drawn after each hill fill but before trees, so trees appear in front of water
        terrainLayer.riverDrawer = TerrainLayer.RiverAtDepthDrawer { canvas, depthFactor, layerIndex, totalLayers ->
            riverLayer.drawAtDepthLayer(
                canvas, lastState ?: return@RiverAtDepthDrawer, parallaxCalculator,
                depthFactor, layerIndex, totalLayers
            )
        }
        riverLayer.drawViaCallback = true
        
        // Wire lake layer to terrain data
        lakeLayer.terrainYProvider = { xRatio, depthFactor ->
            terrainLayer.getHillYAtPosition(xRatio, depthFactor)
        }
        lakeLayer.hillDepthProvider = {
            terrainLayer.getHillDepthFactors()
        }
        // Integrate lake drawing into terrain for correct z-ordering
        terrainLayer.lakeDrawer = TerrainLayer.RiverAtDepthDrawer { canvas, depthFactor, layerIndex, totalLayers ->
            lakeLayer.drawAtDepthLayer(
                canvas, lastState ?: return@RiverAtDepthDrawer, parallaxCalculator,
                depthFactor, layerIndex, totalLayers
            )
        }
        lakeLayer.drawViaCallback = true
        
        // Load settings BEFORE enabling sensor to ensure correct initial strength
        loadPrefs()
        sensor.setListener(this)
        sensor.setShakeListener(this)
        
        // Register listener for spawn trigger events (replaces 100ms polling)
        prefs.registerOnSharedPreferenceChangeListener(spawnTriggerListener)
    }
    
    // Temporary references for ground event drawer callback
    private var lastCanvas: Canvas? = null
    private var lastState: SimulationState? = null

    /**
     * Get the current simulation state snapshot.
     * Used by WeatherWallpaper for onComputeColors() (Material You theming).
     */
    fun getSimulationState(): SimulationState? = lastState

    fun start() {
        sensor.start()
        // Reset timing to avoid delta spikes after visibility changes
        simulationController.resetTiming()
    }

    fun stop() {
        sensor.stop()
    }
    
    /**
     * Check if parallax is currently active (scene is moving due to tilt).
     * Used for idle mode battery optimization.
     */
    fun isParallaxActive(): Boolean {
        val (x, y) = parallaxCalculator.getOffset()
        // Consider active if offset is non-zero and changing
        val magnitude = kotlin.math.sqrt(x * x + y * y)
        return magnitude > 1f  // More than 1 pixel of offset
    }
    
    /**
     * Check if there are any active random events playing.
     * Used for idle mode battery optimization.
     */
    fun hasActiveEvents(): Boolean {
        return randomEventLayer.hasActiveEvents()
    }
    
    /**
     * Check if weather effects are currently active.
     * Used for idle mode battery optimization.
     */
    fun isWeatherActive(): Boolean {
        return weatherEnabled && weatherLayer.isActive()
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

    // Frame timing for performance monitoring (based on ai.md guidance)
    private var frameTimeLoggingEnabled = false
    private var slowFrameCount = 0
    private var totalFrameCount = 0
    private val SLOW_FRAME_THRESHOLD_MS = 16L  // 60 FPS target
    private val FRAME_LOG_INTERVAL = 300  // Log stats every 300 frames (~5 seconds at 60fps)

    fun render(canvas: Canvas, deltaTimeMs: Long = 16L, isIdleMode: Boolean = false) {
        val frameStartTime = if (frameTimeLoggingEnabled) System.nanoTime() else 0L
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
            
            // Check for manual spawn trigger from settings (event-driven via listener)
            // Only reads SharedPreferences when the listener detected a relevant key change
            if (pendingSpawnTrigger) {
                pendingSpawnTrigger = false
                val currentTimeMs = System.currentTimeMillis()
                val spawnTriggerKey = "trigger_spawn_event_time$triggerSuffix"
                val spawnTriggerTime = prefs.getLong(spawnTriggerKey, 0L)
                val timeSinceSpawnTrigger = currentTimeMs - spawnTriggerTime
                if (spawnTriggerTime > 0 && timeSinceSpawnTrigger < 10000) {
                    prefs.edit().putLong(spawnTriggerKey, 0L).apply()
                    randomEventLayer.debugSpawnEvent(spawnTriggerTime)
                    Log.d(TAG, "Manual spawn trigger received [${if (isPreview) "preview" else "wallpaper"}] (delay: ${timeSinceSpawnTrigger}ms)")
                }
            }
            
            if (pendingSpecificTrigger) {
                pendingSpecificTrigger = false
                val currentTimeMs = System.currentTimeMillis()
                val specificEventKey = "trigger_spawn_specific_event$triggerSuffix"
                val specificEventTimeKey = "trigger_spawn_specific_event_time$triggerSuffix"
                val specificEventTime = prefs.getLong(specificEventTimeKey, 0L)
                val specificEvent = prefs.getString(specificEventKey, null)
                val timeSinceSpecificTrigger = currentTimeMs - specificEventTime
                if (specificEvent != null && specificEventTime > 0 && timeSinceSpecificTrigger < 10000) {
                    prefs.edit()
                        .remove(specificEventKey)
                        .putLong(specificEventTimeKey, 0L)
                        .apply()
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
            // 4. Seasonal: rainbow (St. Patrick's), Christmas star - behind mountains
            // 5. Mountains
            // 6. MID SKY events (birds, planes, eagles) - in front of mountains
            // 7. Seasonal: fireworks (New Year, Chinese New Year) - behind terrain/hills
            // 8. Terrain (hills + trees + ground animals interleaved by depth)
            for (layer in layers) {
                try {
                    // Skip layers that render at specific z-depths via special methods
                    if (layer.layerType == LayerType.RANDOM_EVENTS) continue
                    if (layer.layerType == LayerType.SEASONAL_EVENTS) continue
                    
                    // Before drawing mountainLayer, draw far sky events and behind-mountain seasonal effects
                    if (layer.layerType == LayerType.MOUNTAINS) {
                        randomEventLayer.drawFarSkyEvents(canvas, state, parallaxCalculator)
                        seasonalEvents.drawBehindMountains(canvas, parallaxCalculator)
                    }
                    
                    // After drawing mountainLayer, draw mid sky events (birds, planes, eagles)
                    // and fireworks (which launch from behind hills but in front of mountains)
                    if (layer.layerType == LayerType.MOUNTAINS) {
                        layer.draw(canvas, state, parallaxCalculator)
                        randomEventLayer.drawMidSkyEvents(canvas, state, parallaxCalculator)
                        seasonalEvents.drawBehindTerrain(canvas)
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
            
            // Draw Vignette Overlay (always last - on top of everything)
            try {
                vignetteLayer.draw(canvas, state, parallaxCalculator)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing vignette", e)
            }
            
            // Draw Debug Overlay (on top of vignette for visibility)
            try {
                // Update debug overlay with current state info
                debugOverlayLayer.activeEventCount = randomEventLayer.getActiveEventCount()
                debugOverlayLayer.isIdleMode = isIdleMode
                val (px, py) = parallaxCalculator.getOffset()
                debugOverlayLayer.parallaxOffsetX = px
                debugOverlayLayer.parallaxOffsetY = py
                debugOverlayLayer.update(state, 0L)  // Update for FPS calculation
                debugOverlayLayer.draw(canvas, state, parallaxCalculator)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing debug overlay", e)
            }
            
            DebugTracer.traceEnd("render")
            
            // Frame time monitoring for performance analysis
            if (frameTimeLoggingEnabled) {
                val frameTimeMs = (System.nanoTime() - frameStartTime) / 1_000_000
                totalFrameCount++
                if (frameTimeMs > SLOW_FRAME_THRESHOLD_MS) {
                    slowFrameCount++
                }
                // Log statistics periodically
                if (totalFrameCount % FRAME_LOG_INTERVAL == 0) {
                    val slowPercentage = (slowFrameCount * 100f / totalFrameCount)
                    Log.d(TAG, "Frame stats: $totalFrameCount frames, ${String.format("%.1f", slowPercentage)}% slow (>16ms)")
                    if (slowPercentage > 10f) {
                        Log.w(TAG, "High slow frame rate detected - consider reducing event count or visual complexity")
                    }
                }
            }
            
            // Clear temporary references to avoid holding canvas/state across frames
            lastCanvas = null
            lastState = null
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering scene", e)
        }
    }
    
    private fun resizeLayers(w: Float, h: Float) {
        for (layer in layers) {
            layer.resize(w, h)
        }
        weatherLayer.resize(w, h)
        vignetteLayer.resize(w, h)
        debugOverlayLayer.resize(w, h)
    }

    fun loadPrefs() {
        try {
            val parallaxEnabled = prefs.getBoolean("pref_parallax_enabled", PreferenceDefaults.PARALLAX_ENABLED)
            
            // Sensor mode: rotation_vector (our robust implementation) or gyroscope
            val sensorMode = prefs.getString("pref_parallax_sensor_mode", PreferenceDefaults.PARALLAX_SENSOR_MODE) ?: PreferenceDefaults.PARALLAX_SENSOR_MODE
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
            
            // Input sensitivity: slider 1-100% with LOGARITHMIC curve
            // Fine control at low values (~1 old scale), high values reach ~5 old scale
            // Log formula: min * (max/min)^((slider-1)/99)
            // At 1% → 0.01, at 25% → ~0.075, at 50% → ~0.22, at 100% → 5.0
            val sensitivityVal = prefs.getInt("pref_parallax_strength", PreferenceDefaults.PARALLAX_STRENGTH).coerceIn(1, 100)
            val minStrength = 0.01f
            val maxStrength = 5.0f
            val logBase = maxStrength / minStrength  // 500
            val exponent = (sensitivityVal - 1) / 99f  // 0..1
            val rawStrength = minStrength * logBase.toDouble().pow(exponent.toDouble()).toFloat()
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset as percentage of screen height: 2..40% (default 20%)
            // This ensures movement scales proportionally with screen size
            val maxOffsetPercentVal = prefs.getInt("pref_parallax_max_offset", PreferenceDefaults.PARALLAX_MAX_OFFSET).coerceIn(2, 40)
            val maxOffsetPercent = maxOffsetPercentVal.toFloat()
            
            // Legacy pixel offset (not used when percentage is set, but kept for compatibility)
            parallaxMaxOffsetPx = 130f
            
            // Smoothing slider: map 0..100 -> 0f..0.40f
            // 0 = instant/direct (no smoothing), 50 = moderate smoothing, 100 = floaty
            val smoothingVal = prefs.getInt("pref_parallax_smoothing", PreferenceDefaults.PARALLAX_SMOOTHING).coerceIn(0, 100)
            val smoothingFactor = if (smoothingVal <= 0) 0f else 0.02f + (smoothingVal / 100f) * 0.38f
            
            // Edge easing slider: map 0..100 -> 0f..1f
            // 0 = linear (no easing), 50 = moderate easing, 100 = extreme (center-only movement)
            val edgeEasingVal = prefs.getInt("pref_parallax_edge_easing", PreferenceDefaults.PARALLAX_EDGE_EASING).coerceIn(0, 100)
            val edgeEasing = edgeEasingVal / 100f
            
            // Return speed unlocked: when true, return to center is always fast
            val returnSpeedUnlocked = prefs.getBoolean("pref_parallax_return_unlocked", PreferenceDefaults.PARALLAX_RETURN_UNLOCKED)
            
            // Depth separation slider: map 0..100 -> 0f..4.0f (default 33 = ~1.3x effect)
            val depthVal = prefs.getInt("pref_parallax_depth", PreferenceDefaults.PARALLAX_DEPTH).coerceIn(0, 100)
            val depthSeparation = (depthVal / 100f) * 4.0f
            
            // Perspective slider: map 0..100 -> 0f..1f (default 100 = background moves)
            // 0 = foreground moves most (head tilts, window stays)
            // 100 = background moves most (window shifts, head stays)
            val perspectiveVal = prefs.getInt("pref_parallax_perspective", PreferenceDefaults.PARALLAX_PERSPECTIVE).coerceIn(0, 100)
            val perspective = perspectiveVal / 100f
            
            // Return speed slider: map 0..100 -> 0f..1f (default 15 = slow return)
            // 0 = no return (scene stays offset), 50 = medium, 100 = fast return to center
            val returnSpeedVal = prefs.getInt("pref_parallax_return_speed", PreferenceDefaults.PARALLAX_RETURN_SPEED).coerceIn(0, 100)
            val returnSpeed = returnSpeedVal / 100f  // 0->0, 100->1
            
            // Axis inversion settings (for correcting inverted movement direction)
            val invertX = prefs.getBoolean("pref_parallax_invert_x", PreferenceDefaults.PARALLAX_INVERT_X)
            val invertY = prefs.getBoolean("pref_parallax_invert_y", PreferenceDefaults.PARALLAX_INVERT_Y)
            
            // Aspect ratio compensation: map 50..150 -> 0.5..1.5 (default 100 = 1:1 ratio)
            // Adjusts horizontal movement relative to vertical
            // At 100: horizontal uses same % of height as vertical (equal pixel movement)
            val aspectRatioVal = prefs.getInt("pref_parallax_aspect_ratio", PreferenceDefaults.PARALLAX_ASPECT_RATIO).coerceIn(50, 150)
            val aspectRatioCompensation = aspectRatioVal / 100f
            
            // Apply to parallax calculator
            parallaxCalculator.strength = parallaxStrength
            parallaxCalculator.maxOffsetPx = parallaxMaxOffsetPx
            parallaxCalculator.maxOffsetPercent = maxOffsetPercent
            parallaxCalculator.smoothing = smoothingFactor
            parallaxCalculator.depthSeparation = depthSeparation
            parallaxCalculator.perspective = perspective
            parallaxCalculator.returnSpeed = returnSpeed
            parallaxCalculator.invertX = invertX
            parallaxCalculator.invertY = invertY
            parallaxCalculator.aspectRatioCompensation = aspectRatioCompensation
            parallaxCalculator.edgeEasing = edgeEasing
            parallaxCalculator.returnSpeedUnlocked = returnSpeedUnlocked
            
            // Sensor auto-return: gentle return-to-center only when device is still
            sensor.setAutoReturnRate(returnSpeed)
            
            // Weather and seasonal toggles
            weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
            seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
            seasonalEvents.enabled = seasonalEnabled
            
            // Weather particle layer settings (depth effect for rain/snow/lightning)
            val weatherLayers = prefs.getInt("pref_weather_layers", 3).coerceIn(1, 5)
            val weatherSeparation = prefs.getInt("pref_weather_layer_separation", 30) / 100f
            val weatherDepth = prefs.getInt("pref_weather_layer_depth", 20) / 100f
            weatherLayer.setLayerSettings(weatherLayers, weatherSeparation, weatherDepth)
            
            // Reload simulation controller settings
            simulationController.reloadSettings()
            
            // Reload layer settings
            for (layer in layers) {
                layer.reloadSettings()
            }
            weatherLayer.reloadSettings()
            vignetteLayer.reloadSettings()
            debugOverlayLayer.reloadSettings()
            
            // Configure shake detection based on settings
            val shakeEnabled = prefs.getBoolean("pref_shake_fireworks_enabled", true)
            val shakeSensitivity = prefs.getInt("pref_shake_sensitivity", PreferenceDefaults.SHAKE_SENSITIVITY) / 100f
            val shakeDelayMs = prefs.getInt("pref_shake_delay", PreferenceDefaults.SHAKE_DELAY_MS).coerceIn(0, 5000)
            sensor.setShakeSettings(shakeEnabled, shakeSensitivity, shakeDelayMs)
            
            // Frame time logging (debug feature for performance analysis)
            frameTimeLoggingEnabled = prefs.getBoolean("pref_debug_frame_timing", false)
            if (frameTimeLoggingEnabled) {
                // Reset counters when enabled
                slowFrameCount = 0
                totalFrameCount = 0
                Log.d(TAG, "Frame timing logging enabled")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prefs", e)
        }
    }
    
    fun destroy() {
        // Unregister spawn trigger listener to prevent leaks
        prefs.unregisterOnSharedPreferenceChangeListener(spawnTriggerListener)
        for (layer in layers) {
            layer.destroy()
        }
        weatherLayer.destroy()
        vignetteLayer.destroy()
        debugOverlayLayer.destroy()
    }
    
    /**
     * Debug method to spawn a random event immediately.
     */
    fun debugSpawnEvent() {
        randomEventLayer.debugSpawnEvent()
    }
}
