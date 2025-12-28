package com.example.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import com.example.weatherlwp.parallax.ParallaxSensorManager
import com.example.weatherlwp.weather.WeatherRepository

/**
 * Main scene renderer that coordinates all layers.
 * 
 * Architecture:
 * - Manages ParallaxCalculator for sensor input and smoothing
 * - Manages a list of SceneLayers (Sky, Mountains, Terrain, Clouds)
 * - Handles Weather and Seasonal overlays
 * - Coordinates theme and settings updates
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
    private var lastTheme = ""
    private var isNight = false
    
    // Settings
    private var parallaxStrength = 1.0f
    private var parallaxMaxOffsetPx = 40f
    private var parallaxReturnRate = 0.02f
    private var dayNightEnabled = true
    private var manualTimeMinutes = 720  // Minutes since midnight (0-1439)
    private var debugEnabled = false
    
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

    override fun onOffsetChanged(x: Float, y: Float) {
        // Pass normalized sensor input to calculator
        parallaxCalculator.updateFromSensor(x, y)
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
            }
            
            // Update logic
            val now = System.currentTimeMillis()
            
            // Update parallax physics (smoothing, center drift)
            parallaxCalculator.strength = parallaxStrength
            parallaxCalculator.maxOffsetPx = parallaxMaxOffsetPx
            parallaxCalculator.centerDriftRate = parallaxReturnRate
            parallaxCalculator.update()
            
            // Update theme/time
            updateThemeAndTime()
            
            // Update layers
            for (layer in layers) {
                layer.update(16) // Assume ~60fps for now
            }
            weatherLayer.update(16)
            
            // Draw layers
            // Clear background first (SkyLayer does this, but good practice)
            canvas.drawColor(Color.BLACK)
            
            for (layer in layers) {
                layer.draw(canvas, parallaxCalculator)
            }
            
            // Draw Weather Overlay (Rain/Snow)
            // Weather usually doesn't have parallax or has its own logic
            weatherLayer.draw(canvas, parallaxCalculator)
            
            // Draw Seasonal Events (Christmas Star, Fireworks)
            seasonalEvents.render(canvas, true)
            
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
    
    private fun updateThemeAndTime() {
        // Determine theme based on weather and time
        val weatherType = weatherRepo.getWeatherType(context)
        val condition = weatherType.name.lowercase()
        
        // Calculate time of day
        // manualTimeMinutes is in minutes (0-1439), convert to fractional hour for smoother transitions
        val (hour, minute) = if (dayNightEnabled) {
            val cal = java.util.Calendar.getInstance()
            Pair(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        } else {
            Pair(manualTimeMinutes / 60, manualTimeMinutes % 60)
        }
        
        // Use fractional hour for smoother dawn/dusk transitions
        val fractionalHour = hour + minute / 60f
        
        isNight = fractionalHour < 6f || fractionalHour >= 20f
        val isDusk = fractionalHour >= 5.5f && fractionalHour < 7f  // 5:30-7:00
        val isDawn = fractionalHour >= 18.5f && fractionalHour < 20f  // 18:30-20:00
        
        var theme = "day"
        if (condition.contains("rain")) theme = "rain"
        else if (condition.contains("snow")) theme = "snow"
        else if (isNight) theme = "night"
        else if (isDusk || isDawn) theme = "dusk"
        
        if (theme != lastTheme) {
            lastTheme = theme
            Log.d(TAG, "Theme changed to: $theme")
            
            for (layer in layers) {
                layer.updateTheme(theme, isNight)
            }
            weatherLayer.updateTheme(theme, isNight)
            
            // Update Sky Colors based on theme
            updateSkyColors(theme)
        }
    }
    
    private fun updateSkyColors(theme: String) {
        // Define colors
        val SKY_DAY_TOP = Color.rgb(100, 180, 255)
        val SKY_DAY_BOTTOM = Color.rgb(200, 230, 255)
        val SKY_NIGHT_TOP = Color.rgb(10, 20, 40)
        val SKY_NIGHT_BOTTOM = Color.rgb(30, 50, 80)
        val SKY_DUSK_TOP = Color.rgb(60, 40, 100)
        val SKY_DUSK_BOTTOM = Color.rgb(255, 150, 100)
        val SKY_RAIN_TOP = Color.rgb(80, 90, 100)
        val SKY_RAIN_BOTTOM = Color.rgb(140, 150, 160)
        
        val (top, bottom) = when(theme) {
            "night" -> Pair(SKY_NIGHT_TOP, SKY_NIGHT_BOTTOM)
            "dusk" -> Pair(SKY_DUSK_TOP, SKY_DUSK_BOTTOM)
            "rain", "snow" -> Pair(SKY_RAIN_TOP, SKY_RAIN_BOTTOM)
            else -> Pair(SKY_DAY_TOP, SKY_DAY_BOTTOM)
        }
        
        skyLayer.setColors(top, bottom)
    }

    fun loadPrefs() {
        try {
            val parallaxEnabled = prefs.getBoolean("pref_parallax_enabled", true)
            val rawStrength = (prefs.getInt("pref_parallax_strength", 80) / 100f).coerceIn(0f, 2f)
            parallaxStrength = if (parallaxEnabled) rawStrength else 0f
            
            // Max offset slider: 5..200 px
            parallaxMaxOffsetPx = (prefs.getInt("pref_parallax_max_offset", 40)).toFloat().coerceIn(5f, 200f)
            
            // Return speed slider: map 1..100 -> 0.01f..0.15f (wider range for more noticeable effect)
            val returnSpeed = prefs.getInt("pref_parallax_return_speed", 50).coerceIn(1, 100)
            parallaxReturnRate = 0.01f + (returnSpeed / 100f) * 0.14f
            
            dayNightEnabled = prefs.getBoolean("pref_daynight_enabled", true)
            // Manual time is stored as minutes since midnight (0-1439)
            manualTimeMinutes = prefs.getInt("pref_manual_time", 720)
            
            // Debug overrides
            debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
            
            // Reload layer settings
            for (layer in layers) {
                layer.reloadSettings()
            }
            weatherLayer.reloadSettings()
            seasonalEvents.loadSettings()
            
            // Force theme update
            lastTheme = "" 
            
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
