package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import kotlin.random.Random

class CloudLayer(private val context: Context) : SceneLayer {
    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 200
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val path = Path()
    private var width = 0f
    private var height = 0f
    
    private data class CloudData(
        var x: Float,
        var y: Float,
        val size: Float,
        val speed: Float,
        val alpha: Int,
        val seed: Long
    )
    
    private val clouds = ArrayList<CloudData>()
    private var cloudCount = 10
    private var windMultiplier = 1.0f  // Wind affects cloud speed
    
    companion object {
        private const val REFERENCE_ASPECT_RATIO = 0.5625f
        private const val WORLD_MIN_X = -4.0f
        private const val WORLD_MAX_X = 5.0f
    }
    
    init {
        reloadSettings()
    }
    
    private fun generateClouds() {
        clouds.clear()
        val random = Random(System.currentTimeMillis())
        
        for (i in 0 until cloudCount) {
            val x = WORLD_MIN_X + random.nextFloat() * (WORLD_MAX_X - WORLD_MIN_X)
            val y = 0.05f + random.nextFloat() * 0.35f
            val size = 0.08f + random.nextFloat() * 0.25f
            val speed = 0.0003f + (1f - size / 0.33f) * 0.001f
            val alpha = (60 + size * 400).toInt().coerceIn(50, 220)
            
            clouds.add(CloudData(x, y, size, speed, alpha, random.nextLong()))
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(deltaTimeMs: Long) {
        for (cloud in clouds) {
            cloud.x += cloud.speed * windMultiplier * (deltaTimeMs / 16f)
            if (cloud.x > WORLD_MAX_X) {
                cloud.x = WORLD_MIN_X
            }
        }
    }

    override fun draw(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        val scaleX = height * REFERENCE_ASPECT_RATIO
        val (px, py) = parallaxCalculator.getOffsetForDepth(0.08f) // Clouds are far
        
        canvas.save()
        canvas.translate(px, py)
        
        for (cloud in clouds) {
            paint.alpha = cloud.alpha
            
            val cx = (cloud.x - 0.5f) * scaleX + width / 2f
            val cy = cloud.y * height
            val cSize = cloud.size * height * 0.5f
            
            drawCloud(canvas, cx, cy, cSize, cloud.seed)
        }
        
        canvas.restore()
    }
    
    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, size: Float, seed: Long) {
        val random = Random(seed)
        val puffs = 3 + random.nextInt(3)
        
        for (i in 0 until puffs) {
            val px = cx + (random.nextFloat() - 0.5f) * size * 1.5f
            val py = cy + (random.nextFloat() - 0.5f) * size * 0.5f
            val radius = size * (0.5f + random.nextFloat() * 0.5f)
            
            canvas.drawCircle(px, py, radius, paint)
        }
    }

    override fun updateTheme(theme: String, isNight: Boolean) {
        // Adjust cloud color/alpha
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = prefs.getBoolean("pref_clouds_enabled", true)
        
        if (!enabled) {
            cloudCount = 0
        } else {
            // Check debug override first
            val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
            val density = if (debugEnabled) {
                prefs.getString("pref_debug_cloud_override", "auto") ?: "auto"
            } else {
                prefs.getString("pref_cloud_density", "auto") ?: "auto"
            }
            
            cloudCount = when (density) {
                "none" -> 0
                "light" -> 4
                "medium" -> 10
                "heavy" -> 20
                "overcast" -> 35
                else -> 10 // Auto default
            }
        }
        
        // Wind affects cloud speed
        val windVal = prefs.getInt("pref_wind_strength", 50)
        windMultiplier = 0.5f + (windVal / 50f)  // 0.5x at 0 wind, 2.5x at 100 wind
        
        android.util.Log.d("CloudLayer", "Cloud count: $cloudCount, wind: $windMultiplier")
        generateClouds()
    }

    override fun destroy() {
        // Cleanup
    }
}
