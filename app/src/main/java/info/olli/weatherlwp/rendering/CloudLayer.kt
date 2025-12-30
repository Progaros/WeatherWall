package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.CloudDensity
import info.olli.weatherlwp.simulation.SimulationState
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
        maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)  // Reduced blur for better performance
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
    
    // Thread-safe cloud list using volatile reference + immutable list
    // Settings thread creates new list, render thread reads current snapshot
    @Volatile private var clouds: List<CloudData> = emptyList()
    
    private var cloudCount = 10
    private var cloudStretch = 2.0f  // Horizontal stretch multiplier (1.0 = normal, 2.0 = wispy)
    private var cloudOpacity = 0.4f  // Base opacity multiplier (0-1)
    private var densityMultiplier = 1.0f  // Multiplier on weather-based cloud count (0-2)
    private var cloudsEnabled = true
    private var randomSeed = 42
    
    init {
        reloadSettings()
    }
    
    private fun generateClouds() {
        // Build new list (thread-safe: atomic swap at end)
        val newClouds = ArrayList<CloudData>(cloudCount)
        val random = Random(randomSeed.toLong() + 3000)  // Offset from terrain
        
        for (i in 0 until cloudCount) {
            val x = RenderConstants.WORLD_MIN_X + random.nextFloat() * RenderConstants.WORLD_WIDTH
            val y = RenderConstants.CLOUD_Y_MIN + random.nextFloat() * RenderConstants.CLOUD_Y_RANGE
            val size = RenderConstants.CLOUD_SIZE_MIN + random.nextFloat() * RenderConstants.CLOUD_SIZE_RANGE
            // Base speed - smaller clouds move faster (further away)
            val speed = RenderConstants.CLOUD_SPEED_BASE + (1f - size / 0.33f) * RenderConstants.CLOUD_SPEED_RANGE
            // Alpha based on size and opacity setting
            // Small clouds (far/thin) are more transparent
            // Large clouds (near/thick) are more opaque
            // cloudOpacity scales the final alpha (0.1 = very see-through, 1.0 = solid)
            val baseAlpha = (10 + size * 150).toInt()  // 10-60 range before opacity
            val alpha = (baseAlpha * cloudOpacity).toInt().coerceIn(5, 100)
            // Horizontal elongation - base from setting, with random variation
            val elongation = cloudStretch * (0.8f + random.nextFloat() * 0.4f)
            
            newClouds.add(CloudData(x, y, size, speed, alpha, random.nextLong(), elongation))
        }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        clouds = newClouds
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Position is computed in draw() using state.timeSeconds
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !cloudsEnabled) return
        
        // Cloud count = weather-based count * user multiplier, capped for performance
        val baseCount = state.getCloudCount()
        val targetCount = (baseCount * densityMultiplier).toInt().coerceIn(0, 60)  // Max 60 clouds for performance
        if (targetCount != cloudCount) {
            cloudCount = targetCount
            generateClouds()
        }
        
        // Take local snapshot of clouds for thread-safe iteration
        val currentClouds = clouds
        if (currentClouds.isEmpty()) return
        
        val scaleX = height * RenderConstants.REFERENCE_ASPECT_RATIO
        val px = parallaxCalculator.getOffsetXForDepth(RenderConstants.PARALLAX_CLOUDS)
        val py = parallaxCalculator.getOffsetYForDepth(RenderConstants.PARALLAX_CLOUDS)
        
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
        
        for (cloud in currentClouds) {
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
        // Fewer puffs for better performance (was 4-8, now 3-5)
        val puffs = 3 + random.nextInt(3)
        
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
        
        // Cloud enable/disable switch
        val newEnabled = prefs.getBoolean("pref_clouds_enabled", true)
        val enabledChanged = newEnabled != cloudsEnabled
        cloudsEnabled = newEnabled
        
        // Random seed for consistent placement
        val newSeed = prefs.getInt("pref_random_seed", 42)
        val seedChanged = newSeed != randomSeed
        randomSeed = newSeed
        
        // Cloud stretch setting: 50-500 -> 0.5-5.0
        val stretchVal = prefs.getInt("pref_cloud_stretch", 300)
        val newStretch = stretchVal / 100f
        
        // Cloud opacity setting: 10-100 -> 0.1-1.0
        val opacityVal = prefs.getInt("pref_cloud_opacity", 40)
        val newOpacity = opacityVal / 100f
        
        // Density multiplier: 0-200 -> 0.0-2.0 (multiplies weather-based count)
        // 0 = no clouds, 100 = normal weather-based, 200 = double
        val densityVal = prefs.getInt("pref_cloud_density", 100)
        val newMultiplier = densityVal / 100f
        val multiplierChanged = kotlin.math.abs(newMultiplier - densityMultiplier) > 0.01f
        densityMultiplier = newMultiplier
        
        // Detect changes that require regeneration
        val stretchChanged = kotlin.math.abs(newStretch - cloudStretch) > 0.01f
        val opacityChanged = kotlin.math.abs(newOpacity - cloudOpacity) > 0.01f
        cloudStretch = newStretch
        cloudOpacity = newOpacity
        
        if (!cloudsEnabled) {
            cloudCount = 0
            clouds = emptyList()  // Thread-safe: atomic reference swap
        } else if (enabledChanged || stretchChanged || seedChanged || opacityChanged || multiplierChanged) {
            // Regenerate clouds with new settings - count will be recalculated in draw()
            generateClouds()
        }
    }

    override fun destroy() {
        // Cleanup
    }
}
