package com.example.weatherlwp

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.preference.PreferenceManager
import com.example.weatherlwp.prefs.SettingsFragment
import com.example.weatherlwp.rendering.ParallaxScene
import com.example.weatherlwp.rendering.SeasonalEvents
import com.example.weatherlwp.rendering.WeatherLayer
import com.example.weatherlwp.weather.WeatherRepository
import com.example.weatherlwp.weather.WeatherType
import com.google.android.material.appbar.MaterialToolbar
import com.example.weatherlwp.parallax.ParallaxSensorManager
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val TARGET_FPS = 20
        private const val FRAME_TIME_MS = 1000L / TARGET_FPS
    }
    
    private var previewScene: ParallaxScene? = null
    private var weatherLayer: WeatherLayer? = null
    private var seasonalEvents: SeasonalEvents? = null
    
    // Dependencies
    private lateinit var sensorManager: ParallaxSensorManager
    private val weatherRepo = WeatherRepository
    private var previewThread: Thread? = null
    @Volatile private var previewRunning = false
    @Volatile private var surfaceReady = false
    private var surfaceHolder: SurfaceHolder? = null
    
    // Preference change listener for live preview updates
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        Log.d(TAG, "Preference changed: $key")
        // Reload settings on any preference change
        previewScene?.loadPrefs()
        seasonalEvents?.loadSettings()  // Reload seasonal settings (Christmas star, etc.)
        loadPreviewEffects()
    }
    
    private fun loadPreviewEffects() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        val useBreezy = prefs.getBoolean("pref_use_breezy_weather", true)
        val weatherSetting = prefs.getString("pref_weather_type", "auto") ?: "auto"
        
        // Determine effective weather type
        val effectiveWeather = when {
            debugEnabled && weatherSetting != "auto" -> {
                when (weatherSetting) {
                    "rain" -> WeatherLayer.WeatherType.RAIN
                    "snow" -> WeatherLayer.WeatherType.SNOW
                    "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                    else -> WeatherLayer.WeatherType.NONE
                }
            }
            weatherSetting == "auto" && useBreezy -> {
                val breezyType = WeatherRepository.getWeatherType(this)
                when (breezyType) {
                    WeatherType.RAIN -> WeatherLayer.WeatherType.RAIN
                    WeatherType.SNOW -> WeatherLayer.WeatherType.SNOW
                    WeatherType.THUNDERSTORM -> WeatherLayer.WeatherType.LIGHTNING
                    else -> WeatherLayer.WeatherType.NONE
                }
            }
            else -> {
                when (weatherSetting) {
                    "rain" -> WeatherLayer.WeatherType.RAIN
                    "snow" -> WeatherLayer.WeatherType.SNOW
                    "lightning" -> WeatherLayer.WeatherType.LIGHTNING
                    else -> WeatherLayer.WeatherType.NONE
                }
            }
        }
        weatherLayer?.setWeather(effectiveWeather)
    }
    
    // FPS tracking for debugging
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Initialize dependencies
        sensorManager = ParallaxSensorManager(this)

        // Setup live preview
        val previewSurface = findViewById<SurfaceView>(R.id.preview_surface)
        previewScene = ParallaxScene(this, sensorManager, weatherRepo)
        weatherLayer = WeatherLayer()
        seasonalEvents = SeasonalEvents(this)
        
        // Register preference change listener for live updates
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        
        previewSurface.setZOrderOnTop(true)
        previewSurface.holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
        
        previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface created, holder=$holder")
                surfaceHolder = holder
                surfaceReady = true
                previewScene?.start()  // Start sensor for preview
                startPreview()
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Preview surface changed: ${width}x${height}, format=$format")
                surfaceHolder = holder
                // Reload settings when surface changes to ensure proper rendering
                previewScene?.loadPrefs()
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface destroyed")
                surfaceReady = false
                surfaceHolder = null
                stopPreview()
                previewScene?.stop()
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.settings_container, SettingsFragment())
            }
        }
    }
    
    private fun startPreview() {
        // Stop any existing thread first
        stopPreview()
        
        previewRunning = true
        frameCount = 0
        lastFpsLogTime = SystemClock.elapsedRealtime()
        
        previewThread = Thread {
            Log.d(TAG, "Preview thread started")
            
            // Wait for surface to be fully ready
            var waitCount = 0
            while (!surfaceReady && waitCount < 10) {
                SystemClock.sleep(50)
                waitCount++
            }
            
            if (!surfaceReady) {
                Log.e(TAG, "Surface not ready after waiting, aborting preview thread")
                return@Thread
            }
            
            Log.d(TAG, "Starting render loop: running=$previewRunning, ready=$surfaceReady")
            
            var consecutiveNullCanvasCount = 0
            val maxNullCanvas = 5  // Give up after 5 consecutive null canvases
            
            while (previewRunning && surfaceReady && !Thread.currentThread().isInterrupted) {
                val frameStart = SystemClock.elapsedRealtime()
                val holder = surfaceHolder
                
                if (holder == null) {
                    Log.w(TAG, "SurfaceHolder is null, waiting...")
                    SystemClock.sleep(100)
                    continue
                }
                
                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    
                    if (canvas == null) {
                        consecutiveNullCanvasCount++
                        if (consecutiveNullCanvasCount >= maxNullCanvas) {
                            Log.e(TAG, "lockCanvas() returned null $consecutiveNullCanvasCount times, surface may be invalid")
                            // Don't break - the surface might become valid again
                        }
                        SystemClock.sleep(50)
                        continue
                    }
                    
                    consecutiveNullCanvasCount = 0  // Reset on success
                    
                    val cw = canvas.width
                    val ch = canvas.height
                    
                    if (cw <= 0 || ch <= 0) {
                        Log.w(TAG, "Invalid canvas dimensions: ${cw}x${ch}")
                        continue
                    }
                    
                    // Clear canvas with a fallback color first (helps debug)
                    canvas.drawColor(Color.BLACK)
                    
                    // Render scene (includes sky gradient and clouds)
                    previewScene?.render(canvas)
                    
                    // Render weather effects (rain, snow, lightning)
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val weatherEnabled = prefs.getBoolean("pref_weather_enabled", true)
                    val seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
                    weatherLayer?.render(canvas, weatherEnabled)
                    seasonalEvents?.render(canvas, seasonalEnabled)
                    
                    frameCount++
                    
                    // Log first 5 frames for debugging
                    if (frameCount <= 5) {
                        Log.d(TAG, "Frame $frameCount rendered successfully: ${cw}x${ch}")
                    }
                    
                    // Log FPS every 5 seconds
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFpsLogTime >= 5000) {
                        val elapsed = (now - lastFpsLogTime) / 1000f
                        val fps = frameCount / elapsed
                        Log.d(TAG, "Preview FPS: %.1f (${frameCount} frames in %.1fs)".format(fps, elapsed))
                        frameCount = 0
                        lastFpsLogTime = now
                    }
                    
                } catch (e: Exception) {
                    if (previewRunning && surfaceReady) {
                        Log.e(TAG, "Error in preview render loop", e)
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Failed to post canvas (surface destroyed?)")
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "Error posting canvas", e)
                            break
                        }
                    }
                }
                
                // Frame rate limiting
                val frameTime = SystemClock.elapsedRealtime() - frameStart
                val sleepTime = FRAME_TIME_MS - frameTime
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Preview thread interrupted during sleep")
                        break
                    }
                }
            }
            Log.d(TAG, "Preview thread exiting: running=$previewRunning, ready=$surfaceReady, totalFrames=$frameCount")
        }.also { 
            it.name = "PreviewRenderThread"
            it.isDaemon = true
            it.start() 
        }
    }
    
    private fun stopPreview() {
        Log.d(TAG, "Stopping preview...")
        previewRunning = false
        previewThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)  // Wait up to 500ms for thread to finish
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for preview thread to stop")
            }
        }
        previewThread = null
    }
    
    override fun onResume() {
        super.onResume()
        previewScene?.loadPrefs()
        loadPreviewEffects()
    }
    
    override fun onDestroy() {
        // Unregister preference listener
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        stopPreview()
        previewScene?.stop()
        super.onDestroy()
    }
}
