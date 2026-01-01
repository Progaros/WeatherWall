package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.random.Random

/**
 * Renders clouds with wind-based movement using pre-calculated cloud groups.
 * 
 * PERFORMANCE OPTIMIZATION (Issue: Overcast lag)
 * Instead of drawing 120 individual clouds with blur each frame, we:
 * 1. Pre-render cloud groups to bitmaps with transparency baked in
 * 2. Keep most clouds in back layers (75%) with lower alpha
 * 3. Few clouds in front layers (25%) for depth
 * 4. Move entire groups as units, not individual clouds
 * 
 * This reduces draw calls from ~500/frame to ~12-20/frame.
 * 
 * Uses SimulationState for:
 * - windStrength (affects cloud speed)
 * - timeSeconds (for consistent animation across reloads)
 * - cloudDensity (how many clouds to show)
 * - isNight/isDusk/isDawn (affects cloud color tint)
 * - timeOfDayHours (for sunrise/sunset coloring)
 */
class CloudLayer(private val context: Context) : SceneLayer {
    
    // Paint for rendering to bitmap (with blur)
    private val bitmapPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    }
    
    // Paint for drawing cached bitmaps to screen (no blur - already baked)
    private val drawPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    
    private var width = 0f
    private var height = 0f
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f
    
    /**
     * Pre-calculated cloud group with cached bitmap.
     * Each group contains multiple cloud puffs rendered to a single bitmap.
     */
    internal data class CloudGroup(
        val initialX: Float,      // 0-1 across extended width
        val y: Float,             // Y center position (0-1)
        val speed: Float,         // Movement speed
        val alpha: Int,           // Pre-calculated alpha (0-255)
        val isBackLayer: Boolean, // True = back layer (75%), False = front layer (25%)
        val depthFactor: Float,   // For parallax (0.15-0.35)
        val groupWidth: Float,    // Width of the group (0-1 normalized)
        val groupHeight: Float,   // Height of the group (0-1 normalized)
        val seed: Long            // For deterministic regeneration
    ) {
        // Cached bitmap - rendered once, reused many frames
        @Volatile var cachedBitmap: Bitmap? = null
        var lastWidth: Int = 0
        var lastHeight: Int = 0
    }
    
    // Thread-safe cloud group list
    @Volatile private var cloudGroups: List<CloudGroup> = emptyList()
    
    // Cached cloud color for bitmap regeneration
    @Volatile private var cachedCloudColor: Int = Color.WHITE
    
    private var cloudCount = 10
    private var cloudStretch = 2.0f
    private var cloudOpacity = 0.4f
    private var densityMultiplier = 1.0f
    private var cloudsEnabled = true
    private var randomSeed = 42
    
    // Configuration constants
    companion object {
        // Layer distribution: 75% back, 25% front
        const val BACK_LAYER_RATIO = 0.75f
        
        // Clouds per group (reduces individual draw calls)
        const val CLOUDS_PER_GROUP_MIN = 6
        const val CLOUDS_PER_GROUP_MAX = 12
        
        // Back layer: higher alpha for visibility, smaller parallax
        const val BACK_LAYER_ALPHA_MULT = 0.7f
        const val BACK_LAYER_DEPTH_MIN = 0.15f
        const val BACK_LAYER_DEPTH_MAX = 0.22f
        
        // Front layer: highest alpha, larger parallax
        const val FRONT_LAYER_ALPHA_MULT = 1.0f
        const val FRONT_LAYER_DEPTH_MIN = 0.28f
        const val FRONT_LAYER_DEPTH_MAX = 0.35f
        
        // Group size ranges - increased for more visible clouds
        const val GROUP_WIDTH_MIN = 0.25f
        const val GROUP_WIDTH_MAX = 0.50f
        const val GROUP_HEIGHT_MIN = 0.10f
        const val GROUP_HEIGHT_MAX = 0.18f
        
        // Extra padding for blur effect to prevent edge cutoff (fraction of group size)
        const val BLUR_PADDING = 0.3f
        
        // Max groups for performance (even at overcast)
        const val MAX_GROUPS = 25
    }
    
    init {
        reloadSettings()
    }
    
    /**
     * Generate cloud groups with pre-calculated properties.
     * Groups are distributed: 75% back layer, 25% front layer.
     */
    internal fun generateCloudGroups() {
        // Calculate target group count based on cloud count
        // Each group represents 4-8 clouds, so fewer groups needed
        val avgCloudsPerGroup = (CLOUDS_PER_GROUP_MIN + CLOUDS_PER_GROUP_MAX) / 2
        val targetGroups = (cloudCount / avgCloudsPerGroup).coerceIn(2, MAX_GROUPS)
        
        val newGroups = ArrayList<CloudGroup>(targetGroups)
        val random = Random(randomSeed.toLong() + 4000)
        
        val backCount = (targetGroups * BACK_LAYER_RATIO).toInt().coerceAtLeast(1)
        val frontCount = (targetGroups - backCount).coerceAtLeast(1)
        
        // Generate back layer groups (majority of clouds, lower alpha)
        for (i in 0 until backCount) {
            newGroups.add(createCloudGroup(random, isBackLayer = true))
        }
        
        // Generate front layer groups (fewer clouds, higher alpha)
        for (i in 0 until frontCount) {
            newGroups.add(createCloudGroup(random, isBackLayer = false))
        }
        
        cloudGroups = newGroups
    }
    
    private fun createCloudGroup(random: Random, isBackLayer: Boolean): CloudGroup {
        val x = random.nextFloat()
        val y = RenderConstants.CLOUD_Y_MIN + random.nextFloat() * RenderConstants.CLOUD_Y_RANGE
        
        val groupWidth = GROUP_WIDTH_MIN + random.nextFloat() * (GROUP_WIDTH_MAX - GROUP_WIDTH_MIN)
        val groupHeight = GROUP_HEIGHT_MIN + random.nextFloat() * (GROUP_HEIGHT_MAX - GROUP_HEIGHT_MIN)
        
        val speed = RenderConstants.CLOUD_SPEED_BASE + random.nextFloat() * RenderConstants.CLOUD_SPEED_RANGE
        
        // Alpha and depth based on layer
        val (alphaMult, depthMin, depthMax) = if (isBackLayer) {
            Triple(BACK_LAYER_ALPHA_MULT, BACK_LAYER_DEPTH_MIN, BACK_LAYER_DEPTH_MAX)
        } else {
            Triple(FRONT_LAYER_ALPHA_MULT, FRONT_LAYER_DEPTH_MIN, FRONT_LAYER_DEPTH_MAX)
        }
        
        // Increased base alpha range for better visibility (was 30-70, now 60-120)
        val baseAlpha = (60 + random.nextFloat() * 60).toInt()
        val alpha = (baseAlpha * alphaMult * cloudOpacity).toInt().coerceIn(20, 220)
        val depth = depthMin + random.nextFloat() * (depthMax - depthMin)
        
        return CloudGroup(
            initialX = x,
            y = y,
            speed = speed,
            alpha = alpha,
            isBackLayer = isBackLayer,
            depthFactor = depth,
            groupWidth = groupWidth,
            groupHeight = groupHeight,
            seed = random.nextLong()
        )
    }
    
    /**
     * Render a cloud group to its cached bitmap.
     * This is called once per group, then the bitmap is reused.
     * 
     * Includes edge fadeout and extra padding for blur to prevent cutoff.
     */
    private fun renderGroupToBitmap(group: CloudGroup, bitmapWidth: Int, bitmapHeight: Int, color: Int) {
        // Add padding for blur effect to prevent edge cutoff
        val paddingX = (bitmapWidth * BLUR_PADDING).toInt()
        val paddingY = (bitmapHeight * BLUR_PADDING).toInt()
        val paddedWidth = bitmapWidth + paddingX * 2
        val paddedHeight = bitmapHeight + paddingY * 2
        
        // Recycle old bitmap if size changed
        // Fix race condition: null the reference first, then recycle
        val oldBitmap = group.cachedBitmap
        if (oldBitmap != null && 
            (group.lastWidth != paddedWidth || group.lastHeight != paddedHeight)) {
            group.cachedBitmap = null  // Clear reference first to prevent use-after-recycle
            oldBitmap.recycle()
        }
        
        // Create or reuse bitmap with padding
        val bitmap = group.cachedBitmap ?: Bitmap.createBitmap(
            paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        bitmapPaint.color = color
        bitmapPaint.alpha = group.alpha
        
        val random = Random(group.seed)
        val numPuffs = CLOUDS_PER_GROUP_MIN + random.nextInt(CLOUDS_PER_GROUP_MAX - CLOUDS_PER_GROUP_MIN + 1)
        
        // Center of padded bitmap (cloud content is centered)
        val centerX = paddedWidth / 2f
        val centerY = paddedHeight / 2f
        
        // Edge fadeout margin - use original dimensions for fade calculation
        // but draw in padded space so blur has room to expand
        val fadeMarginX = bitmapWidth * 0.35f  // Increased margin for softer edges
        val fadeMarginY = bitmapHeight * 0.35f
        
        for (i in 0 until numPuffs) {
            // Distribute puffs across the group (within original content area)
            val offsetX = (random.nextFloat() - 0.5f) * bitmapWidth * 0.7f
            val offsetY = (random.nextFloat() - 0.5f) * bitmapHeight * 0.5f
            
            val puffCenterX = centerX + offsetX
            val puffCenterY = centerY + offsetY
            
            // Calculate edge proximity relative to original content bounds (not padded bounds)
            val contentLeft = paddingX.toFloat()
            val contentRight = paddingX + bitmapWidth.toFloat()
            val contentTop = paddingY.toFloat()
            val contentBottom = paddingY + bitmapHeight.toFloat()
            
            val distFromLeftEdge = puffCenterX - contentLeft
            val distFromRightEdge = contentRight - puffCenterX
            val distFromTopEdge = puffCenterY - contentTop
            val distFromBottomEdge = contentBottom - puffCenterY
            
            val xFade = (minOf(distFromLeftEdge, distFromRightEdge) / fadeMarginX).coerceIn(0f, 1f)
            val yFade = (minOf(distFromTopEdge, distFromBottomEdge) / fadeMarginY).coerceIn(0f, 1f)
            // Use sqrt for smoother fade curve
            val edgeFade = kotlin.math.sqrt(xFade * yFade)
            
            // Apply edge fade to alpha
            val puffAlpha = (group.alpha * edgeFade).toInt().coerceIn(0, 255)
            bitmapPaint.alpha = puffAlpha
            
            // Larger, more visible cloud puffs
            val radiusX = bitmapWidth * (0.18f + random.nextFloat() * 0.22f) * cloudStretch * 0.35f
            val radiusY = bitmapHeight * (0.25f + random.nextFloat() * 0.30f)
            
            canvas.drawOval(
                puffCenterX - radiusX, puffCenterY - radiusY,
                puffCenterX + radiusX, puffCenterY + radiusY,
                bitmapPaint
            )
        }
        
        group.cachedBitmap = bitmap
        group.lastWidth = paddedWidth
        group.lastHeight = paddedHeight
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)
        
        // Invalidate cached bitmaps on resize
        for (group in cloudGroups) {
            group.cachedBitmap?.recycle()
            group.cachedBitmap = null
        }
        
        generateCloudGroups()
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Position is computed in draw() using state.timeSeconds
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !cloudsEnabled) return
        
        // Update cloud count based on weather
        val baseCount = state.getCloudCount()
        val targetCount = (baseCount * densityMultiplier).toInt().coerceIn(0, 120)
        if (targetCount != cloudCount) {
            cloudCount = targetCount
            generateCloudGroups()
        }
        
        val currentGroups = cloudGroups
        if (currentGroups.isEmpty()) return
        
        // Calculate cloud color for this frame
        val cloudColor = calculateCloudColor(state)
        
        // Regenerate bitmaps if color changed significantly
        val colorChanged = cloudColor != cachedCloudColor
        if (colorChanged) {
            cachedCloudColor = cloudColor
            for (group in currentGroups) {
                group.cachedBitmap?.recycle()
                group.cachedBitmap = null
            }
        }
        
        val windSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN + 
            state.windStrength * (RenderConstants.WIND_CLOUD_SPEED_MAX - RenderConstants.WIND_CLOUD_SPEED_MIN)
        
        // Draw back layer first, then front layer
        for (isBack in listOf(true, false)) {
            for (group in currentGroups) {
                if (group.isBackLayer != isBack) continue
                
                // Calculate parallax for this group's depth
                val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
                val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
                
                // Calculate group position with time-based movement
                val timeOffset = state.timeSeconds * group.speed * windSpeed
                var groupX = group.initialX + timeOffset / extendedWidth * 0.1f
                groupX = groupX % 1f
                if (groupX < 0) groupX += 1f
                
                // Convert to screen coordinates
                val screenX = groupX * extendedWidth - extendedOffset + px
                val screenY = group.y * height + py
                
                val groupWidthPx = (group.groupWidth * width).toInt().coerceAtLeast(50)
                val groupHeightPx = (group.groupHeight * height).toInt().coerceAtLeast(30)
                
                // Calculate padded dimensions (must match renderGroupToBitmap)
                val paddingX = (groupWidthPx * BLUR_PADDING).toInt()
                val paddingY = (groupHeightPx * BLUR_PADDING).toInt()
                val paddedWidth = groupWidthPx + paddingX * 2
                val paddedHeight = groupHeightPx + paddingY * 2
                
                // Ensure bitmap is rendered
                if (group.cachedBitmap == null) {
                    renderGroupToBitmap(group, groupWidthPx, groupHeightPx, cloudColor)
                }
                
                group.cachedBitmap?.let { bitmap ->
                    // Draw with padding offset so cloud content is centered at target position
                    canvas.drawBitmap(
                        bitmap,
                        screenX - paddedWidth / 2f,
                        screenY - paddedHeight / 2f,
                        drawPaint
                    )
                }
            }
        }
    }
    
    private fun calculateCloudColor(state: SimulationState): Int {
        val hour = state.timeOfDayHours
        return when {
            state.isNight -> Color.rgb(150, 155, 180)
            state.isDawn -> {
                val t = (hour - 5f).coerceIn(0f, 2f) / 2f
                RenderConstants.lerpColor(Color.rgb(180, 170, 190), Color.rgb(255, 200, 180), t)
            }
            state.isDusk -> {
                val t = (hour - 17f).coerceIn(0f, 2f) / 2f
                RenderConstants.lerpColor(Color.rgb(255, 220, 200), Color.rgb(200, 170, 190), t)
            }
            else -> Color.rgb(250, 250, 255)
        }
    }
    
    // Expose for testing
    internal fun getCloudGroups(): List<CloudGroup> = cloudGroups
    internal fun getBackLayerCount(): Int = cloudGroups.count { it.isBackLayer }
    internal fun getFrontLayerCount(): Int = cloudGroups.count { !it.isBackLayer }

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
            // Cleanup cached bitmaps
            for (group in cloudGroups) {
                group.cachedBitmap?.recycle()
            }
            cloudGroups = emptyList()
        } else if (enabledChanged || stretchChanged || seedChanged || opacityChanged || multiplierChanged) {
            // Invalidate cached bitmaps when settings change
            for (group in cloudGroups) {
                group.cachedBitmap?.recycle()
                group.cachedBitmap = null
            }
            // Regenerate cloud groups with new settings
            generateCloudGroups()
        }
    }

    override fun destroy() {
        // Cleanup cached bitmaps to prevent memory leaks
        for (group in cloudGroups) {
            group.cachedBitmap?.recycle()
            group.cachedBitmap = null
        }
        cloudGroups = emptyList()
    }
}
