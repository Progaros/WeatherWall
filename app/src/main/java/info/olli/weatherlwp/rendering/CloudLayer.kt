package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
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
 * Cloud render mode - determines how clouds are drawn.
 */
enum class CloudRenderMode {
    /** Pre-render cloud groups to bitmaps with blur. Default, best quality. */
    CACHED,
    /** Single scrolling noise texture. Fastest, stylized look. */
    TILED,
    /** AGSL RuntimeShader for GPU-accelerated procedural clouds (Android 13+). */
    SHADER
}

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

    // API 31+: RenderNode recording paint; blur is applied via RenderEffect
    private val renderNodePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private var width = 0f
    private var height = 0f
    private var minDim = 0f  // min(width, height) - used for cloud sizing to prevent giant bitmaps
    
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

        // API 31+: hardware-rendered cloud content
        @Volatile var renderNode: RenderNode? = null
        var renderNodeWidth: Int = 0
        var renderNodeHeight: Int = 0
        
        // Current X position (accumulated via delta time, wraps 0-1)
        @Volatile var currentX: Float = initialX
        
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
    private var renderMode = CloudRenderMode.CACHED  // Which drawing method to use
    
    // Tiled mode resources
    private var tiledNoiseBitmap: Bitmap? = null
    private var tiledShader: BitmapShader? = null
    private val tiledMatrix = Matrix()
    private val tiledPaint = Paint().apply { isAntiAlias = true }
    // Reused path to avoid allocating a new Path every frame in tiled mode
    private val tiledWavyPath = android.graphics.Path()
    // Reused color filters: only recreated when cloud tint actually changes
    private var cachedMultiplyFilterColor = Int.MIN_VALUE
    private var cachedMultiplyFilter: PorterDuffColorFilter? = null
    private var cachedSrcInFilterColor = Int.MIN_VALUE
    private var cachedSrcInFilter: PorterDuffColorFilter? = null
    private var tiledOffsetX = 0f
    private var tiledOffsetY = 0f

    // API 31+ cloud RenderEffect cache (blur + tint chain)
    private var cachedNodeEffectColor = Int.MIN_VALUE
    private var cachedNodeEffect: RenderEffect? = null
    
    // AGSL shader (Android 13+)
    @Suppress("DEPRECATION")
    private var agslShader: android.graphics.RuntimeShader? = null
    private val shaderPaint = Paint().apply { isAntiAlias = true }
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
        const val BLUR_PADDING = 0.20f  // Reduced from 0.25 for smaller bitmaps

        // API 31+ RenderNode blur radius (matches legacy BlurMaskFilter look)
        const val RENDER_NODE_BLUR_RADIUS = 10f
        
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
                clearGroupRenderNodes()
                resetRenderNodeEffects()
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun renderGroupToRenderNode(group: CloudGroup, contentWidth: Int, contentHeight: Int) {
        // Match bitmap path padding so blur has off-edge room.
        val stretchPaddingMult = (cloudStretch / 2f).coerceIn(1f, 3f)
        val paddingX = (contentWidth * BLUR_PADDING * stretchPaddingMult).toInt()
        val paddingY = (contentHeight * BLUR_PADDING).toInt()
        val paddedWidth = contentWidth + paddingX * 2
        val paddedHeight = contentHeight + paddingY * 2

        val node = group.renderNode ?: RenderNode("CloudGroup_${group.seed}").also {
            group.renderNode = it
        }

        if (group.renderNodeWidth != paddedWidth || group.renderNodeHeight != paddedHeight) {
            node.setPosition(0, 0, paddedWidth, paddedHeight)
            group.renderNodeWidth = paddedWidth
            group.renderNodeHeight = paddedHeight
        }

        val recordingCanvas = node.beginRecording(paddedWidth, paddedHeight)
        renderNodePaint.color = Color.WHITE
        renderNodePaint.alpha = 255

        val random = Random(group.seed)
        val minPuffs = (CLOUDS_PER_GROUP_MIN_BASE * fluffinessMultiplier).toInt().coerceAtLeast(3)
        val maxPuffs = (CLOUDS_PER_GROUP_MAX_BASE * fluffinessMultiplier).toInt().coerceAtLeast(minPuffs + 1)
        val numPuffs = minPuffs + random.nextInt(maxPuffs - minPuffs + 1)

        val centerX = paddedWidth / 2f
        val centerY = paddedHeight / 2f
        val fadeMarginX = contentWidth * 0.35f
        val fadeMarginY = contentHeight * 0.35f

        var i = 0
        while (i < numPuffs) {
            val offsetX = (random.nextFloat() - 0.5f) * contentWidth * 0.7f
            val offsetY = (random.nextFloat() - 0.5f) * contentHeight * 0.5f

            val puffCenterX = centerX + offsetX
            val puffCenterY = centerY + offsetY

            val contentLeft = paddingX.toFloat()
            val contentRight = paddingX + contentWidth.toFloat()
            val contentTop = paddingY.toFloat()
            val contentBottom = paddingY + contentHeight.toFloat()

            val distFromLeftEdge = puffCenterX - contentLeft
            val distFromRightEdge = contentRight - puffCenterX
            val distFromTopEdge = puffCenterY - contentTop
            val distFromBottomEdge = contentBottom - puffCenterY

            val xFade = (minOf(distFromLeftEdge, distFromRightEdge) / fadeMarginX).coerceIn(0f, 1f)
            val yFade = (minOf(distFromTopEdge, distFromBottomEdge) / fadeMarginY).coerceIn(0f, 1f)
            val edgeFade = kotlin.math.sqrt(xFade * yFade)

            val puffAlpha = (group.alpha * edgeFade).toInt().coerceIn(0, 255)
            renderNodePaint.alpha = puffAlpha

            val radiusX = contentWidth * (0.18f + random.nextFloat() * 0.22f) * cloudStretch * 0.35f
            val radiusY = contentHeight * (0.25f + random.nextFloat() * 0.30f)

            recordingCanvas.drawOval(
                puffCenterX - radiusX, puffCenterY - radiusY,
                puffCenterX + radiusX, puffCenterY + radiusY,
                renderNodePaint
            )

            i++
        }

        node.endRecording()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun getCloudRenderNodeEffect(tintColor: Int): RenderEffect {
        val bucketedColor = quantizeCloudTintColor(tintColor)
        val existing = cachedNodeEffect
        if (existing != null && cachedNodeEffectColor == bucketedColor) {
            return existing
        }

        val blurEffect = RenderEffect.createBlurEffect(
            RENDER_NODE_BLUR_RADIUS,
            RENDER_NODE_BLUR_RADIUS,
            Shader.TileMode.CLAMP
        )
        val tintFilter = PorterDuffColorFilter(bucketedColor, PorterDuff.Mode.MULTIPLY)
        val effect = RenderEffect.createColorFilterEffect(tintFilter, blurEffect)

        cachedNodeEffectColor = bucketedColor
        cachedNodeEffect = effect
        return effect
    }

    private fun quantizeCloudTintColor(color: Int): Int = ColorMath.quantizeColor(color)

    private fun clearGroupBitmaps() {
        for (group in cloudGroups) {
            group.cachedBitmap?.recycle()
            group.cachedBitmap = null
            group.lastWidth = 0
            group.lastHeight = 0
        }
    }

    private fun clearGroupRenderNodes() {
        for (group in cloudGroups) {
            group.renderNode = null
            group.renderNodeWidth = 0
            group.renderNodeHeight = 0
        }
    }

    private fun resetRenderNodeEffects() {
        cachedNodeEffect = null
        cachedNodeEffectColor = Int.MIN_VALUE
    }

    private fun clearTiledResources() {
        tiledNoiseBitmap?.recycle()
        tiledNoiseBitmap = null
        tiledShader = null
        tiledOffsetX = 0f
        tiledOffsetY = 0f
    }

    override fun resize(w: Float, h: Float) {
        val sizeChanged = width != w || height != h
        width = w
        height = h
        minDim = minOf(w, h)  // Use smaller dimension to prevent gigantic bitmaps on portrait
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)

        if (!sizeChanged) return
        
        // Invalidate cached bitmaps on resize
        clearGroupBitmaps()
        clearGroupRenderNodes()
        resetRenderNodeEffects()

        // Tiled noise/shader resources are tied to previous rendering surface assumptions.
        // Recreate lazily on the next frame at the new size.
        clearTiledResources()
        
        generateCloudGroups()
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
        
        // Update each cloud group's position
        // Speed is in normalized units per second (0-1 = screen width)
        // At max wind + storm, clouds cross screen in ~8-15 seconds
        // At calm weather, clouds cross screen in ~60-90 seconds
        for (group in currentGroups) {
            // Move cloud based on current speed with direction
            // CLOUD_MOVEMENT_SCALE scales to reasonable speed (full screen in ~8s at max, ~80s at min)
            val movement = deltaSec * group.speed * effectiveSpeed * RenderConstants.CLOUD_MOVEMENT_SCALE * dirX
            var newX = group.currentX + movement
            // Wrap position smoothly (0-1 range)
            while (newX > 1f) newX -= 1f
            while (newX < 0f) newX += 1f
            group.currentX = newX
        }
        
        // Update tiled/shader mode offset using same wind calculation as cached mode
        // This ensures all render modes have consistent cloud movement speed
        val tiledMovement = deltaSec * effectiveSpeed * RenderConstants.CLOUD_MOVEMENT_SCALE * dirX * width
        tiledOffsetX += tiledMovement
        tiledOffsetY += deltaSec * effectiveSpeed * 10f  // Slight vertical drift
        
        // Wrap offsets to prevent overflow
        if (tiledOffsetX > 10000f) tiledOffsetX -= 10000f
        if (tiledOffsetX < -10000f) tiledOffsetX += 10000f
        if (tiledOffsetY > 10000f) tiledOffsetY -= 10000f
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !cloudsEnabled) return
        
        // Calculate cloud color for this frame (used by all render modes)
        currentCloudColor = calculateCloudColor(state)
        
        // Dispatch to appropriate render mode
        when (renderMode) {
            CloudRenderMode.CACHED -> drawCachedMode(canvas, state, parallaxCalculator)
            CloudRenderMode.TILED -> drawTiledMode(canvas, state, parallaxCalculator)
            CloudRenderMode.SHADER -> drawShaderMode(canvas, state, parallaxCalculator)
        }
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
     * Return a cached SRC_IN color filter for tiled cloud tinting.
     * Recreates only when tint color changes.
     */
    private fun getSrcInFilter(color: Int): PorterDuffColorFilter {
        val existing = cachedSrcInFilter
        if (existing != null && cachedSrcInFilterColor == color) return existing
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        cachedSrcInFilter = filter
        cachedSrcInFilterColor = color
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
        // Keep scaling conservative to avoid excessive bitmap rendering.
        val skyAreaRatio = (extendedWidth / width) * (height / 1920f)
        val areaScale = skyAreaRatio.coerceIn(1.0f, 1.5f)
        val baseCount = state.getCloudCount()
        val targetCount = (baseCount * areaScale).toInt().coerceIn(0, effectiveMaxGroups)
        
        // Regenerate groups if count changed significantly
        if (kotlin.math.abs(targetCount - cloudCount) > 3 || 
            (targetCount == 0 && cloudCount > 0) || 
            (targetCount > 0 && cloudCount == 0)) {
            clearGroupBitmaps()
            clearGroupRenderNodes()
            resetRenderNodeEffects()
            cloudCount = targetCount
            generateCloudGroups()
        }
        
        val currentGroups = cloudGroups
        if (currentGroups.isEmpty()) return

        // API 31+: Use RenderNode + RenderEffect on hardware canvas for fully GPU blur/compositing.
        // Fallback to legacy cached bitmaps on older devices or software canvas paths.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
            val effect = getCloudRenderNodeEffect(currentCloudColor)
            drawGroupsRenderNode(canvas, currentGroups, isBackLayer = true, parallaxCalculator, effect)
            drawGroupsRenderNode(canvas, currentGroups, isBackLayer = false, parallaxCalculator, effect)
            return
        }
        
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
        var i = 0
        while (i < groups.size) {
            val group = groups[i]
            i++
            if (group.isBackLayer != isBackLayer) continue

            val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
            val groupX = group.currentX
            val screenX = groupX * extendedWidth - extendedOffset + px
            val screenY = group.y * height + py

            val groupWidthPx = (group.groupWidth * minDim).toInt()
                .coerceIn(50, effectiveMaxDimension)

            // Viewport culling: skip clouds entirely off-screen
            val halfW = groupWidthPx * 0.7f  // approximate with padding
            if (screenX + halfW < 0f || screenX - halfW > width) continue
            if (screenY + groupWidthPx * 0.5f < 0f || screenY - groupWidthPx * 0.5f > height) continue
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
                canvas.drawBitmap(
                    bitmap,
                    screenX - paddedWidth / 2f,
                    screenY - paddedHeight / 2f,
                    drawPaint
                )
            }
        }
    }

    /**
     * API 31+ draw path: pre-recorded RenderNode cloud groups with GPU RenderEffect blur.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawGroupsRenderNode(
        canvas: Canvas,
        groups: List<CloudGroup>,
        isBackLayer: Boolean,
        parallaxCalculator: ParallaxCalculator,
        effect: RenderEffect
    ) {
        var i = 0
        while (i < groups.size) {
            val group = groups[i]
            i++
            if (group.isBackLayer != isBackLayer) continue

            val px = parallaxCalculator.getOffsetXForDepth(group.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(group.depthFactor)
            val groupX = group.currentX
            val screenX = groupX * extendedWidth - extendedOffset + px
            val screenY = group.y * height + py

            val groupWidthPx = (group.groupWidth * minDim).toInt()
                .coerceIn(50, effectiveMaxDimension)

            // Viewport culling: skip clouds entirely off-screen
            val halfW = groupWidthPx * 0.7f
            if (screenX + halfW < 0f || screenX - halfW > width) continue
            if (screenY + groupWidthPx * 0.5f < 0f || screenY - groupWidthPx * 0.5f > height) continue

            val heightRef = maxOf(minDim, height * 0.5f)
            val groupHeightPx = (group.groupHeight * heightRef).toInt()
                .coerceIn(30, effectiveMaxDimension)

            val stretchPaddingMult = (cloudStretch / 2f).coerceIn(1f, 3f)
            val paddingX = (groupWidthPx * BLUR_PADDING * stretchPaddingMult).toInt()
            val paddingY = (groupHeightPx * BLUR_PADDING).toInt()
            val paddedWidth = groupWidthPx + paddingX * 2
            val paddedHeight = groupHeightPx + paddingY * 2

            val needsRecord = group.renderNode == null ||
                group.renderNodeWidth != paddedWidth ||
                group.renderNodeHeight != paddedHeight
            if (needsRecord) {
                renderGroupToRenderNode(group, groupWidthPx, groupHeightPx)
            }

            val node = group.renderNode ?: continue
            node.setRenderEffect(effect)

            canvas.save()
            canvas.translate(
                screenX - paddedWidth / 2f,
                screenY - paddedHeight / 2f
            )
            canvas.drawRenderNode(node)
            canvas.restore()
        }
    }
    
    /**
     * Tiled texture mode - single scrolling noise texture, very fast.
     * Uses a pre-generated noise bitmap with BitmapShader for tiling.
     */
    private fun drawTiledMode(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Skip if no clouds
        if (state.getCloudCount() == 0) return
        
        // Generate tiled noise bitmap if needed (once only)
        if (tiledNoiseBitmap == null || tiledShader == null) {
            generateTiledNoiseBitmap()
        }
        
        val shader = tiledShader ?: return
        if (tiledNoiseBitmap == null) return  // Ensure bitmap exists for shader
        
        // Calculate cloud coverage based on cloud count (0-50 maps to alpha 0-255)
        val coverage = (state.getCloudCount() / 50f).coerceIn(0f, 1f)
        
        // tiledOffsetX/Y are now updated in update() with consistent wind speed
        
        // Apply cloud color as tint using SRC_IN mode (tints the white texture)
        tiledPaint.shader = shader
        tiledPaint.colorFilter = getSrcInFilter(currentCloudColor)
        
        // Create wavy path for cloud bottom edge (more natural than hard rectangle)
        val wavyPath = tiledWavyPath
        
        // Draw two layers for depth - back layer and front layer
        // Back layer - higher, more transparent, gentler wave
        val backPx = parallaxCalculator.getOffsetXForDepth(0.15f)
        val backPy = parallaxCalculator.getOffsetYForDepth(0.15f)
        tiledMatrix.reset()
        tiledMatrix.setScale(2.5f, 2.5f)
        tiledMatrix.postTranslate(tiledOffsetX * 0.6f + backPx, tiledOffsetY * 0.3f + backPy)
        shader.setLocalMatrix(tiledMatrix)
        tiledPaint.alpha = (coverage * 180).toInt().coerceIn(0, 255)
        
        // Draw back layer with wavy bottom (gentler wave)
        wavyPath.reset()
        wavyPath.moveTo(0f, 0f)
        wavyPath.lineTo(width, 0f)
        // Wavy bottom edge
        val backBaseY = height * 0.45f
        wavyPath.lineTo(width, backBaseY)
        val backWaveAmp = height * 0.04f
        val backWaveFreq = kotlin.math.PI / (width * 0.25f)
        val backWavePhase = tiledOffsetX * 0.02f
        var bx = width
        while (bx >= 0f) {
            val waveY = backBaseY + kotlin.math.sin((bx * backWaveFreq + backWavePhase).toDouble()).toFloat() * backWaveAmp
            wavyPath.lineTo(bx, waveY)
            bx -= width / 30f
        }
        wavyPath.close()
        canvas.drawPath(wavyPath, tiledPaint)
        
        // Front layer - lower, more opaque, more pronounced wave
        val frontPx = parallaxCalculator.getOffsetXForDepth(0.30f)
        val frontPy = parallaxCalculator.getOffsetYForDepth(0.30f)
        tiledMatrix.reset()
        tiledMatrix.setScale(1.5f, 1.5f)
        tiledMatrix.postTranslate(tiledOffsetX + frontPx, tiledOffsetY * 0.5f + frontPy)
        shader.setLocalMatrix(tiledMatrix)
        tiledPaint.alpha = (coverage * 220).toInt().coerceIn(0, 255)
        
        // Draw front layer with wavy bottom
        wavyPath.reset()
        wavyPath.moveTo(0f, 0f)
        wavyPath.lineTo(width, 0f)
        val frontBaseY = height * 0.40f
        val frontWaveAmp = height * 0.06f
        val frontWaveFreq = kotlin.math.PI / (width * 0.20f)
        val frontWavePhase = tiledOffsetX * 0.03f
        var fx = width
        while (fx >= 0f) {
            val waveY = frontBaseY + kotlin.math.sin((fx * frontWaveFreq + frontWavePhase).toDouble()).toFloat() * frontWaveAmp
            wavyPath.lineTo(fx, waveY)
            fx -= width / 30f
        }
        wavyPath.close()
        canvas.drawPath(wavyPath, tiledPaint)
        
        tiledPaint.shader = null
        tiledPaint.colorFilter = null
    }
    
    /**
     * Generate a cloud noise bitmap for tiled texture.
     * Uses simple Perlin-style value noise with guaranteed visible output.
     */
    private fun generateTiledNoiseBitmap() {
        val size = 128  // Smaller = faster generation
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(randomSeed.toLong())
        
        // Pre-generate random values grid (for seamless tiling, edges must match)
        val gridSize = 8  // 8x8 grid of control points
        val grid = Array(gridSize + 1) { FloatArray(gridSize + 1) }
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                grid[y][x] = random.nextFloat()
            }
        }
        // Wrap edges for seamless tiling
        for (i in 0..gridSize) {
            grid[gridSize][i] = grid[0][i % gridSize]
            grid[i][gridSize] = grid[i % gridSize][0]
        }
        
        // Smoothstep for interpolation
        fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
        
        // Bilinear interpolation from grid
        fun sampleNoise(x: Float, y: Float): Float {
            val gx = (x * gridSize).coerceIn(0f, gridSize.toFloat() - 0.001f)
            val gy = (y * gridSize).coerceIn(0f, gridSize.toFloat() - 0.001f)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val x1 = (x0 + 1).coerceAtMost(gridSize)
            val y1 = (y0 + 1).coerceAtMost(gridSize)
            val fx = smoothstep(gx - x0)
            val fy = smoothstep(gy - y0)
            
            val v00 = grid[y0][x0]
            val v10 = grid[y0][x1]
            val v01 = grid[y1][x0]
            val v11 = grid[y1][x1]
            
            val top = v00 + fx * (v10 - v00)
            val bot = v01 + fx * (v11 - v01)
            return top + fy * (bot - top)
        }
        
        // Generate pixels with multi-octave noise
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val nx = x.toFloat() / size
                val ny = y.toFloat() / size
                
                // 3 octaves of noise (fast) - combine for cloud-like pattern
                var value = sampleNoise(nx, ny) * 0.5f +
                           sampleNoise(nx * 2f % 1f, ny * 2f % 1f) * 0.3f +
                           sampleNoise(nx * 4f % 1f, ny * 4f % 1f) * 0.2f
                
                // Remap from 0.0-1.0 to have more cloud coverage
                // Original range is roughly 0.2-0.8, we want some to be solid
                value = ((value - 0.2f) / 0.5f).coerceIn(0f, 1f)
                
                // Apply soft threshold (clouds have softer edges)
                value = kotlin.math.sqrt(value)  // Soften - sqrt expands lower values
                
                // Pixel: white color with varying alpha based on cloud density
                val alpha = (value * 255).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (alpha shl 24) or 0x00FFFFFF  // White with varying alpha
            }
        }
        
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        tiledNoiseBitmap = bitmap
        tiledShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        android.util.Log.d("CloudLayer", "Generated tiled noise bitmap ${size}x${size}")
    }
    
    /**
     * AGSL RuntimeShader mode - GPU procedural clouds (Android 13+).
     * Falls back to tiled mode on older devices.
     * 
     * Note: Requires hardware canvas (lockHardwareCanvas) for RuntimeShader to work.
     * WeatherWallpaper.kt already uses lockHardwareCanvas with fallback.
     */
    private fun drawShaderMode(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        // Fall back to tiled mode on older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            drawTiledMode(canvas, state, parallaxCalculator)
            return
        }
        
        // Skip if no clouds
        if (state.getCloudCount() == 0) return
        
        // Create AGSL shader if needed
        if (agslShader == null) {
            createAgslShader()
        }
        
        val shader = agslShader ?: run {
            drawTiledMode(canvas, state, parallaxCalculator)
            return
        }
        
        // Set shader uniforms - use 'i' prefix matching shader code
        val coverage = (state.getCloudCount() / 50f).coerceIn(0f, 1f)
        
        // Resolution matches the drawn rect size (full screen for shader mode)
        shader.setFloatUniform("iResolution", width, height)
        shader.setFloatUniform("iTime", tiledOffsetX * 0.01f)  // Scale down for shader animation
        shader.setFloatUniform("iCoverage", coverage)
        // Use setColorUniform for layout(color) uniform
        shader.setColorUniform("iCloudColor", currentCloudColor)
        
        shaderPaint.shader = shader
        // Draw full screen - shader handles fading at edges
        canvas.drawRect(0f, 0f, width, height, shaderPaint)
        shaderPaint.shader = null
    }
    
    /**
     * Create AGSL RuntimeShader for procedural clouds.
     * 
     * Uses layout(color) uniform for proper Android color handling and
     * carefully tuned coverage→threshold mapping to avoid solid-white saturation.
     */
    private fun createAgslShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        
        // Load AGSL shader from raw resource for better readability and IDE support
        val agslCode = try {
            context.resources.openRawResource(info.olli.weatherlwp.R.raw.cloud_shader)
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.e("CloudLayer", "Failed to load AGSL shader resource: ${e.message}")
            return
        }
        
        try {
            agslShader = android.graphics.RuntimeShader(agslCode)
        } catch (e: Exception) {
            android.util.Log.e("CloudLayer", "Failed to create AGSL shader: ${e.message}")
            agslShader = null
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
        
        // Cloud render mode
        val renderModeStr = settings.getString("pref_cloud_render_mode", "cached")
        val newRenderMode = when (renderModeStr) {
            "tiled" -> CloudRenderMode.TILED
            "shader" -> CloudRenderMode.SHADER
            else -> CloudRenderMode.CACHED
        }
        // Note: renderModeChanged was used for cache invalidation, now handled by mode comparison
        renderMode = newRenderMode
        
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
            // Cleanup cached cloud resources
            clearGroupBitmaps()
            clearGroupRenderNodes()
            resetRenderNodeEffects()
            cloudGroups = emptyList()
        } else if (enabledChanged || stretchChanged || seedChanged || fluffinessChanged) {
            // Invalidate cached cloud resources when structural settings change
            // Note: opacity is applied at draw time, no bitmap regeneration needed
            clearGroupBitmaps()
            clearGroupRenderNodes()
            resetRenderNodeEffects()
            // Regenerate cloud groups with new settings
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
        clearGroupRenderNodes()
        resetRenderNodeEffects()
        cloudGroups = emptyList()
        
        // Cleanup tiled mode resources
        clearTiledResources()
    }
}
