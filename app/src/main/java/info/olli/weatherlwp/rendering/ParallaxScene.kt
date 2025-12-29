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
                try {
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
            
            // Input sensitivity: slider 0-50 maps to 0-5 with smoother curve
            // Default 25 = 2.5x, centered feel
            // Using sqrt for smoother low-end: sqrt(slider/10) * sqrt(5) ≈ slider/10 * 0.7
            // Actually simpler: linear slider/10 gives 0->0, 25->2.5, 50->5
            val sensitivityVal = prefs.getInt("pref_parallax_strength", 25).coerceIn(0, 50)
            val rawStrength = (sensitivityVal / 10f).coerceIn(0f, 5f)
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset slider: 5..200 px (default 80)
            parallaxMaxOffsetPx = (prefs.getInt("pref_parallax_max_offset", 80)).toFloat().coerceIn(5f, 200f)
            
            // Return speed slider: map 1..100 -> 0.005f..0.05f (faster return = lower center drift)
            val returnSpeed = prefs.getInt("pref_parallax_return_speed", 50).coerceIn(1, 100)
            parallaxReturnRate = 0.005f + (returnSpeed / 100f) * 0.045f
            
            // Smoothing slider: map 1..100 -> 0.1f..0.9f (filter strength)
            // Higher = more filtering = smoother but slower response
            val smoothingVal = prefs.getInt("pref_parallax_smoothing", 40).coerceIn(1, 100)
            val smoothingFactor = 0.1f + (smoothingVal / 100f) * 0.8f
            
            // Depth separation slider: map 0..100 -> 0f..1.2f (default 35 = more 3D effect)
            // Multiplied by 1.2f for stronger depth separation
            val depthVal = prefs.getInt("pref_parallax_depth", 35).coerceIn(0, 100)
            val depthSeparation = (depthVal / 100f) * 1.2f
            
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
