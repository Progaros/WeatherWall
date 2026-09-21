package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.os.Trace
import android.util.Log
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.DebugTracer
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.simulation.SimulationController
import info.olli.weatherlwp.simulation.SimulationSettings
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

    private inline fun traceSection(sectionName: String, block: () -> Unit) {
        Trace.beginSection(sectionName)
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    companion object {
        private const val TAG = "ParallaxScene"
        private const val DRAW_ERROR_LOG_INTERVAL_MS = 5000L
    }

    @Volatile
    private var currentSettings: SimulationSettings = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
    
    // Trigger key suffixes based on context (preview vs wallpaper)
    private val triggerSuffix = if (isPreview) "_preview" else "_wallpaper"
    
    // Simulation Controller - single source of truth
    private val simulationController = SimulationController(context)
    
    // Parallax Calculator
    private val parallaxCalculator = ParallaxCalculator()
    
    // Layers
    private val layers = ArrayList<SceneLayer>()
    // Draw failures repeat every frame, so log at most one per interval and count the rest.
    private var lastDrawErrorLogMs = 0L
    private var suppressedDrawErrors = 0

    private fun logDrawError(what: String, e: Exception) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDrawErrorLogMs >= DRAW_ERROR_LOG_INTERVAL_MS) {
            val suffix = if (suppressedDrawErrors > 0) " (+$suppressedDrawErrors suppressed)" else ""
            lastDrawErrorLogMs = now
            suppressedDrawErrors = 0
            Log.e(TAG, "Error drawing $what$suffix", e)
        } else {
            suppressedDrawErrors++
        }
    }

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
    
    // Trigger dedupe to avoid respawning the same manual event each frame.
    private var lastHandledSpawnTriggerTime = 0L
    private var lastHandledSpecificTriggerTime = 0L
    
    init {
        // Add layers in draw order (Background -> Foreground)
        layers.add(skyLayer)
        layers.add(cloudLayer) // Clouds behind mountains? Or in front? Usually behind or mixed.
                               // Original code had clouds behind mountains (layer 0.5).
                               // Let's put them behind mountains for now.
        cloudLayer.isPreviewMode = isPreview
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
            
            override fun getEventsInRange(minDepth: Float, maxDepth: Float): List<TerrainLayer.GroundEventDrawEntry> {
                return randomEventLayer.getGroundEventsInRangeWithY(
                    lastCanvas ?: return emptyList(),
                    lastState ?: return emptyList(),
                    parallaxCalculator,
                    minDepth,
                    maxDepth
                )
            }
        }
        
        // Set up terrain provider so ground events can follow the hill contour
        randomEventLayer.eventTerrainProvider = { eventX, depthFactor ->
            // Convert from extended-field normalized x to screen-width ratio for terrain lookup
            val screenRatio = randomEventLayer.eventXToScreenRatio(eventX)
            terrainLayer.getHillYAtPosition(screenRatio, depthFactor)
        }
        
        // Set up hill info provider so events snap to actual hill depths for correct draw ordering
        randomEventLayer.hillInfoWorldProvider = { worldX, requestedDepthFactor ->
            terrainLayer.getHillInfoAtWorldX(worldX, requestedDepthFactor).second
        }
        
        // Wire river layer to terrain data so water flows through valleys
        @Suppress("DEPRECATION")
        riverLayer.terrainYProvider = { xRatio, depthFactor ->
            terrainLayer.getHillYAtPosition(xRatio, depthFactor)
        }
        riverLayer.terrainYWorldProvider = { worldX, depthFactor ->
            terrainLayer.getHillYAtWorldX(worldX, depthFactor)
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
        @Suppress("DEPRECATION")
        lakeLayer.terrainYProvider = { xRatio, depthFactor ->
            terrainLayer.getHillYAtPosition(xRatio, depthFactor)
        }
        lakeLayer.terrainYWorldProvider = { worldX, depthFactor ->
            terrainLayer.getHillYAtWorldX(worldX, depthFactor)
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
        
        // Load settings BEFORE enabling sensor to ensure correct initial strength.
        // WeatherWallpaper pushes flow snapshots; preview path can still call loadPrefs().
        reloadSettings(currentSettings)
        sensor.setListener(this)
        sensor.setShakeListener(this)
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
        // Reset parallax to center BEFORE starting sensors.
        // Ensures the first visible frame renders centered — no stale offset,
        // no visible slide-back-to-center animation.
        parallaxCalculator.reset()
        simulationController.resetTiming()
        sensor.start()
    }

    fun stop() {
        sensor.stop()
    }
    
    /**
     * Check if parallax is currently active (scene is moving due to tilt).
     * Used for idle mode battery optimization.
     */
    fun isParallaxActive(): Boolean {
        // Unconsumed sensor motion counts as active so idle mode wakes up on the very
        // first frame of a movement instead of after the offset has already drifted.
        if (parallaxCalculator.hasPendingSensorMotion()) return true
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
                // Inform sensor manager of actual canvas orientation so axis remapping
                // matches the canvas coordinate system (fixes wallpaper landscape parallax)
                sensor.setCanvasOrientation(w, h)
            }
            
            // Get current simulation state (single source of truth)
            val state = simulationController.getState()
            
            // Store for ground event drawer callback
            lastCanvas = canvas
            lastState = state
            
            // Process manual spawn triggers from the latest settings snapshot.
            processManualSpawnTriggers()
            
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
                    when (layer.layerType) {
                        LayerType.CLOUDS -> traceSection("Draw_CloudLayer") {
                            layer.draw(canvas, state, parallaxCalculator)
                        }

                        LayerType.TERRAIN -> traceSection("Draw_TerrainLayer") {
                            layer.draw(canvas, state, parallaxCalculator)
                        }

                        else -> layer.draw(canvas, state, parallaxCalculator)
                    }
                } catch (e: Exception) {
                    logDrawError("layer ${layer.javaClass.simpleName}", e)
                }
            }
            
            // Draw Weather Overlay (only if enabled)
            if (weatherEnabled) {
                try {
                    traceSection("Draw_WeatherLayer") {
                        weatherLayer.draw(canvas, state, parallaxCalculator)
                    }
                } catch (e: Exception) {
                    logDrawError("weather", e)
                }
            }
            
            // Draw Vignette Overlay (always last - on top of everything)
            try {
                vignetteLayer.draw(canvas, state, parallaxCalculator)
            } catch (e: Exception) {
                logDrawError("vignette", e)
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
                logDrawError("debug overlay", e)
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

    private fun processManualSpawnTriggers() {
        val settings = currentSettings
        val currentTimeMs = System.currentTimeMillis()

        val spawnTriggerKey = "trigger_spawn_event_time$triggerSuffix"
        val spawnTriggerTime = settings.getLong(spawnTriggerKey, 0L)
        if (spawnTriggerTime > 0L && spawnTriggerTime != lastHandledSpawnTriggerTime) {
            lastHandledSpawnTriggerTime = spawnTriggerTime
            val timeSinceTrigger = currentTimeMs - spawnTriggerTime
            if (timeSinceTrigger < 10000L) {
                randomEventLayer.debugSpawnEvent(spawnTriggerTime)
                Log.d(TAG, "Manual spawn trigger received [${if (isPreview) "preview" else "wallpaper"}] (delay: ${timeSinceTrigger}ms)")
            }
        }

        val specificEventKey = "trigger_spawn_specific_event$triggerSuffix"
        val specificEventTimeKey = "trigger_spawn_specific_event_time$triggerSuffix"
        val specificEventTime = settings.getLong(specificEventTimeKey, 0L)
        val specificEvent = settings.getNullableString(specificEventKey)
        if (!specificEvent.isNullOrBlank() &&
            specificEventTime > 0L &&
            specificEventTime != lastHandledSpecificTriggerTime
        ) {
            lastHandledSpecificTriggerTime = specificEventTime
            val timeSinceTrigger = currentTimeMs - specificEventTime
            if (timeSinceTrigger < 10000L) {
                randomEventLayer.spawnSpecificEvent(specificEvent, specificEventTime)
                Log.d(TAG, "Specific spawn trigger received [${if (isPreview) "preview" else "wallpaper"}]: $specificEvent (delay: ${timeSinceTrigger}ms)")
            }
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
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    fun reloadSettings(settings: SimulationSettings) {
        currentSettings = settings

        try {
            val parallaxEnabled = settings.getBoolean("pref_parallax_enabled", PreferenceDefaults.PARALLAX_ENABLED)

            // Sensor mode: rotation_vector (our robust implementation) or gyroscope
            val sensorMode = settings.getString("pref_parallax_sensor_mode", PreferenceDefaults.PARALLAX_SENSOR_MODE)
            val parallaxMode = when (sensorMode) {
                "gyroscope" -> info.olli.weatherlwp.parallax.ParallaxSensorManager.ParallaxMode.GYROSCOPE
                else -> info.olli.weatherlwp.parallax.ParallaxSensorManager.ParallaxMode.ROTATION_VECTOR
            }
            sensor.setParallaxMode(parallaxMode)

            // Start/stop sensor based on parallax enabled state
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
            val sensitivityVal = settings.getInt("pref_parallax_strength", PreferenceDefaults.PARALLAX_STRENGTH).coerceIn(1, 100)
            val minStrength = 0.01f
            val maxStrength = 5.0f
            val logBase = maxStrength / minStrength
            val exponent = (sensitivityVal - 1) / 99f
            val rawStrength = minStrength * logBase.toDouble().pow(exponent.toDouble()).toFloat()
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f

            // Max offset as percentage of screen height: 2..40% (default 20%)
            val maxOffsetPercentVal = settings.getInt("pref_parallax_max_offset", PreferenceDefaults.PARALLAX_MAX_OFFSET).coerceIn(2, 40)
            val maxOffsetPercent = maxOffsetPercentVal.toFloat()

            // Legacy pixel offset (not used when percentage is set, but kept for compatibility)
            parallaxMaxOffsetPx = 130f

            // Smoothing slider: map 0..100 -> 0f..0.40f
            val smoothingVal = settings.getInt("pref_parallax_smoothing", PreferenceDefaults.PARALLAX_SMOOTHING).coerceIn(0, 100)
            val smoothingFactor = if (smoothingVal <= 0) 0f else 0.02f + (smoothingVal / 100f) * 0.38f

            // Edge easing slider: map 0..100 -> 0f..1f
            val edgeEasingVal = settings.getInt("pref_parallax_edge_easing", PreferenceDefaults.PARALLAX_EDGE_EASING).coerceIn(0, 100)
            val edgeEasing = edgeEasingVal / 100f

            // Return speed unlocked: when true, return to center is always fast
            val returnSpeedUnlocked = settings.getBoolean("pref_parallax_return_unlocked", PreferenceDefaults.PARALLAX_RETURN_UNLOCKED)

            // Depth separation slider: map 0..100 -> 0f..4.0f
            val depthVal = settings.getInt("pref_parallax_depth", PreferenceDefaults.PARALLAX_DEPTH).coerceIn(0, 100)
            val depthSeparation = (depthVal / 100f) * 4.0f

            // Perspective slider: map 0..100 -> 0f..1f
            val perspectiveVal = settings.getInt("pref_parallax_perspective", PreferenceDefaults.PARALLAX_PERSPECTIVE).coerceIn(0, 100)
            val perspective = perspectiveVal / 100f

            // Return speed slider: map 0..100 -> 0f..1f
            val returnSpeedVal = settings.getInt("pref_parallax_return_speed", PreferenceDefaults.PARALLAX_RETURN_SPEED).coerceIn(0, 100)
            val returnSpeed = returnSpeedVal / 100f

            // Axis inversion settings
            val invertX = settings.getBoolean("pref_parallax_invert_x", PreferenceDefaults.PARALLAX_INVERT_X)
            val invertY = settings.getBoolean("pref_parallax_invert_y", PreferenceDefaults.PARALLAX_INVERT_Y)

            // Aspect ratio compensation
            val aspectRatioVal = settings.getInt("pref_parallax_aspect_ratio", PreferenceDefaults.PARALLAX_ASPECT_RATIO).coerceIn(50, 150)
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
            weatherEnabled = settings.getBoolean("pref_weather_enabled", true)
            seasonalEnabled = settings.getBoolean("pref_seasonal_enabled", true)
            seasonalEvents.enabled = seasonalEnabled

            // Weather particle layer settings (depth effect for rain/snow/lightning)
            val weatherLayers = settings.getInt("pref_weather_layers", 3).coerceIn(1, 5)
            val weatherSeparation = settings.getInt("pref_weather_layer_separation", 30) / 100f
            val weatherDepth = settings.getInt("pref_weather_layer_depth", 20) / 100f
            weatherLayer.setLayerSettings(weatherLayers, weatherSeparation, weatherDepth)

            // Push settings into simulation and layers
            simulationController.applySettings(settings)
            for (layer in layers) {
                layer.reloadSettings(settings)
            }
            weatherLayer.reloadSettings(settings)
            vignetteLayer.reloadSettings(settings)
            debugOverlayLayer.reloadSettings(settings)

            // Configure shake detection based on settings
            val shakeEnabled = settings.getBoolean("pref_shake_fireworks_enabled", true)
            val shakeSensitivity = settings.getInt("pref_shake_sensitivity", PreferenceDefaults.SHAKE_SENSITIVITY) / 100f
            val shakeDelayMs = settings.getInt("pref_shake_delay", PreferenceDefaults.SHAKE_DELAY_MS).coerceIn(0, 5000)
            sensor.setShakeSettings(shakeEnabled, shakeSensitivity, shakeDelayMs)

            // Frame time logging (debug feature for performance analysis)
            frameTimeLoggingEnabled = settings.getBoolean("pref_debug_frame_timing", false)
            if (frameTimeLoggingEnabled) {
                slowFrameCount = 0
                totalFrameCount = 0
                Log.d(TAG, "Frame timing logging enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prefs", e)
        }
    }
    
    fun destroy() {
        // Stop sensor flow and detach callbacks first to avoid late events during teardown.
        sensor.stop()
        sensor.clearCallbacks()

        // Break callback chains between layers so destroyed objects are not retained.
        terrainLayer.groundEventDrawer = null
        terrainLayer.riverDrawer = null
        terrainLayer.lakeDrawer = null
        randomEventLayer.eventTerrainProvider = null
        randomEventLayer.hillInfoWorldProvider = null
        @Suppress("DEPRECATION")
        riverLayer.terrainYProvider = null
        riverLayer.terrainYWorldProvider = null
        riverLayer.hillDepthProvider = null
        @Suppress("DEPRECATION")
        lakeLayer.terrainYProvider = null
        lakeLayer.terrainYWorldProvider = null
        lakeLayer.hillDepthProvider = null

        // Drop frame-scoped references to avoid retaining canvas/state snapshots.
        lastCanvas = null
        lastState = null

        for (layer in layers) {
            layer.destroy()
        }
        weatherLayer.destroy()
        vignetteLayer.destroy()
        debugOverlayLayer.destroy()

        // Release strong references held by the draw-order list.
        layers.clear()
    }
    
    /**
     * Debug method to spawn a random event immediately.
     */
    fun debugSpawnEvent() {
        randomEventLayer.debugSpawnEvent()
    }
}
