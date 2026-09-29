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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
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
        val seed: Long,           // For deterministic regeneration
        val stableId: Int         // Deterministic ID for stable draw ordering
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

    // Accumulated animation time for sinusoidal drift and scale oscillation
    private var animationTime = 0f

    // Exponentially smoothed frame delta used to pace cloud movement (0 = uninitialised)
    private var smoothedDeltaSec = 0f

    // World-space cloud band values — ABSOLUTE, computed once from constants.
    // NOT recalculated on resize. Cloud Y is stable regardless of viewport.
    private var cachedBandTop = ABS_BAND_TOP
    private var cachedBandBottom = ABS_BAND_BOTTOM
    private var cachedBandHeight = ABS_BAND_HEIGHT

    // Cell-based generation cache: cellIndex → generated groups
    // Prevents regeneration stalls by caching deterministic cell results
    // ConcurrentHashMap: accessed from render thread (getOrPut/removeAll) and main thread (clear)
    private val cellCache = ConcurrentHashMap<Int, List<CloudGroup>>()

    // Reusable list for draw prioritization (avoids allocation per frame)
    private val drawCandidates = ArrayList<DrawCandidate>(MAX_GROUPS)

    // Grow-only pool of DrawCandidate objects, reused across frames so pass 1 never
    // allocates a new candidate per visible cloud.
    private val drawCandidatePool = ArrayList<DrawCandidate>(MAX_GROUPS)
    private var drawCandidatePoolUsed = 0

    // Reusable Rect for direct bitmap draws (avoids allocation per cloud)
    private val dstRect = android.graphics.RectF()

    /** Lightweight mutable struct for draw prioritization. Pooled and reused per frame. */
    private class DrawCandidate {
        lateinit var group: CloudGroup
        var screenX: Float = 0f
        var screenY: Float = 0f
        var paddedWidth: Int = 0
        var paddedHeight: Int = 0
        var drawScale: Float = 0f
        var screenArea: Float = 0f
        var isBackLayer: Boolean = false

        fun set(
            group: CloudGroup, screenX: Float, screenY: Float,
            paddedWidth: Int, paddedHeight: Int, drawScale: Float,
            screenArea: Float, isBackLayer: Boolean
        ) {
            this.group = group
            this.screenX = screenX
            this.screenY = screenY
            this.paddedWidth = paddedWidth
            this.paddedHeight = paddedHeight
            this.drawScale = drawScale
            this.screenArea = screenArea
            this.isBackLayer = isBackLayer
        }
    }

    /** Fetch a reusable pooled DrawCandidate, growing the pool only when needed. */
    private fun obtainDrawCandidate(): DrawCandidate {
        val pooled = if (drawCandidatePoolUsed < drawCandidatePool.size) {
            drawCandidatePool[drawCandidatePoolUsed]
        } else {
            DrawCandidate().also { drawCandidatePool.add(it) }
        }
        drawCandidatePoolUsed++
        return pooled
    }

    // Reusable comparator: back layer first, then by descending screen area, stable tiebreaker
    private val drawCandidateComparator = compareBy<DrawCandidate> { !it.isBackLayer }
        .thenByDescending { it.screenArea }
        .thenBy { it.group.stableId }
    
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
        const val BLUR_PADDING_VERTICAL = 0.35f  // Larger vertical: puffs can extend ±0.80 from center
        
        // Hard cap on bitmap dimensions to avoid GPU texture limits
        // REDUCED for performance: smaller max = less GPU work
        const val MAX_BITMAP_DIMENSION = 512
        const val MAX_BITMAP_DIMENSION_PREVIEW = 256
        
        // Max groups for overcast conditions
        const val MAX_GROUPS = 28
        const val MAX_GROUPS_PREVIEW = 18
        
        // Preview cloud visibility: minimum draw scale to prevent tiny preview clouds
        const val PREVIEW_MIN_DRAW_SCALE = 0.65f
        
        // World-space cloud band — ABSOLUTE coordinates derived from REFERENCE_HEIGHT.
        // These are constant and never recalculated on viewport resize, so cloud Y
        // positions are stable and never clip due to viewport/parallax drift.
        const val BAND_HEIGHT_FRACTION = 0.55f      // Cloud content height as fraction of reference height
        const val BAND_MARGIN_FRACTION = 0.20f      // Extra margin above/below for parallax safety
        const val CELL_SIZE_FRACTION = 0.5f         // X-cell width as fraction of band height
        const val FIXED_CELL_SIZE = 336f             // Fixed world-unit cell size for stable grid

        // Absolute cloud band Y coordinates — never change, never clip.
        // World Y origin: center=0.5, top = 0.5 - REFERENCE_HEIGHT/2 = -959.5
        private const val ABS_WORLD_TOP = 0.5f - RenderConstants.REFERENCE_HEIGHT / 2f  // -959.5
        private const val ABS_BAND_HEIGHT = RenderConstants.REFERENCE_HEIGHT * BAND_HEIGHT_FRACTION  // 1056
        private const val ABS_BAND_MARGIN = RenderConstants.REFERENCE_HEIGHT * BAND_MARGIN_FRACTION  // 384
        // Band extends from above visible top down to mid-sky, with generous parallax padding
        val ABS_BAND_TOP = ABS_WORLD_TOP - ABS_BAND_MARGIN          // -1343.5
        val ABS_BAND_BOTTOM = ABS_WORLD_TOP + ABS_BAND_HEIGHT + ABS_BAND_MARGIN  // 480.5
        const val SPAWN_PROB_MIN = 0.3f             // Cell spawn probability at low coverage
        const val SPAWN_PROB_MAX = 0.95f            // Cell spawn probability at overcast
        const val COVERAGE_SCALE_MIN = 0.6f         // Cloud draw-size scale at low coverage
        const val COVERAGE_SCALE_MAX = 1.6f         // Cloud draw-size scale at overcast
        const val CULL_MARGIN_FRACTION = 0.25f      // Screen-space culling margin
        const val MAX_SCREEN_WIDTH_FRACTION = 0.60f  // Max cloud width as fraction of screen width
        const val MAX_DRAW_PER_FRAME = 20            // Hard cap on draw calls per layer pass
        const val MAX_CLOUD_COUNT = 50               // Maximum cloud count from getCloudCount()
        const val DELTA_SMOOTHING_ALPHA = 0.15f      // EMA factor for frame-delta pacing
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

        // Use fixed cell size for stable grid (no layout drift when viewport changes)
        val cellSize = FIXED_CELL_SIZE
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

        // Coverage drives spawn probability & draw-size scale.
        // Use MAX_CLOUD_COUNT as denominator so overcast (50) reaches full coverage.
        val cloudCoverage = (cloudCount.toFloat() / MAX_CLOUD_COUNT).coerceIn(0f, 1f)
        val spawnProbability = SPAWN_PROB_MIN + (SPAWN_PROB_MAX - SPAWN_PROB_MIN) * cloudCoverage
        coverageSizeMult = COVERAGE_SCALE_MIN + (COVERAGE_SCALE_MAX - COVERAGE_SCALE_MIN) * cloudCoverage

        val allGroups = mutableListOf<CloudGroup>()
        for (cellX in startCell..endCell) {
            val groups = cellCache.computeIfAbsent(cellX) {
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

        // At very high coverage (overcast), allow a second cloud per cell
        // for dense, overlapping sky coverage
        if (spawnProbability > 0.85f && rng.nextFloat() < 0.5f) {
            val cloud2 = createCloudGroup(rng, !isBack, cellX, cellSize)
            return listOf(cloud, cloud2)
        }

        return listOf(cloud)
    }
    
    private fun createCloudGroup(random: Random, isBackLayer: Boolean, cellX: Int, cellSize: Float): CloudGroup {
        // World-space X: place within the cell range
        val worldX = cellX * cellSize + random.nextFloat() * cellSize

        val groupWidth = GROUP_WIDTH_MIN + random.nextFloat() * (GROUP_WIDTH_MAX - GROUP_WIDTH_MIN)
        val groupHeight = GROUP_HEIGHT_MIN + random.nextFloat() * (GROUP_HEIGHT_MAX - GROUP_HEIGHT_MIN)

        // World-space Y: place within the ABSOLUTE cloud band.
        // Uses constant band coordinates — never viewport-relative.
        // Cloud centers are clamped by actual drawn half-height (including padding + parallax safety)
        // so the full rendered bitmap stays inside the visible area.
        val bandTop = cachedBandTop
        val bandBottom = cachedBandBottom
        val bandH = cachedBandHeight
        // Conservative half-height: includes blur padding factor (1.35×) for actual drawn extent
        val halfHWorld = groupHeight * 0.5f * bandH * 1.35f
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
        
        // Deterministic stable ID from cell position, layer, and seed bits
        val stableId = (cellX * 31 + (if (isBackLayer) 7 else 13)) xor random.nextInt()
        
        return CloudGroup(
            initialWorldX = worldX,
            worldY = worldY,
            speed = speed,
            alpha = alpha,
            isBackLayer = isBackLayer,
            depthFactor = depth,
            groupWidth = groupWidth,
            groupHeight = groupHeight,
            seed = random.nextLong(),
            stableId = stableId
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
    /** Compute padding for a cloud bitmap. Shared between render and draw paths for consistency. */
    private fun computePadding(contentWidth: Int, contentHeight: Int, stretch: Float): Pair<Int, Int> {
        val stretchPaddingMult = (stretch / 2f).coerceIn(1f, 3f)
        val paddingX = (contentWidth * BLUR_PADDING * stretchPaddingMult).toInt()
        val paddingY = (contentHeight * BLUR_PADDING_VERTICAL).toInt()
        return paddingX to paddingY
    }

    private fun renderGroupToBitmap(group: CloudGroup, bitmapWidth: Int, bitmapHeight: Int) {
        val (paddingX, paddingY) = computePadding(bitmapWidth, bitmapHeight, cloudStretch)
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
        
        // Create or reuse bitmap with padding; bail on OOM without crashing
        val bitmap = try {
            group.cachedBitmap ?: Bitmap.createBitmap(
                paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888
            )
        } catch (_: OutOfMemoryError) {
            group.cachedBitmap = null
            return
        }
        
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
            val bmp = group.cachedBitmap
            group.cachedBitmap = null  // Clear reference first to prevent use-after-recycle
            bmp?.recycle()
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

        // Cloud band Y is ABSOLUTE — NOT recalculated on resize.
        // cachedBandTop/Bottom/Height are initialized from ABS_BAND_* constants.
        // This ensures cloud Y positions never drift when viewport changes.
        
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
        
        // Combined wind factor, normalised to 0..1 before it is mapped onto the
        // cloud speed band. `windMultiplier` defaults to 2.0 (pref 200%), so leaving
        // this unclamped made clouds drift several times faster than
        // WIND_CLOUD_SPEED_MAX and completely detached from the reported wind.
        val windFactor = (baseWind + gustAdd).coerceIn(0f, 1f)
        val windSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN + 
            windFactor * (RenderConstants.WIND_CLOUD_SPEED_MAX - RenderConstants.WIND_CLOUD_SPEED_MIN)
        val effectiveSpeed = (windSpeed * cloudSpeedMultiplier * stormBoost)
            .coerceAtMost(RenderConstants.WIND_CLOUD_SPEED_MAX * cloudSpeedMultiplier)
        
        // Wind direction X component (-1 to 1, positive = rightward).
        // MINIMUM WIND GUARANTEE: even calm wind produces visible slow drift.
        // This prevents clouds from appearing frozen at low/perpendicular wind.
        val rawDirX = state.getWindDirX()
        val dirX = if (kotlin.math.abs(rawDirX) < 0.30f) {
            // Wind is near-perpendicular to screen — use fallback rightward drift
            if (rawDirX >= 0f) 0.30f else -0.30f
        } else {
            rawDirX
        }
        
        // Convert delta time to seconds, cap to prevent jumps.
        // The raw frame delta jitters by several milliseconds even at a stable frame
        // rate; that jitter is invisible on slow clouds but shows up as judder once
        // the wind pushes them across the screen, so pace movement on a smoothed delta.
        val rawDeltaSec = (deltaTimeMs / 1000f).coerceIn(0.001f, 0.1f)
        val deltaSec = if (smoothedDeltaSec <= 0f) {
            rawDeltaSec
        } else {
            smoothedDeltaSec + (rawDeltaSec - smoothedDeltaSec) * DELTA_SMOOTHING_ALPHA
        }
        smoothedDeltaSec = deltaSec
        
        // Accumulate animation time for per-cloud drift/oscillation in draw phase
        animationTime += deltaSec
        
        // MINIMUM effective speed: even in dead calm, clouds drift slowly.
        // This guarantees visible movement at ANY wind speed > 0.
        val minEffectiveSpeed = RenderConstants.WIND_CLOUD_SPEED_MIN * 0.5f
        val finalSpeed = effectiveSpeed.coerceAtLeast(minEffectiveSpeed)
        
        // Update each cloud group's position in world-space
        // Movement in world-space units per second (float accumulation, no quantization)
        val wrapRange = cachedWrapRange
        val ppu = cachedTransform.viewport.pixelsPerUnit
        // Minimum world-space movement per frame to guarantee 0.5 screen pixels of motion
        val minWorldMovement = if (ppu > 0f) 0.5f / ppu else 0.5f
        for (group in currentGroups) {
            // Depth-based speed: back clouds (low depth) drift slower, front clouds faster
            // Simulates atmospheric perspective — nearer objects move faster
            val depthSpeed = 0.6f + (group.depthFactor - BACK_LAYER_DEPTH_MIN) /
                (FRONT_LAYER_DEPTH_MAX - BACK_LAYER_DEPTH_MIN) * 0.4f
            // Move cloud in world-space units — pure float, no toInt() or floor()
            var movement = deltaSec * group.speed * depthSpeed * finalSpeed * RenderConstants.CLOUD_MOVEMENT_SCALE * dirX * wrapRange
            // Enforce minimum visible movement: at least 0.5 screen pixels per frame
            if (kotlin.math.abs(movement) > 0f && kotlin.math.abs(movement) < minWorldMovement) {
                movement = if (movement > 0f) minWorldMovement else -minWorldMovement
            }
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
        val targetCount = (baseCount * widthScale).toInt().coerceIn(0, MAX_CLOUD_COUNT)
        
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
        
        // Draw all groups sorted by depth layer then size
        drawGroupsBitmap(canvas, currentGroups, parallaxCalculator)
    }

    /**
     * Draw cloud groups as cached bitmaps.
     * Uses clipRect to prevent edge artifacts. Sorted by depth layer (back first)
     * then by screen area descending for deterministic draw capping.
     * Uses direct drawBitmap with dst rect to avoid per-cloud canvas state changes.
     */
    private fun drawGroupsBitmap(
        canvas: Canvas,
        groups: List<CloudGroup>,
        parallaxCalculator: ParallaxCalculator
    ) {
        val transform = cachedTransform
        val mapper = WorldToScreenMapper(transform.viewport)
        val ppu = transform.viewport.pixelsPerUnit
        val effectiveDrawScale = if (isPreviewMode) {
            transform.heightScale.coerceAtLeast(PREVIEW_MIN_DRAW_SCALE) * coverageSizeMult
        } else {
            transform.heightScale * (1f + (coverageSizeMult - 1f) * 0.5f)
        }
        val maxScreenW = width * MAX_SCREEN_WIDTH_FRACTION
        val canvasW = width
        val canvasH = height

        // --- Pass 1: collect visible candidates ---
        drawCandidates.clear()
        drawCandidatePoolUsed = 0
        var i = 0
        while (i < groups.size) {
            val group = groups[i]
            i++

            // Skip tiny alpha (invisible clouds waste GPU)
            if (group.alpha < 3) continue

            val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
            val worldPX = if (ppu > 0f) px / ppu else 0f
            val worldPY = if (ppu > 0f) py / ppu else 0f

            val renderX = group.currentWorldX + worldPX
            val renderY = group.worldY + worldPY

            // Per-cloud sinusoidal drift: breaks uniform straight-line motion.
            // Each cloud gets a unique phase from its seed, so they drift independently.
            // This makes the eye detect motion even when wind speed is homogeneous.
            val phase = (group.seed % 10000L).toFloat()
            val driftX = sin(animationTime * 0.3 + phase * 0.001).toFloat() * 15f
            val driftY = sin(animationTime * 0.15 + phase * 0.002).toFloat() * 8f

            val screenX = mapper.x(renderX + driftX)
            val screenY = mapper.y(renderY + driftY)

            val groupWidthPx = (group.groupWidth * minDim).toInt()
                .coerceIn(50, effectiveMaxDimension)
            val heightRef = maxOf(minDim, height * 0.5f)
            val groupHeightPx = (group.groupHeight * heightRef).toInt()
                .coerceIn(30, effectiveMaxDimension)

            val (paddingX, paddingY) = computePadding(groupWidthPx, groupHeightPx, cloudStretch)
            val paddedWidth = groupWidthPx + paddingX * 2
            val paddedHeight = groupHeightPx + paddingY * 2

            val halfW = paddedWidth / 2f * effectiveDrawScale

            val clampedDrawScale = if (halfW * 2f > maxScreenW && maxScreenW > 0f) {
                effectiveDrawScale * (maxScreenW / (halfW * 2f))
            } else {
                effectiveDrawScale
            }
            val clampedHalfW = paddedWidth / 2f * clampedDrawScale
            val clampedHalfH = paddedHeight / 2f * clampedDrawScale

            // Offscreen rejection: use generous margin to prevent pop-in/blinking during parallax
            val marginX = canvasW * CULL_MARGIN_FRACTION
            val marginY = canvasH * CULL_MARGIN_FRACTION
            if (screenX + clampedHalfW < -marginX - 0.5f || screenX - clampedHalfW > canvasW + marginX + 0.5f) continue
            if (screenY + clampedHalfH < -marginY - 0.5f || screenY - clampedHalfH > canvasH + marginY + 0.5f) continue
            if (clampedHalfW < 1f || clampedHalfH < 1f) continue

            // Queue async bitmap render if not ready
            if (group.cachedBitmap == null) {
                if (group.isRendering.compareAndSet(false, true)) {
                    val w = groupWidthPx
                    val h = groupHeightPx
                    bitmapExecutor.execute {
                        try { renderGroupToBitmap(group, w, h) }
                        finally { group.isRendering.set(false) }
                    }
                }
                continue
            }

            val screenArea = clampedHalfW * clampedHalfH * 4f
            drawCandidates.add(obtainDrawCandidate().apply {
                set(group, screenX, screenY,
                    paddedWidth, paddedHeight, clampedDrawScale, screenArea,
                    group.isBackLayer)
            })
        }

        // --- Pass 2: sort by layer (back first) then area descending ---
        drawCandidates.sortWith(drawCandidateComparator)

        // Layered draw budget: split budget between back and front layers
        // so both layers get fair representation even at high density.
        // Soft cap: if candidate count is only slightly above limit, draw all to prevent flicker.
        val totalCandidates = drawCandidates.size
        val limit = if (totalCandidates <= MAX_DRAW_PER_FRAME + 3) {
            totalCandidates
        } else {
            val backCount = drawCandidates.count { it.isBackLayer }
            val frontCount = totalCandidates - backCount
            val halfBudget = MAX_DRAW_PER_FRAME / 2
            val backBudget = halfBudget.coerceAtMost(backCount)
            val frontBudget = (MAX_DRAW_PER_FRAME - backBudget).coerceAtMost(frontCount)
            backBudget + frontBudget
        }

        canvas.save()
        canvas.clipRect(0f, 0f, canvasW, canvasH)
        for (j in 0 until limit) {
            val c = drawCandidates[j]
            val bitmap = c.group.cachedBitmap ?: continue
            if (bitmap.isRecycled) continue  // Guard against race with background recycle
            // Subtle scale oscillation: each cloud "breathes" slightly at its own rate.
            // ±3% size variation makes the scene feel alive without being distracting.
            val oscPhase = (c.group.seed % 10000L).toFloat()
            val scaleOsc = 1f + 0.03f * sin(animationTime * 0.25 + oscPhase * 0.003).toFloat()
            val finalScale = c.drawScale * scaleOsc
            // Direct draw via dst rect — avoids per-cloud save/translate/scale/restore
            val halfW = c.paddedWidth / 2f * finalScale
            val halfH = c.paddedHeight / 2f * finalScale
            dstRect.set(
                c.screenX - halfW, c.screenY - halfH,
                c.screenX + halfW, c.screenY + halfH
            )
            canvas.drawBitmap(bitmap, null, dstRect, drawPaint)
        }
        canvas.restore()
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
        val newSeed = settings.getInt("pref_random_seed", PreferenceDefaults.RANDOM_SEED)
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
