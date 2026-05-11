package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.ColorMath
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    override val layerType = LayerType.CLOUDS
    
    // Paint for rendering to bitmap (with blur)
    // PERFORMANCE: Reduced blur radius from 15 to 10 for faster rendering
    private val bitmapPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }
    
    // Paint for drawing cached bitmaps to screen (no blur - already baked)
    private val drawPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private var width = 0f
    private var height = 0f
    private var minDim = 0f  // min(width, height) - used for cloud sizing to prevent giant bitmaps
    
    /**
     * Pre-calculated cloud group with cached bitmap.
     * Each group contains multiple cloud puffs rendered to a single bitmap.
     */
    internal data class CloudGroup(
        val initialWorldX: Float,  // World-space x coordinate (0 = center)
        val y: Float,             // Y center position (0-1)
        val speed: Float,         // Movement speed multiplier
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
        
        // Current world-space X position (accumulated via wind delta, wraps in world-space)
        @Volatile var currentWorldX: Float = initialWorldX
        
        // Flag to prevent duplicate background render requests
        val isRendering = AtomicBoolean(false)
    }
    
    // Thread-safe cloud group list
    @Volatile private var cloudGroups: List<CloudGroup> = emptyList()
    
    // Current cloud tint color (applied at draw time, no bitmap regeneration needed)
    @Volatile private var currentCloudColor: Int = Color.WHITE
    
    private var cloudCount = 10
    private var cloudStretch = 2.0f
    private var cloudOpacity = 0.4f
    private var cloudSpeedMultiplier = 1.0f  // Controls cloud movement speed (0=frozen, 1=normal, 2=fast)
    private var fluffinessMultiplier = 1.0f  // Controls puffs per cloud (density of each cloud)
    
    // Reused color filter: only recreated when cloud tint actually changes
    private var cachedMultiplyFilterColor = Int.MIN_VALUE
    private var cachedMultiplyFilter: PorterDuffColorFilter? = null
    private var cloudsEnabled = true
    private var randomSeed = 42
    
    // Background executor for async bitmap rendering (prevents frame stutters)
    private val bitmapExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CloudBitmapRenderer").apply { isDaemon = true }
    }
    
    // Configuration constants
    companion object {
        // Layer distribution: 75% back, 25% front
        const val BACK_LAYER_RATIO = 0.75f
        
        // Base clouds per group (will be multiplied by fluffinessMultiplier)
        const val CLOUDS_PER_GROUP_MIN_BASE = 5
        const val CLOUDS_PER_GROUP_MAX_BASE = 9
        
        // Back layer: higher alpha for visibility, smaller parallax
        const val BACK_LAYER_ALPHA_MULT = 0.7f
        const val BACK_LAYER_DEPTH_MIN = 0.15f
        const val BACK_LAYER_DEPTH_MAX = 0.22f
        
        // Front layer: highest alpha, larger parallax
        const val FRONT_LAYER_ALPHA_MULT = 1.0f
        const val FRONT_LAYER_DEPTH_MIN = 0.28f
        const val FRONT_LAYER_DEPTH_MAX = 0.35f
        
        // Group size ranges - relative to minDim (min of width/height) for consistent sizing
        // REDUCED for performance: smaller bitmaps = faster drawing
        const val GROUP_WIDTH_MIN = 0.40f
        const val GROUP_WIDTH_MAX = 0.80f
        const val GROUP_HEIGHT_MIN = 0.15f
        const val GROUP_HEIGHT_MAX = 0.30f
        
        // Extra padding for blur effect to prevent edge cutoff (fraction of group size)
        const val BLUR_PADDING = 0.20f
        
        // Hard cap on bitmap dimensions to avoid GPU texture limits
        // REDUCED for performance: smaller max = less GPU work
        const val MAX_BITMAP_DIMENSION = 512
        const val MAX_BITMAP_DIMENSION_PREVIEW = 256
        
        // Max groups for overcast conditions
        const val MAX_GROUPS = 30
        const val MAX_GROUPS_PREVIEW = 12
    }

    /** When true, reduces bitmap sizes and group count for better preview performance. */
    var isPreviewMode = false
        set(value) {
            if (field != value) {
                field = value
                clearGroupBitmaps()
                generateCloudGroups()
            }
        }

    private val effectiveMaxDimension get() = if (isPreviewMode) MAX_BITMAP_DIMENSION_PREVIEW else MAX_BITMAP_DIMENSION
    private val effectiveMaxGroups get() = if (isPreviewMode) MAX_GROUPS_PREVIEW else MAX_GROUPS
    
    init {
        reloadSettings()
    }
    
    /**
     * Generate cloud groups with pre-calculated properties.
     * Groups are distributed: 75% back layer, 25% front layer.
     * Cloud count comes from weather/simulation data; fluffiness affects each cloud's density.
     */
    internal fun generateCloudGroups() {
        // Cloud count is directly from weather - number of cloud groups on screen
        // fluffinessMultiplier only affects how many puffs each cloud has (set later in render)
        val targetGroups = cloudCount.coerceIn(0, effectiveMaxGroups)
        
        // If no clouds requested (NONE density), return empty list
        if (targetGroups == 0) {
            cloudGroups = emptyList()
            return
        }
        
        val newGroups = ArrayList<CloudGroup>(targetGroups)
        
        val backCount = (targetGroups * BACK_LAYER_RATIO).toInt().coerceAtLeast(1)
        val frontCount = (targetGroups - backCount).coerceAtLeast(1)
        
        // Generate back layer groups (majority of clouds, lower alpha)
        // Per-cloud deterministic seed: stable regardless of count changes
        for (i in 0 until backCount) {
            val cloudRandom = DeterministicLayout.random(randomSeed + 4000, section = 401, index = i)
            newGroups.add(createCloudGroup(cloudRandom, isBackLayer = true))
        }
        
        // Generate front layer groups (fewer clouds, higher alpha)
        for (i in 0 until frontCount) {
            val cloudRandom = DeterministicLayout.random(randomSeed + 4000, section = 402, index = i)
            newGroups.add(createCloudGroup(cloudRandom, isBackLayer = false))
        }
        
        cloudGroups = newGroups
    }
    
    private fun createCloudGroup(random: Random, isBackLayer: Boolean): CloudGroup {
        // Generate world-space position: clouds distributed across visible range + padding
        val visibleHalf = RenderConstants.getVisibleWorldHalf(width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        val worldX = (random.nextFloat() - 0.5f) * visibleHalf * 2f
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
            initialWorldX = worldX,
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
     * fluffinessMultiplier controls how many puffs make up each cloud (more puffs = denser/fluffier).
     * Clouds are rendered as WHITE - color tint applied at draw time for performance.
     */
    private fun renderGroupToBitmap(group: CloudGroup, bitmapWidth: Int, bitmapHeight: Int) {
        // Add padding for blur effect to prevent edge cutoff
        // Scale padding with cloudStretch to accommodate wider puffs
        val stretchPaddingMult = (cloudStretch / 2f).coerceIn(1f, 3f)  // 1x at stretch 2, up to 3x at stretch 6
        val paddingX = (bitmapWidth * BLUR_PADDING * stretchPaddingMult).toInt()
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
        
        // Render as white - color tint applied at draw time via PorterDuffColorFilter
        // This eliminates expensive bitmap regeneration during dawn/dusk color transitions
        bitmapPaint.color = Color.WHITE
        bitmapPaint.alpha = group.alpha
        
        val random = Random(group.seed)
        
        // Apply fluffiness multiplier to puff count - more puffs = denser/fluffier clouds
        val minPuffs = (CLOUDS_PER_GROUP_MIN_BASE * fluffinessMultiplier).toInt().coerceAtLeast(3)
        val maxPuffs = (CLOUDS_PER_GROUP_MAX_BASE * fluffinessMultiplier).toInt().coerceAtLeast(minPuffs + 1)
        val numPuffs = minPuffs + random.nextInt(maxPuffs - minPuffs + 1)
        
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

    private fun clearGroupBitmaps() {
        for (group in cloudGroups) {
            group.cachedBitmap?.recycle()
            group.cachedBitmap = null
            group.lastWidth = 0
            group.lastHeight = 0
        }
    }

    override fun resize(w: Float, h: Float) {
        val oldW = width
        val sizeChanged = width != w || height != h
        width = w
        height = h
        minDim = minOf(w, h)

        if (!sizeChanged) return
        
        // Invalidate cached bitmaps on any resize (pixel dimensions changed)
        clearGroupBitmaps()
        
        // Only regenerate cloud group positions on WIDTH changes.
        val firstGenerate = oldW <= 0f
        val widthChangedSignificantly = kotlin.math.abs(oldW - w) > 1f
        if (firstGenerate || widthChangedSignificantly) {
            generateCloudGroups()
        }
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Update cloud positions using delta time for smooth wind-speed transitions
        val currentGroups = cloudGroups
        if (currentGroups.isEmpty()) return
        
        // Calculate effective wind using windStrength, windGust, and windMultiplier
        // Don't clamp windMultiplier - allow values > 1.0 for dramatic wind (settings allow 0-300%)
        val baseWind = state.windStrength * state.windMultiplier
        val gustAdd = state.windGust * 0.6f
        
        // Storm boost for dramatic cloud movement
        val stormBoost = when (state.weather) {
            info.olli.weatherlwp.weather.WeatherType.THUNDERSTORM -> 1.8f
            info.olli.weatherlwp.weather.WeatherType.RAIN -> 1.25f
            else -> 1.0f
        }
        
        // Combined wind factor - don't clamp, allow gusty strong winds to exceed 1.0
        // This creates more dramatic cloud movement at high wind settings
        val windFactor = (baseWind + gustAdd).coerceAtLeast(0f)  // Only prevent negative
        val windSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN + 
            windFactor * (RenderConstants.WIND_CLOUD_SPEED_MAX - RenderConstants.WIND_CLOUD_SPEED_MIN)
        val effectiveSpeed = windSpeed * cloudSpeedMultiplier * stormBoost
        
        // Get wind direction for X component (-1 to 1, positive = rightward)
        // Use precalculated value from SimulationState for efficiency
        val dirX = state.getWindDirX()
        
        // Convert delta time to seconds, cap to prevent jumps
        val deltaSec = (deltaTimeMs / 1000f).coerceAtMost(0.1f)
        
        // Update each cloud group's position in world-space
        // Movement in world-space units per second
        val wrapRange = RenderConstants.getVisibleWorldHalf(width.coerceAtLeast(1f), height.coerceAtLeast(1f)) * 2f
        for (group in currentGroups) {
            // Move cloud in world-space units
            val movement = deltaSec * group.speed * effectiveSpeed * RenderConstants.CLOUD_MOVEMENT_SCALE * dirX * wrapRange
            var newX = group.currentWorldX + movement
            // Wrap position in world-space (symmetric around center)
            val halfRange = wrapRange / 2f
            while (newX > halfRange) newX -= wrapRange
            while (newX < -halfRange) newX += wrapRange
            group.currentWorldX = newX
        }
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !cloudsEnabled) return
        
        // Calculate cloud color for this frame
        currentCloudColor = calculateCloudColor(state)
        
        drawCachedMode(canvas, state, parallaxCalculator)
    }

    /**
     * Return a cached MULTIPLY color filter for cloud bitmap tinting.
     * Recreates only when tint color changes.
     */
    private fun getMultiplyFilter(color: Int): PorterDuffColorFilter {
        val existing = cachedMultiplyFilter
        if (existing != null && cachedMultiplyFilterColor == color) return existing
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        cachedMultiplyFilter = filter
        cachedMultiplyFilterColor = color
        return filter
    }

    /**
     * Cached bitmap mode - draws pre-rendered cloud bitmaps directly.
     * Each cloud group has its own cached bitmap with blur baked in.
     * 
     * PERFORMANCE: Bitmaps are rendered asynchronously in background thread
     * to prevent frame stutters during allocation/blur operations.
     */
    private fun drawCachedMode(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Cloud count from weather, scaled modestly by visible area.
        // Only scale by width ratio to prevent height-only resize from changing cloud count.
        val screenWidth = width.coerceAtLeast(1f)
        val widthScale = (screenWidth / RenderConstants.REFERENCE_EXTENDED_WIDTH).coerceIn(1.0f, 1.5f)
        val baseCount = state.getCloudCount()
        val targetCount = (baseCount * widthScale).toInt().coerceIn(0, effectiveMaxGroups)
        
        // Regenerate groups if count changed significantly
        if (kotlin.math.abs(targetCount - cloudCount) > 3 || 
            (targetCount == 0 && cloudCount > 0) || 
            (targetCount > 0 && cloudCount == 0)) {
            clearGroupBitmaps()
            cloudCount = targetCount
            generateCloudGroups()
        }
        
        val currentGroups = cloudGroups
        if (currentGroups.isEmpty()) return
        
        // Apply color tint to all clouds
        drawPaint.colorFilter = getMultiplyFilter(currentCloudColor)
        
        // Draw all groups directly (back layer first, then front)
        drawGroupsBitmap(canvas, currentGroups, isBackLayer = true, parallaxCalculator)
        drawGroupsBitmap(canvas, currentGroups, isBackLayer = false, parallaxCalculator)
    }

    /**
     * Legacy cached-bitmap draw path.
     */
    private fun drawGroupsBitmap(
        canvas: Canvas,
        groups: List<CloudGroup>,
        isBackLayer: Boolean,
        parallaxCalculator: ParallaxCalculator
    ) {
        val transform = WorldViewportTransform(width, height, RenderConstants.getHeightScale(height, width))
        var i = 0
        while (i < groups.size) {
            val group = groups[i]
            i++
            if (group.isBackLayer != isBackLayer) continue

            val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
            // World-space to screen projection via canonical transform + sensor parallax
            val screenX = transform.worldToScreenX(group.currentWorldX) + px
            val screenY = group.y * height + py

            val groupWidthPx = (group.groupWidth * minDim).toInt()
                .coerceIn(50, effectiveMaxDimension)
            // Scale cloud height with screen height so they fill tall sky areas
            val heightRef = maxOf(minDim, height * 0.5f)
            val groupHeightPx = (group.groupHeight * heightRef).toInt()
                .coerceIn(30, effectiveMaxDimension)

            // Padding scales with cloudStretch to accommodate wider puffs
            val stretchPaddingMult = (cloudStretch / 2f).coerceIn(1f, 3f)
            val paddingX = (groupWidthPx * BLUR_PADDING * stretchPaddingMult).toInt()
            val paddingY = (groupHeightPx * BLUR_PADDING).toInt()
            val paddedWidth = groupWidthPx + paddingX * 2
            val paddedHeight = groupHeightPx + paddingY * 2

            // Viewport culling using actual padded dimensions (accounting for zoom scale)
            val halfW = paddedWidth / 2f * transform.heightScale
            val halfH = paddedHeight / 2f * transform.heightScale
            if (screenX + halfW < 0f || screenX - halfW > width) continue
            if (screenY + halfH < 0f || screenY - halfH > height) continue

            // Queue async bitmap render if not ready and not already rendering
            if (group.cachedBitmap == null) {
                if (group.isRendering.compareAndSet(false, true)) {
                    // Capture values for background thread
                    val w = groupWidthPx
                    val h = groupHeightPx
                    bitmapExecutor.execute {
                        try {
                            renderGroupToBitmap(group, w, h)
                        } finally {
                            group.isRendering.set(false)
                        }
                    }
                }
                // Skip drawing this group until bitmap is ready
                continue
            }

            val bitmap = group.cachedBitmap
            if (bitmap != null) {
                // Scale clouds proportionally to scene zoom so they don't shrink relative to other elements
                canvas.save()
                canvas.translate(screenX, screenY)
                canvas.scale(transform.heightScale, transform.heightScale)
                canvas.drawBitmap(bitmap, -paddedWidth / 2f, -paddedHeight / 2f, drawPaint)
                canvas.restore()
            }
        }
    }

    private fun calculateCloudColor(state: SimulationState): Int {
        val hour = state.timeOfDayHours
        val baseColor = when {
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
        
        // Apply night shade darkening
        return if (state.isNight && state.nightShadeStrength > 0f) {
            ColorMath.shadeRgb(baseColor, state.nightShadeStrength)
        } else {
            baseColor
        }
    }
    
    // Expose for testing
    internal fun getCloudGroups(): List<CloudGroup> = cloudGroups
    internal fun getBackLayerCount(): Int = cloudGroups.count { it.isBackLayer }
    internal fun getFrontLayerCount(): Int = cloudGroups.count { !it.isBackLayer }

    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        
        // Cloud enable/disable switch
        val newEnabled = settings.getBoolean("pref_clouds_enabled", true)
        val enabledChanged = newEnabled != cloudsEnabled
        cloudsEnabled = newEnabled
        
        // Random seed for consistent placement
        val newSeed = settings.getInt("pref_random_seed", 42)
        val seedChanged = newSeed != randomSeed
        randomSeed = newSeed
        
        // Cloud stretch setting: 50-500 -> 0.5-5.0
        val stretchVal = settings.getInt("pref_cloud_stretch", PreferenceDefaults.CLOUD_STRETCH)
        val newStretch = stretchVal / 100f
        
        // Cloud opacity setting: 10-100 -> 0.1-1.0
        val opacityVal = settings.getInt("pref_cloud_opacity", PreferenceDefaults.CLOUD_OPACITY)
        val newOpacity = opacityVal / 100f
        
        // Cloud speed setting: 0-100 -> 0.0-2.0 (50 = normal 1.0)
        val speedVal = settings.getInt("pref_cloud_speed", PreferenceDefaults.CLOUD_SPEED)
        cloudSpeedMultiplier = speedVal / 50f  // 0=frozen, 50=normal, 100=2x speed
        
        // Fluffiness/density multiplier: 50-300 -> 0.5-3.0 (controls puffs per cloud)
        // Higher = more puffs per cloud = denser/fluffier individual clouds
        // Does NOT affect number of clouds (that comes from weather data)
        val densityVal = settings.getInt("pref_cloud_density", PreferenceDefaults.CLOUD_DENSITY)
        val newFluffiness = densityVal / 100f
        val fluffinessChanged = kotlin.math.abs(newFluffiness - fluffinessMultiplier) > 0.01f
        fluffinessMultiplier = newFluffiness
        
        // Detect changes that require regeneration
        val stretchChanged = kotlin.math.abs(newStretch - cloudStretch) > 0.01f
        cloudStretch = newStretch
        cloudOpacity = newOpacity
        
        if (!cloudsEnabled) {
            cloudCount = 0
            clearGroupBitmaps()
            cloudGroups = emptyList()
        } else if (enabledChanged || stretchChanged || seedChanged || fluffinessChanged) {
            // Invalidate cached cloud resources when structural settings change
            // Note: opacity is applied at draw time, no bitmap regeneration needed
            clearGroupBitmaps()
            generateCloudGroups()
        }
    }

    override fun destroy() {
        // Graceful shutdown: let in-flight bitmap renders finish (up to 2s)
        bitmapExecutor.shutdown()
        try {
            if (!bitmapExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                bitmapExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            bitmapExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // Cleanup cached cloud group bitmaps to prevent memory leaks
        clearGroupBitmaps()
        cloudGroups = emptyList()
    }
}
