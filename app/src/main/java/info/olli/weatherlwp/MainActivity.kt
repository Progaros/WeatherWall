package info.olli.weatherlwp

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.rendering.ParallaxScene
import info.olli.weatherlwp.wallpaper.WeatherWallpaper
import info.olli.weatherlwp.weather.WeatherRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import info.olli.weatherlwp.parallax.ParallaxSensorManager
import info.olli.weatherlwp.settings.RootSettingsFragment
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout

class MainActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    companion object {
        private const val TAG = "MainActivity"
        // Match wallpaper FPS (30) for consistent parallax preview
        private const val TARGET_FPS = 30
        private const val FRAME_TIME_MS = 1000L / TARGET_FPS
        private const val PREF_RELOAD_THROTTLE_MS = 100L  // Max 10 reloads per second
        
        // Preview resize constants
        private const val PREVIEW_HEIGHT_MIN_DP = 80
        private const val PREVIEW_HEIGHT_DEFAULT_DP = 180
        private const val PREVIEW_HEIGHT_MAX_PERCENT = 0.90f  // Allow up to 90% of screen
        private const val SNAP_THRESHOLD_DP = 40  // Snap to default if within this range
        
        private const val TITLE_TAG = "settingsFragmentTitle"
    }
    
    private var previewScene: ParallaxScene? = null
    // NOTE: WeatherLayer and SeasonalEvents are handled internally by ParallaxScene
    // No separate instances needed here
    
    // Preview resize tracking
    private var previewHeightPx = 0
    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var density = 1f
    private var screenHeightPx = 0
    
    // Dependencies
    private lateinit var sensorManager: ParallaxSensorManager
    private val weatherRepo = WeatherRepository
    private var previewThread: Thread? = null
    @Volatile private var previewRunning = false
    @Volatile private var surfaceReady = false
    private var surfaceHolder: SurfaceHolder? = null
    
    // Preference change throttling
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPrefReload: Runnable? = null
    private var lastPrefReloadTime = 0L
    
    // FAB for applying wallpaper
    private var fabApply: ExtendedFloatingActionButton? = null
    
    // Toolbar for navigation
    private var toolbar: MaterialToolbar? = null
    
    // Preference change listener for live preview updates
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        Log.d(TAG, "Preference changed: $key")
        
        // Throttle preference reloads to avoid flickering during slider drags
        val now = SystemClock.elapsedRealtime()
        val timeSinceLastReload = now - lastPrefReloadTime
        
        // Cancel any pending reload
        pendingPrefReload?.let { mainHandler.removeCallbacks(it) }
        
        if (timeSinceLastReload >= PREF_RELOAD_THROTTLE_MS) {
            // Enough time has passed, reload immediately
            doPreferenceReload()
        } else {
            // Schedule reload after throttle delay
            val delay = PREF_RELOAD_THROTTLE_MS - timeSinceLastReload
            pendingPrefReload = Runnable { doPreferenceReload() }
            mainHandler.postDelayed(pendingPrefReload!!, delay)
        }
    }
    
    private fun doPreferenceReload() {
        lastPrefReloadTime = SystemClock.elapsedRealtime()
        // ParallaxScene.loadPrefs() handles all layer settings including weather and seasonal
        previewScene?.loadPrefs()
    }
    
    // FPS tracking for debugging
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Initialize density and screen dimensions for dp to px conversions
        density = resources.displayMetrics.density
        screenHeightPx = resources.displayMetrics.heightPixels

        // Initialize dependencies
        sensorManager = ParallaxSensorManager(this)

        // Setup live preview
        val previewSurface = findViewById<SurfaceView>(R.id.preview_surface)
        val previewContainer = findViewById<FrameLayout>(R.id.preview_container)
        // ParallaxScene handles all rendering including weather and seasonal internally
        // isPreview=true so it uses preview-specific trigger keys
        previewScene = ParallaxScene(this, sensorManager, weatherRepo, isPreview = true)
        
        // Load saved preview height or use default
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedHeightDp = prefs.getInt("preview_height_dp", PREVIEW_HEIGHT_DEFAULT_DP)
        previewHeightPx = (savedHeightDp * density).toInt()
        updatePreviewHeight(previewContainer)
        
        // Setup resize handle for dragging preview height
        setupResizeHandle(previewContainer)
        
        // Register preference change listener for live updates
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        
        // Use setZOrderMediaOverlay instead of setZOrderOnTop to allow UI elements on top
        previewSurface.setZOrderMediaOverlay(true)
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

        // Setup root settings fragment if this is a fresh start
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, RootSettingsFragment())
                .commit()
        } else {
            // Restore toolbar title from saved state
            title = savedInstanceState.getCharSequence(TITLE_TAG)
        }
        
        // Handle back stack changes to update toolbar
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                // Back to root - hide back button, show app title
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
                setTitle(R.string.app_name)
            }
        }
        
        // Setup FAB for applying wallpaper
        fabApply = findViewById(R.id.fab_apply)
        fabApply?.setOnClickListener {
            openWallpaperChooser()
        }
        updateFabVisibility()
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the current title
        outState.putCharSequence(TITLE_TAG, title)
    }
    
    /**
     * Handle navigation to sub-screen preferences.
     * Called when a Preference with app:fragment attribute is clicked.
     */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Instantiate the new Fragment
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment ?: return false
        ).apply {
            arguments = args
            setTargetFragment(caller, 0)
        }
        
        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        
        // Update toolbar with the preference title
        title = pref.title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        return true
    }
    
    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }
    
    override fun onResume() {
        super.onResume()
        // Check wallpaper status when returning to app
        updateFabVisibility()
        // ParallaxScene.loadPrefs() handles all layer settings including weather and seasonal
        previewScene?.loadPrefs()
    }
    
    /**
     * Check if our live wallpaper is currently applied
     */
    private fun isWallpaperApplied(): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(this)
            val myComponent = ComponentName(this, WeatherWallpaper::class.java)
            
            val info = if (Build.VERSION.SDK_INT >= 34) {
                wm.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            } else {
                @Suppress("DEPRECATION")
                wm.wallpaperInfo
            }
            
            if (info != null) {
                val currentComponent = ComponentName(info.packageName, info.serviceName)
                currentComponent == myComponent
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking wallpaper status", e)
            false  // Assume not applied if we can't check
        }
    }
    
    /**
     * Show/hide FAB based on whether wallpaper is already applied
     */
    private fun updateFabVisibility() {
        fabApply?.visibility = if (isWallpaperApplied()) View.GONE else View.VISIBLE
    }
    
    /**
     * Open the system wallpaper chooser for our live wallpaper
     */
    private fun openWallpaperChooser() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, WeatherWallpaper::class.java)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening wallpaper chooser", e)
            // Fallback to generic live wallpaper picker
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening live wallpaper chooser", e2)
            }
        }
    }
    
    private fun setupResizeHandle(previewContainer: FrameLayout) {
        val resizeHandle = findViewById<View>(R.id.resize_handle)
        
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartHeight = previewHeightPx
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - dragStartY
                    val newHeight = (dragStartHeight + deltaY).toInt()
                    val minPx = (PREVIEW_HEIGHT_MIN_DP * density).toInt()
                    // Calculate max as 90% of available screen height (minus toolbar ~56dp and tabs ~48dp)
                    val toolbarAndTabsHeight = (104 * density).toInt()  // toolbar + tabs
                    val maxPx = ((screenHeightPx - toolbarAndTabsHeight) * PREVIEW_HEIGHT_MAX_PERCENT).toInt()
                    previewHeightPx = newHeight.coerceIn(minPx, maxPx)
                    updatePreviewHeight(previewContainer)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Snap to default if close
                    val defaultPx = (PREVIEW_HEIGHT_DEFAULT_DP * density).toInt()
                    val snapThresholdPx = (SNAP_THRESHOLD_DP * density).toInt()
                    if (kotlin.math.abs(previewHeightPx - defaultPx) < snapThresholdPx) {
                        previewHeightPx = defaultPx
                        updatePreviewHeight(previewContainer)
                    }
                    // Save the height preference
                    val heightDp = (previewHeightPx / density).toInt()
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .edit()
                        .putInt("preview_height_dp", heightDp)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun updatePreviewHeight(previewContainer: FrameLayout) {
        val params = previewContainer.layoutParams
        params.height = previewHeightPx
        previewContainer.layoutParams = params
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
            var lastFrameTimeMs = 0L
            
            while (previewRunning && surfaceReady && !Thread.currentThread().isInterrupted) {
                val frameStart = SystemClock.elapsedRealtime()
                
                // Calculate real frame delta for accurate physics
                val deltaTimeMs = if (lastFrameTimeMs > 0) {
                    (frameStart - lastFrameTimeMs).coerceIn(1L, 200L)
                } else {
                    16L
                }
                lastFrameTimeMs = frameStart
                
                val holder = surfaceHolder
                
                if (holder == null) {
                    Log.w(TAG, "SurfaceHolder is null, waiting...")
                    SystemClock.sleep(100)
                    continue
                }
                
                var canvas: android.graphics.Canvas? = null
                try {
                    // Use hardware-accelerated canvas for better GPU performance
                    canvas = holder.lockHardwareCanvas()
                    if (canvas == null) {
                        // Fall back to software canvas if hardware canvas fails
                        canvas = holder.lockCanvas()
                    }
                    
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
                    
                    // Render scene with real delta time for accurate physics
                    // ParallaxScene internally handles weather and seasonal layers,
                    // so we only call scene.render() - no separate weather/seasonal calls needed
                    previewScene?.render(canvas, deltaTimeMs)
                    
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
    
    override fun onDestroy() {
        // Unregister preference listener
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        stopPreview()
        previewScene?.stop()
        super.onDestroy()
    }
}
