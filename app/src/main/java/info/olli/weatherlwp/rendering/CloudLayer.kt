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

    // Cached values rebuilt on resize only
    private var cachedWrapRange = 0f
    private var cachedTransform = WorldViewportTransform(1f, 1f)
    
    /**
     * Pre-calculated cloud group with cached bitmap.
     * Each group contains multiple cloud puffs rendered to a single bitmap.
     */
    internal data class CloudGroup(
        val initialWorldX: Float,  // World-space x coordinate (0 = center)
        val worldY: Float,        // World-space Y coordinate (sky height)
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
    private var coverageSizeMult = 1.0f      // Coverage-based draw size multiplier (fewer groups, each drawn larger)
    
    // Reused color filter: only recreated when cloud tint actually changes
    private var cachedMultiplyFilterColor = Int.MIN_VALUE
    private var cachedMultiplyFilter: PorterDuffColorFilter? = null
    private var cloudsEnabled = true
    private var randomSeed = 42

    // World-space cloud band values (recalculated on resize)
    private var cachedBandTop = 0f
    private var cachedBandBottom = 0f
    private var cachedBandHeight = 0f

    // Cell-based generation cache: cellIndex → generated groups
    // Prevents regeneration stalls by caching deterministic cell results
    private val cellCache = mutableMapOf<Int, List<CloudGroup>>()
    
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
        const val GROUP_WIDTH_MAX = 0.65f
        const val GROUP_HEIGHT_MIN = 0.15f
        const val GROUP_HEIGHT_MAX = 0.30f
        
        // Extra padding for blur effect to prevent edge cutoff (fraction of group size)
        const val BLUR_PADDING = 0.20f
        
        // Hard cap on bitmap dimensions to avoid GPU texture limits
        // REDUCED for performance: smaller max = less GPU work
        const val MAX_BITMAP_DIMENSION = 512
        const val MAX_BITMAP_DIMENSION_PREVIEW = 256
        
        // Max groups for overcast conditions (reduced: coverage-based uses fewer, larger groups)
        const val MAX_GROUPS = 18
        const val MAX_GROUPS_PREVIEW = 14
        
        // Preview cloud visibility: minimum draw scale to prevent tiny preview clouds
        const val PREVIEW_MIN_DRAW_SCALE = 0.65f
        
        // World-space cloud band (fraction of visible world height).
        // Clouds exist within a band defined relative to the visible world top,
        // with extra margin above/below so parallax never causes clipping.
        const val BAND_HEIGHT_FRACTION = 0.35f      // Cloud content height as fraction of world height
        const val BAND_MARGIN_FRACTION = 0.20f      // Extra margin above/below for parallax safety
        const val CELL_SIZE_FRACTION = 0.5f         // X-cell width as fraction of band height
        const val SPAWN_PROB_MIN = 0.4f             // Cell spawn probability at low coverage
        const val SPAWN_PROB_MAX = 0.9f             // Cell spawn probability at overcast
        const val COVERAGE_SCALE_MIN = 0.6f         // Cloud draw-size scale at low coverage
        const val COVERAGE_SCALE_MAX = 1.4f         // Cloud draw-size scale at overcast (capped to prevent giant clouds)
        const val CULL_MARGIN_FRACTION = 0.25f      // Screen-space culling margin
        const val MAX_SCREEN_WIDTH_FRACTION = 0.60f  // Max cloud width as fraction of screen width
        const val MAX_DRAW_PER_FRAME = 12            // Hard cap on draw calls per layer pass
    }

    /** When true, reduces bitmap sizes and group count for better preview performance. */
    var isPreviewMode = false
        set(value) {
            if (field != value) {
                field = value
                clearGroupBitmaps()
                cellCache.clear()
                generateCloudGroups()
            }
        }

    private val effectiveMaxDimension get() = if (isPreviewMode) MAX_BITMAP_DIMENSION_PREVIEW else MAX_BITMAP_DIMENSION
    private val effectiveMaxGroups get() = if (isPreviewMode) MAX_GROUPS_PREVIEW else MAX_GROUPS
    
    init {
        reloadSettings()
    }
    
    /**
     * Generate cloud groups using cell-based deterministic placement.
     *
     * World-space X is divided into cells. Each cell deterministically
     * decides whether to spawn a cloud based on coverage-derived probability.
     * Results are cached per cell to prevent regeneration stalls.
     *
     * Groups are distributed: 75% back layer, 25% front layer (via cell hash).
     */
    internal fun generateCloudGroups() {
        if (cloudCount <= 0 || !cloudsEnabled) {
            cloudGroups = emptyList()
            return
        }

        val bandHeight = cachedBandHeight
        if (bandHeight <= 0f) {
            cloudGroups = emptyList()
            return
        }

        val cellSize = bandHeight * CELL_SIZE_FRACTION
        if (cellSize <= 0f) {
            cloudGroups = emptyList()
            return
        }

        val transform = cachedTransform
        val worldRect = transform.viewport.worldRect
        val parallaxPad = RenderConstants.REFERENCE_HEIGHT * 0.45f
        val extraMarginX = worldRect.width * 0.25f  // Extra margin prevents edge pop-in
        val startCell = kotlin.math.floor((worldRect.left - parallaxPad - extraMarginX) / cellSize.toDouble()).toInt()
        val endCell = kotlin.math.ceil((worldRect.right + parallaxPad + extraMarginX) / cellSize.toDouble()).toInt()

        // Coverage drives spawn probability & draw-size scale
        val cloudCoverage = (cloudCount.toFloat() / effectiveMaxGroups).coerceIn(0f, 1f)
        val spawnProbability = SPAWN_PROB_MIN + (SPAWN_PROB_MAX - SPAWN_PROB_MIN) * cloudCoverage
        coverageSizeMult = COVERAGE_SCALE_MIN + (COVERAGE_SCALE_MAX - COVERAGE_SCALE_MIN) * cloudCoverage

        val allGroups = mutableListOf<CloudGroup>()
        for (cellX in startCell..endCell) {
            val groups = cellCache.getOrPut(cellX) {
                generateCellClouds(cellX, cellSize, spawnProbability)
            }
            allGroups.addAll(groups)
        }

        // Cleanup cells far outside the visible range to prevent memory leaks
        val buffer = 2
        cellCache.keys.removeAll { it < startCell - buffer || it > endCell + buffer }

        cloudGroups = allGroups
    }

    /**
     * Generate clouds for a single cell. Deterministic: same cellX + seed → same result.
     */
    private fun generateCellClouds(cellX: Int, cellSize: Float, spawnProbability: Float): List<CloudGroup> {
        val cellSeed = DeterministicLayout.mix(randomSeed + 5000, section = 500, index = cellX)
        val rng = Random(cellSeed)

        if (rng.nextFloat() >= spawnProbability) return emptyList()

        // Decide back vs front layer deterministically (75/25 split)
        val isBack = rng.nextFloat() < BACK_LAYER_RATIO
        val cloud = createCloudGroup(rng, isBack, cellX, cellSize)
        return listOf(cloud)
    }
    
    private fun createCloudGroup(random: Random, isBackLayer: Boolean, cellX: Int, cellSize: Float): CloudGroup {
        // World-space X: place within the cell range
        val worldX = cellX * cellSize + random.nextFloat() * cellSize

        val groupWidth = GROUP_WIDTH_MIN + random.nextFloat() * (GROUP_WIDTH_MAX - GROUP_WIDTH_MIN)
        val groupHeight = GROUP_HEIGHT_MIN + random.nextFloat() * (GROUP_HEIGHT_MAX - GROUP_HEIGHT_MIN)

        // World-space Y: place within the cloud band (with extra margin for parallax).
        // Cloud centers are clamped by half-height so the full bitmap stays inside the band.
        val bandTop = cachedBandTop
        val bandBottom = cachedBandBottom
        val worldRect = cachedTransform.viewport.worldRect
        val halfHWorld = groupHeight * 0.5f * worldRect.height
        val minY = bandTop + halfHWorld
        val maxY = (bandBottom - halfHWorld).coerceAtLeast(minY)
        val worldY = if (maxY > minY) {
            minY + random.nextFloat() * (maxY - minY)
        } else {
            (bandTop + bandBottom) * 0.5f
        }
        
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
            worldY = worldY,
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
     * Map raw cloud count to coverage-driven group parameters.
     * Fewer groups drawn larger produces same visual coverage with less GPU cost.
     * @return Pair(effectiveGroupCount, sizeMultiplier)
     */
    internal fun cloudCountToCoverageGroups(rawCount: Int): Pair<Int, Float> {
        return when {
            rawCount <= 0 -> 0 to 1.0f
            rawCount <= 5 -> rawCount.coerceAtLeast(2) to 1.0f
            rawCount <= 15 -> ((rawCount * 0.7f).toInt().coerceAtLeast(4)) to 1.15f
            rawCount <= 30 -> ((rawCount * 0.5f).toInt().coerceAtLeast(8)) to 1.3f
            else -> ((rawCount * 0.4f).toInt().coerceIn(12, 20)) to 1.5f
        }
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
        cellCache.clear()
    }

    override fun resize(w: Float, h: Float) {
        val oldW = width
        val oldH = height
        val sizeChanged = width != w || height != h
        width = w
        height = h
        minDim = minOf(w, h)

        if (!sizeChanged) return

        // Rebuild cached values
        cachedTransform = WorldViewportTransform(w, h)
        // Wrap range = full visible world width + parallax padding on each side.
        // Uses actual viewport worldWidth so landscape views get the correct wider wrap.
        val worldHalf = cachedTransform.worldWidth / 2f
        val parallaxPad = RenderConstants.REFERENCE_HEIGHT * 0.45f
        cachedWrapRange = (worldHalf + parallaxPad) * 2f

        // Compute world-space cloud band from viewport geometry.
        // Band lives in the upper portion of the visible world with generous
        // margins so parallax never clips clouds at top or bottom.
        val worldRect = cachedTransform.viewport.worldRect
        val worldHeight = worldRect.height
        cachedBandHeight = worldHeight * BAND_HEIGHT_FRACTION
        val extraMargin = worldHeight * BAND_MARGIN_FRACTION
        cachedBandTop = worldRect.top - extraMargin
        cachedBandBottom = worldRect.top + cachedBandHeight + extraMargin
        
        // Invalidate cached bitmaps on any resize (pixel dimensions changed)
        clearGroupBitmaps()
        
        // Regenerate cloud group positions on any dimension change
        // (HEIGHT changes now affect density via visible world width in landscape).
        val firstGenerate = oldW <= 0f
        val widthChangedSignificantly = kotlin.math.abs(oldW - w) > 1f
        val heightChangedSignificantly = kotlin.math.abs(oldH - h) > 1f
        if (firstGenerate || widthChangedSignificantly || heightChangedSignificantly) {
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
        val wrapRange = cachedWrapRange
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
        // Cloud count scales with visible world width (aspect-aware, height-independent).
        // This ensures landscape shows the same on-screen density as portrait.
        val visibleWorldWidth = cachedTransform.worldWidth
        val referenceWorldWidth = RenderConstants.REFERENCE_HEIGHT * 0.5625f  // reference portrait width
        val widthScale = (visibleWorldWidth / referenceWorldWidth).coerceIn(1.0f, 3.0f)
        val baseCount = state.getCloudCount()
        val targetCount = (baseCount * widthScale).toInt().coerceIn(0, effectiveMaxGroups)
        
        // Regenerate groups if count changed significantly
        if (kotlin.math.abs(targetCount - cloudCount) > 3 || 
            (targetCount == 0 && cloudCount > 0) || 
            (targetCount > 0 && cloudCount == 0)) {
            clearGroupBitmaps()
            cloudCount = targetCount
            generateCloudGroups()
            android.util.Log.d("CloudPerf", "regenerated: groups=${cloudGroups.size} coverage=${"%.2f".format(coverageSizeMult)} base=$baseCount preview=$isPreviewMode")
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
     * Draw cloud groups as cached bitmaps.
     * Uses clipRect to prevent edge artifacts and caps draw count per pass.
     */
    private fun drawGroupsBitmap(
        canvas: Canvas,
        groups: List<CloudGroup>,
        isBackLayer: Boolean,
        parallaxCalculator: ParallaxCalculator
    ) {
        val transform = cachedTransform
        val mapper = WorldToScreenMapper(transform.viewport)
        val ppu = transform.viewport.pixelsPerUnit
        // Effective draw scale: includes heightScale clamping and coverage size multiplier.
        // Preview: full coverage multiplier for better visibility.
        // Live: dampened multiplier to preserve established visual style.
        val effectiveDrawScale = if (isPreviewMode) {
            transform.heightScale.coerceAtLeast(PREVIEW_MIN_DRAW_SCALE) * coverageSizeMult
        } else {
            transform.heightScale * (1f + (coverageSizeMult - 1f) * 0.5f)
        }
        // Generous cull margin prevents pop-in at screen edges
        val cullMargin = height * CULL_MARGIN_FRACTION
        val maxScreenW = width * MAX_SCREEN_WIDTH_FRACTION

        // Clip all cloud drawing to canvas bounds to prevent edge render artifacts
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)

        var drawCount = 0
        var i = 0
        while (i < groups.size) {
            val group = groups[i]
            i++
            if (group.isBackLayer != isBackLayer) continue

            // Hard cap on draw calls per layer pass
            if (drawCount >= MAX_DRAW_PER_FRAME) break

            // Parallax offsets in screen pixels, convert to world-space
            val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
            val worldPX = if (ppu > 0f) px / ppu else 0f
            val worldPY = if (ppu > 0f) py / ppu else 0f

            // Apply parallax in world-space BEFORE projection
            val renderX = group.currentWorldX + worldPX
            val renderY = group.worldY + worldPY

            // Single projection: world-space → screen-space
            val screenX = mapper.x(renderX)
            val screenY = mapper.y(renderY)

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

            // Screen-space rect-intersects culling with generous margin
            val halfW = paddedWidth / 2f * effectiveDrawScale
            val halfH = paddedHeight / 2f * effectiveDrawScale

            // Clamp drawn size so no single cloud exceeds MAX_SCREEN_WIDTH_FRACTION of screen
            val clampedDrawScale = if (halfW * 2f > maxScreenW && maxScreenW > 0f) {
                effectiveDrawScale * (maxScreenW / (halfW * 2f))
            } else {
                effectiveDrawScale
            }
            val clampedHalfW = paddedWidth / 2f * clampedDrawScale
            val clampedHalfH = paddedHeight / 2f * clampedDrawScale

            if (screenX + clampedHalfW < -cullMargin || screenX - clampedHalfW > width + cullMargin) continue
            if (screenY + clampedHalfH < -cullMargin || screenY - clampedHalfH > height + cullMargin) continue

            // Skip sub-pixel clouds (too small to see)
            if (clampedHalfW < 1f || clampedHalfH < 1f) continue

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
                canvas.save()
                canvas.translate(screenX, screenY)
                canvas.scale(clampedDrawScale, clampedDrawScale)
                canvas.drawBitmap(bitmap, -paddedWidth / 2f, -paddedHeight / 2f, drawPaint)
                canvas.restore()
                drawCount++
            }
        }

        canvas.restore()  // Restore clipRect
    }

    private fun calculateCloudColor(state: SimulationState): Int {
        val hour = state.timeOfDayHours
        val sunrise = state.sunriseHours
        val sunset = state.sunsetHours

        val nightColor = Color.rgb(150, 155, 180)
        val dawnColor = Color.rgb(255, 200, 180)
        val dayColor = Color.rgb(250, 250, 255)
        val duskColor = Color.rgb(255, 220, 200)

        // Smooth time-based interpolation using actual sunrise/sunset
        val dawnStart = sunrise - 1.0f
        val dawnEnd = sunrise + 0.5f
        val duskStart = sunset - 0.5f
        val duskEnd = sunset + 1.0f

        val baseColor = when {
            // Pre-dawn: Night → Dawn
            hour >= dawnStart && hour < sunrise -> {
                val t = ((hour - dawnStart) / (sunrise - dawnStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(nightColor, dawnColor, t)
            }
            // Dawn → Day
            hour >= sunrise && hour < dawnEnd -> {
                val t = ((hour - sunrise) / (dawnEnd - sunrise)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(dawnColor, dayColor, t)
            }
            // Day
            hour >= dawnEnd && hour < duskStart -> dayColor
            // Day → Dusk
            hour >= duskStart && hour < sunset -> {
                val t = ((hour - duskStart) / (sunset - duskStart)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(dayColor, duskColor, t)
            }
            // Dusk → Night
            hour >= sunset && hour < duskEnd -> {
                val t = ((hour - sunset) / (duskEnd - sunset)).coerceIn(0f, 1f)
                RenderConstants.lerpColor(duskColor, nightColor, t)
            }
            // Night
            else -> nightColor
        }
        
        // Apply night shade darkening smoothly based on light level
        return if (state.nightShadeStrength > 0f && state.getLightLevel() < 0.5f) {
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
            cellCache.clear()
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
        cellCache.clear()
        cloudGroups = emptyList()
    }
}
