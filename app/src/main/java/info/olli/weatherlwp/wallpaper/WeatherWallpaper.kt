package info.olli.weatherlwp.wallpaper

import android.app.WallpaperColors
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.rendering.ParallaxScene
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.parallax.ParallaxSensorManager

class WeatherWallpaper : WallpaperService() {
    companion object {
        private const val TAG = "WeatherWallpaper"
    }

    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine() {
        private var renderThread: Thread? = null
        @Volatile private var isVisible: Boolean = false

        private lateinit var scene: ParallaxScene
        private val sensorManager = ParallaxSensorManager(this@WeatherWallpaper)
        private val weatherRepo = WeatherRepository
        
        // Track atmosphere phase for notifyColorsChanged()
        private var lastAtmospherePhase = -1

        // Preferences
        private var targetFps = 30
        
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
                    Log.d(TAG, "Wallpaper visible - starting render loop and sensors")
                    // Reset frame timing to avoid large delta on first frame after resume
                    lastFrameTimeMs = 0L
                    loadPrefs()
                    scene.start()  // Start sensors
                    startRender()
                } else {
                    Log.d(TAG, "Wallpaper hidden - stopping render loop and sensors (battery optimization)")
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
                scene.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDestroy", e)
            }
            super.onDestroy()
        }

        /**
         * Report wallpaper colors to the system for Material You dynamic theming.
         * Uses current sky and terrain colors based on time of day.
         */
        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            try {
                val simState = if (::scene.isInitialized) scene.getSimulationState() else null
                val phaseVal = simState?.getAtmospherePhase() ?: -1f
                val phase = when {
                    phaseVal >= 0.5f -> 2    // day
                    phaseVal >= -0.25f -> 1  // golden hour (dawn/dusk)
                    phaseVal >= -0.75f -> 3  // blue hour
                    else -> 0                // night
                }
                
                val (primary, secondary, tertiary) = when (phase) {
                    1 -> Triple(  // Dawn
                        RenderConstants.SKY_DAWN_MID,
                        RenderConstants.SKY_DAWN_BOTTOM,
                        RenderConstants.HILL_BASE_COLOR
                    )
                    2 -> Triple(  // Day
                        RenderConstants.SKY_DAY_TOP,
                        RenderConstants.SKY_DAY_BOTTOM,
                        RenderConstants.HILL_BASE_COLOR
                    )
                    3 -> Triple(  // Dusk
                        RenderConstants.SKY_DUSK_MID,
                        RenderConstants.SKY_DUSK_BOTTOM,
                        RenderConstants.HILL_BASE_COLOR
                    )
                    else -> Triple(  // Night
                        RenderConstants.SKY_NIGHT_TOP,
                        RenderConstants.SKY_NIGHT_MID,
                        RenderConstants.HILL_BASE_COLOR
                    )
                }
                return WallpaperColors(
                    Color.valueOf(primary),
                    Color.valueOf(secondary),
                    Color.valueOf(tertiary)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in onComputeColors", e)
                return null
            }
        }

        private fun loadPrefs() {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@WeatherWallpaper)
                targetFps = prefs.getString("pref_max_fps", PreferenceDefaults.MAX_FPS)?.toIntOrNull() ?: 30
                targetFps = targetFps.coerceIn(10, 60)
                
                // Idle mode for battery optimization
                idleModeEnabled = prefs.getBoolean("pref_idle_mode_enabled", PreferenceDefaults.IDLE_ENABLED)
                idleFps = prefs.getInt("pref_idle_fps", PreferenceDefaults.IDLE_FPS).coerceIn(5, 20)
                // Idle timeout is now a ListPreference (String) with values in seconds
                idleTimeoutMs = (prefs.getString("pref_idle_timeout", PreferenceDefaults.IDLE_TIMEOUT)?.toLongOrNull() ?: 5L) * 1000L
                
                // Reload scene settings (handles parallax, clouds, day/night, terrain, weather, seasonal)
                // ParallaxScene internally manages WeatherLayer and SeasonalEvents
                scene.loadPrefs()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading prefs", e)
            }
        }

        // Performance tracking for debug logging
        private var frameCount = 0L
        private var totalDrawTimeMs = 0L
        private var lastLogTime = 0L
        private var maxFrameTimeMs = 0L
        
        // Idle mode for battery optimization: reduce FPS when scene is static
        private var idleModeEnabled = true
        private var idleFps = 10  // Frame rate when idle (minimal updates for time/weather changes)
        private var lastActivityTimeMs = 0L
        private var idleTimeoutMs = 5000L  // 5 seconds of no activity before entering idle
        private var isIdle = false
        
        // Check if there's any scene activity (parallax movement, weather, etc.)
        private fun checkSceneActivity(): Boolean {
            // Consider active if:
            // 1. Parallax is moving (sensor input detected)
            // 2. Weather effects are active
            // 3. Random events are playing
            val parallaxActive = scene.isParallaxActive()
            val weatherActive = scene.isWeatherActive()
            val eventsActive = scene.hasActiveEvents()
            
            return parallaxActive || weatherActive || eventsActive
        }
        
        private fun startRender() {
            if (renderThread != null) return
            frameCount = 0L
            totalDrawTimeMs = 0L
            lastLogTime = SystemClock.uptimeMillis()
            maxFrameTimeMs = 0L
            
            renderThread = Thread {
                // Elevate thread priority for smoother rendering
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        if (!isVisible) {
                            SystemClock.sleep(250)
                            continue
                        }
                        
                        // Idle mode: check for scene activity and adjust FPS
                        val now = SystemClock.uptimeMillis()
                        if (idleModeEnabled) {
                            if (checkSceneActivity()) {
                                lastActivityTimeMs = now
                                isIdle = false
                            } else if (now - lastActivityTimeMs > idleTimeoutMs) {
                                isIdle = true
                            }
                        } else {
                            isIdle = false
                        }
                        
                        // Use idle FPS when scene is static, otherwise use target FPS
                        val effectiveFps = if (isIdle) idleFps else targetFps
                        val frameTimeMs = (1000L / effectiveFps.toLong()).coerceAtLeast(16L)

                        val start = SystemClock.uptimeMillis()
                        drawFrame()
                        val dt = SystemClock.uptimeMillis() - start
                        
                        // Track performance
                        frameCount++
                        totalDrawTimeMs += dt
                        if (dt > maxFrameTimeMs) maxFrameTimeMs = dt
                        
                        // Log performance stats every 5 seconds
                        if (start - lastLogTime > 5000) {
                            val avgFrameMs = if (frameCount > 0) totalDrawTimeMs / frameCount else 0
                            val actualFps = if (avgFrameMs > 0) 1000 / avgFrameMs else 0
                            val idleInfo = if (isIdle) " (IDLE)" else ""
                            Log.d(TAG, "Perf: ${frameCount} frames, avg=${avgFrameMs}ms, max=${maxFrameTimeMs}ms, ~${actualFps}fps$idleInfo")
                            frameCount = 0L
                            totalDrawTimeMs = 0L
                            maxFrameTimeMs = 0L
                            lastLogTime = start
                        }
                        
                        val sleep = frameTimeMs - dt
                        // Always sleep at least 1ms to prevent 100% CPU when frame exceeds budget
                        SystemClock.sleep(sleep.coerceAtLeast(1L))
                    }
                } catch (e: InterruptedException) {
                    // Normal exit
                } catch (e: Exception) {
                    Log.e(TAG, "Error in render thread", e)
                }
            }.also { it.isDaemon = true; it.start() }
        }

        private fun stopRender() {
            val thread = renderThread
            renderThread = null
            thread?.interrupt()
            // Wait for thread to actually stop to prevent race conditions
            try {
                thread?.join(500)  // Wait up to 500ms
            } catch (e: InterruptedException) {
                // Ignore
            }
        }
        
        // Track frame timing for proper delta calculation
        private var lastFrameTimeMs = 0L

        private fun drawFrame() {
            val holder = surfaceHolder
            if (holder == null) return
            
            // Calculate real frame delta for accurate physics
            val now = SystemClock.uptimeMillis()
            val deltaTimeMs = if (lastFrameTimeMs > 0) {
                (now - lastFrameTimeMs).coerceIn(1L, 200L)  // Clamp to avoid huge jumps
            } else {
                16L  // First frame defaults to ~60fps
            }
            lastFrameTimeMs = now
            
            var canvas: Canvas? = null
            try {
                // Use hardware-accelerated canvas for better GPU performance
                canvas = holder.lockHardwareCanvas()
                if (canvas == null) {
                    // Fall back to software canvas if hardware canvas fails
                    canvas = holder.lockCanvas()
                }
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

                // Parallax scene (sky, clouds, mountains, trees, weather overlays, seasonal)
                // Pass actual frame delta for accurate physics/smoothing
                scene.render(canvas, deltaTimeMs, isIdle)
                
                // Notify system when atmosphere phase changes (dawn/day/dusk/night)
                // for Material You dynamic theming updates
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val state = scene.getSimulationState()
                    // Bucket: -1=night, -0.5=blue hour, 0=golden hour, 1=day
                    val phaseVal = state?.getAtmospherePhase() ?: -1f
                    val currentPhase = when {
                        phaseVal >= 0.5f -> 2    // day
                        phaseVal >= -0.25f -> 1  // golden hour (dawn/dusk)
                        phaseVal >= -0.75f -> 3  // blue hour (dusk/dawn)
                        else -> 0                // night
                    }
                    if (currentPhase != lastAtmospherePhase && lastAtmospherePhase >= 0) {
                        notifyColorsChanged()
                    }
                    lastAtmospherePhase = currentPhase
                }

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
