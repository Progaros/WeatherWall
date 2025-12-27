package com.example.weatherlwp.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.example.weatherlwp.rendering.DayNightCycle
import com.example.weatherlwp.rendering.CloudLayer
import com.example.weatherlwp.rendering.ParallaxScene
import com.example.weatherlwp.rendering.SeasonalEvents
import com.example.weatherlwp.rendering.WeatherLayer
import com.example.weatherlwp.weather.WeatherRepository
import com.example.weatherlwp.weather.WeatherType

class WeatherWallpaper : WallpaperService() {
    companion object {
        private const val TAG = "WeatherWallpaper"
    }

    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine() {
        private var renderThread: Thread? = null
        private var visible: Boolean = false

        private lateinit var scene: ParallaxScene
        private lateinit var seasonal: SeasonalEvents
        private val clouds = CloudLayer()
        private val weather = WeatherLayer()
        private val dayNight = DayNightCycle()

        // Preferences
        private var targetFps = 30
        private var cloudsEnabled = true
        private var parallaxEnabled = true
        private var weatherEnabled = true
        private var dayNightEnabled = true
        private var seasonalEnabled = true
        private var currentWeather = "none"
        private var cloudDensity = "auto"  // "auto" uses Breezy Weather data
        private var useBreezyWeather = true

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                scene = ParallaxScene(this@WeatherWallpaper)
                seasonal = SeasonalEvents(this@WeatherWallpaper)
                loadPrefs()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCreate", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            try {
                if (visible) {
                    loadPrefs()
                    if (parallaxEnabled) scene.start()
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
                parallaxEnabled = prefs.getBoolean("pref_parallax_enabled", true)
                cloudsEnabled = prefs.getBoolean("pref_clouds_enabled", true)
                weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
                dayNightEnabled = prefs.getBoolean("pref_daynight_enabled", true)
                seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
                useBreezyWeather = prefs.getBoolean("pref_use_breezy_weather", true)
                targetFps = prefs.getString("pref_max_fps", "30")?.toIntOrNull() ?: 30
                targetFps = targetFps.coerceIn(10, 60)
                
                // Cloud density - can be auto (from Breezy Weather) or manual
                cloudDensity = prefs.getString("pref_cloud_density", "auto") ?: "auto"
                
                val effectiveDensity = if (cloudDensity == "auto" && useBreezyWeather) {
                    // Get cloud density from Breezy Weather data
                    val breezyType = WeatherRepository.getWeatherType(this@WeatherWallpaper)
                    when (breezyType) {
                        WeatherType.CLEAR -> CloudLayer.CloudDensity.NONE
                        WeatherType.CLOUDY -> CloudLayer.CloudDensity.MEDIUM
                        WeatherType.RAIN, WeatherType.THUNDERSTORM -> CloudLayer.CloudDensity.HEAVY
                        WeatherType.SNOW -> CloudLayer.CloudDensity.OVERCAST
                        WeatherType.FOG -> CloudLayer.CloudDensity.HEAVY
                    }
                } else {
                    when (cloudDensity) {
                        "none" -> CloudLayer.CloudDensity.NONE
                        "light" -> CloudLayer.CloudDensity.LIGHT
                        "medium" -> CloudLayer.CloudDensity.MEDIUM
                        "heavy" -> CloudLayer.CloudDensity.HEAVY
                        "overcast" -> CloudLayer.CloudDensity.OVERCAST
                        else -> CloudLayer.CloudDensity.LIGHT
                    }
                }
                clouds.setDensity(effectiveDensity)

                // Reload scene settings
                scene.loadSettings()

                // Weather type - can be auto (from Breezy Weather) or manual
                currentWeather = prefs.getString("pref_weather_type", "auto") ?: "auto"
                
                val effectiveWeather = if (currentWeather == "auto" && useBreezyWeather) {
                    // Get weather from Breezy Weather data
                    val breezyType = WeatherRepository.getWeatherType(this@WeatherWallpaper)
                    when (breezyType) {
                        WeatherType.RAIN -> WeatherLayer.WeatherType.RAIN
                        WeatherType.SNOW -> WeatherLayer.WeatherType.SNOW
                        WeatherType.THUNDERSTORM -> WeatherLayer.WeatherType.LIGHTNING
                        else -> WeatherLayer.WeatherType.NONE
                    }
                } else {
                    when (currentWeather) {
                        "rain" -> WeatherLayer.WeatherType.RAIN
                        "snow" -> WeatherLayer.WeatherType.SNOW
                        "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                        else -> WeatherLayer.WeatherType.NONE
                    }
                }
                weather.setWeather(effectiveWeather)
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
                        if (!visible) {
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
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) return

                // Base black
                canvas.drawColor(Color.BLACK)

                // Day/Night sky gradient
                dayNight.render(canvas, dayNightEnabled)

                // Parallax scene (mountains, trees)
                if (parallaxEnabled) {
                    scene.render(canvas)
                }

                // Cloud layer
                clouds.render(canvas, cloudsEnabled)

                // Weather effects (rain, snow, lightning)
                weather.render(canvas, weatherEnabled)

                // Seasonal events (Christmas stars, New Year fireworks)
                seasonal.render(canvas, seasonalEnabled)

            } catch (e: Exception) {
                Log.e(TAG, "Error drawing frame", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unlocking canvas", e)
                    }
                }
            }
        }
    }
}
