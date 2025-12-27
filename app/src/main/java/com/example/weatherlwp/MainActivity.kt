package com.example.weatherlwp

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
import com.example.weatherlwp.rendering.CloudLayer
import com.example.weatherlwp.rendering.ParallaxScene
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var previewScene: ParallaxScene? = null
    private var cloudLayer: CloudLayer? = null
    private var previewThread: Thread? = null
    @Volatile private var previewRunning = false
    private var surfaceReady = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Setup live preview
        val previewSurface = findViewById<SurfaceView>(R.id.preview_surface)
        previewScene = ParallaxScene(this)
        cloudLayer = CloudLayer()
        
        // Set cloud density from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val cloudDensity = prefs.getString("pref_debug_cloud_override", "light") ?: "light"
        cloudLayer?.setDensity(when (cloudDensity) {
            "none" -> CloudLayer.CloudDensity.NONE
            "light" -> CloudLayer.CloudDensity.LIGHT
            "medium" -> CloudLayer.CloudDensity.MEDIUM
            "heavy" -> CloudLayer.CloudDensity.HEAVY
            "overcast" -> CloudLayer.CloudDensity.OVERCAST
            else -> CloudLayer.CloudDensity.LIGHT
        })
        
        previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface created")
                surfaceReady = true
                previewScene?.start()  // Start sensor for preview
                startPreview(holder)
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Preview surface changed: ${width}x${height}")
                // Reload settings when surface changes to ensure proper rendering
                previewScene?.loadSettings()
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface destroyed")
                surfaceReady = false
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
    
    private fun startPreview(holder: SurfaceHolder) {
        previewRunning = true
        var frameCount = 0
        previewThread = Thread {
            Log.d(TAG, "Preview thread started")
            // Small delay to ensure surface is ready
            SystemClock.sleep(100)
            
            Log.d(TAG, "Entering render loop: running=$previewRunning, ready=$surfaceReady")
            while (previewRunning && surfaceReady && !Thread.currentThread().isInterrupted) {
                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null && canvas.width > 0 && canvas.height > 0) {
                        // Render scene (includes sky gradient)
                        previewScene?.render(canvas)
                        // Render clouds on top
                        cloudLayer?.render(canvas, true)
                        frameCount++
                        // Log only first few frames
                        if (frameCount <= 3) {
                            Log.d(TAG, "Frame $frameCount rendered: ${canvas.width}x${canvas.height}")
                        }
                    }
                } catch (e: Exception) {
                    if (previewRunning) {
                        Log.e(TAG, "Error in preview render", e)
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            // Surface destroyed, stop loop
                            break
                        }
                    }
                }
                try {
                    Thread.sleep(50)  // ~20 FPS for preview
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "Preview thread stopped after $frameCount frames")
        }.also { 
            it.isDaemon = true
            it.start() 
        }
    }
    
    private fun stopPreview() {
        previewRunning = false
        previewThread?.interrupt()
        previewThread = null
    }
    
    override fun onResume() {
        super.onResume()
        previewScene?.loadSettings()
        
        // Reload cloud settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val cloudDensity = prefs.getString("pref_debug_cloud_override", "light") ?: "light"
        cloudLayer?.setDensity(when (cloudDensity) {
            "none" -> CloudLayer.CloudDensity.NONE
            "light" -> CloudLayer.CloudDensity.LIGHT
            "medium" -> CloudLayer.CloudDensity.MEDIUM
            "heavy" -> CloudLayer.CloudDensity.HEAVY
            "overcast" -> CloudLayer.CloudDensity.OVERCAST
            else -> CloudLayer.CloudDensity.LIGHT
        })
    }
    
    override fun onDestroy() {
        stopPreview()
        previewScene?.stop()
        super.onDestroy()
    }
}
