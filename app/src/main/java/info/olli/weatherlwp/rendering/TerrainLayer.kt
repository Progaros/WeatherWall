package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.SeasonHelper
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders hills and trees with wind-based animation.
 * 
 * Uses SimulationState for:
 * - windStrength (affects tree sway amplitude)
 * - timeSeconds (for consistent wind animation across reloads)
 * - isNight (affects color tinting)
 * 
 * Uses RenderConstants for:
 * - World coordinate bounds
 * - Reference aspect ratio
 * - Parallax depth range
 * - Base colors
 * 
 * Supports integration with RandomEventLayer to draw ground animals at correct depth.
 */
class TerrainLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.TERRAIN
    private val hillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val hillPath = Path()

    private var width = 0f
    private var height = 0f
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f  // Offset to center extended content
    private var lastGeneratedWidth = 0f
    private var lastGeneratedHeight = 0f
    
    // Cached hill gradients to avoid per-frame LinearGradient allocation
    private var cachedHillGradientAmbientBucket = -1
    private var cachedHillGradientSnow = -1
    private val cachedHillGradients = HashMap<Int, LinearGradient>()

    // Reused draw buffer for Y-sorted terrain interleaving (trees, bushes, animals)
    private class DrawEntry {
        var y = 0f
        var kind = DRAW_KIND_TREE
        var index = 0
    }

    companion object {
        private const val DRAW_KIND_TREE = 0
        private const val DRAW_KIND_BUSH = 1
        private const val DRAW_KIND_ANIMAL = 2
    }

    private var drawEntryBuffer = Array(128) { DrawEntry() }
    
    // Delegated renderers for decomposition
    private val treeRenderer = TreeRenderer()
    private val bushRenderer = BushRenderer()
    
    // Callback for drawing ground events at specific depth levels
    // Called during draw() between tree layers to integrate animals with proper depth
    var groundEventDrawer: GroundEventDrawer? = null
    
    /**
     * Callback interface for drawing ground events at specific depth levels.
     */
    interface GroundEventDrawEntry {
        val yRatio: Float
        fun draw()
    }

    interface GroundEventDrawer {
        /**
         * Draw ground events in the depth range [minDepth, maxDepth).
         * @param minDepth Minimum depth factor (inclusive)
         * @param maxDepth Maximum depth factor (exclusive)
         */
        fun drawInRange(minDepth: Float, maxDepth: Float)
        
        /**
         * Get ground events in the depth range [minDepth, maxDepth) with their current Y positions.
         * Used for Y-based sorting with trees.
         * Returns reusable entry objects to avoid per-frame lambda/Pair allocations.
         */
        fun getEventsInRange(minDepth: Float, maxDepth: Float): List<GroundEventDrawEntry> = emptyList()
    }
    
    /**
     * Callback for drawing river/water at a specific depth layer.
     * Called after each hill fill but BEFORE trees, so trees appear in front of water.
     */
    fun interface RiverAtDepthDrawer {
        fun drawAtDepth(canvas: Canvas, depthFactor: Float, layerIndex: Int, totalLayers: Int)
    }
    
    /** River drawer, set by ParallaxScene to draw river segments between terrain layers. */
    var riverDrawer: RiverAtDepthDrawer? = null
    
    /** Lake drawer, set by ParallaxScene to draw lake at its target terrain layer. */
    var lakeDrawer: RiverAtDepthDrawer? = null
    
    // Data structures (immutable after creation)
    private data class HillData(
        val controlPoints: List<Float>,
        val depthFactor: Float,
        val heightOffset: Float,
        val dayColor: Int
    )
    
    // Thread-safe terrain data using volatile references + immutable lists
    @Volatile private var hills: List<HillData> = emptyList()
    
    // Settings
    private var hillCount = 5
    private var hillStartY = 0.50f
    private var hillEndY = 0.85f
    private var randomSeed = 42
    private var hillRoughness = 1.0f
    private var hillAmplitude = 1.0f
    private var hillFrequency = 2.0f
    
    // Hill layer depth settings
    private var hillDistance = 0f
    private var hillDepthSpread = 0.5f
    
    // Seasonal settings
    private var terrainSeasonOverride = "auto"
    private var springFlowersEnabled = true
    private var springFlowerDensity = 500
    private var springFlowerSpread = 0.5f
    private var autumnGrassEnabled = true
    private var autumnGrassIntensity = 50
    private var winterSnowEnabled = true
    private var winterSnowCoverage = 50
    
    // Dynamic snow from weather history
    private var useWeatherSnow = true
    
    // Season transition
    private var seasonTransitionDays = 14
    
    // Seasonal decoration settings
    private var fallenLeavesEnabled = true
    private var fallenLeavesDensity = 50
    private var mushroomsEnabled = true
    private var mushroomDensity = 20
    private var frostEnabled = true
    
    // Pre-generated flower positions (per hill)
    private data class FlowerData(
        val xRatio: Float,
        val yOffset: Float,
        val color: Int,
        val size: Float
    )
    @Volatile private var flowersByHill: Map<Int, List<FlowerData>> = emptyMap()
    
    // Pre-generated fallen leaf positions (per hill)
    private data class FallenLeafData(
        val xRatio: Float,
        val yOffset: Float,
        val color: Int,
        val size: Float,
        val rotation: Float
    )
    @Volatile private var fallenLeavesByHill: Map<Int, List<FallenLeafData>> = emptyMap()
    
    // Pre-generated mushroom positions (per hill)
    private data class MushroomData(
        val xRatio: Float,
        val yOffset: Float,
        val capColor: Int,
        val size: Float
    )
    @Volatile private var mushroomsByHill: Map<Int, List<MushroomData>> = emptyMap()
    
    // Pre-generated snow patch positions (per hill)
    private data class SnowPatchData(
        val startXRatio: Float,
        val endXRatio: Float,
        val alpha: Int
    )
    @Volatile private var snowByHill: Map<Int, List<SnowPatchData>> = emptyMap()
    
    private val leafPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mushroomPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mushroomCapPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val frostPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val flowerPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val snowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    init {
        reloadSettings()
    }
    
    /**
     * Determines current terrain season based on date or override setting.
     * Returns: "spring" (Mar-May), "summer" (Jun-Aug), "autumn" (Sep-Nov), "winter" (Dec-Feb)
     * 
     * Delegates to centralized SeasonHelper for consistency with MountainLayer.
     */
    private fun getCurrentTerrainSeason(): String {
        return if (terrainSeasonOverride != "auto") {
            terrainSeasonOverride
        } else {
            SeasonHelper.getCurrentCalendarSeasonKey()
        }
    }

    // Pseudo-random hash function for value noise
    private fun hash(n: Int): Float {
        var x = n
        x = (x shr 13) xor x
        x = (x * (x * x * 15731 + 789221) + 1376312589)
        return (x and 0x7fffffff) / 2147483647.0f
    }

    // 1D value noise with smoothstep interpolation
    private fun noise(x: Float, seed: Int): Float {
        val i = x.toInt()
        val f = x - i
        val h1 = hash(i + seed)
        val h2 = hash(i + 1 + seed)
        // Smoothstep interpolation for smooth curves
        val t = f * f * (3 - 2 * f)
        return h1 + t * (h2 - h1)
    }

    // Darken a color for gradient shading
    private fun darkenColor(color: Int, factor: Float = 0.65f): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }
    
    // Blend color towards brown for autumn
    private fun autumnColor(color: Int, intensity: Float): Int {
        // Brown tones: reduce green, add red/yellow
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val brownR = 139
        val brownG = 119
        val brownB = 101
        return Color.rgb(
            (r + (brownR - r) * intensity).toInt().coerceIn(0, 255),
            (g + (brownG - g) * intensity * 0.6f).toInt().coerceIn(0, 255),
            (b + (brownB - b) * intensity).toInt().coerceIn(0, 255)
        )
    }

    private fun generateTerrain() {
        generateHills()
        val hillInfos = hills.map { TreeRenderer.HillInfo(it.controlPoints, it.depthFactor, it.heightOffset) }
        treeRenderer.generateTrees(hillInfos, extendedWidth, extendedOffset, height, randomSeed)
        treeRenderer.generateTreeDecorations(randomSeed)
        bushRenderer.generateBushes(hillInfos, extendedWidth, extendedOffset, height, randomSeed)
        bushRenderer.generateBushDecorations(randomSeed)
    }
    
    private fun generateHills() {
        // Build new list (thread-safe: atomic swap at end)
        val newHills = ArrayList<HillData>(hillCount)
        
        for (i in 0 until hillCount) {
            val hillRandom = DeterministicLayout.random(randomSeed + 1000, section = 200, index = i)
            // Base depth from 0 (background) to 1 (foreground)
            val baseDepth = i.toFloat() / hillCount.coerceAtLeast(1)
            
            // Apply hillDistance and hillDepthSpread:
            // - hillDistance (0-1): shifts all hills back (0=at camera, 1=near mountains)
            // - hillDepthSpread (0-1): controls spread along z-axis (0=flat, 1=deep)
            // 
            // At distance=0, spread=0.5: hills range from 0.5 to 1.0 (default behavior)
            // At distance=0.5, spread=0.3: hills range from 0.35 to 0.65
            // At distance=1, spread=0: all hills at depth 0 (very far, near mountains)
            val effectiveSpread = hillDepthSpread * (1f - hillDistance)
            val minDepth = 1f - hillDistance - effectiveSpread
            val maxDepth = 1f - hillDistance
            val depth = minDepth + baseDepth * (maxDepth - minDepth)
            
            // Parallax depth factor using normalized 0-1 range from RenderConstants
            // - Background hills (depth=0): lower factor = less movement
            // - Foreground hills (depth=1): higher factor = more movement  
            // Range: PARALLAX_HILLS_MIN (0.50) to PARALLAX_HILLS_MAX (0.80)
            val depthFactor = RenderConstants.PARALLAX_HILLS_MIN + 
                depth.coerceIn(0f, 1f) * (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)
            
            val heightOffset = hillStartY + baseDepth * (hillEndY - hillStartY)
            
            // Depth-based amplitude scaling: background hills have smaller amplitude
            // depth=0 (background): 40% amplitude, depth=1 (foreground): 100% amplitude
            val depthAmplitudeScale = 0.4f + depth.coerceIn(0f, 1f) * 0.6f
            
            // More points for smoother curves
            val numPoints = RenderConstants.HILL_CURVE_POINTS_MIN + hillRandom.nextInt(RenderConstants.HILL_CURVE_POINTS_RANGE)
            val points = ArrayList<Float>()
            
            // Use Fractal Brownian Motion (fBm) for natural terrain
            // This overlays multiple noise octaves: large hills + smaller bumps + fine details
            val noiseSeed = i * 1337 + randomSeed
            
            for (j in 0 until numPoints) {
                // x position along the hill, scaled by frequency
                val x = j.toFloat() / (numPoints - 1) * hillFrequency * 5.0f
                
                // Fractal Brownian Motion: 3-4 octaves
                // Octave 1: Large rolling hills (base shape)
                var noiseVal = noise(x, noiseSeed) * 1.0f
                // Octave 2: Smaller bumps (double frequency, half amplitude)
                noiseVal += noise(x * 2f, noiseSeed + 100) * 0.5f * hillRoughness
                // Octave 3: Fine details (4x frequency, quarter amplitude)
                noiseVal += noise(x * 4f, noiseSeed + 200) * 0.25f * hillRoughness
                // Octave 4: Micro details (8x frequency, 1/8 amplitude)
                noiseVal += noise(x * 8f, noiseSeed + 300) * 0.125f * hillRoughness
                
                // Normalize by sum of amplitudes: 1 + 0.5 + 0.25 + 0.125 = 1.875
                val maxAmp = 1.0f + 0.5f * hillRoughness + 0.25f * hillRoughness + 0.125f * hillRoughness
                noiseVal /= maxAmp
                
                // Map to hill height range - apply depth-based amplitude scaling
                // Background hills have smaller amplitude to appear further away
                val effectiveAmplitude = hillAmplitude * depthAmplitudeScale
                // noiseVal is 0.0-1.0, center it and apply amplitude
                // HILL_HEIGHT_RANGE is now 0.4, so at amplitude 2.0 we get 0.8 range!
                val normalizedValue = (RenderConstants.HILL_BASE_HEIGHT + 
                    (noiseVal - 0.5f) * RenderConstants.HILL_HEIGHT_RANGE * effectiveAmplitude)
                    .coerceIn(0.05f, 0.75f)  // Allow taller hills reaching higher on screen
                
                points.add(normalizedValue)
            }
            
            // Improved brightness calculation
            val brightness = 0.5f + depth * 0.5f
            var dayColor = Color.rgb(
                (Color.red(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.green(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.blue(RenderConstants.HILL_BASE_COLOR) * brightness).toInt()
            )
            
            // Autumn tint is now applied at draw time for smooth season transitions
            newHills.add(HillData(points, depthFactor, heightOffset, dayColor))
        }
        // Sort by depthFactor: most negative (background) draws first
        newHills.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        hills = newHills
        
        // Generate seasonal decorations after hills are created
        generateSeasonalDecorations(newHills)
    }
    
    private fun generateSeasonalDecorations(currentHills: List<HillData>) {
        val random = Random(randomSeed.toLong() + 5000)
        
        // Always pre-generate all seasonal decorations.
        // Visibility/opacity is controlled at draw time using season blend factors,
        // enabling smooth transitions between seasons.
        
        // Generate spring flowers (always, visibility controlled at draw time)
        if (springFlowersEnabled) {
            val flowerColors = intArrayOf(
                Color.rgb(255, 182, 193),  // Light pink
                Color.rgb(255, 255, 0),    // Yellow
                Color.rgb(255, 165, 0),    // Orange
                Color.rgb(148, 0, 211),    // Violet
                Color.rgb(255, 0, 255),    // Magenta
                Color.rgb(255, 255, 255)   // White
            )
            val newFlowers = mutableMapOf<Int, List<FlowerData>>()
            val flowersPerHill = (springFlowerDensity / 8).coerceIn(5, 200)
            
            for (index in currentHills.indices) {
                val hillFlowers = mutableListOf<FlowerData>()
                for (f in 0 until flowersPerHill) {
                    val xRatio = random.nextFloat()
                    val maxVerticalOffset = springFlowerSpread * 0.12f
                    val verticalOffset = random.nextFloat() * maxVerticalOffset * height
                    hillFlowers.add(FlowerData(
                        xRatio = xRatio,
                        yOffset = verticalOffset,
                        color = flowerColors[random.nextInt(flowerColors.size)],
                        size = 0.002f + random.nextFloat() * 0.003f
                    ))
                }
                newFlowers[index] = hillFlowers
            }
            flowersByHill = newFlowers
        } else {
            flowersByHill = emptyMap()
        }
        
        // Generate winter snow patches (always, visibility controlled at draw time)
        if (winterSnowEnabled) {
            val newSnow = mutableMapOf<Int, List<SnowPatchData>>()
            val patchesPerHill = (winterSnowCoverage / 20).coerceAtLeast(1)
            
            for ((index, _) in currentHills.withIndex()) {
                val hillSnow = mutableListOf<SnowPatchData>()
                for (p in 0 until patchesPerHill) {
                    val startX = random.nextFloat() * 0.9f
                    val width = 0.05f + random.nextFloat() * 0.1f * (winterSnowCoverage / 50f)
                    hillSnow.add(SnowPatchData(
                        startXRatio = startX,
                        endXRatio = (startX + width).coerceAtMost(1f),
                        alpha = 150 + random.nextInt(80)
                    ))
                }
                newSnow[index] = hillSnow
            }
            snowByHill = newSnow
        } else {
            snowByHill = emptyMap()
        }
        
        // Generate fallen leaves (always, visibility controlled at draw time)
        if (fallenLeavesEnabled) {
            val leafColors = intArrayOf(
                Color.rgb(200, 80, 20),    // Orange-red
                Color.rgb(180, 120, 30),   // Golden
                Color.rgb(160, 50, 20),    // Dark red
                Color.rgb(210, 150, 40),   // Yellow-gold
                Color.rgb(140, 70, 20),    // Brown
                Color.rgb(220, 100, 30)    // Bright orange
            )
            val newLeaves = mutableMapOf<Int, List<FallenLeafData>>()
            val leavesPerHill = (fallenLeavesDensity * currentHills.size / 100 + 3).coerceIn(3, 80)
            
            for (index in currentHills.indices) {
                val hillLeaves = mutableListOf<FallenLeafData>()
                for (l in 0 until leavesPerHill) {
                    hillLeaves.add(FallenLeafData(
                        xRatio = random.nextFloat(),
                        yOffset = random.nextFloat() * 0.08f * height,
                        color = leafColors[random.nextInt(leafColors.size)],
                        size = 0.003f + random.nextFloat() * 0.004f,
                        rotation = random.nextFloat() * 360f
                    ))
                }
                newLeaves[index] = hillLeaves
            }
            fallenLeavesByHill = newLeaves
        } else {
            fallenLeavesByHill = emptyMap()
        }
        
        // Generate mushrooms (always, visibility controlled at draw time)
        if (mushroomsEnabled) {
            val capColors = intArrayOf(
                Color.rgb(180, 50, 30),    // Red cap
                Color.rgb(200, 160, 80),   // Tan/beige cap
                Color.rgb(160, 120, 60),   // Brown cap
                Color.rgb(255, 200, 50),   // Yellow cap
                Color.rgb(140, 90, 40)     // Dark brown
            )
            val newMushrooms = mutableMapOf<Int, List<MushroomData>>()
            val mushroomsPerHill = (mushroomDensity * currentHills.size / 150 + 1).coerceIn(1, 30)
            
            for (index in currentHills.indices) {
                val hillMushrooms = mutableListOf<MushroomData>()
                for (m in 0 until mushroomsPerHill) {
                    hillMushrooms.add(MushroomData(
                        xRatio = random.nextFloat(),
                        yOffset = random.nextFloat() * 0.04f * height,
                        capColor = capColors[random.nextInt(capColors.size)],
                        size = 0.004f + random.nextFloat() * 0.005f
                    ))
                }
                newMushrooms[index] = hillMushrooms
            }
            mushroomsByHill = newMushrooms
        } else {
            mushroomsByHill = emptyMap()
        }
    }
    
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        // Need at least 2 points to interpolate between
        if (points.size < 2) return hill.heightOffset
        
        // xRatio is 0-1 across the extended width (matches hill control points)
        // Simply interpolate along the points array
        val indexFloat = xRatio * (points.size - 1)
        val index = indexFloat.toInt().coerceIn(0, points.size - 2)
        val fraction = indexFloat - index
        
        val p1 = points[index]
        val p2 = points[index + 1]
        val interpolated = p1 + fraction * (p2 - p1)
        
        return hill.heightOffset + 0.12f * (1f - interpolated)
    }
    
    /**
     * Get hill Y position at given X ratio and depth factor.
     * Used by ground events to follow terrain contour.
     * @param xRatio X position as ratio of extended width (0.0 to 1.0)
     * @param depthFactor Depth layer to sample (matches hill depthFactor)
     * @return Y position as ratio of screen height (0.0=top, 1.0=bottom)
     */
    fun getHillYAtPosition(xRatio: Float, depthFactor: Float): Float {
        return getHillInfoAtPosition(xRatio, depthFactor).first
    }
    
    /**
     * Get full hill info (Y position and actual depthFactor) at given X ratio.
     * Finds the closest hill to the requested depth factor.
     * @param xRatio X position as ratio of extended width (0.0 to 1.0)
     * @param requestedDepthFactor Approximate depth to find closest hill for
     * @return Pair of (Y ratio, actual hill depthFactor)
     */
    fun getHillInfoAtPosition(xRatio: Float, requestedDepthFactor: Float): Pair<Float, Float> {
        val currentHills = hills
        if (currentHills.isEmpty()) return Pair(0.85f, requestedDepthFactor)  // Default ground level
        
        // Find the hill closest to the requested depth factor
        var closestHill: HillData? = null
        var closestDistance = Float.MAX_VALUE
        
        for (hill in currentHills) {
            val distance = kotlin.math.abs(hill.depthFactor - requestedDepthFactor)
            if (distance < closestDistance) {
                closestDistance = distance
                closestHill = hill
            }
        }
        
        return closestHill?.let { Pair(getHillY(xRatio, it), it.depthFactor) } 
            ?: Pair(0.85f, requestedDepthFactor)
    }

    /**
     * Get the sorted list of hill depth factors (back to front).
     * Used by RiverLayer to draw water segments at each terrain layer.
     */
    fun getHillDepthFactors(): List<Float> {
        return hills.map { it.depthFactor }.sorted()
    }

    override fun resize(w: Float, h: Float) {
        val oldW = width
        val oldH = height
        val sizeChanged = oldW != w || oldH != h
        width = w
        height = h
        // Calculate extended width for parallax content
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)

        if (!sizeChanged) return

        // Any geometry change invalidates cached gradients that embed old coordinates.
        cachedHillGradients.clear()
        cachedHillGradientAmbientBucket = -1
        cachedHillGradientSnow = -1

        val firstGenerate = lastGeneratedWidth <= 0f || lastGeneratedHeight <= 0f
        val sizeChangedSignificantly =
            kotlin.math.abs(lastGeneratedWidth - w) > 1f ||
            kotlin.math.abs(lastGeneratedHeight - h) > 1f

        if (firstGenerate || sizeChangedSignificantly) {
            generateTerrain()
            lastGeneratedWidth = w
            lastGeneratedHeight = h
        }
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Animation state comes from SimulationState.timeSeconds
    }

    // Cached season to avoid repeated calls per-frame
    @Volatile private var cachedSeason: String = "summer"
    @Volatile private var cachedSeasonBlend: Float = 1f  // 1 = fully in cached season
    @Volatile private var cachedPrevSeason: String = "summer"
    private var dynamicSnowLevel: Float = 0f  // Snow from weather history (0-1)
    
    /**
     * Compute smooth weight (0-1) for a given season based on current transition state.
     * Returns 1.0 when fully in the target season, 0.0 when not in it at all,
     * and intermediate values during transitions into/out of the season.
     */
    private fun getSeasonWeight(season: String): Float {
        return when {
            cachedSeason == season && cachedPrevSeason == season -> 1f
            cachedSeason == season -> cachedSeasonBlend        // Transitioning INTO this season
            cachedPrevSeason == season -> 1f - cachedSeasonBlend  // Transitioning OUT of this season
            else -> 0f
        }
    }

    /**
     * Bucket ambient color so gradient cache only refreshes on visible color steps,
     * not every frame during tiny time-of-day transitions.
     */
    private fun ambientColorBucket(color: Int): Int {
        val r = Color.red(color) shr 3
        val g = Color.green(color) shr 3
        val b = Color.blue(color) shr 3
        return (r shl 10) or (g shl 5) or b
    }

    /** Ensure reusable draw-entry buffer can hold this hill's interleaved items. */
    private fun ensureDrawEntryCapacity(required: Int) {
        if (required <= drawEntryBuffer.size) return

        var newSize = drawEntryBuffer.size
        while (newSize < required) {
            newSize = if (newSize < 512) newSize * 2 else newSize + 256
        }

        val old = drawEntryBuffer
        drawEntryBuffer = Array(newSize) { idx ->
            if (idx < old.size) old[idx] else DrawEntry()
        }
    }

    /** In-place insertion sort on the reusable draw-entry buffer by ascending Y. */
    private fun sortDrawEntriesByY(entryCount: Int) {
        var i = 1
        while (i < entryCount) {
            val current = drawEntryBuffer[i]
            var j = i - 1
            while (j >= 0 && drawEntryBuffer[j].y > current.y) {
                drawEntryBuffer[j + 1] = drawEntryBuffer[j]
                j--
            }
            drawEntryBuffer[j + 1] = current
            i++
        }
    }

    private fun drawTreeInPlace(
        canvas: Canvas,
        tree: TreeRenderer.TreeData,
        state: SimulationState,
        parallaxCalculator: ParallaxCalculator,
        ambientColor: Int,
        treeAutumnWeight: Float
    ) {
        val treePx = parallaxCalculator.getOffsetXForDepth(tree.depthFactor)
        val treePy = parallaxCalculator.getOffsetYForDepth(tree.depthFactor)
        canvas.save()
        canvas.translate(treePx, treePy)

        // Needle trees stay green year-round; only leaf trees get autumn colours
        val effectiveTreeColor = if (tree.treeType == TreeRenderer.TreeType.LEAF) {
            treeRenderer.autumnTreeColor(tree.dayColor, treeAutumnWeight, tree.index)
        } else {
            tree.dayColor
        }

        val yBasedDepth = ((tree.yRatio - 0.6f) / 0.35f).coerceIn(0f, 1f)
        val timeMultiplier = if (!state.isNight && !state.isDawn && !state.isDusk) 0.15f else 1.0f
        val treeBlendAmount = RenderConstants.applyAmbientTint(effectiveTreeColor, ambientColor, yBasedDepth) * timeMultiplier
        val treeColor = RenderConstants.blendWithAmbient(effectiveTreeColor, ambientColor, treeBlendAmount)
        treeRenderer.drawTree(canvas, tree, state, tree.index, treeColor)

        canvas.restore()
    }

    private fun drawBushInPlace(
        canvas: Canvas,
        bush: BushRenderer.BushData,
        state: SimulationState,
        parallaxCalculator: ParallaxCalculator,
        ambientColor: Int
    ) {
        val bushPx = parallaxCalculator.getOffsetXForDepth(bush.depthFactor)
        val bushPy = parallaxCalculator.getOffsetYForDepth(bush.depthFactor)
        canvas.save()
        canvas.translate(bushPx, bushPy)
        bushRenderer.drawBush(canvas, bush, state, ambientColor)
        canvas.restore()
    }
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Take local snapshots for thread-safe iteration
        val currentHills = hills
        val currentTreesByDepth = treeRenderer.treesByDepth
        
        // Cache current season once per frame (avoid repeated lookups in loops)
        cachedSeason = state.currentSeason
        cachedPrevSeason = state.previousSeason
        cachedSeasonBlend = state.seasonTransitionProgress
        
        // Sync season state to renderers
        treeRenderer.cachedSeason = cachedSeason
        treeRenderer.cachedPrevSeason = cachedPrevSeason
        treeRenderer.cachedSeasonBlend = cachedSeasonBlend
        bushRenderer.cachedSeason = cachedSeason
        bushRenderer.cachedPrevSeason = cachedPrevSeason
        bushRenderer.cachedSeasonBlend = cachedSeasonBlend
        
        // Dynamic snow level: use weather history if available, else calendar-based
        dynamicSnowLevel = if (useWeatherSnow) {
            state.snowLevel
        } else {
            0f
        }
        treeRenderer.dynamicSnowLevel = dynamicSnowLevel
        
        // Get ambient color for current time of day (global tint system)
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        
        // Invalidate cached hill gradients only when bucketed ambient or snow changes.
        // This avoids rebuilding gradients on tiny per-frame color deltas.
        val ambientBucket = ambientColorBucket(ambientColor)
        val snowBucket = (dynamicSnowLevel * 20).toInt()  // ~5% granularity
        if (ambientBucket != cachedHillGradientAmbientBucket || snowBucket != cachedHillGradientSnow) {
            cachedHillGradientAmbientBucket = ambientBucket
            cachedHillGradientSnow = snowBucket
            cachedHillGradients.clear()
        }
        
        // Pre-compute autumn foliage weight once per frame (shared by all trees)
        val treeAutumnWeight = getSeasonWeight("autumn")

        // Draw Hills AND their Trees together, sorted by depth
        // This ensures trees on background hills are drawn before foreground hills
        // Within each hill layer: sort trees and animals by Y position (top to bottom = far to near)
        // Elements lower on screen (higher Y) are drawn later (appear in front)
        var index = 0
        while (index < currentHills.size) {
            val hill = currentHills[index]
            // Draw this hill layer
            val px = parallaxCalculator.getOffsetXForDepth(hill.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(hill.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            // Apply autumn brown tint at draw time for smooth season transitions
            var effectiveDayColor = hill.dayColor
            if (autumnGrassEnabled) {
                val autumnWeight = getSeasonWeight("autumn")
                if (autumnWeight > 0f) {
                    val autumnIntensity = autumnGrassIntensity / 100f * 0.5f * autumnWeight
                    effectiveDayColor = autumnColor(effectiveDayColor, autumnIntensity)
                }
            }
            
            // Blend with ambient color based on depth (distant hills blend more)
            val blendAmount = RenderConstants.applyAmbientTint(effectiveDayColor, ambientColor, hill.depthFactor)
            val hillColor = RenderConstants.blendWithAmbient(effectiveDayColor, ambientColor, blendAmount)
            drawHill(canvas, index, hill, hillColor)
            
            // Draw seasonal decorations on top of hill
            drawSeasonalDecorations(canvas, index, hill, ambientColor)
            
            canvas.restore()
            
            // Draw river/water at this depth layer (after hill fill, before trees)
            // This ensures river water sits on top of hills but behind trees
            riverDrawer?.drawAtDepth(canvas, hill.depthFactor, index, currentHills.size)
            
            // Draw lake at its target depth layer (same z-ordering as river)
            lakeDrawer?.drawAtDepth(canvas, hill.depthFactor, index, currentHills.size)
            
            // Get trees and animals for this depth range
            val nextDepth = if (index + 1 < currentHills.size) {
                currentHills[index + 1].depthFactor
            } else {
                Float.MAX_VALUE
            }
            val hillTrees = currentTreesByDepth[hill.depthFactor] ?: emptyList()
            val hillBushes = bushRenderer.bushesByDepth[hill.depthFactor] ?: emptyList()
            
            // Get animals for this depth range (may be empty)
            val animals = groundEventDrawer?.getEventsInRange(hill.depthFactor, nextDepth) ?: emptyList()

            // Fast path: trees only, already Y-sorted during generation.
            if (animals.isEmpty() && hillBushes.isEmpty()) {
                var treeIdx = 0
                while (treeIdx < hillTrees.size) {
                    drawTreeInPlace(
                        canvas,
                        hillTrees[treeIdx],
                        state,
                        parallaxCalculator,
                        ambientColor,
                        treeAutumnWeight
                    )
                    treeIdx++
                }
            } else {
                // Merge trees, bushes, and animals into reusable draw-entry buffer.
                val totalItems = hillTrees.size + hillBushes.size + animals.size
                ensureDrawEntryCapacity(totalItems)

                var entryCount = 0

                var treeIdx = 0
                while (treeIdx < hillTrees.size) {
                    val entry = drawEntryBuffer[entryCount]
                    entry.y = hillTrees[treeIdx].yRatio
                    entry.kind = DRAW_KIND_TREE
                    entry.index = treeIdx
                    entryCount++
                    treeIdx++
                }

                var bushIdx = 0
                while (bushIdx < hillBushes.size) {
                    val entry = drawEntryBuffer[entryCount]
                    entry.y = hillBushes[bushIdx].yRatio
                    entry.kind = DRAW_KIND_BUSH
                    entry.index = bushIdx
                    entryCount++
                    bushIdx++
                }

                var animalIdx = 0
                while (animalIdx < animals.size) {
                    val entry = drawEntryBuffer[entryCount]
                    entry.y = animals[animalIdx].yRatio
                    entry.kind = DRAW_KIND_ANIMAL
                    entry.index = animalIdx
                    entryCount++
                    animalIdx++
                }

                // Sort by Y (lower Y = further away, drawn first; higher Y = closer, drawn last)
                sortDrawEntriesByY(entryCount)

                // Draw in sorted order without allocating lambdas/temporary lists.
                var drawIdx = 0
                while (drawIdx < entryCount) {
                    val entry = drawEntryBuffer[drawIdx]
                    when (entry.kind) {
                        DRAW_KIND_TREE -> drawTreeInPlace(
                            canvas,
                            hillTrees[entry.index],
                            state,
                            parallaxCalculator,
                            ambientColor,
                            treeAutumnWeight
                        )

                        DRAW_KIND_BUSH -> drawBushInPlace(
                            canvas,
                            hillBushes[entry.index],
                            state,
                            parallaxCalculator,
                            ambientColor
                        )

                        else -> animals[entry.index].draw()
                    }
                    drawIdx++
                }
            }

            index++
        }
    }
    
    private fun drawHill(canvas: Canvas, hillIndex: Int, hill: HillData, hillColor: Int) {
        hillPath.reset()
        
        // Use extended width for hill drawing
        // Hills span the full extended canvas width, centered on screen
        val parallaxMargin = height * 0.3f  // Extra for parallax movement at bottom
        
        // Map hill points across the extended width, centered on screen
        val startX = -extendedOffset - width * 0.1f  // Start before visible area
        val endX = width + extendedOffset + width * 0.1f  // End after visible area
        val stepX = (endX - startX) / (hill.controlPoints.size - 1)
        val bottomY = height + parallaxMargin  // Extend below screen
        
        // Find the highest point in the hill for gradient
        var minY = bottomY
        hillPath.moveTo(startX, bottomY)
        
        for (i in hill.controlPoints.indices) {
            val x = startX + i * stepX
            val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[i])
            val y = yRatio * height
            if (y < minY) minY = y
            hillPath.lineTo(x, y)
        }
        
        hillPath.lineTo(endX, bottomY)
        hillPath.close()
        
        // Check if we should apply snow cap effect
        // When useWeatherSnow is true, use ONLY weather snow level to avoid
        // falling back to calendar-based snow (which causes visible jumps at thresholds)
        val effectiveSnowCoverage = if (useWeatherSnow) {
            // Weather-driven snow: smooth linear scale from snow level
            if (dynamicSnowLevel > 0.005f) {
                (dynamicSnowLevel * 100).toInt().coerceIn(1, 100)
            } else {
                0
            }
        } else if (winterSnowEnabled && cachedSeason == "winter") {
            // Calendar-based winter snow with transition blending
            val seasonWeight = if (cachedPrevSeason != "winter" && cachedSeason == "winter") {
                cachedSeasonBlend  // Blending INTO winter
            } else if (cachedSeason != "winter" && cachedPrevSeason == "winter") {
                1f - cachedSeasonBlend  // Blending OUT of winter
            } else if (cachedSeason == "winter") {
                1f
            } else {
                0f
            }
            (winterSnowCoverage * seasonWeight).toInt()
        } else if (!useWeatherSnow && cachedPrevSeason == "winter" && cachedSeason != "winter" && cachedSeasonBlend < 1f) {
            // Transitioning OUT of winter - fade snow (only when not using weather snow)
            (winterSnowCoverage * (1f - cachedSeasonBlend)).toInt()
        } else {
            0
        }
        val applySnow = effectiveSnowCoverage > 0
        
        // Apply linear gradient from top to bottom (cached to avoid per-frame allocation)
        val gradientKey = hillIndex * 2 + (if (applySnow) 1 else 0)
        val gradient = cachedHillGradients.getOrPut(gradientKey) {
            if (applySnow) {
                // Snow coverage: use effectiveSnowCoverage for dynamic depth
                // Allow very thin snow at low levels (0.01) for gradual appearance
                val snowDepth = (effectiveSnowCoverage / 100f).coerceIn(0.01f, 0.6f)
                val snowColor = Color.rgb(255, 255, 255)
                val snowTransition = Color.rgb(
                    (Color.red(hillColor) + 255) / 2,
                    (Color.green(hillColor) + 255) / 2, 
                    (Color.blue(hillColor) + 255) / 2
                )
                
                LinearGradient(
                    0f, minY,
                    0f, bottomY,
                    intArrayOf(
                        snowColor,
                        snowTransition,
                        hillColor,
                        darkenColor(hillColor, 0.7f)
                    ),
                    floatArrayOf(0f, snowDepth * 0.4f, snowDepth, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f, minY,
                    0f, bottomY,
                    hillColor,
                    darkenColor(hillColor, 0.7f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        hillPaint.shader = gradient
        
        canvas.drawPath(hillPath, hillPaint)
        
        // Clear shader after drawing
        hillPaint.shader = null
    }
    
    private fun drawSeasonalDecorations(canvas: Canvas, hillIndex: Int, hill: HillData, ambientColor: Int) {
        val startX = -extendedOffset - width * 0.1f
        val endX = width + extendedOffset + width * 0.1f
        
        // Note: Snow is now integrated into the hill gradient (drawHill),
        // so we no longer draw floating snow patches here.
        
        // Compute season weights for gradual visibility control
        val springWeight = getSeasonWeight("spring")
        val summerWeight = getSeasonWeight("summer")
        val autumnWeight = getSeasonWeight("autumn")
        val winterWeight = getSeasonWeight("winter")
        
        // Flowers: visible in spring + summer, fade out in autumn
        val flowerAlpha = (springWeight + summerWeight).coerceIn(0f, 1f)
        if (flowerAlpha > 0.01f) {
            flowersByHill[hillIndex]?.let { flowers ->
            flowerPaint.alpha = (255 * flowerAlpha).toInt()
            for (flower in flowers) {
                val x = startX + flower.xRatio * (endX - startX)
                
                // Get hill Y at flower position
                val pointIndex = (flower.xRatio * (hill.controlPoints.size - 1)).toInt()
                    .coerceIn(0, hill.controlPoints.size - 1)
                val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[pointIndex])
                val y = yRatio * height + flower.yOffset
                
                // Blend flower color with ambient for time-of-day consistency
                val blendedColor = RenderConstants.blendWithAmbient(flower.color, ambientColor, 0.15f)
                flowerPaint.color = blendedColor
                flowerPaint.alpha = (Color.alpha(blendedColor) * flowerAlpha).toInt()
                // Scale flower size by height for consistent proportions
                val flowerRadius = flower.size * height * (0.5f + flowerAlpha * 0.5f)  // Grow as they appear
                canvas.drawCircle(x, y, flowerRadius, flowerPaint)
            }
            flowerPaint.alpha = 255
        }
        }
        
        // Fallen leaves: visible in autumn + winter
        val leafAlpha = (autumnWeight + winterWeight).coerceIn(0f, 1f)
        if (leafAlpha > 0.01f) {
        fallenLeavesByHill[hillIndex]?.let { leaves ->
            leafPaint.alpha = (255 * leafAlpha).toInt()
            for (leaf in leaves) {
                val x = startX + leaf.xRatio * (endX - startX)
                
                val pointIndex = (leaf.xRatio * (hill.controlPoints.size - 1)).toInt()
                    .coerceIn(0, hill.controlPoints.size - 1)
                val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[pointIndex])
                val y = yRatio * height + leaf.yOffset
                
                val blendedColor = RenderConstants.blendWithAmbient(leaf.color, ambientColor, 0.15f)
                leafPaint.color = blendedColor
                leafPaint.alpha = (Color.alpha(blendedColor) * leafAlpha).toInt()
                val leafSize = leaf.size * height
                
                canvas.save()
                canvas.rotate(leaf.rotation, x, y)
                // Oval leaf shape
                canvas.drawOval(x - leafSize, y - leafSize * 0.5f, x + leafSize, y + leafSize * 0.5f, leafPaint)
                canvas.restore()
            }
            leafPaint.alpha = 255
        }
        }
        
        // Mushrooms: visible in summer + autumn
        val mushroomAlpha = (summerWeight + autumnWeight).coerceIn(0f, 1f)
        if (mushroomAlpha > 0.01f) {
        mushroomsByHill[hillIndex]?.let { mushrooms ->
            for (mushroom in mushrooms) {
                val x = startX + mushroom.xRatio * (endX - startX)
                
                val pointIndex = (mushroom.xRatio * (hill.controlPoints.size - 1)).toInt()
                    .coerceIn(0, hill.controlPoints.size - 1)
                val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[pointIndex])
                val y = yRatio * height + mushroom.yOffset
                
                val size = mushroom.size * height * (0.5f + mushroomAlpha * 0.5f)  // Grow as they appear
                
                // Stem (light beige)
                val stemColor = RenderConstants.blendWithAmbient(Color.rgb(230, 210, 180), ambientColor, 0.2f)
                mushroomPaint.color = stemColor
                mushroomPaint.alpha = (Color.alpha(stemColor) * mushroomAlpha).toInt()
                canvas.drawRect(x - size * 0.2f, y - size * 0.8f, x + size * 0.2f, y, mushroomPaint)
                
                // Cap (colored dome)
                val capColor = RenderConstants.blendWithAmbient(mushroom.capColor, ambientColor, 0.15f)
                mushroomCapPaint.color = capColor
                mushroomCapPaint.alpha = (Color.alpha(capColor) * mushroomAlpha).toInt()
                canvas.drawOval(x - size * 0.6f, y - size * 1.3f, x + size * 0.6f, y - size * 0.5f, mushroomCapPaint)
            }
            mushroomPaint.alpha = 255
            mushroomCapPaint.alpha = 255
        }
        }
        
        // Frost: visible in winter (already uses smooth blending via getSeasonWeight)
        if (frostEnabled) {
            val frostWeight = if (useWeatherSnow && dynamicSnowLevel > 0.2f) {
                dynamicSnowLevel.coerceIn(0f, 1f)
            } else {
                winterWeight
            }
            if (frostWeight > 0.05f) {
                val frostAlpha = if (useWeatherSnow && dynamicSnowLevel > 0.2f) {
                    (dynamicSnowLevel * 40).toInt().coerceIn(0, 35)
                } else {
                    (25 * frostWeight).toInt()
                }
                if (frostAlpha > 3) {
                    frostPaint.color = Color.argb(frostAlpha, 220, 230, 255)
                    // Draw frost as thin lines along hill contour
                    val points = hill.controlPoints
                    for (i in 0 until points.size - 1) {
                        val x1 = startX + (i.toFloat() / (points.size - 1)) * (endX - startX)
                        val x2 = startX + ((i + 1).toFloat() / (points.size - 1)) * (endX - startX)
                        val y1 = (hill.heightOffset + 0.12f * (1f - points[i])) * height
                        val y2 = (hill.heightOffset + 0.12f * (1f - points[i + 1])) * height
                        canvas.drawLine(x1, y1, x2, y2, frostPaint)
                    }
                }
            }
        }
    }

    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        
        // Random seed for consistent placement
        randomSeed = settings.getInt("pref_random_seed", 42)
        
        hillCount = settings.getInt("pref_hill_count", 5)  // 5 hill layers default, range 0-20
        
        hillStartY = settings.getInt("pref_hill_lower_start", 60) / 100f  // 60% instead of 50%
        // Hills extend below screen (1.0 = bottom edge, >1.0 = below screen)
        // This allows parallax movement to reveal more terrain
        hillEndY = settings.getInt("pref_hill_upper_end", 95) / 100f      // 95% - closer to bottom for parallax room
        
        // Hill roughness: controls sine wave detail (0-200 -> 0.0-2.0)
        val roughVal = settings.getInt("pref_hill_roughness", 100)
        hillRoughness = roughVal / 100f
        
        // Hill amplitude: controls hill vertical height (0-1000)
        // 0 = flat, 100 = normal (1.0), 1000 = extreme (10.0)
        val ampVal = settings.getInt("pref_hill_amplitude", PreferenceDefaults.HILL_AMPLITUDE)
        hillAmplitude = ampVal / 100f  // 0 -> 0.0, 100 -> 1.0, 1000 -> 10.0
        
        // Hill frequency: controls how many hills visible per layer (0-1000 -> 0.0-10.0)
        val freqVal = settings.getInt("pref_hill_frequency", PreferenceDefaults.HILL_FREQUENCY)
        hillFrequency = freqVal / 100f
        
        // Hill distance: pushes hills away from camera (0=at camera, 100=near mountains)
        val distVal = settings.getInt("pref_hill_distance", 0)
        hillDistance = distVal / 100f
        
        // Hill depth spread: controls z-axis spread (0=flat layer, 100=deep range)
        val spreadVal = settings.getInt("pref_hill_depth_spread", 50)
        hillDepthSpread = spreadVal / 100f
        
        // Seasonal terrain settings
        // Only use terrain season override when debug/simulation mode is enabled
        val debugEnabled = settings.getBoolean("pref_debug_enabled", false)
        terrainSeasonOverride = if (debugEnabled) {
            settings.getString("pref_terrain_season", "auto")
        } else {
            "auto"  // Always use real season when not in debug mode
        }
        springFlowersEnabled = settings.getBoolean("pref_spring_flowers", true)
        springFlowerDensity = settings.getInt("pref_spring_flower_density", 500)
        springFlowerSpread = settings.getInt("pref_spring_flower_spread", 50) / 100f
        autumnGrassEnabled = settings.getBoolean("pref_autumn_grass", true)
        autumnGrassIntensity = settings.getInt("pref_autumn_grass_intensity", 50)
        winterSnowEnabled = settings.getBoolean("pref_winter_snow_hills", true)
        winterSnowCoverage = settings.getInt("pref_winter_snow_coverage", 50)
        useWeatherSnow = settings.getBoolean("pref_weather_snow_enabled", true)
        seasonTransitionDays = settings.getInt("pref_season_transition_days", PreferenceDefaults.SEASON_TRANSITION_DAYS)
        
        // Seasonal decoration settings
        fallenLeavesEnabled = settings.getBoolean("pref_fallen_leaves_enabled", PreferenceDefaults.FALLEN_LEAVES_ENABLED)
        fallenLeavesDensity = settings.getInt("pref_fallen_leaves_density", PreferenceDefaults.FALLEN_LEAVES_DENSITY)
        mushroomsEnabled = settings.getBoolean("pref_mushrooms_enabled", PreferenceDefaults.MUSHROOMS_ENABLED)
        mushroomDensity = settings.getInt("pref_mushroom_density", PreferenceDefaults.MUSHROOM_DENSITY)
        frostEnabled = settings.getBoolean("pref_frost_enabled", PreferenceDefaults.FROST_ENABLED)
        
        // Delegate tree and bush settings to their renderers
        treeRenderer.reloadSettings(settings)
        bushRenderer.reloadSettings(settings)
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, freq=$hillFrequency, amp=$hillAmplitude, dist=$hillDistance, spread=$hillDepthSpread")
        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
