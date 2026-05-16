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
import info.olli.weatherlwp.util.ColorMath
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
    private var lastGeneratedWidth = 0f
    private var lastGeneratedHeight = 0f
    /** Cached world-space half-width used for generation; avoids regeneration when viewport still fits. */
    private var generatedWorldHalf = 0f
    
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
        val noiseSeed: Int,
        val depthAmplitudeScale: Float,
        val depthFactor: Float,
        val heightOffset: Float,
        val dayColor: Int
    )
    
    // Thread-safe terrain data using volatile references + immutable lists
    @Volatile private var hills: List<HillData> = emptyList()

    // Cached transform: rebuilt on resize, reused every frame
    private var cachedTransform = WorldViewportTransform(1f, 1f)
    
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
    
    // Flower cluster settings
    private var flowerClusterEnabled = PreferenceDefaults.FLOWER_CLUSTER_ENABLED
    private var flowerClusterDensity = PreferenceDefaults.FLOWER_CLUSTER_DENSITY
    private var flowerClusterRadius = PreferenceDefaults.FLOWER_CLUSTER_RADIUS
    private var flowerClusterMinCount = PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT
    private var flowerClusterMaxCount = PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT
    private var flowerClusterFalloff = PreferenceDefaults.FLOWER_CLUSTER_FALLOFF / 100f
    private var flowerClusterNoise = PreferenceDefaults.FLOWER_CLUSTER_NOISE / 100f
    
    // Mushroom cluster settings
    private var mushroomClusterEnabled = PreferenceDefaults.MUSHROOM_CLUSTER_ENABLED
    private var mushroomClusterDensity = PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY
    private var mushroomClusterRadius = PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS
    private var mushroomClusterMinCount = PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT
    private var mushroomClusterMaxCount = PreferenceDefaults.MUSHROOM_CLUSTER_MAX_COUNT
    private var mushroomClusterFalloff = PreferenceDefaults.MUSHROOM_CLUSTER_FALLOFF / 100f
    private var mushroomClusterNoise = PreferenceDefaults.MUSHROOM_CLUSTER_NOISE / 100f
    
    // Foliage accumulation settings
    private var foliageUnderTreeBias = PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS / 100f
    private var foliageMatchingTreeBias = PreferenceDefaults.FOLIAGE_MATCHING_TREE_BIAS / 100f
    private var foliageWindScatterStrength = PreferenceDefaults.FOLIAGE_WIND_SCATTER_STRENGTH / 100f
    private var foliageWindDirection = PreferenceDefaults.FOLIAGE_WIND_DIRECTION.toFloat()
    private var foliageRandomScatter = PreferenceDefaults.FOLIAGE_RANDOM_SCATTER / 100f
    
    // Pre-generated flower positions (per hill)
    private data class FlowerData(
        val worldX: Float,
        val yOffset: Float,
        val color: Int,
        val size: Float
    )
    @Volatile private var flowersByHill: Map<Int, List<FlowerData>> = emptyMap()
    
    // Pre-generated fallen leaf positions (per hill)
    private data class FallenLeafData(
        val worldX: Float,
        val yOffset: Float,
        val color: Int,
        val size: Float,
        val rotation: Float
    )
    @Volatile private var fallenLeavesByHill: Map<Int, List<FallenLeafData>> = emptyMap()
    
    // Pre-generated mushroom positions (per hill)
    private data class MushroomData(
        val worldX: Float,
        val yOffset: Float,
        val capColor: Int,
        val size: Float
    )
    @Volatile private var mushroomsByHill: Map<Int, List<MushroomData>> = emptyMap()
    
    // Pre-generated snow patch positions (per hill)
    private data class SnowPatchData(
        val startWorldX: Float,
        val endWorldX: Float,
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
    


    // Pseudo-random hash function for value noise
    private fun darkenColor(color: Int, factor: Float = 0.65f): Int = ColorMath.darkenColor(color, factor)
    
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
        regenerateVegetation()
    }

    /**
     * Regenerate trees, bushes, and seasonal decorations using existing hills.
     * Called when vegetation/decoration settings change without hill shape changes.
     */
    private fun regenerateVegetation() {
        val currentHills = hills
        if (currentHills.isEmpty() || width <= 0f) return
        val hillInfos = currentHills.map {
            TreeRenderer.HillInfo(
                it.noiseSeed, it.depthAmplitudeScale, it.depthFactor, it.heightOffset,
                hillFrequency, hillRoughness, hillAmplitude
            )
        }
        treeRenderer.generateTrees(hillInfos, width, height, randomSeed)
        treeRenderer.generateTreeDecorations(randomSeed)
        bushRenderer.generateBushes(hillInfos, width, height, randomSeed)
        bushRenderer.generateBushDecorations(randomSeed)
        generateSeasonalDecorations(currentHills)
    }
    
    private fun generateHills() {
        // Build new list (thread-safe: atomic swap at end)
        val newHills = ArrayList<HillData>(hillCount)
        
        for (i in 0 until hillCount) {
            // Base depth from 0 (background) to 1 (foreground)
            val baseDepth = i.toFloat() / hillCount.coerceAtLeast(1)
            
            // Apply hillDistance and hillDepthSpread:
            // - hillDistance (0-1): shifts all hills back (0=at camera, 1=near mountains)
            // - hillDepthSpread (0-1): controls spread along z-axis (0=flat, 1=deep)
            val effectiveSpread = hillDepthSpread * (1f - hillDistance)
            val minDepth = 1f - hillDistance - effectiveSpread
            val maxDepth = 1f - hillDistance
            val depth = minDepth + baseDepth * (maxDepth - minDepth)
            
            // Parallax depth factor using normalized 0-1 range from RenderConstants
            val depthFactor = RenderConstants.PARALLAX_HILLS_MIN + 
                depth.coerceIn(0f, 1f) * (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)
            
            val heightOffset = hillStartY + baseDepth * (hillEndY - hillStartY)
            
            // Depth-based amplitude scaling: background hills have smaller amplitude
            val depthAmplitudeScale = 0.4f + depth.coerceIn(0f, 1f) * 0.6f
            
            // Noise seed for this hill (deterministic from hill index + randomSeed)
            val noiseSeed = i * 1337 + randomSeed
            
            // Brightness based on depth
            val brightness = 0.5f + depth * 0.5f
            val dayColor = Color.rgb(
                (Color.red(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.green(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.blue(RenderConstants.HILL_BASE_COLOR) * brightness).toInt()
            )
            
            newHills.add(HillData(noiseSeed, depthAmplitudeScale, depthFactor, heightOffset, dayColor))
        }
        // Sort by depthFactor: most negative (background) draws first
        newHills.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        hills = newHills
        
        // Generate seasonal decorations after hills are created
        generateSeasonalDecorations(newHills)
    }
    
    private fun generateSeasonalDecorations(currentHills: List<HillData>) {
        // Always pre-generate all seasonal decorations.
        // Visibility/opacity is controlled at draw time using season blend factors,
        // enabling smooth transitions between seasons.
        
        // Cell-based generation range: use the cached coverage (includes preload margin)
        val worldHalf = generatedWorldHalf.takeIf { it > 0f }
            ?: RenderConstants.getVisibleWorldHalf(width, height)
        
        // Cell sizes for each decoration type (world units)
        val flowerCellSize = 5f
        val leafCellSize = 8f
        val mushroomCellSize = 20f
        val snowCellSize = 40f
        
        // Generate spring flowers using cell-based placement (with optional clustering)
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
            val densityFraction = (springFlowerDensity / 500f).coerceIn(0.05f, 1f)
            val indexRange = WorldViewportTransform.indexRangeForWorldRange(-worldHalf, worldHalf, flowerCellSize)
            
            for (hillIndex in currentHills.indices) {
                val hillFlowers = mutableListOf<FlowerData>()
                if (flowerClusterEnabled) {
                    // Cluster mode: pick cluster centers, scatter flowers with
                    // uniform-disk sampling, smoothstep falloff, and noise displacement.
                    val clusterDensity = (flowerClusterDensity / 100f).coerceIn(0.05f, 1f)
                    val clusterRadiusWorld = flowerClusterRadius / 100f * flowerCellSize * 4f
                    for (cellIndex in indexRange) {
                        val cellSeed = randomSeed + 6000 + hillIndex
                        val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                        if (h > clusterDensity * densityFraction) continue
                        
                        val centerWorldX = cellIndex * flowerCellSize +
                            (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * flowerCellSize * 0.9f
                        val clusterRandom = DeterministicLayout.random(cellSeed, section = 610, index = cellIndex)
                        val count = flowerClusterMinCount + clusterRandom.nextInt(
                            (flowerClusterMaxCount - flowerClusterMinCount + 1).coerceAtLeast(1))
                        for (f in 0 until count) {
                            // Uniform-disk sampling: sqrt(u) * r gives uniform area distribution
                            val angle = clusterRandom.nextFloat() * 2f * kotlin.math.PI.toFloat()
                            val rawDist = kotlin.math.sqrt(clusterRandom.nextFloat().toDouble()).toFloat() * clusterRadiusWorld
                            
                            // Smoothstep falloff: reduce item size near edge based on falloff curve
                            val normalizedDist = (rawDist / clusterRadiusWorld.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                            val falloffWeight = 1f - normalizedDist * normalizedDist * flowerClusterFalloff
                            
                            // Noise-based displacement: break circular symmetry
                            val noiseOffset = if (flowerClusterNoise > 0.01f) {
                                val nx = DeterministicLayout.hashToFloat(cellSeed + f, cellIndex, 10) - 0.5f
                                val ny = DeterministicLayout.hashToFloat(cellSeed + f, cellIndex, 11) - 0.5f
                                Pair(nx * flowerClusterNoise * clusterRadiusWorld, ny * flowerClusterNoise * clusterRadiusWorld * 0.3f)
                            } else {
                                Pair(0f, 0f)
                            }
                            
                            val worldX = centerWorldX +
                                kotlin.math.cos(angle.toDouble()).toFloat() * rawDist + noiseOffset.first
                            val maxVerticalOffset = springFlowerSpread * 0.12f
                            val verticalOffset = clusterRandom.nextFloat() * maxVerticalOffset + noiseOffset.second * 0.02f
                            val colorIdx = clusterRandom.nextInt(flowerColors.size)
                            val baseSize = 0.002f + clusterRandom.nextFloat() * 0.003f
                            hillFlowers.add(FlowerData(
                                worldX = worldX,
                                yOffset = verticalOffset,
                                color = flowerColors[colorIdx],
                                size = baseSize * falloffWeight.coerceAtLeast(0.3f)
                            ))
                        }
                    }
                } else {
                    // Uniform placement (original behavior)
                    for (cellIndex in indexRange) {
                        val cellSeed = randomSeed + 6000 + hillIndex
                        val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                        if (h > densityFraction) continue
                        
                        val worldX = cellIndex * flowerCellSize +
                            (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * flowerCellSize * 0.9f
                        val maxVerticalOffset = springFlowerSpread * 0.12f
                        val verticalOffset = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 2) * maxVerticalOffset
                        val colorIdx = (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 3) * flowerColors.size).toInt()
                            .coerceIn(0, flowerColors.size - 1)
                        hillFlowers.add(FlowerData(
                            worldX = worldX,
                            yOffset = verticalOffset,
                            color = flowerColors[colorIdx],
                            size = 0.002f + DeterministicLayout.hashToFloat(cellSeed, cellIndex, 4) * 0.003f
                        ))
                    }
                }
                newFlowers[hillIndex] = hillFlowers
            }
            flowersByHill = newFlowers
        } else {
            flowersByHill = emptyMap()
        }
        
        // Generate winter snow patches using cell-based placement
        if (winterSnowEnabled) {
            val newSnow = mutableMapOf<Int, List<SnowPatchData>>()
            val densityFraction = (winterSnowCoverage / 100f).coerceIn(0.1f, 1f)
            val snowIndexRange = WorldViewportTransform.indexRangeForWorldRange(-worldHalf, worldHalf, snowCellSize)
            
            for ((hillIndex, _) in currentHills.withIndex()) {
                val hillSnow = mutableListOf<SnowPatchData>()
                for (cellIndex in snowIndexRange) {
                    val cellSeed = randomSeed + 7000 + hillIndex
                    val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                    if (h > densityFraction) continue
                    
                    val startWX = cellIndex * snowCellSize +
                        (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * snowCellSize * 0.8f
                    val patchWidth = (0.3f + DeterministicLayout.hashToFloat(cellSeed, cellIndex, 2) * 0.7f) * snowCellSize
                    hillSnow.add(SnowPatchData(
                        startWorldX = startWX,
                        endWorldX = startWX + patchWidth,
                        alpha = 150 + (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 3) * 80).toInt()
                    ))
                }
                newSnow[hillIndex] = hillSnow
            }
            snowByHill = newSnow
        } else {
            snowByHill = emptyMap()
        }
        
        // Generate fallen leaves with optional tree-biased accumulation
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
            val densityFraction = (fallenLeavesDensity / 100f).coerceIn(0.05f, 1f)
            val leafIndexRange = WorldViewportTransform.indexRangeForWorldRange(-worldHalf, worldHalf, leafCellSize)
            
            // Pre-compute tree positions per hill for foliage biasing
            val treesByHillIndex = if (foliageUnderTreeBias > 0.01f) {
                val allTrees = treeRenderer.treesByDepth
                val hillInfos = currentHills.map { it.depthFactor }
                hillInfos.mapIndexed { idx, depth ->
                    idx to (allTrees[depth] ?: emptyList())
                }.toMap()
            } else {
                emptyMap()
            }
            
            // Wind direction for scatter (radians)
            val windRad = Math.toRadians(foliageWindDirection.toDouble()).toFloat()
            val windDirX = kotlin.math.cos(windRad)
            val windDirY = kotlin.math.sin(windRad) * 0.3f  // Flatten vertical component
            
            for (hillIndex in currentHills.indices) {
                val hillLeaves = mutableListOf<FallenLeafData>()
                val hillTrees = treesByHillIndex[hillIndex] ?: emptyList()
                
                // Tree-biased leaves: every tree with foliage produces leaves.
                // Count scales with bias and tree type. No random skip of entire trees.
                if (hillTrees.isNotEmpty() && foliageUnderTreeBias > 0.01f) {
                    for (tree in hillTrees) {
                        val treeSeed = randomSeed + 8500 + tree.index
                        val treeRandom = DeterministicLayout.random(treeSeed, section = 820, index = hillIndex)
                        
                        // Deciduous trees produce more leaves; conifers shed very few
                        val typeBias = if (tree.treeType == TreeRenderer.TreeType.LEAF)
                            1f + foliageMatchingTreeBias
                        else
                            0.3f
                        
                        // Leaf count proportional to bias, density, and tree scale (canopy proxy)
                        val canopyFactor = tree.scale.coerceIn(0.5f, 2f)
                        val numLeaves = (foliageUnderTreeBias * typeBias * densityFraction * canopyFactor * 6f)
                            .toInt().coerceIn(1, 10)
                        
                        // Scatter radius scales with tree canopy (larger trees → wider scatter)
                        val scatterRadius = foliageRandomScatter * leafCellSize * canopyFactor * 1.5f
                        
                        for (l in 0 until numLeaves) {
                            // Uniform-disk sampling around tree base
                            val scatterAngle = treeRandom.nextFloat() * 2f * kotlin.math.PI.toFloat()
                            val rawDist = kotlin.math.sqrt(treeRandom.nextFloat().toDouble()).toFloat() * scatterRadius
                            
                            // Distance-based falloff: leaves closer to trunk are larger
                            val distFraction = (rawDist / scatterRadius.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                            val sizeFalloff = 1f - distFraction * 0.4f
                            
                            var worldX = tree.worldX +
                                kotlin.math.cos(scatterAngle.toDouble()).toFloat() * rawDist
                            
                            // Wind scatter: asymmetric offset (stronger downwind, weaker crosswind)
                            val windMagnitude = foliageWindScatterStrength * leafCellSize * treeRandom.nextFloat()
                            // Add crosswind jitter for natural asymmetry
                            val crossWind = (treeRandom.nextFloat() - 0.5f) * windMagnitude * 0.3f
                            worldX += windDirX * windMagnitude + crossWind
                            
                            val colorIdx = treeRandom.nextInt(leafColors.size)
                            hillLeaves.add(FallenLeafData(
                                worldX = worldX,
                                yOffset = treeRandom.nextFloat() * 0.08f +
                                    windDirY * foliageWindScatterStrength * 0.02f,
                                color = leafColors[colorIdx],
                                size = (0.003f + treeRandom.nextFloat() * 0.004f) * sizeFalloff,
                                rotation = treeRandom.nextFloat() * 360f
                            ))
                        }
                    }
                }
                
                // Base uniform scatter (always present for ambient ground cover)
                for (cellIndex in leafIndexRange) {
                    val cellSeed = randomSeed + 8000 + hillIndex
                    val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                    // Reduce uniform density when tree-biased leaves are present
                    val uniformDensity = if (foliageUnderTreeBias > 0.01f && hillTrees.isNotEmpty())
                        densityFraction * (1f - foliageUnderTreeBias * 0.5f)
                    else
                        densityFraction
                    if (h > uniformDensity) continue
                    
                    val worldX = cellIndex * leafCellSize +
                        (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * leafCellSize * 0.9f
                    val colorIdx = (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 2) * leafColors.size).toInt()
                        .coerceIn(0, leafColors.size - 1)
                    hillLeaves.add(FallenLeafData(
                        worldX = worldX,
                        yOffset = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 3) * 0.08f,
                        color = leafColors[colorIdx],
                        size = 0.003f + DeterministicLayout.hashToFloat(cellSeed, cellIndex, 4) * 0.004f,
                        rotation = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 5) * 360f
                    ))
                }
                newLeaves[hillIndex] = hillLeaves
            }
            fallenLeavesByHill = newLeaves
        } else {
            fallenLeavesByHill = emptyMap()
        }
        
        // Generate mushrooms using cell-based placement (with optional clustering)
        if (mushroomsEnabled) {
            val capColors = intArrayOf(
                Color.rgb(180, 50, 30),    // Red cap
                Color.rgb(200, 160, 80),   // Tan/beige cap
                Color.rgb(160, 120, 60),   // Brown cap
                Color.rgb(255, 200, 50),   // Yellow cap
                Color.rgb(140, 90, 40)     // Dark brown
            )
            val newMushrooms = mutableMapOf<Int, List<MushroomData>>()
            val densityFraction = (mushroomDensity / 100f).coerceIn(0.05f, 1f)
            val mushIndexRange = WorldViewportTransform.indexRangeForWorldRange(-worldHalf, worldHalf, mushroomCellSize)
            
            for (hillIndex in currentHills.indices) {
                val hillMushrooms = mutableListOf<MushroomData>()
                if (mushroomClusterEnabled) {
                    // Cluster mode: uniform-disk sampling with falloff and noise displacement
                    val clusterDensity = (mushroomClusterDensity / 100f).coerceIn(0.05f, 1f)
                    val clusterRadiusWorld = mushroomClusterRadius / 100f * mushroomCellSize * 2f
                    for (cellIndex in mushIndexRange) {
                        val cellSeed = randomSeed + 9000 + hillIndex
                        val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                        if (h > clusterDensity * densityFraction) continue
                        
                        val centerWorldX = cellIndex * mushroomCellSize +
                            (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * mushroomCellSize * 0.8f
                        val clusterRandom = DeterministicLayout.random(cellSeed, section = 910, index = cellIndex)
                        val count = mushroomClusterMinCount + clusterRandom.nextInt(
                            (mushroomClusterMaxCount - mushroomClusterMinCount + 1).coerceAtLeast(1))
                        for (m in 0 until count) {
                            val angle = clusterRandom.nextFloat() * 2f * kotlin.math.PI.toFloat()
                            val rawDist = kotlin.math.sqrt(clusterRandom.nextFloat().toDouble()).toFloat() * clusterRadiusWorld
                            
                            val normalizedDist = (rawDist / clusterRadiusWorld.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                            val falloffWeight = 1f - normalizedDist * normalizedDist * mushroomClusterFalloff
                            
                            val noiseOffset = if (mushroomClusterNoise > 0.01f) {
                                val nx = DeterministicLayout.hashToFloat(cellSeed + m, cellIndex, 10) - 0.5f
                                nx * mushroomClusterNoise * clusterRadiusWorld
                            } else { 0f }
                            
                            val worldX = centerWorldX +
                                kotlin.math.cos(angle.toDouble()).toFloat() * rawDist + noiseOffset
                            val colorIdx = clusterRandom.nextInt(capColors.size)
                            val baseSize = 0.004f + clusterRandom.nextFloat() * 0.005f
                            hillMushrooms.add(MushroomData(
                                worldX = worldX,
                                yOffset = clusterRandom.nextFloat() * 0.04f,
                                capColor = capColors[colorIdx],
                                size = baseSize * falloffWeight.coerceAtLeast(0.4f)
                            ))
                        }
                    }
                } else {
                    // Uniform placement (original behavior)
                    for (cellIndex in mushIndexRange) {
                        val cellSeed = randomSeed + 9000 + hillIndex
                        val h = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 0)
                        if (h > densityFraction) continue
                        
                        val worldX = cellIndex * mushroomCellSize +
                            (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 1) - 0.5f) * mushroomCellSize * 0.8f
                        val colorIdx = (DeterministicLayout.hashToFloat(cellSeed, cellIndex, 2) * capColors.size).toInt()
                            .coerceIn(0, capColors.size - 1)
                        hillMushrooms.add(MushroomData(
                            worldX = worldX,
                            yOffset = DeterministicLayout.hashToFloat(cellSeed, cellIndex, 3) * 0.04f,
                            capColor = capColors[colorIdx],
                            size = 0.004f + DeterministicLayout.hashToFloat(cellSeed, cellIndex, 4) * 0.005f
                        ))
                    }
                }
                newMushrooms[hillIndex] = hillMushrooms
            }
            mushroomsByHill = newMushrooms
        } else {
            mushroomsByHill = emptyMap()
        }
    }
    
    /** Evaluate hill Y at world-space X using noise. */
    private fun getHillY(worldX: Float, hill: HillData): Float {
        return VegetationUtil.getHillYFromNoise(
            worldX, hill.noiseSeed, hill.depthAmplitudeScale, hill.heightOffset,
            hillFrequency, hillRoughness, hillAmplitude
        )
    }
    
    /**
     * Get hill Y position at given world-space X and depth factor.
     * Used by ground events to follow terrain contour.
     * @param worldX World-space X position (0 = center)
     * @param depthFactor Depth layer to sample (matches hill depthFactor)
     * @return Y position as ratio of screen height (0.0=top, 1.0=bottom)
     */
    fun getHillYAtWorldX(worldX: Float, depthFactor: Float): Float {
        return getHillInfoAtWorldX(worldX, depthFactor).first
    }

    /**
     * Legacy xRatio-based API — converts xRatio to worldX using current screen width and heightScale.
     * @param xRatio X position as ratio (0.0 to 1.0, 0.5 = center)
     */
    @Deprecated("Use getHillYAtWorldX(worldX, depthFactor) instead")
    fun getHillYAtPosition(xRatio: Float, depthFactor: Float): Float {
        val transform = cachedTransform
        val screenX = xRatio * width
        val worldX = transform.screenToWorldX(screenX)
        return getHillYAtWorldX(worldX, depthFactor)
    }
    
    /**
     * Get full hill info (Y position and actual depthFactor) at given world-space X.
     * Finds the closest hill to the requested depth factor.
     * @param worldX World-space X position (0 = center)
     * @param requestedDepthFactor Approximate depth to find closest hill for
     * @return Pair of (Y ratio, actual hill depthFactor)
     */
    fun getHillInfoAtWorldX(worldX: Float, requestedDepthFactor: Float): Pair<Float, Float> {
        val currentHills = hills
        if (currentHills.isEmpty()) return Pair(0.85f, requestedDepthFactor)
        
        var closestHill: HillData? = null
        var closestDistance = Float.MAX_VALUE
        
        for (hill in currentHills) {
            val distance = kotlin.math.abs(hill.depthFactor - requestedDepthFactor)
            if (distance < closestDistance) {
                closestDistance = distance
                closestHill = hill
            }
        }
        
        return closestHill?.let { Pair(getHillY(worldX, it), it.depthFactor) } 
            ?: Pair(0.85f, requestedDepthFactor)
    }

    /**
     * Legacy xRatio-based API — converts xRatio to worldX using current screen width and heightScale.
     */
    @Deprecated("Use getHillInfoAtWorldX(worldX, requestedDepthFactor) instead")
    fun getHillInfoAtPosition(xRatio: Float, requestedDepthFactor: Float): Pair<Float, Float> {
        val transform = cachedTransform
        val screenX = xRatio * width
        val worldX = transform.screenToWorldX(screenX)
        return getHillInfoAtWorldX(worldX, requestedDepthFactor)
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

        if (!sizeChanged) return

        // Rebuild cached transform for current dimensions
        cachedTransform = WorldViewportTransform(w, h)

        // Any geometry change invalidates cached gradients that embed old coordinates.
        cachedHillGradients.clear()
        cachedHillGradientAmbientBucket = -1
        cachedHillGradientSnow = -1

        // Always keep sub-renderer dimensions in sync
        treeRenderer.updateDimensions(w, h)
        bushRenderer.updateDimensions(w, h)

        val firstGenerate = lastGeneratedWidth <= 0f || lastGeneratedHeight <= 0f
        // Regenerate only when current generated coverage can no longer contain the
        // visible world range.  We add a preload margin on generation and require
        // the visible range to escape that margin before re-generating, preventing
        // per-frame thrash from tiny float-precision size changes.
        val requiredWorldHalf = RenderConstants.getVisibleWorldHalf(w, h)
        val needsWiderCoverage = requiredWorldHalf > generatedWorldHalf

        if (firstGenerate || needsWiderCoverage) {
            // Generate with preload margin so small subsequent changes don't retrigger
            generatedWorldHalf = requiredWorldHalf * 1.25f
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
        return VegetationUtil.getSeasonWeight(season, cachedSeason, cachedPrevSeason, cachedSeasonBlend)
    }

    /**
     * Bucket ambient color so gradient cache only refreshes on visible color steps,
     * not every frame during tiny time-of-day transitions.
     */
    private fun ambientColorBucket(color: Int): Int = ColorMath.ambientColorBucket(color)

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

    /**
     * Draw a tree using pre-computed parallax offsets for the hill layer.
     * All trees on the same hill share the same depth factor, so parallax
     * is computed once per hill layer and reused here. This avoids N
     * getOffsetForDepth() calls per hill (where N = tree count) with zero
     * visual cost — trees are close enough in depth that individual offsets
     * would differ by sub-pixel amounts.
     */
    private fun drawTreeInPlace(
        canvas: Canvas,
        tree: TreeRenderer.TreeData,
        state: SimulationState,
        hillPx: Float,
        hillPy: Float,
        ambientColor: Int,
        treeAutumnWeight: Float
    ) {
        canvas.save()
        canvas.translate(hillPx, hillPy)

        // Needle trees stay green year-round; only leaf trees get autumn colours
        val effectiveTreeColor = if (tree.treeType == TreeRenderer.TreeType.LEAF) {
            treeRenderer.autumnTreeColor(tree.dayColor, treeAutumnWeight, tree.index)
        } else {
            tree.dayColor
        }

        val yBasedDepth = ((tree.yRatio - 0.6f) / 0.35f).coerceIn(0f, 1f)
        // Continuous ambient tint: full at night (1.0), minimal during day (0.15)
        val lightLevel = state.getLightLevel()
        val dayFactor = ((lightLevel - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val timeMultiplier = 1.0f - dayFactor * 0.85f
        val treeBlendAmount = RenderConstants.applyAmbientTint(effectiveTreeColor, ambientColor, yBasedDepth) * timeMultiplier
        val treeColor = RenderConstants.blendWithAmbient(effectiveTreeColor, ambientColor, treeBlendAmount)
        treeRenderer.drawTree(canvas, tree, state, tree.index, treeColor)

        canvas.restore()
    }

    /**
     * Draw a bush using pre-computed parallax offsets for the hill layer.
     */
    private fun drawBushInPlace(
        canvas: Canvas,
        bush: BushRenderer.BushData,
        state: SimulationState,
        hillPx: Float,
        hillPy: Float,
        ambientColor: Int
    ) {
        canvas.save()
        canvas.translate(hillPx, hillPy)
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
            
            canvas.restore()
            
            // Draw river/water at this depth layer (after hill fill)
            riverDrawer?.drawAtDepth(canvas, hill.depthFactor, index, currentHills.size)
            
            // Draw lake at its target depth layer (same z-ordering as river)
            lakeDrawer?.drawAtDepth(canvas, hill.depthFactor, index, currentHills.size)
            
            // Draw seasonal decorations inside the same canvas.translate(px, py) as
            // the parent hill. This is the parallax optimization: flowers, mushrooms,
            // leaves, and other small ground decorations share the hill-layer parallax
            // offset rather than computing individual per-object parallax. These objects
            // are too small for individual parallax to be perceptible, and rendering them
            // at the hill's depth avoids per-object getOffsetForDepth() calls.
            canvas.save()
            canvas.translate(px, py)
            drawSeasonalDecorations(canvas, index, hill, ambientColor)
            canvas.restore()
            
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
            // Parallax offset is shared for all items on this hill layer.
            if (animals.isEmpty() && hillBushes.isEmpty()) {
                var treeIdx = 0
                while (treeIdx < hillTrees.size) {
                    drawTreeInPlace(
                        canvas,
                        hillTrees[treeIdx],
                        state,
                        px,
                        py,
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
                            px,
                            py,
                            ambientColor,
                            treeAutumnWeight
                        )

                        DRAW_KIND_BUSH -> drawBushInPlace(
                            canvas,
                            hillBushes[entry.index],
                            state,
                            px,
                            py,
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
        
        // Use cached transform (rebuilt on resize only)
        val transform = cachedTransform
        val parallaxMargin = height * 0.3f
        val padding = height * 0.45f + width * 0.1f  // parallax + edge padding
        val startX = -padding
        val endX = width + padding
        // Number of line segments: ~3px per step for smooth curves
        val numSteps = ((endX - startX) / 3f).toInt().coerceIn(80, 800)
        val stepX = (endX - startX) / numSteps
        val bottomY = height + parallaxMargin
        
        var minY = bottomY
        hillPath.moveTo(startX, bottomY)
        
        for (i in 0..numSteps) {
            val screenX = startX + i * stepX
            // Convert screen X to world-space X (inverse of zoom transform)
            val worldX = (screenX - transform.centerX) / transform.heightScale
            val yRatio = getHillY(worldX, hill)
            val y = yRatio * height
            if (y < minY) minY = y
            hillPath.lineTo(screenX, y)
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
        val transform = cachedTransform
        // Viewport culling bounds: skip decorations whose screen X is far offscreen.
        // Use a generous margin to avoid popping at edges with parallax shift.
        val cullLeft = -width * 0.15f
        val cullRight = width * 1.15f
        
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
                val x = transform.worldToScreenX(flower.worldX)
                if (x < cullLeft || x > cullRight) continue
                
                // Get hill Y at flower position using world-space coordinate directly
                val yRatio = getHillY(flower.worldX, hill)
                val y = yRatio * height + flower.yOffset * height
                
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
                val x = transform.worldToScreenX(leaf.worldX)
                if (x < cullLeft || x > cullRight) continue
                
                val yRatio = getHillY(leaf.worldX, hill)
                val y = yRatio * height + leaf.yOffset * height
                
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
                val x = transform.worldToScreenX(mushroom.worldX)
                if (x < cullLeft || x > cullRight) continue
                
                val yRatio = getHillY(mushroom.worldX, hill)
                val y = yRatio * height + mushroom.yOffset * height
                
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
                    // Draw frost as thin lines along hill contour using noise evaluation
                    // Pixel-walking loop: walk screen pixels, convert to world for noise
                    val frostPadding = height * 0.45f + width * 0.1f
                    val frostStartX = -frostPadding
                    val frostEndX = width + frostPadding
                    val frostSteps = ((frostEndX - frostStartX) / 4f).toInt().coerceIn(40, 400)
                    val frostStepX = (frostEndX - frostStartX) / frostSteps
                    for (i in 0 until frostSteps) {
                        val x1 = frostStartX + i * frostStepX
                        val x2 = x1 + frostStepX
                        val worldX1 = transform.screenToWorldX(x1)
                        val worldX2 = transform.screenToWorldX(x2)
                        val y1 = getHillY(worldX1, hill) * height
                        val y2 = getHillY(worldX2, hill) * height
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
        
        // Track previous structural values to detect changes
        val prevSeed = randomSeed
        val prevHillCount = hillCount
        val prevStartY = hillStartY
        val prevEndY = hillEndY
        val prevRoughness = hillRoughness
        val prevAmplitude = hillAmplitude
        val prevFrequency = hillFrequency
        val prevDistance = hillDistance
        val prevSpread = hillDepthSpread

        // Track previous decoration settings for change detection
        val prevFlowersEnabled = springFlowersEnabled
        val prevFlowerDensity = springFlowerDensity
        val prevFlowerSpread = springFlowerSpread
        val prevSnowEnabled = winterSnowEnabled
        val prevSnowCoverage = winterSnowCoverage
        val prevLeavesEnabled = fallenLeavesEnabled
        val prevLeavesDensity = fallenLeavesDensity
        val prevMushroomsEnabled = mushroomsEnabled
        val prevMushroomDensity = mushroomDensity
        val prevFrostEnabled = frostEnabled
        val prevFlowerCluster = flowerClusterEnabled
        val prevMushroomCluster = mushroomClusterEnabled
        val prevFoliageBias = foliageUnderTreeBias
        
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
        
        // Flower cluster settings
        flowerClusterEnabled = settings.getBoolean("pref_flower_cluster_enabled", PreferenceDefaults.FLOWER_CLUSTER_ENABLED)
        flowerClusterDensity = settings.getInt("pref_flower_cluster_density", PreferenceDefaults.FLOWER_CLUSTER_DENSITY)
        flowerClusterRadius = settings.getInt("pref_flower_cluster_radius", PreferenceDefaults.FLOWER_CLUSTER_RADIUS)
        flowerClusterMinCount = settings.getInt("pref_flower_cluster_min_count", PreferenceDefaults.FLOWER_CLUSTER_MIN_COUNT)
        flowerClusterMaxCount = settings.getInt("pref_flower_cluster_max_count", PreferenceDefaults.FLOWER_CLUSTER_MAX_COUNT)
        flowerClusterFalloff = settings.getInt("pref_flower_cluster_falloff", PreferenceDefaults.FLOWER_CLUSTER_FALLOFF) / 100f
        flowerClusterNoise = settings.getInt("pref_flower_cluster_noise", PreferenceDefaults.FLOWER_CLUSTER_NOISE) / 100f
        
        // Mushroom cluster settings
        mushroomClusterEnabled = settings.getBoolean("pref_mushroom_cluster_enabled", PreferenceDefaults.MUSHROOM_CLUSTER_ENABLED)
        mushroomClusterDensity = settings.getInt("pref_mushroom_cluster_density", PreferenceDefaults.MUSHROOM_CLUSTER_DENSITY)
        mushroomClusterRadius = settings.getInt("pref_mushroom_cluster_radius", PreferenceDefaults.MUSHROOM_CLUSTER_RADIUS)
        mushroomClusterMinCount = settings.getInt("pref_mushroom_cluster_min_count", PreferenceDefaults.MUSHROOM_CLUSTER_MIN_COUNT)
        mushroomClusterMaxCount = settings.getInt("pref_mushroom_cluster_max_count", PreferenceDefaults.MUSHROOM_CLUSTER_MAX_COUNT)
        mushroomClusterFalloff = settings.getInt("pref_mushroom_cluster_falloff", PreferenceDefaults.MUSHROOM_CLUSTER_FALLOFF) / 100f
        mushroomClusterNoise = settings.getInt("pref_mushroom_cluster_noise", PreferenceDefaults.MUSHROOM_CLUSTER_NOISE) / 100f
        
        // Foliage accumulation settings
        foliageUnderTreeBias = settings.getInt("pref_foliage_under_tree_bias", PreferenceDefaults.FOLIAGE_UNDER_TREE_BIAS) / 100f
        foliageMatchingTreeBias = settings.getInt("pref_foliage_matching_tree_bias", PreferenceDefaults.FOLIAGE_MATCHING_TREE_BIAS) / 100f
        foliageWindScatterStrength = settings.getInt("pref_foliage_wind_scatter_strength", PreferenceDefaults.FOLIAGE_WIND_SCATTER_STRENGTH) / 100f
        foliageWindDirection = settings.getInt("pref_foliage_wind_direction", PreferenceDefaults.FOLIAGE_WIND_DIRECTION).toFloat()
        foliageRandomScatter = settings.getInt("pref_foliage_random_scatter", PreferenceDefaults.FOLIAGE_RANDOM_SCATTER) / 100f
        
        // Delegate tree and bush settings to their renderers.
        // Returns true if any generation-affecting setting changed.
        val treeSettingsChanged = treeRenderer.reloadSettings(settings)
        val bushSettingsChanged = bushRenderer.reloadSettings(settings)

        // Detect decoration setting changes
        val decorationChanged = prevFlowersEnabled != springFlowersEnabled ||
            prevFlowerDensity != springFlowerDensity ||
            kotlin.math.abs(prevFlowerSpread - springFlowerSpread) > 0.001f ||
            prevSnowEnabled != winterSnowEnabled ||
            prevSnowCoverage != winterSnowCoverage ||
            prevLeavesEnabled != fallenLeavesEnabled ||
            prevLeavesDensity != fallenLeavesDensity ||
            prevMushroomsEnabled != mushroomsEnabled ||
            prevMushroomDensity != mushroomDensity ||
            prevFrostEnabled != frostEnabled ||
            prevFlowerCluster != flowerClusterEnabled ||
            prevMushroomCluster != mushroomClusterEnabled ||
            kotlin.math.abs(prevFoliageBias - foliageUnderTreeBias) > 0.001f
        
        // Only regenerate hills when hill structural settings change (expensive)
        val structuralChanged = prevSeed != randomSeed ||
            prevHillCount != hillCount ||
            prevStartY != hillStartY ||
            prevEndY != hillEndY ||
            kotlin.math.abs(prevRoughness - hillRoughness) > 0.001f ||
            kotlin.math.abs(prevAmplitude - hillAmplitude) > 0.001f ||
            kotlin.math.abs(prevFrequency - hillFrequency) > 0.001f ||
            kotlin.math.abs(prevDistance - hillDistance) > 0.001f ||
            kotlin.math.abs(prevSpread - hillDepthSpread) > 0.001f
        
        if (structuralChanged) {
            android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, freq=$hillFrequency, amp=$hillAmplitude, dist=$hillDistance, spread=$hillDepthSpread")
            generateTerrain()
        } else if (treeSettingsChanged || bushSettingsChanged || decorationChanged) {
            android.util.Log.d("TerrainLayer", "Vegetation/decoration settings changed, regenerating")
            regenerateVegetation()
        }
    }

    override fun destroy() {
        // Cleanup
    }
}
