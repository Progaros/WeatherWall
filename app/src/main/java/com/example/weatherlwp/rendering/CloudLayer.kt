package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import com.example.weatherlwp.simulation.CloudDensity
import com.example.weatherlwp.simulation.SimulationState
import kotlin.random.Random

/**
 * Renders clouds with wind-based movement.
 * 
 * Uses SimulationState for:
 * - windStrength (affects cloud speed)
 * - timeSeconds (for consistent animation across reloads)
 * - cloudDensity (how many clouds to show)
 * - isNight (affects cloud color)
 */
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
    
    /**
     * Cloud data with initial position.
     * Actual position is computed from initial + time * speed
     */
    private data class CloudData(
        val initialX: Float,   // Starting X position
        val y: Float,          // Y position (doesn't change)
        val size: Float,
        val speed: Float,
        val alpha: Int,
        val seed: Long
    )
    
    private val clouds = ArrayList<CloudData>()
    private var cloudCount = 10
    
    companion object {
        private const val REFERENCE_ASPECT_RATIO = 0.5625f
        private const val WORLD_MIN_X = -4.0f
        private const val WORLD_MAX_X = 5.0f
        private const val WORLD_WIDTH = WORLD_MAX_X - WORLD_MIN_X
    }
    
    init {
        reloadSettings()
    }
    
    private fun generateClouds() {
        clouds.clear()
        val random = Random(42) // Fixed seed for consistent cloud shapes
        
        for (i in 0 until cloudCount) {
            val x = WORLD_MIN_X + random.nextFloat() * WORLD_WIDTH
            val y = 0.05f + random.nextFloat() * 0.35f
            val size = 0.08f + random.nextFloat() * 0.25f
            // Base speed - smaller clouds move faster (further away)
            val speed = 0.05f + (1f - size / 0.33f) * 0.1f
            val alpha = (60 + size * 400).toInt().coerceIn(50, 220)
            
            clouds.add(CloudData(x, y, size, speed, alpha, random.nextLong()))
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Position is computed in draw() using state.timeSeconds
        // No mutable state updates needed here
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Use cloud count from simulation state
        val targetCount = state.getCloudCount()
        if (targetCount != cloudCount) {
            cloudCount = targetCount
            generateClouds()
        }
        
        val scaleX = height * REFERENCE_ASPECT_RATIO
        val (px, py) = parallaxCalculator.getOffsetForDepth(0.08f) // Clouds are far
        
        // Wind affects cloud speed
        val windSpeed = 0.5f + state.windStrength * 2f  // 0.5x to 2.5x
        
        // Night clouds are slightly darker
        if (state.isNight) {
            paint.color = Color.rgb(180, 180, 200)
        } else {
            paint.color = Color.WHITE
        }
        
        canvas.save()
        canvas.translate(px, py)
        
        for (cloud in clouds) {
            paint.alpha = cloud.alpha
            
            // Calculate cloud X position based on time
            // Use timeSeconds for consistent position at any given real-world time
            val timeOffset = state.timeSeconds * cloud.speed * windSpeed
            var cloudX = cloud.initialX + timeOffset
            
            // Wrap around when cloud moves past the right edge
            cloudX = WORLD_MIN_X + ((cloudX - WORLD_MIN_X) % WORLD_WIDTH + WORLD_WIDTH) % WORLD_WIDTH
            
            val cx = (cloudX - 0.5f) * scaleX + width / 2f
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

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = prefs.getBoolean("pref_clouds_enabled", true)
        
        if (!enabled) {
            cloudCount = 0
            clouds.clear()
        }
        // Cloud count is now managed by SimulationState.getCloudCount()
        // The draw() method will update cloudCount when state changes
        
        android.util.Log.d("CloudLayer", "Clouds enabled: $enabled, count managed by state")
    }

    override fun destroy() {
        // Cleanup
    }
}
