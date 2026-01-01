package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.util.Log
import androidx.preference.PreferenceManager
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
    private val weatherRepo: WeatherRepository
) : ParallaxSensorManager.Listener {

    companion object {
        private const val TAG = "ParallaxScene"
    }

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
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
    
    // Settings
    private var parallaxStrength = 1.0f
    private var parallaxMaxOffsetPx = 40f
    private var weatherEnabled = true
    private var seasonalEnabled = true
    
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
        // This allows animals to appear behind/in front of trees based on their depth
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
        }
        
        sensor.setListener(this)
        loadPrefs()
    }
    
    // Temporary references for ground event drawer callback
    private var lastCanvas: Canvas? = null
    private var lastState: SimulationState? = null

    fun start() {
        sensor.start()
    }

    fun stop() {
        sensor.stop()
    }

    override fun onTiltChanged(tiltX: Float, tiltY: Float) {
        // Pass absolute tilt position to calculator
        parallaxCalculator.updateFromSensor(tiltX, tiltY)
    }

    fun render(canvas: Canvas) {
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
            }
            
            // Get current simulation state (single source of truth)
            val state = simulationController.getState()
            
            // Store for ground event drawer callback
            lastCanvas = canvas
            lastState = state
            
            // Check for manual spawn trigger from settings
            // Use timestamp-based trigger - extended window to 10s for reliability when 
            // switching from settings back to home screen (wallpaper may not be visible immediately)
            // Both preview and live wallpaper will respond to this trigger
            val spawnTriggerTime = prefs.getLong("trigger_spawn_event_time", 0L)
            val timeSinceSpawnTrigger = System.currentTimeMillis() - spawnTriggerTime
            if (spawnTriggerTime > 0 && timeSinceSpawnTrigger < 10000) {
                // Clear immediately to prevent duplicate spawns
                prefs.edit().putLong("trigger_spawn_event_time", 0L).commit()
                randomEventLayer.debugSpawnEvent()
                Log.d(TAG, "Manual spawn trigger received (delay: ${timeSinceSpawnTrigger}ms)")
            }
            
            // Check for specific event spawn trigger from individual toggles
            val specificEventTime = prefs.getLong("trigger_spawn_specific_event_time", 0L)
            val specificEvent = prefs.getString("trigger_spawn_specific_event", null)
            val timeSinceSpecificTrigger = System.currentTimeMillis() - specificEventTime
            if (specificEvent != null && specificEventTime > 0 && timeSinceSpecificTrigger < 10000) {
                prefs.edit()
                    .remove("trigger_spawn_specific_event")
                    .putLong("trigger_spawn_specific_event_time", 0L)
                    .commit()
                randomEventLayer.spawnSpecificEvent(specificEvent)
                Log.d(TAG, "Specific spawn trigger received: $specificEvent (delay: ${timeSinceSpecificTrigger}ms)")
            }
            
            // Update parallax physics (smoothing, center drift)
            // Note: parallax settings are applied in loadPrefs(), not here
            parallaxCalculator.update()
            
            // Update layers with state
            for (layer in layers) {
                layer.update(state, 16) // Assume ~60fps for now
            }
            weatherLayer.update(state, 16)
            
            // Draw layers in proper order with events at correct depths:
            // 1. Sky (gradient)
            // 2. Clouds 
            // 3. FAR SKY events (shooting stars, balloons, UFOs) - behind mountains
            // 4. Mountains
            // 5. MID SKY events (birds, planes, eagles) - in front of mountains
            // 6. Terrain (hills + trees + ground animals interleaved by depth)
            for (layer in layers) {
                try {
                    // Skip randomEventLayer in normal iteration - ground events drawn by terrain callback
                    if (layer === randomEventLayer) continue
                    
                    // Before drawing mountainLayer, draw far sky events
                    if (layer === mountainLayer) {
                        randomEventLayer.drawFarSkyEvents(canvas, state, parallaxCalculator)
                    }
                    
                    // After drawing mountainLayer, draw mid sky events (birds, planes, eagles)
                    if (layer === mountainLayer) {
                        layer.draw(canvas, state, parallaxCalculator)
                        randomEventLayer.drawMidSkyEvents(canvas, state, parallaxCalculator)
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
            
            // Draw Seasonal Events (Christmas Star, Fireworks) - only if enabled
            if (seasonalEnabled) {
                try {
                    seasonalEvents.render(canvas, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing seasonal", e)
                }
            }
            
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
            
            // Input sensitivity: slider 0-50 maps to 0-2 (more subtle range)
            // User feedback: best feel around value 1-2
            // Default 25 = 1.0x (centered, natural feel)
            val sensitivityVal = prefs.getInt("pref_parallax_strength", 25).coerceIn(0, 50)
            val rawStrength = (sensitivityVal / 25f).coerceIn(0f, 2f)  // 0->0, 25->1, 50->2
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset slider: 5..200 px (default 80)
            parallaxMaxOffsetPx = (prefs.getInt("pref_parallax_max_offset", 80)).toFloat().coerceIn(5f, 200f)
            
            // Smoothing slider: map 1..100 -> 0.1f..0.9f (filter strength)
            // Higher = more filtering = smoother but slower response
            val smoothingVal = prefs.getInt("pref_parallax_smoothing", 40).coerceIn(1, 100)
            val smoothingFactor = 0.1f + (smoothingVal / 100f) * 0.8f
            
            // Depth separation slider: map 0..100 -> 0f..2.0f (default 50 = full 3D effect)
            // At max (100), gives 2.0x depth separation for dramatic window-like parallax
            val depthVal = prefs.getInt("pref_parallax_depth", 50).coerceIn(0, 100)
            val depthSeparation = (depthVal / 100f) * 2.0f
            
            // Apply to parallax calculator
            parallaxCalculator.strength = parallaxStrength
            parallaxCalculator.maxOffsetPx = parallaxMaxOffsetPx
            parallaxCalculator.smoothing = smoothingFactor
            parallaxCalculator.depthSeparation = depthSeparation
            
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
