package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.simulation.SimulationController
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
    private val skyLayer = SkyLayer()
    private val mountainLayer = MountainLayer(context)
    private val cloudLayer = CloudLayer(context)
    private val terrainLayer = TerrainLayer(context)
    
    // Overlays
    private val weatherLayer = WeatherLayer()
    private val seasonalEvents = SeasonalEvents(context)
    
    // State
    private var width = 0f
    private var height = 0f
    
    // Settings
    private var parallaxStrength = 1.0f
    private var parallaxMaxOffsetPx = 40f
    private var parallaxReturnRate = 0.02f
    private var weatherEnabled = true
    private var seasonalEnabled = true
    
    init {
        // Add layers in draw order (Background -> Foreground)
        layers.add(skyLayer)
        layers.add(cloudLayer) // Clouds behind mountains? Or in front? Usually behind or mixed.
                               // Original code had clouds behind mountains (layer 0.5).
                               // Let's put them behind mountains for now.
        layers.add(mountainLayer)
        layers.add(terrainLayer) // Hills and Trees
        
        sensor.setListener(this)
        loadPrefs()
    }

    fun start() {
        sensor.start()
    }

    fun stop() {
        sensor.stop()
    }

    override fun onOffsetChanged(velocityX: Float, velocityY: Float) {
        // Pass normalized sensor input to calculator
        parallaxCalculator.updateFromSensor(velocityX, velocityY)
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
            
            // Update parallax physics (smoothing, center drift)
            // Note: parallax settings are applied in loadPrefs(), not here
            parallaxCalculator.update()
            
            // Update layers with state
            for (layer in layers) {
                layer.update(state, 16) // Assume ~60fps for now
            }
            weatherLayer.update(state, 16)
            
            // Draw layers
            canvas.drawColor(Color.BLACK)
            
            for (layer in layers) {
                layer.draw(canvas, state, parallaxCalculator)
            }
            
            // Draw Weather Overlay (only if enabled)
            if (weatherEnabled) {
                weatherLayer.draw(canvas, state, parallaxCalculator)
            }
            
            // Draw Seasonal Events (Christmas Star, Fireworks) - only if enabled
            if (seasonalEnabled) {
                seasonalEvents.render(canvas, true)
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
            
            // Input sensitivity: slider 0-50 maps to 0-5 with logarithmic scale
            // 0 = off, 25 = 1.0 (default), 50 = 5.0
            // Using exponential: strength = (slider/25)^2, so 0->0, 25->1, 50->4
            // Or simpler log: strength = 5^(slider/50) - 1, gives 0->0, 25->1.24, 50->4
            // Using quadratic for smooth feel: (slider/25)^2 gives 0->0, 12.5->0.25, 25->1, 50->4
            val sensitivityVal = prefs.getInt("pref_parallax_strength", 25).coerceIn(0, 50)
            val rawStrength = if (sensitivityVal == 0) 0f else {
                // Logarithmic scale: 10^(slider/25 - 1) gives 0.1 at 0, 1.0 at 25, 10 at 50
                // But we want 0-5, so: (slider/10)^2 / 25 * 5 = slider^2 / 500
                // Simpler: (slider / 10)^1.5 gives 0->0, 10->3.16, 25->15.8, 50->35
                // Best: exponential with base adjustment: 5^(slider/50) - 1 mapped to 0-5
                // Actually simplest: quadratic (slider/50)^2 * 5 = 0->0, 25->1.25, 50->5
                val normalized = sensitivityVal / 50f
                (normalized * normalized * 5f).coerceIn(0f, 5f)
            }
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset slider: 5..200 px (default 100 for velocity-based system)
            parallaxMaxOffsetPx = (prefs.getInt("pref_parallax_max_offset", 100)).toFloat().coerceIn(5f, 200f)
            
            // Return speed slider: map 1..100 -> 0.01f..0.10f
            val returnSpeed = prefs.getInt("pref_parallax_return_speed", 50).coerceIn(1, 100)
            parallaxReturnRate = 0.01f + (returnSpeed / 100f) * 0.09f
            
            // Smoothing slider: map 1..100 -> 0.05f..0.5f (higher = more smooth but still responsive)
            // At max (100), smoothFactor in calculator = 1-0.5 = 0.5, meaning 50% of new velocity applied per frame
            val smoothingVal = prefs.getInt("pref_parallax_smoothing", 30).coerceIn(1, 100)
            val smoothingFactor = 0.05f + (smoothingVal / 100f) * 0.45f
            
            // Depth separation slider: map 0..100 -> 0f..1f
            val depthVal = prefs.getInt("pref_parallax_depth", 50).coerceIn(0, 100)
            val depthSeparation = depthVal / 100f
            
            // Apply to parallax calculator
            parallaxCalculator.strength = parallaxStrength
            parallaxCalculator.maxOffsetPx = parallaxMaxOffsetPx
            parallaxCalculator.centerDriftRate = parallaxReturnRate
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
}
