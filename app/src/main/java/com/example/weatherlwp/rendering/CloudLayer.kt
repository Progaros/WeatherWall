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
 * - isNight/isDusk/isDawn (affects cloud color tint)
 * - timeOfDayHours (for sunrise/sunset coloring)
 * 
 * Uses RenderConstants for:
 * - World coordinate bounds
 * - Parallax depth
 * - Reference aspect ratio
 * 
 * Design notes (from ai.md):
 * - Clouds should be soft, wispy, and semi-transparent
 * - Use low alpha (30-50%) with blur effects
 * - Apply time-of-day tinting (orange at sunset, purple at night)
 */
class CloudLayer(private val context: Context) : SceneLayer {
    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 80  // Lower alpha for atmospheric softness
        maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)  // Larger blur for wispier effect
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
        val alpha: Int,        // Base alpha (30-80 for soft appearance)
        val seed: Long,
        val elongation: Float  // Horizontal stretch factor for wispy shape
    )
    
    private val clouds = ArrayList<CloudData>()
    private var cloudCount = 10
    
    init {
        reloadSettings()
    }
    
    private fun generateClouds() {
        clouds.clear()
        val random = Random(42) // Fixed seed for consistent cloud shapes
        
        for (i in 0 until cloudCount) {
            val x = RenderConstants.WORLD_MIN_X + random.nextFloat() * RenderConstants.WORLD_WIDTH
            val y = 0.05f + random.nextFloat() * 0.35f
            val size = 0.08f + random.nextFloat() * 0.25f
            // Base speed - smaller clouds move faster (further away)
            val speed = 0.05f + (1f - size / 0.33f) * 0.1f
            // Lower alpha for lighter, more see-through clouds
            // Small clouds (far/thin) are more transparent: 15-40
            // Large clouds (near/thick) are slightly more opaque: 40-60
            val alpha = (15 + size * 135).toInt().coerceIn(15, 60)
            // Horizontal elongation for wispy appearance (1.5-3.0)
            val elongation = 1.5f + random.nextFloat() * 1.5f
            
            clouds.add(CloudData(x, y, size, speed, alpha, random.nextLong(), elongation))
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Position is computed in draw() using state.timeSeconds
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Use cloud count from simulation state
        val targetCount = state.getCloudCount()
        if (targetCount != cloudCount) {
            cloudCount = targetCount
            generateClouds()
        }
        
        val scaleX = height * RenderConstants.REFERENCE_ASPECT_RATIO
        val (px, py) = parallaxCalculator.getOffsetForDepth(RenderConstants.PARALLAX_CLOUDS)
        
        // Wind affects cloud speed
        val windSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN + 
            state.windStrength * (RenderConstants.WIND_CLOUD_SPEED_MAX - RenderConstants.WIND_CLOUD_SPEED_MIN)
        
        // Time-of-day cloud color tinting
        val hour = state.timeOfDayHours
        val cloudColor = when {
            // Night: bluish-gray clouds
            state.isNight -> Color.rgb(150, 155, 180)
            // Dawn/dusk: orange/pink tint
            state.isDawn -> {
                val t = (hour - 5f).coerceIn(0f, 2f) / 2f
                RenderConstants.lerpColor(Color.rgb(180, 170, 190), Color.rgb(255, 200, 180), t)
            }
            state.isDusk -> {
                val t = (hour - 17f).coerceIn(0f, 2f) / 2f
                RenderConstants.lerpColor(Color.rgb(255, 220, 200), Color.rgb(200, 170, 190), t)
            }
            // Day: white with slight warmth
            else -> Color.rgb(250, 250, 255)
        }
        paint.color = cloudColor
        
        canvas.save()
        canvas.translate(px, py)
        
        for (cloud in clouds) {
            paint.alpha = cloud.alpha
            
            // Calculate cloud X position based on time
            val timeOffset = state.timeSeconds * cloud.speed * windSpeed
            var cloudX = cloud.initialX + timeOffset
            
            // Wrap around when cloud moves past the right edge
            cloudX = RenderConstants.WORLD_MIN_X + 
                ((cloudX - RenderConstants.WORLD_MIN_X) % RenderConstants.WORLD_WIDTH + RenderConstants.WORLD_WIDTH) % RenderConstants.WORLD_WIDTH
            
            val cx = (cloudX - 0.5f) * scaleX + width / 2f
            val cy = cloud.y * height
            val cSize = cloud.size * height * 0.5f
            
            drawCloud(canvas, cx, cy, cSize, cloud.seed, cloud.elongation)
        }
        
        canvas.restore()
    }
    
    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, size: Float, seed: Long, elongation: Float) {
        val random = Random(seed)
        val puffs = 4 + random.nextInt(4)  // More puffs for wispier appearance
        
        for (i in 0 until puffs) {
            // Elongated horizontal spread for wispy look
            val px = cx + (random.nextFloat() - 0.5f) * size * elongation * 1.8f
            val py = cy + (random.nextFloat() - 0.5f) * size * 0.4f  // Less vertical spread
            // Varied radii for organic look
            val radiusX = size * (0.4f + random.nextFloat() * 0.6f) * elongation * 0.6f
            val radiusY = size * (0.3f + random.nextFloat() * 0.4f)
            
            // Draw elongated ellipse for wispy appearance
            canvas.drawOval(
                px - radiusX, py - radiusY,
                px + radiusX, py + radiusY,
                paint
            )
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
        
        android.util.Log.d("CloudLayer", "Clouds enabled: $enabled, count managed by state")
    }

    override fun destroy() {
        // Cleanup
    }
}
