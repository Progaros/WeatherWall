package info.olli.weatherlwp.wallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.Trace
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import info.olli.weatherlwp.BuildConfig
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationSettingsRepository
import info.olli.weatherlwp.rendering.CanvasHelper
import info.olli.weatherlwp.rendering.ParallaxScene
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WeatherWallpaper : WallpaperService() {
    companion object {
        private const val TAG = "WeatherWallpaper"

        // Small pacing slack (< one vsync period) so a frame landing just before the target
        // interval still renders instead of waiting a whole extra vsync (vsync quantization).
        // Without it a 60fps cap on a 120Hz panel drops to ~50, and 30 -> ~26.
        private const val FRAME_PACING_SLACK_NANOS = 4_000_000L
        // When the OS Battery Saver is active, cap the active frame rate to conserve power.
        // This only ever lowers the rate; idle mode still applies its own (lower) cap.
        private const val POWER_SAVE_MAX_FPS = 30
        /** Bucket atmosphere phase into discrete categories: 0=night, 1=golden hour, 2=day, 3=blue hour. */
        private fun bucketAtmospherePhase(phaseVal: Float): Int = when {
            phaseVal >= 0.5f -> 2
            phaseVal >= -0.25f -> 1
            phaseVal >= -0.75f -> 3
            else -> 0
        }
    }

    override fun onCreateEngine(): Engine = WeatherEngine()

    inner class WeatherEngine : Engine(), Choreographer.FrameCallback {
        private var choreographer: Choreographer? = null
        @Volatile private var frameCallbackPosted: Boolean = false
        @Volatile private var isVisible: Boolean = false
        private var lastDrawFrameNanos = 0L
        private var preferHardwareCanvas = true

        // OS Battery Saver awareness: cached flag updated via broadcast, capping FPS when active.
        private var powerManager: PowerManager? = null
        @Volatile private var powerSaveActive = false
        private var powerSaveReceiver: BroadcastReceiver? = null

        private lateinit var scene: ParallaxScene
        private val sensorManager = ParallaxSensorManager(this@WeatherWallpaper)
        private val weatherRepo = WeatherRepository
        private lateinit var settingsRepository: SimulationSettingsRepository
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var settingsCollectionJob: Job? = null
        @Volatile private var currentSettings: SimulationSettings = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
        
        // Track atmosphere phase for notifyColorsChanged()
        private var lastAtmospherePhase = -1

        private fun fallbackSkyClearColor(phase: Int): Int = when (phase) {
            1 -> RenderConstants.SKY_DAWN_TOP
            2 -> RenderConstants.SKY_DAY_TOP
            3 -> RenderConstants.SKY_DUSK_TOP
            else -> RenderConstants.SKY_NIGHT_TOP
        }

        // Preferences
        private var targetFps = 30

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // This wallpaper uses device tilt for parallax, not scroll offsets.
            // Disabling offset notifications is a performance optimization per Android docs.
            setOffsetNotificationsEnabled(false)
            try {
                choreographer = Choreographer.getInstance()
                powerManager = this@WeatherWallpaper.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerSaveActive = powerManager?.isPowerSaveMode == true
                registerPowerSaveReceiver()
                settingsRepository = SimulationSettingsRepository(this@WeatherWallpaper)
                currentSettings = settingsRepository.settingsFlow.value
                scene = ParallaxScene(this@WeatherWallpaper, sensorManager, weatherRepo)
                applySettings(currentSettings)
                startSettingsCollection()
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
                preferHardwareCanvas = true
                runCatching { scene.start() }
                    .onFailure { Log.e(TAG, "scene.start() failed, continuing without sensors", it) }
                startRender()
                // Paint at least one frame immediately so previews don't stay black.
                drawFrame(System.nanoTime())
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

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            try {
                // Reset frame timing when surface geometry changes to avoid delta spikes.
                lastFrameTimeMs = 0L
                lastDrawFrameNanos = 0L
                preferHardwareCanvas = true

                // Render one frame immediately so resized surfaces don't show stale buffers.
                if (isVisible && width > 0 && height > 0) {
                    drawFrame(System.nanoTime())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSurfaceChanged", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            try {
                if (visible) {
                    Log.d(TAG, "Wallpaper visible - starting render loop and sensors")
                    lastFrameTimeMs = 0L
                    preferHardwareCanvas = true
                    // scene.start() resets parallax to center and starts sensors.
                    // First frame will always render centered (no stale offset).
                    runCatching { scene.start() }
                        .onFailure { Log.e(TAG, "scene.start() failed, continuing without sensors", it) }
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
            isVisible = false

            runCatching { unregisterPowerSaveReceiver() }
                .onFailure { Log.e(TAG, "Error unregistering power-save receiver", it) }

            // Always stop frame callbacks first.
            runCatching { stopRender() }
                .onFailure { Log.e(TAG, "Error stopping render loop in onDestroy", it) }

            // Stop settings collection before closing repository/scope.
            runCatching {
                settingsCollectionJob?.cancel()
                settingsCollectionJob = null
            }.onFailure { Log.e(TAG, "Error cancelling settings collection", it) }

            if (::settingsRepository.isInitialized) {
                runCatching { settingsRepository.close() }
                    .onFailure { Log.e(TAG, "Error closing settings repository", it) }
            }

            runCatching { engineScope.cancel() }
                .onFailure { Log.e(TAG, "Error cancelling engine scope", it) }

            if (::scene.isInitialized) {
                runCatching {
                    scene.stop()
                    sensorManager.clearCallbacks()
                    scene.destroy()
                }.onFailure { Log.e(TAG, "Error destroying scene", it) }
            }

            // Clear references so no frame callback or collector can re-post work.
            choreographer = null
            lastDrawFrameNanos = 0L
            lastFrameTimeMs = 0L
            frameCallbackPosted = false
            preferHardwareCanvas = true
            currentSettings = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
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
                val phase = bucketAtmospherePhase(phaseVal)
                
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

        private fun startSettingsCollection() {
            if (settingsCollectionJob != null) return

            settingsCollectionJob = engineScope.launch {
                settingsRepository.settingsFlow.collect { settings ->
                    applySettings(settings)
                }
            }
        }

        private fun registerPowerSaveReceiver() {
            if (powerSaveReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    powerSaveActive = powerManager?.isPowerSaveMode == true
                }
            }
            powerSaveReceiver = receiver
            runCatching {
                this@WeatherWallpaper.registerReceiver(
                    receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                )
            }.onFailure { Log.e(TAG, "Failed to register power-save receiver", it) }
        }

        private fun unregisterPowerSaveReceiver() {
            val receiver = powerSaveReceiver ?: return
            powerSaveReceiver = null
            runCatching { this@WeatherWallpaper.unregisterReceiver(receiver) }
                .onFailure { Log.e(TAG, "Failed to unregister power-save receiver", it) }
        }

        private fun applySettings(settings: SimulationSettings) {
            currentSettings = settings

            try {
                targetFps = settings.getString("pref_max_fps", PreferenceDefaults.MAX_FPS).toIntOrNull() ?: 30
                targetFps = targetFps.coerceIn(10, 60)
                
                // Idle mode for battery optimization
                idleModeEnabled = settings.getBoolean("pref_idle_mode_enabled", PreferenceDefaults.IDLE_ENABLED)
                idleFps = settings.getInt("pref_idle_fps", PreferenceDefaults.IDLE_FPS).coerceIn(5, 20)
                // Idle timeout is now a ListPreference (String) with values in seconds
                idleTimeoutMs = (settings.getString("pref_idle_timeout", PreferenceDefaults.IDLE_TIMEOUT).toLongOrNull() ?: 5L) * 1000L
                
                // Push full immutable settings snapshot into the scene graph.
                if (::scene.isInitialized) {
                    scene.reloadSettings(settings)
                }
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
            if (!isVisible) return

            frameCount = 0L
            totalDrawTimeMs = 0L
            lastLogTime = SystemClock.uptimeMillis()
            maxFrameTimeMs = 0L
            lastActivityTimeMs = lastLogTime
            isIdle = false
            lastDrawFrameNanos = 0L
            postNextFrameCallback()
        }

        private fun stopRender() {
            choreographer?.removeFrameCallback(this)
            frameCallbackPosted = false
            lastDrawFrameNanos = 0L
            lastFrameTimeMs = 0L
        }

        private fun postNextFrameCallback() {
            if (frameCallbackPosted || !isVisible) return
            val choreographerInstance = choreographer ?: Choreographer.getInstance().also { choreographer = it }
            choreographerInstance.postFrameCallback(this)
            frameCallbackPosted = true
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameCallbackPosted = false
            if (!isVisible) return

            try {
                // Idle mode: check for scene activity and adjust effective FPS.
                val nowMs = SystemClock.uptimeMillis()
                if (idleModeEnabled) {
                    if (checkSceneActivity()) {
                        lastActivityTimeMs = nowMs
                        isIdle = false
                    } else if (nowMs - lastActivityTimeMs > idleTimeoutMs) {
                        isIdle = true
                    }
                } else {
                    isIdle = false
                }

                // Cap rendering frequency while staying synced to display vsync callbacks.
                // In OS Battery Saver mode, cap the active rate to reduce power draw.
                val activeFps = if (powerSaveActive) minOf(targetFps, POWER_SAVE_MAX_FPS) else targetFps
                val effectiveFps = (if (isIdle) idleFps else activeFps).coerceAtLeast(1)
                val minFrameIntervalNanos = 1_000_000_000L / effectiveFps.toLong()
                val renderThresholdNanos = (minFrameIntervalNanos - FRAME_PACING_SLACK_NANOS).coerceAtLeast(1L)
                val shouldRender =
                    lastDrawFrameNanos == 0L || (frameTimeNanos - lastDrawFrameNanos) >= renderThresholdNanos

                if (shouldRender) {
                    val start = SystemClock.uptimeMillis()
                    Trace.beginSection("Wallpaper_Frame")
                    try {
                        drawFrame(frameTimeNanos)
                    } finally {
                        Trace.endSection()
                    }
                    val dt = SystemClock.uptimeMillis() - start

                    // Track performance (debug builds only)
                    if (BuildConfig.DEBUG) {
                        frameCount++
                        totalDrawTimeMs += dt
                        if (dt > maxFrameTimeMs) maxFrameTimeMs = dt

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
                    }

                    lastDrawFrameNanos = frameTimeNanos
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame callback", e)
            } finally {
                if (isVisible) {
                    postNextFrameCallback()
                }
            }
        }
        
        // Track frame timing for proper delta calculation
        private var lastFrameTimeMs = 0L

        private fun drawFrame(frameTimeNanos: Long) {
            val holder = surfaceHolder ?: return
            
            // Calculate real frame delta for accurate physics
            val now = frameTimeNanos / 1_000_000L
            val deltaTimeMs = if (lastFrameTimeMs > 0) {
                (now - lastFrameTimeMs).coerceIn(1L, 200L)  // Clamp to avoid huge jumps
            } else {
                16L  // First frame defaults to ~60fps
            }
            lastFrameTimeMs = now
            
            var canvas: Canvas? = null
            try {
                canvas = acquireCanvas(holder)
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

                // Clear canvas with sky-matched color (defensive: invisible if overscan is correct)
                canvas.drawColor(fallbackSkyClearColor(lastAtmospherePhase))

                // Parallax scene (sky, clouds, mountains, trees, weather overlays, seasonal)
                // Pass actual frame delta for accurate physics/smoothing
                scene.render(canvas, deltaTimeMs, isIdle)
                
                // Notify system when atmosphere phase changes (dawn/day/dusk/night)
                // for Material You dynamic theming updates
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val state = scene.getSimulationState()
                    // Bucket: -1=night, -0.5=blue hour, 0=golden hour, 1=day
                    val phaseVal = state?.getAtmospherePhase() ?: -1f
                    val currentPhase = bucketAtmospherePhase(phaseVal)
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

        /**
         * Acquire the best available canvas for this frame.
         * Tries hardware canvas first, then safely falls back to software canvas.
         */
        private fun acquireCanvas(holder: SurfaceHolder): Canvas? {
            return CanvasHelper.acquire(holder, preferHardwareCanvas) {
                preferHardwareCanvas = false
            }
        }

    }
}
