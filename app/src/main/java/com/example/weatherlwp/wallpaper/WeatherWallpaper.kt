package com.example.weatherlwp.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.example.weatherlwp.rendering.ParallaxScene
import com.example.weatherlwp.rendering.SeasonalEvents
import com.example.weatherlwp.rendering.WeatherLayer
import com.example.weatherlwp.weather.WeatherRepository
import com.example.weatherlwp.weather.WeatherType
import com.example.weatherlwp.parallax.ParallaxSensorManager

class WeatherWallpaper : WallpaperService() {
    companion object {
        private const val TAG = "WeatherWallpaper"
    }

    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine() {
        private var renderThread: Thread? = null
        @Volatile private var isVisible: Boolean = false

        private lateinit var scene: ParallaxScene
        private lateinit var seasonal: SeasonalEvents
        private val sensorManager = ParallaxSensorManager(this@WeatherWallpaper)
        private val weatherRepo = WeatherRepository
        private val weather = WeatherLayer()

        // Preferences
        private var targetFps = 30
        private var weatherEnabled = true
        private var seasonalEnabled = true
        private var currentWeather = "none"
        private var useBreezyWeather = true
        
        // Preference change listener - responds to ALL setting changes
        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Reload all preferences and update scene when any setting changes
            Log.d(TAG, "Preference changed: $key")
            loadPrefs()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                scene = ParallaxScene(this@WeatherWallpaper, sensorManager, weatherRepo)
                seasonal = SeasonalEvents(this@WeatherWallpaper)
                loadPrefs()
                // Register for live weather updates from Breezy Weather
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
                prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCreate", e)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            try {
                // Some wallpaper preview flows create a surface without reliably calling
                // onVisibilityChanged(true) first. Treat a created surface as visible.
                isVisible = true
                loadPrefs()
                scene.start()  // Start sensors
                startRender()
                // Paint at least one frame immediately so previews don't stay black.
                drawFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSurfaceCreated", e)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            try {
                isVisible = false
                stopRender()
                scene.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSurfaceDestroyed", e)
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            try {
                if (visible) {
                    loadPrefs()
                    scene.start()  // Start sensors
                    startRender()
                } else {
                    stopRender()
                    scene.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onVisibilityChanged", e)
            }
        }

        override fun onDestroy() {
            try {
                // Unregister weather update listener
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
                stopRender()
                scene.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDestroy", e)
            }
            super.onDestroy()
        }

        private fun loadPrefs() {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
                weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
                seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
                useBreezyWeather = prefs.getBoolean("pref_use_breezy_weather", true)
                targetFps = prefs.getString("pref_max_fps", "30")?.toIntOrNull() ?: 30
                targetFps = targetFps.coerceIn(10, 60)
                
                // Check if debug/simulation mode is enabled
                val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
                
                // Reload scene settings (handles parallax, clouds, day/night, terrain)
                scene.loadPrefs()
                
                // Reload seasonal settings (Christmas star size/trail, etc.)
                seasonal.loadSettings()

                // Weather type - check debug override first, then auto/manual
                currentWeather = prefs.getString("pref_weather_type", "auto") ?: "auto"
                
                val effectiveWeather = when {
                    // Debug mode: use the weather type setting directly (even if it's not "auto")
                    debugEnabled && currentWeather != "auto" -> {
                        when (currentWeather) {
                            "rain" -> WeatherLayer.WeatherType.RAIN
                            "snow" -> WeatherLayer.WeatherType.SNOW
                            "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                            "fog" -> WeatherLayer.WeatherType.FOG
                            else -> WeatherLayer.WeatherType.NONE
                        }
                    }
                    // Auto mode with Breezy Weather
                    currentWeather == "auto" && useBreezyWeather -> {
                        val breezyType = WeatherRepository.getWeatherType(this@WeatherWallpaper)
                        when (breezyType) {
                            WeatherType.RAIN -> WeatherLayer.WeatherType.RAIN
                            WeatherType.SNOW -> WeatherLayer.WeatherType.SNOW
                            WeatherType.THUNDERSTORM -> WeatherLayer.WeatherType.LIGHTNING
                            WeatherType.FOG -> WeatherLayer.WeatherType.FOG
                            else -> WeatherLayer.WeatherType.NONE
                        }
                    }
                    // Manual selection (not in debug mode)
                    else -> {
                        when (currentWeather) {
                            "rain" -> WeatherLayer.WeatherType.RAIN
                            "snow" -> WeatherLayer.WeatherType.SNOW
                            "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                            "fog" -> WeatherLayer.WeatherType.FOG
                            else -> WeatherLayer.WeatherType.NONE
                        }
                    }
                }
                weather.setWeather(effectiveWeather)
                
                // Set precipitation intensity based on wind speed (as proxy for storm intensity)
                // Higher wind = heavier precipitation
                val windIntensity = if (useBreezyWeather) {
                    WeatherRepository.getNormalizedWindStrength(this@WeatherWallpaper)
                } else {
                    0.5f // Default medium
                }
                // Map wind to precipitation: 0.3 base + 0.7 * wind (so light rain at calm, heavy at windy)
                weather.setIntensity(0.3f + windIntensity * 0.7f)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading prefs", e)
            }
        }

        private fun startRender() {
            if (renderThread != null) return
            renderThread = Thread {
                try {
                    val frameTimeMs = (1000L / targetFps.toLong()).coerceAtLeast(16L)
                    while (!Thread.currentThread().isInterrupted) {
                        if (!isVisible) {
                            SystemClock.sleep(250)
                            continue
                        }

                        val start = SystemClock.uptimeMillis()
                        drawFrame()
                        val dt = SystemClock.uptimeMillis() - start
                        val sleep = frameTimeMs - dt
                        if (sleep > 0) SystemClock.sleep(sleep)
                    }
                } catch (e: InterruptedException) {
                    // Normal exit
                } catch (e: Exception) {
                    Log.e(TAG, "Error in render thread", e)
                }
            }.also { it.isDaemon = true; it.start() }
        }

        private fun stopRender() {
            renderThread?.interrupt()
            renderThread = null
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            if (holder == null) return
            
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) {
                    Log.w(TAG, "lockCanvas() returned null")
                    return
                }
                
                val w = canvas.width
                val h = canvas.height
                
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "Invalid canvas dimensions: ${w}x${h}")
                    return
                }

                // Base black
                canvas.drawColor(Color.BLACK)

                // Parallax scene (sky, clouds, mountains, trees)
                scene.render(canvas)

                // Weather effects (rain, snow, lightning)
                weather.render(canvas, weatherEnabled)
                
                // Seasonal effects (fireworks, etc)
                seasonal.render(canvas, seasonalEnabled)

            } catch (e: Exception) {
                Log.e(TAG, "Error drawing frame", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error posting canvas", e)
                    }
                }
            }
        }

    }
}
