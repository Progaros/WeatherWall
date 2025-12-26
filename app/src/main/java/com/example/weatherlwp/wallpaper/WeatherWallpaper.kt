package com.example.weatherlwp.wallpaper

import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.example.weatherlwp.easter.MultiTapDetector
import com.example.weatherlwp.rendering.DayNightCycle
import com.example.weatherlwp.rendering.FogLayer
import com.example.weatherlwp.rendering.ParallaxScene
import com.example.weatherlwp.rendering.SeasonalEvents
import com.example.weatherlwp.rendering.WeatherLayer

class WeatherWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine(), MultiTapDetector.Listener {
        private var renderThread: Thread? = null
        private var visible: Boolean = false

        private lateinit var scene: ParallaxScene
        private val fog = FogLayer()
        private val weather = WeatherLayer()
        private val dayNight = DayNightCycle()
        private val seasonal = SeasonalEvents()

        private val tap = MultiTapDetector().apply { setListener(this@WeatherEngine) }
        private var easterMode = false

        // Preferences
        private var targetFps = 30
        private var fogEnabled = true
        private var parallaxEnabled = true
        private var weatherEnabled = true
        private var dayNightEnabled = true
        private var seasonalEnabled = true
        private var currentWeather = "none"

        // Pre-allocated paint for easter mode (no per-frame allocations)
        private val easterPaint = Paint().apply {
            color = Color.MAGENTA
            alpha = 35
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            scene = ParallaxScene(this@WeatherWallpaper)
            loadPrefs()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                loadPrefs()
                if (parallaxEnabled) scene.start()
                startRender()
            } else {
                stopRender()
                scene.stop()
            }
        }

        override fun onDestroy() {
            stopRender()
            scene.stop()
            super.onDestroy()
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                tap.onTap()
            }
            super.onTouchEvent(event)
        }

        override fun onTriggered(taps: Int) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
            val enabled = prefs.getBoolean("pref_easter_enabled", true)
            if (enabled) {
                easterMode = !easterMode
                // Also save to prefs so settings can read it
                prefs.edit().putBoolean("easter_mode_active", easterMode).apply()
            }
        }

        private fun loadPrefs() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
            parallaxEnabled = prefs.getBoolean("pref_parallax_enabled", true)
            fogEnabled = prefs.getBoolean("pref_fog_enabled", true)
            weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
            dayNightEnabled = prefs.getBoolean("pref_daynight_enabled", true)
            seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
            targetFps = prefs.getString("pref_max_fps", "30")?.toIntOrNull() ?: 30
            targetFps = targetFps.coerceIn(10, 60)

            // Load easter mode from prefs (can be set from settings too)
            easterMode = prefs.getBoolean("easter_mode_active", false)

            // Reload scene settings
            scene.loadSettings()

            // Weather type: none, rain, snow, lightning
            currentWeather = prefs.getString("pref_weather_type", "none") ?: "none"
            weather.setWeather(
                when (currentWeather) {
                    "rain" -> WeatherLayer.WeatherType.RAIN
                    "snow" -> WeatherLayer.WeatherType.SNOW
                    "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                    else -> WeatherLayer.WeatherType.NONE
                }
            )
        }

        private fun startRender() {
            if (renderThread != null) return
            renderThread = Thread {
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

                // Fog overlay
                fog.render(canvas, fogEnabled)

                // Weather effects (rain, snow, lightning)
                weather.render(canvas, weatherEnabled)

                // Seasonal events (Christmas stars, New Year fireworks)
                seasonal.render(canvas, seasonalEnabled)

                // Easter mode: simple overlay hint
                if (easterMode) {
                    canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), easterPaint)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
