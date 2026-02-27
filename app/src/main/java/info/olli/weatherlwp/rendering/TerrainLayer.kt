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
    private val hillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val shadowPaint = Paint().apply { 
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.argb(60, 0, 0, 0)  // Semi-transparent black for shadows
    }
    private val treePath = Path()
    private val shadowPath = Path()
    private val hillPath = Path()

    private var width = 0f
    private var height = 0f
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f  // Offset to center extended content
    
    // Callback for drawing ground events at specific depth levels
    // Called during draw() between tree layers to integrate animals with proper depth
    var groundEventDrawer: GroundEventDrawer? = null
    
    /**
     * Callback interface for drawing ground events at specific depth levels.
     */
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
         * @return List of pairs (event Y ratio, draw lambda)
         */
        fun getEventsInRange(minDepth: Float, maxDepth: Float): List<Pair<Float, () -> Unit>> = emptyList()
    }
    
    // Data structures (immutable after creation)
    private data class HillData(
        val controlPoints: List<Float>,
        val depthFactor: Float,
        val heightOffset: Float,
        val dayColor: Int
    )
    
    private data class TreeData(
        val xRatio: Float,    // 0-1 across extended width
        val yRatio: Float,
        val scale: Float,
        val depthFactor: Float,
        val dayColor: Int,
        val index: Int = -1   // Global index for decoration lookup
    )
    
    // Thread-safe terrain data using volatile references + immutable lists
    // Settings thread creates new lists, render thread reads current snapshots
    @Volatile private var hills: List<HillData> = emptyList()
    @Volatile private var trees: List<TreeData> = emptyList()
    // Pre-grouped trees by depth factor (built once during generation, avoids per-frame filter)
    @Volatile private var treesByDepth: Map<Float, List<TreeData>> = emptyMap()
    
    // Settings
    private var treeDensity = 50        // 0-100 percentage of tree coverage
    private var hillCount = 5
    private var treeSizeScale = 1.0f
    private var treeWidthScale = 1.0f
    private var treeSizeRandomness = 0.3f  // 0-1, how much size varies between trees
    private var treeSpread = 0.8f         // 0-1, how far along hills trees spread (0=peaks, 1=full)
    private var treeSparsity = 0.3f       // 0-1, gap probability between trees (0=dense, 1=sparse)
    private var hillStartY = 0.50f
    private var hillEndY = 0.85f
    private var treeColorVariation = 1.0f
    private var treeSegments = 1
    private var randomSeed = 42
    private var hillRoughness = 1.0f  // Controls sine wave detail/roughness
    private var hillAmplitude = 1.0f  // Controls hill vertical height (0-10 range)
    private var hillFrequency = 2.0f  // Controls how many hills visible per layer (0-10 range)
    
    // Hill layer depth settings
    private var hillDistance = 0f         // 0-1: How far back hills start (0=at camera, 1=near mountains)
    private var hillDepthSpread = 0.5f    // 0-1: Spread of hills along z-axis (0=flat, 1=deep)
    
    // Seasonal settings
    private var terrainSeasonOverride = "auto"  // "auto", "spring", "summer", "autumn", "winter"
    private var springFlowersEnabled = true
    private var springFlowerDensity = 500  // 0-1000
    private var springFlowerSpread = 0.5f  // 0-1, how far down from hill line (like trees)
    private var autumnGrassEnabled = true
    private var autumnGrassIntensity = 50  // 0-100 how brown the grass is
    private var winterSnowEnabled = true
    private var winterSnowCoverage = 50   // 0-100 how much of hill tops are covered
    
    // Dynamic snow from weather history
    private var useWeatherSnow = true  // Use weather history for snow level
    
    // Season transition
    private var seasonTransitionDays = 14
    
    // New seasonal decoration settings
    private var pineconesEnabled = true
    private var pineconeDensity = 30  // 0-100
    private var fallenLeavesEnabled = true
    private var fallenLeavesDensity = 50  // 0-100
    private var mushroomsEnabled = true
    private var mushroomDensity = 20  // 0-100
    private var berriesEnabled = true
    private var berryDensity = 30    // 0-100
    private var frostEnabled = true  // Winter frost overlay
    
    // Pre-generated flower positions (per hill)
    private data class FlowerData(
        val xRatio: Float,    // 0-1 along hill
        val yOffset: Float,   // Offset from hill surface
        val color: Int,
        val size: Float
    )
    @Volatile private var flowersByHill: Map<Int, List<FlowerData>> = emptyMap()
    
    // Pre-generated pinecone positions (per tree index)
    private data class PineconeData(
        val xOffset: Float,   // Horizontal offset from tree trunk
        val yRatio: Float,    // Vertical position on tree (0=top, 1=bottom)
        val size: Float
    )
    @Volatile private var pineconesByTree: Map<Int, List<PineconeData>> = emptyMap()
    
    // Pre-generated fallen leaf positions (per hill)
    private data class FallenLeafData(
        val xRatio: Float,
        val yOffset: Float,
        val color: Int,
        val size: Float,
        val rotation: Float  // Degrees
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
    
    // Pre-generated berry positions (per tree index)
    private data class BerryData(
        val xOffset: Float,
        val yRatio: Float,
        val color: Int,
        val size: Float
    )
    @Volatile private var berriesByTree: Map<Int, List<BerryData>> = emptyMap()
    
    private val pineconePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val leafPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mushroomPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mushroomCapPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val berryPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val frostPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    
    // Pre-generated snow patch positions (per hill)
    private data class SnowPatchData(
        val startXRatio: Float,
        val endXRatio: Float,
        val alpha: Int
    )
    @Volatile private var snowByHill: Map<Int, List<SnowPatchData>> = emptyMap()
    
    private val flowerPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val snowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treeSnowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true; color = Color.WHITE }
    private val treeSnowPath = Path()

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
        generateTrees()
        generateTreeDecorations()
    }
    
    private fun generateHills() {
        // Build new list (thread-safe: atomic swap at end)
        val newHills = ArrayList<HillData>(hillCount)
        val random = Random(randomSeed.toLong() + 1000)  // Offset from mountains
        
        for (i in 0 until hillCount) {
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
            val numPoints = RenderConstants.HILL_CURVE_POINTS_MIN + random.nextInt(RenderConstants.HILL_CURVE_POINTS_RANGE)
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
            
            // Apply autumn brown tint if enabled (with transition blending)
            val currentSeason = getCurrentTerrainSeason()
            if (autumnGrassEnabled) {
                val autumnWeight = when {
                    currentSeason == "autumn" -> 1f
                    else -> 0f
                }
                if (autumnWeight > 0f) {
                    val autumnIntensity = autumnGrassIntensity / 100f * 0.5f * autumnWeight
                    dayColor = autumnColor(dayColor, autumnIntensity)
                }
            }
            
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
        val season = getCurrentTerrainSeason()
        val random = Random(randomSeed.toLong() + 5000)
        
        // Generate spring flowers
        if (springFlowersEnabled && season == "spring") {
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
        
        // Generate winter snow patches
        if (winterSnowEnabled && season == "winter") {
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
        
        // Generate fallen leaves (autumn)
        if (fallenLeavesEnabled && (season == "autumn" || season == "winter")) {
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
        
        // Generate mushrooms (autumn, after rain - always placed, visibility controlled in draw)
        if (mushroomsEnabled && (season == "autumn" || season == "summer")) {
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
    
    /**
     * Generate tree decorations (pinecones, berries) after trees are generated.
     */
    private fun generateTreeDecorations() {
        val season = getCurrentTerrainSeason()
        val currentTrees = trees
        val random = Random(randomSeed.toLong() + 7000)
        
        // Generate pinecones (autumn and winter)
        if (pineconesEnabled && (season == "autumn" || season == "winter")) {
            val newPinecones = mutableMapOf<Int, List<PineconeData>>()
            val pineconeProbability = pineconeDensity / 100f
            
            for ((index, _) in currentTrees.withIndex()) {
                if (random.nextFloat() > pineconeProbability) continue
                val cones = mutableListOf<PineconeData>()
                val numCones = 1 + random.nextInt(3)  // 1-3 pinecones per tree
                for (c in 0 until numCones) {
                    cones.add(PineconeData(
                        xOffset = (random.nextFloat() - 0.5f) * 0.6f,  // -0.3 to 0.3 of tree width
                        yRatio = 0.3f + random.nextFloat() * 0.5f,     // mid to lower portion
                        size = 0.003f + random.nextFloat() * 0.003f
                    ))
                }
                newPinecones[index] = cones
            }
            pineconesByTree = newPinecones
        } else {
            pineconesByTree = emptyMap()
        }
        
        // Generate berries (summer and autumn)
        if (berriesEnabled && (season == "summer" || season == "autumn")) {
            val berryColors = intArrayOf(
                Color.rgb(200, 30, 30),    // Red berries
                Color.rgb(60, 60, 180),    // Blueberries
                Color.rgb(180, 50, 100),   // Dark pink
                Color.rgb(100, 20, 60)     // Dark berry
            )
            val newBerries = mutableMapOf<Int, List<BerryData>>()
            val berryProbability = berryDensity / 100f
            
            for ((index, _) in currentTrees.withIndex()) {
                if (random.nextFloat() > berryProbability) continue
                val treeBerries = mutableListOf<BerryData>()
                val numBerries = 2 + random.nextInt(5)  // 2-6 berries per tree
                for (b in 0 until numBerries) {
                    treeBerries.add(BerryData(
                        xOffset = (random.nextFloat() - 0.5f) * 0.8f,
                        yRatio = 0.2f + random.nextFloat() * 0.6f,
                        color = berryColors[random.nextInt(berryColors.size)],
                        size = 0.002f + random.nextFloat() * 0.002f
                    ))
                }
                newBerries[index] = treeBerries
            }
            berriesByTree = newBerries
        } else {
            berriesByTree = emptyMap()
        }
    }
    
    private fun generateTrees() {
        // Take snapshot of hills for tree placement (thread-safe read)
        val currentHills = hills
        if (currentHills.isEmpty() || treeDensity <= 0 || extendedWidth <= 0) {
            trees = emptyList()
            return
        }
        
        // Calculate tree count based on density and extended width
        // More width = more trees to maintain density
        // Density 100 = normal forest, 200 = very dense forest
        val widthRatio = extendedWidth / (height * RenderConstants.REFERENCE_ASPECT_RATIO).coerceAtLeast(1f)
        // Base trees gives target coverage at 100% density
        // 200 trees across standard width creates moderate density
        val baseTrees = 200
        // Square root scaling: density 200 gives ~1.4x base (not 2x) for more nuanced control
        // Use linear scaling for now: 200% = 2x trees
        val treeCount = ((treeDensity / 100f) * baseTrees * widthRatio).toInt().coerceIn(0, 3000)
        if (treeCount <= 0) {
            trees = emptyList()
            return
        }
        
        // Build new list (thread-safe: atomic swap at end)
        val newTrees = ArrayList<TreeData>(treeCount)
        val random = Random(randomSeed.toLong() + 2000)  // Offset from hills
        
        // Calculate number of trees per hill (more on foreground hills)
        val treesPerHill = (treeCount.toFloat() / currentHills.size).toInt().coerceAtLeast(3)
        
        for (hillIndex in currentHills.indices) {
            val hill = currentHills[hillIndex]
            val depthFactor = hill.depthFactor
            
            // More trees on foreground hills
            val extraTrees = if (hillIndex >= currentHills.size - 2) 5 else 0
            val hillTreeCount = (treesPerHill + extraTrees).coerceAtMost(treeCount - newTrees.size)
            
            // Find peak positions for reference (to determine "upper" parts of hill)
            val points = hill.controlPoints
            val peakPositions = mutableListOf<Float>()
            if (points.size >= 3) {
                for (j in 1 until points.size - 1) {
                    if (points[j] > points[j - 1] && points[j] > points[j + 1]) {
                        val normalizedX = j.toFloat() / (points.size - 1)
                        peakPositions.add(normalizedX)
                    }
                }
            }
            if (peakPositions.isEmpty()) {
                peakPositions.add(0.25f)
                peakPositions.add(0.5f)
                peakPositions.add(0.75f)
            }
            
            // Generate clearings (Lichtungen) for this hill layer
            // Higher sparsity = more and larger clearings
            val numClearings = ((treeSparsity * 5).toInt() + if (random.nextFloat() < treeSparsity) 1 else 0)
                .coerceIn(0, 6)
            val clearings = mutableListOf<Pair<Float, Float>>()  // List of (start, end) x-positions
            for (c in 0 until numClearings) {
                // Clearing width: 5% to 20% of the width depending on sparsity
                val clearingWidth = 0.05f + random.nextFloat() * 0.15f * treeSparsity
                val clearingStart = random.nextFloat() * (1f - clearingWidth)
                val clearingEnd = clearingStart + clearingWidth
                clearings.add(clearingStart to clearingEnd)
            }
            
            // Place trees along the extended width
            for (t in 0 until hillTreeCount) {
                // xRatio is 0-1 across the extended width - distributed evenly with some randomness
                val baseSpacing = 1.0f / hillTreeCount
                val xRatio = (t * baseSpacing + random.nextFloat() * baseSpacing).coerceIn(0f, 1f)
                
                // Skip tree if it falls within a clearing (Lichtung)
                val inClearing = clearings.any { (start, end) -> xRatio in start..end }
                if (inClearing) {
                    continue  // Skip this tree - it's in a clearing
                }
                
                // Additional random skip for some extra variation (but less than before)
                if (random.nextFloat() < treeSparsity * 0.3f) {
                    continue  // Occasional extra skip for natural variation
                }
                
                // Get the hill surface Y at this X position (this is the hill line)
                val groundY = getHillY(xRatio, hill)
                
                // Tree spread controls vertical distribution DOWN from the hill line
                // Spread 0 = trees sit exactly on hill line
                // Spread 1 = trees can spread down significantly below hill line
                val maxVerticalOffset = treeSpread * 0.15f  // Max 15% of height below hill line
                val verticalOffset = random.nextFloat() * maxVerticalOffset
                val yRatio = groundY + verticalOffset  // Move tree down (higher Y = lower on screen)
                
                // Normalize depth to 0-1 range for scale using RenderConstants
                // depthFactor is PARALLAX_HILLS_MIN to PARALLAX_HILLS_MAX (0.50 to 0.80)
                val hillsRange = RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN
                val normalizedDepth = ((depthFactor - RenderConstants.PARALLAX_HILLS_MIN) / hillsRange).coerceIn(0f, 1f)
                
                // Tree scale: foreground trees (normalizedDepth near 1) are larger
                // Apply size randomness: random variation within treeSizeRandomness range
                val baseScale = RenderConstants.TREE_SCALE_MIN + normalizedDepth * RenderConstants.TREE_SCALE_RANGE
                val sizeVariation = 1f + (random.nextFloat() - 0.5f) * 2f * treeSizeRandomness
                val scale = baseScale * sizeVariation.coerceIn(0.3f, 2.0f)
                
                val variationAmount = (20 * treeColorVariation).toInt()
                val varR = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
                val varG = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
                val dayColor = Color.rgb(
                    (Color.red(RenderConstants.TREE_BASE_COLOR) + varR).coerceIn(0, 255),
                    (Color.green(RenderConstants.TREE_BASE_COLOR) + varG).coerceIn(0, 255),
                    Color.blue(RenderConstants.TREE_BASE_COLOR)
                )
                
                newTrees.add(TreeData(xRatio, yRatio, scale, depthFactor, dayColor, newTrees.size))
            }
        }
        // Sort by depthFactor: LOWER depthFactor (background) draws first
        newTrees.sortBy { it.depthFactor }
        
        // Build pre-grouped tree map (avoids per-frame filter allocations)
        // Also pre-sort each group by yRatio for correct Y-based draw order
        val groupedByDepth = newTrees.groupBy { it.depthFactor }
            .mapValues { (_, trees) -> trees.sortedBy { it.yRatio } }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        trees = newTrees
        treesByDepth = groupedByDepth
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

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        // Calculate extended width for parallax content
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)
        // Regenerate terrain for new dimensions
        generateTerrain()
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Animation state comes from SimulationState.timeSeconds
    }

    // Cached season to avoid repeated calls per-frame
    @Volatile private var cachedSeason: String = "summer"
    @Volatile private var cachedSeasonBlend: Float = 1f  // 1 = fully in cached season
    @Volatile private var cachedPrevSeason: String = "summer"
    private var dynamicSnowLevel: Float = 0f  // Snow from weather history (0-1)
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Take local snapshots for thread-safe iteration
        val currentHills = hills
        val currentTreesByDepth = treesByDepth  // Use pre-grouped map instead of per-frame filter
        
        // Cache current season once per frame (avoid repeated lookups in loops)
        cachedSeason = state.currentSeason
        cachedPrevSeason = state.previousSeason
        cachedSeasonBlend = state.seasonTransitionProgress
        
        // Dynamic snow level: use weather history if available, else calendar-based
        dynamicSnowLevel = if (useWeatherSnow) {
            state.snowLevel
        } else {
            0f
        }
        
        // Get ambient color for current time of day (global tint system)
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength
        )
        
        // Draw Hills AND their Trees together, sorted by depth
        // This ensures trees on background hills are drawn before foreground hills
        // Within each hill layer: sort trees and animals by Y position (top to bottom = far to near)
        // Elements lower on screen (higher Y) are drawn later (appear in front)
        for ((index, hill) in currentHills.withIndex()) {
            // Draw this hill layer
            val px = parallaxCalculator.getOffsetXForDepth(hill.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(hill.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            // Blend with ambient color based on depth (distant hills blend more)
            val blendAmount = RenderConstants.applyAmbientTint(hill.dayColor, ambientColor, hill.depthFactor)
            val hillColor = RenderConstants.blendWithAmbient(hill.dayColor, ambientColor, blendAmount)
            drawHill(canvas, hill, hillColor)
            
            // Draw seasonal decorations on top of hill
            drawSeasonalDecorations(canvas, index, hill, ambientColor)
            
            canvas.restore()
            
            // Get trees and animals for this depth range
            val nextDepth = currentHills.getOrNull(index + 1)?.depthFactor ?: Float.MAX_VALUE
            val hillTrees = currentTreesByDepth[hill.depthFactor] ?: emptyList()
            
            // Get animals for this depth range (may be empty)
            val animals = groundEventDrawer?.getEventsInRange(hill.depthFactor, nextDepth) ?: emptyList()
            
            // Optimization: if no animals, just draw trees directly (already Y-sorted during generation)
            if (animals.isEmpty()) {
                for (tree in hillTrees) {
                    val treePx = parallaxCalculator.getOffsetXForDepth(tree.depthFactor)
                    val treePy = parallaxCalculator.getOffsetYForDepth(tree.depthFactor)
                    canvas.save()
                    canvas.translate(treePx, treePy)
                    
                    val yBasedDepth = ((tree.yRatio - 0.6f) / 0.35f).coerceIn(0f, 1f)
                    val timeMultiplier = if (!state.isNight && !state.isDawn && !state.isDusk) 0.15f else 1.0f
                    val treeBlendAmount = RenderConstants.applyAmbientTint(tree.dayColor, ambientColor, yBasedDepth) * timeMultiplier
                    treePaint.color = RenderConstants.blendWithAmbient(tree.dayColor, ambientColor, treeBlendAmount)
                    drawTree(canvas, tree, state, tree.index)
                    
                    canvas.restore()
                }
            } else {
                // Animals present: merge trees and animals by Y position for correct draw order
                // Trees are already sorted by yRatio; use merge sort approach
                val drawables = mutableListOf<Pair<Float, () -> Unit>>()
                
                // Add trees as drawables
                for (tree in hillTrees) {
                    drawables.add(Pair(tree.yRatio) {
                        val treePx = parallaxCalculator.getOffsetXForDepth(tree.depthFactor)
                        val treePy = parallaxCalculator.getOffsetYForDepth(tree.depthFactor)
                        canvas.save()
                        canvas.translate(treePx, treePy)
                        
                        val yBasedDepth = ((tree.yRatio - 0.6f) / 0.35f).coerceIn(0f, 1f)
                        val timeMultiplier = if (!state.isNight && !state.isDawn && !state.isDusk) 0.15f else 1.0f
                        val treeBlendAmount = RenderConstants.applyAmbientTint(tree.dayColor, ambientColor, yBasedDepth) * timeMultiplier
                        treePaint.color = RenderConstants.blendWithAmbient(tree.dayColor, ambientColor, treeBlendAmount)
                        drawTree(canvas, tree, state, tree.index)
                        
                        canvas.restore()
                    })
                }
                
                // Add animals
                drawables.addAll(animals)
                
                // Sort by Y (lower Y = further away, drawn first; higher Y = closer, drawn last)
                drawables.sortBy { it.first }
                
                // Draw all elements in Y-sorted order
                for ((_, drawFunc) in drawables) {
                    drawFunc()
                }
            }
        }
    }
    
    private fun drawHill(canvas: Canvas, hill: HillData, hillColor: Int) {
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
        // Uses weather-driven snow level when available, otherwise calendar season
        val effectiveSnowCoverage = if (useWeatherSnow && dynamicSnowLevel > 0.01f) {
            // Weather-driven snow: coverage scales with accumulated snow depth
            (dynamicSnowLevel * 100).toInt().coerceIn(5, 100)
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
        } else if (cachedPrevSeason == "winter" && cachedSeason != "winter" && cachedSeasonBlend < 1f) {
            // Transitioning OUT of winter - fade snow
            (winterSnowCoverage * (1f - cachedSeasonBlend)).toInt()
        } else {
            0
        }
        val applySnow = effectiveSnowCoverage > 0
        
        // Apply linear gradient from top to bottom
        val gradient = if (applySnow) {
            // Snow coverage: use effectiveSnowCoverage for dynamic depth
            val snowDepth = (effectiveSnowCoverage / 100f).coerceIn(0.1f, 0.6f)  // 10-60% of hill
            val snowColor = Color.rgb(255, 255, 255)  // Fully opaque white
            val snowTransition = Color.rgb(
                (Color.red(hillColor) + 255) / 2,
                (Color.green(hillColor) + 255) / 2, 
                (Color.blue(hillColor) + 255) / 2
            )  // Blend of white and hill color (fully opaque)
            
            LinearGradient(
                0f, minY,           // Start: top of hill
                0f, bottomY,        // End: bottom
                intArrayOf(
                    snowColor,                      // Snow white at very top
                    snowTransition,                 // Transition zone
                    hillColor,                      // Original hill color
                    darkenColor(hillColor, 0.7f)    // Darker at bottom
                ),
                floatArrayOf(0f, snowDepth * 0.4f, snowDepth, 1f),  // Stop positions
                Shader.TileMode.CLAMP
            )
        } else {
            // Non-winter: standard gradient
            LinearGradient(
                0f, minY,           // Start: top of hill (lighter)
                0f, bottomY,        // End: bottom (darker)
                hillColor,          // Top color (original)
                darkenColor(hillColor, 0.7f),  // Bottom color (darkened)
                Shader.TileMode.CLAMP
            )
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
        
        // Draw flowers (small colored dots)
        flowersByHill[hillIndex]?.let { flowers ->
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
                // Scale flower size by height for consistent proportions
                val flowerRadius = flower.size * height
                canvas.drawCircle(x, y, flowerRadius, flowerPaint)
            }
        }
        
        // Draw fallen leaves (small colored ovals scattered on hills)
        fallenLeavesByHill[hillIndex]?.let { leaves ->
            for (leaf in leaves) {
                val x = startX + leaf.xRatio * (endX - startX)
                
                val pointIndex = (leaf.xRatio * (hill.controlPoints.size - 1)).toInt()
                    .coerceIn(0, hill.controlPoints.size - 1)
                val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[pointIndex])
                val y = yRatio * height + leaf.yOffset
                
                val blendedColor = RenderConstants.blendWithAmbient(leaf.color, ambientColor, 0.15f)
                leafPaint.color = blendedColor
                val leafSize = leaf.size * height
                
                canvas.save()
                canvas.rotate(leaf.rotation, x, y)
                // Oval leaf shape
                canvas.drawOval(x - leafSize, y - leafSize * 0.5f, x + leafSize, y + leafSize * 0.5f, leafPaint)
                canvas.restore()
            }
        }
        
        // Draw mushrooms (small stem + cap shapes)
        mushroomsByHill[hillIndex]?.let { mushrooms ->
            for (mushroom in mushrooms) {
                val x = startX + mushroom.xRatio * (endX - startX)
                
                val pointIndex = (mushroom.xRatio * (hill.controlPoints.size - 1)).toInt()
                    .coerceIn(0, hill.controlPoints.size - 1)
                val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[pointIndex])
                val y = yRatio * height + mushroom.yOffset
                
                val size = mushroom.size * height
                
                // Stem (light beige)
                val stemColor = RenderConstants.blendWithAmbient(Color.rgb(230, 210, 180), ambientColor, 0.2f)
                mushroomPaint.color = stemColor
                canvas.drawRect(x - size * 0.2f, y - size * 0.8f, x + size * 0.2f, y, mushroomPaint)
                
                // Cap (colored dome)
                val capColor = RenderConstants.blendWithAmbient(mushroom.capColor, ambientColor, 0.15f)
                mushroomCapPaint.color = capColor
                canvas.drawOval(x - size * 0.6f, y - size * 1.3f, x + size * 0.6f, y - size * 0.5f, mushroomCapPaint)
            }
        }
        
        // Draw frost overlay on hill (winter - subtle white shimmer on edges)
        if (frostEnabled) {
            val showFrost = (useWeatherSnow && dynamicSnowLevel > 0.2f) ||
                (cachedSeason == "winter") ||
                (cachedPrevSeason == "winter" && cachedSeasonBlend < 0.8f)
            if (showFrost) {
                val frostAlpha = if (useWeatherSnow && dynamicSnowLevel > 0.2f) {
                    (dynamicSnowLevel * 40).toInt().coerceIn(0, 35)
                } else if (cachedSeason == "winter") {
                    (25 * (if (cachedPrevSeason != "winter") cachedSeasonBlend else 1f)).toInt()
                } else {
                    (25 * (1f - cachedSeasonBlend)).toInt()
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
    
    private fun drawTree(canvas: Canvas, tree: TreeData, state: SimulationState, treeIndex: Int = -1) {
        // xRatio is 0-1 across extended width, convert to screen coordinates
        // xRatio=0 is left edge of extended canvas, xRatio=1 is right edge
        // Screen center corresponds to xRatio=0.5 when extendedWidth == width
        val x = tree.xRatio * extendedWidth - extendedOffset
        
        // yRatio is the ground level where tree BASE sits
        val groundY = tree.yRatio * height
        
        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        // Base width is calculated from total height, not segment height,
        // so it stays consistent regardless of segment count
        val baseTreeWidth = totalHeight * 0.25f * treeWidthScale
        
        // Wind system: base wind + gusts + multiplier
        val baseWindStrength = state.windStrength.coerceIn(0f, 1f)
        val gustIntensity = state.windGust  // 0-1 current gust strength
        val windMultiplier = state.windMultiplier  // User multiplier
        val swaySpeed = state.treeSwaySpeed        // User sway speed multiplier (0-2)
        
        // Combine base wind with gust (gust adds on top)
        val effectiveWind = (baseWindStrength + gustIntensity * 0.5f).coerceIn(0f, 1f) * windMultiplier
        val timeSec = state.timeSeconds
        
        // Base sway frequency: affected by sway speed setting
        // swaySpeed=0 freezes trees, swaySpeed=1 is normal, swaySpeed=2 is fast
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * swaySpeed
        
        // TRAVELING WIND WAVE: Gusts move across the scene left-to-right
        // Phase based on tree X position - creates spatial coherence (nearby trees move together)
        // The wave travels across the screen based on time
        // Reduced phase divergence for more coherent tree movement (was * 3f)
        val spatialPhase = tree.xRatio * kotlin.math.PI.toFloat() * 1.0f  // 1 wave cycle across screen
        val waveSpeed = 1.2f + baseWindStrength * 1.0f  // Wave travels faster with more wind
        
        // The gust wave: a traveling sine wave that affects tree intensity
        // Trees at similar X positions have similar phase, creating "waves" of wind passing through
        val gustWavePhase = timeSec * waveSpeed - spatialPhase
        val gustWave = (sin(gustWavePhase) * 0.5f + 0.5f)  // 0-1 wave intensity at this tree
        
        // Additional secondary wave for more natural variation
        val secondaryWave = (sin(gustWavePhase * 0.6f + 1.5f) * 0.3f + 0.5f)  // Slower secondary wave
        val combinedWave = gustWave * 0.7f + secondaryWave * 0.3f
        
        // When gust is active, the wave causes trees to lean in sequence
        // Local gust intensity = base gust * wave position (so gust "passes through")
        val localGust = gustIntensity * combinedWave
        
        // Wind direction for gust lean (-1 to 1, positive = rightward)
        // Use precalculated value from SimulationState for efficiency
        val windX = state.getWindDirX()
        
        // Gust impact: when gustIntensity is high, trees lean strongly then recover
        // Use a stronger one-sided push during gusts - trees bend in wind direction
        val gustPush = localGust * localGust * 1.5f * windX  // Quadratic for snappy response, follows wind direction
        
        // Maximum sway amplitude at tree top
        // ALWAYS have ambient sway (affected by swaySpeed), plus wind effects
        // When swaySpeed=0, amplitude is 0 (frozen). When swaySpeed=1, normal ambient sway.
        // Only use ambient sway in maxAmplitude - wind effect is applied in sway formula
        val ambientSway = 0.08f * swaySpeed  // Subtle ambient movement
        val maxAmplitude = totalHeight * (ambientSway + 0.15f)  // Base amplitude with minimum movement
        
        // Get shadow offset from light source (sun/moon position)
        val shadowStrength = if (state.treeShadowsEnabled) state.getEffectiveShadowStrength() else 0f
        val (shadowDx, _) = state.getShadowOffset(tree.scale)  // shadowDy unused, shadows are ground-cast
        
        // Draw shadow first (if shadows enabled)
        if (shadowStrength > 0.01f) {
            // Shadow alpha: linear mapping from 0-1 strength to 20-150 alpha
            // Using roundToInt for proper rounding instead of truncation
            val shadowAlpha = (20f + 130f * shadowStrength).toInt().coerceIn(20, 150)
            shadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
            
            // Calculate shadow skew direction based on light angle
            // Shadow extends horizontally along ground, skewed away from light source
            val skewDirection = if (shadowDx > 0) 1f else -1f
            val skewFactor = kotlin.math.abs(shadowDx).coerceIn(0.2f, 2.0f)  // How far shadow stretches
            
            // Calculate tree tip sway for shadow
            val tipPhase = spatialPhase + 0.4f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.5f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f
            val ambientFactor = swaySpeed * 0.4f
            val shadowWindFactor = ambientFactor + effectiveWind * 0.6f  // Match tree sway calculation
            val shadowGustLean = gustPush * RenderConstants.GUST_LEAN_FACTOR
            val tipSway = tipWave * shadowWindFactor * maxAmplitude * 0.5f + shadowGustLean * totalHeight * 0.5f
            
            // Draw shadow as a skewed tree triangle flattened onto the ground
            // The shadow is a triangle: base at tree trunk, tip extended along ground
            val shadowLength = totalHeight * skewFactor * 0.8f
            val shadowBaseWidth = baseTreeWidth * 0.6f
            
            // Shadow tip is where the tree top would project onto the ground
            val shadowTipX = x + shadowLength * skewDirection + tipSway
            val shadowTipY = groundY + 2f  // Slightly below ground line to blend
            
            // Shadow base is at the tree trunk (same as tree base)
            val shadowBaseX = x
            val shadowBaseY = groundY + 2f
            
            // Draw triangular shadow shape
            treePath.reset()
            treePath.moveTo(shadowTipX, shadowTipY)  // Far tip of shadow
            treePath.lineTo(shadowBaseX - shadowBaseWidth, shadowBaseY)  // Left side of trunk
            treePath.lineTo(shadowBaseX + shadowBaseWidth, shadowBaseY)  // Right side of trunk
            treePath.close()
            
            // Clip shadow to not extend beyond screen bottom (ground level)
            canvas.save()
            canvas.clipRect(0f, 0f, width, groundY + 10f)
            canvas.drawPath(treePath, shadowPaint)
            canvas.restore()
        }
        
        // Draw tree segments from bottom to top with wind-based rotation
        // Calculate overall tree lean angle based on wind and gusts
        val ambientFactor = swaySpeed * 0.4f
        val windFactor = ambientFactor + effectiveWind * 0.6f
        
        // Primary wave for tree rotation
        val treeWave = sin(timeSec * baseFreq + spatialPhase) * 0.5f +
            sin(timeSec * baseFreq * 0.4f + spatialPhase) * 0.3f
        
        // Calculate rotation angle in degrees
        // At max wind (windFactor=1.0), trees lean up to 15 degrees
        // Gust push adds additional directional lean
        val baseRotationDeg = treeWave * windFactor * 12f  // oscillating rotation
        val gustRotationDeg = gustPush * RenderConstants.GUST_LEAN_FACTOR * 8f  // directional gust lean
        val totalRotationDeg = (baseRotationDeg + gustRotationDeg).coerceIn(-25f, 25f)
        
        // Apply rotation transform around tree base
        canvas.save()
        canvas.rotate(totalRotationDeg, x, groundY)
        
        for (seg in 0 until treeSegments) {
            val segBaseY = groundY - seg * segmentHeight * 0.7f
            val segTipY = segBaseY - segmentHeight
            
            // Width decreases with each segment (tapers towards top)
            // Uses baseTreeWidth so total width is independent of segment count
            val widthMultiplier = 1.0f - (seg.toFloat() / treeSegments * 0.6f)
            val segWidth = baseTreeWidth * widthMultiplier
            
            val baseHeightRatio = seg.toFloat() / treeSegments
            val tipHeightRatio = (seg + 1).toFloat() / treeSegments
            
            // Phase varies up the tree for wave-like bending
            val basePhase = spatialPhase + baseHeightRatio * 0.4f
            val tipPhase = spatialPhase + tipHeightRatio * 0.4f
            
            // Multi-frequency oscillation for organic feel
            // This creates additional curve deformation within the rotated tree
            val baseWave = sin(timeSec * baseFreq + basePhase) * 0.3f + 
                sin(timeSec * baseFreq * 2.5f + basePhase) * 0.15f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.3f +
                sin(timeSec * baseFreq * 2.5f + tipPhase) * 0.15f
            
            // Additional local sway within the rotated frame (creates curve)
            // Much smaller since main rotation is already applied
            val localBaseSway = baseWave * windFactor * maxAmplitude * baseHeightRatio * 0.3f
            val localTipSway = tipWave * windFactor * maxAmplitude * tipHeightRatio * tipHeightRatio * 0.3f
            
            treePath.reset()
            treePath.moveTo(x + localTipSway, segTipY)
            treePath.lineTo(x - segWidth + localBaseSway, segBaseY)
            treePath.lineTo(x + segWidth + localBaseSway, segBaseY)
            treePath.close()
            
            canvas.drawPath(treePath, treePaint)
            
            // Draw snow on tree if snow is present (weather-driven or calendar)
            val treeSnowActive = (useWeatherSnow && dynamicSnowLevel > 0.05f) || 
                (winterSnowEnabled && cachedSeason == "winter") ||
                (cachedPrevSeason == "winter" && cachedSeasonBlend < 1f)
            if (treeSnowActive) {
                // Snow coverage driven by weather history or settings
                val treeSnowAmount = if (useWeatherSnow && dynamicSnowLevel > 0.05f) {
                    dynamicSnowLevel
                } else if (cachedSeason == "winter") {
                    (winterSnowCoverage / 100f) * (if (cachedPrevSeason != "winter") cachedSeasonBlend else 1f)
                } else {
                    (winterSnowCoverage / 100f) * (1f - cachedSeasonBlend)
                }
                val snowCoverage = treeSnowAmount.coerceIn(0.1f, 0.8f)
                val segmentSnowFactor = (1f - baseHeightRatio) * 0.3f + 0.5f  // Upper segments have more snow
                val snowHeight = segmentHeight * snowCoverage * segmentSnowFactor
                val snowTipY = segTipY
                val snowBaseY = segTipY + snowHeight
                // Snow width tapers from tip (narrower) to snow base (wider)
                val snowBaseWidth = segWidth * (snowHeight / segmentHeight).coerceIn(0.2f, 0.6f)
                
                treeSnowPath.reset()
                treeSnowPath.moveTo(x + localTipSway, snowTipY)
                treeSnowPath.lineTo(x - snowBaseWidth + localTipSway * 0.7f, snowBaseY)
                treeSnowPath.lineTo(x + snowBaseWidth + localTipSway * 0.7f, snowBaseY)
                treeSnowPath.close()
                
                canvas.drawPath(treeSnowPath, treeSnowPaint)
            }
        }
        
        // Draw pinecones hanging from branches (treeIndex from TreeData.index)
        pineconesByTree[treeIndex]?.let { cones ->
            val ambientColor = RenderConstants.getAmbientColor(
                state.dayProgress, state.isNight, state.isDawn, state.isDusk)
            for (cone in cones) {
                val segIndex = (cone.yRatio * treeSegments).toInt().coerceIn(0, treeSegments - 1)
                val segBaseY = groundY - segIndex * segmentHeight * 0.7f
                val widthMultiplier = 1.0f - (segIndex.toFloat() / treeSegments * 0.6f)
                val segWidth = baseTreeWidth * widthMultiplier
                
                val coneX = x + cone.xOffset * segWidth * 2f
                val coneY = segBaseY - segmentHeight * 0.3f
                val coneSize = cone.size * height
                
                // Brown pinecone
                val coneColor = RenderConstants.blendWithAmbient(
                    Color.rgb(120, 80, 30), ambientColor, 0.2f)
                pineconePaint.color = coneColor
                // Teardrop shape: oval body
                canvas.drawOval(
                    coneX - coneSize * 0.4f, coneY - coneSize,
                    coneX + coneSize * 0.4f, coneY + coneSize * 0.3f,
                    pineconePaint)
            }
        }
        
        // Draw berries as small colored clusters (treeIndex from TreeData.index)
        berriesByTree[treeIndex]?.let { berries ->
            val ambientColor = RenderConstants.getAmbientColor(
                state.dayProgress, state.isNight, state.isDawn, state.isDusk)
            for (berry in berries) {
                val segIndex = (berry.yRatio * treeSegments).toInt().coerceIn(0, treeSegments - 1)
                val widthMultiplier = 1.0f - (segIndex.toFloat() / treeSegments * 0.6f)
                val segWidth = baseTreeWidth * widthMultiplier
                val segBaseY = groundY - segIndex * segmentHeight * 0.7f
                
                val berryX = x + berry.xOffset * segWidth * 2f
                val berryY = segBaseY - segmentHeight * 0.4f
                val berrySize = berry.size * height
                
                val berryColor = RenderConstants.blendWithAmbient(berry.color, ambientColor, 0.15f)
                berryPaint.color = berryColor
                // Small cluster of 2-3 circles
                canvas.drawCircle(berryX, berryY, berrySize, berryPaint)
                canvas.drawCircle(berryX + berrySize * 0.8f, berryY + berrySize * 0.4f, berrySize * 0.8f, berryPaint)
                canvas.drawCircle(berryX - berrySize * 0.5f, berryY + berrySize * 0.6f, berrySize * 0.7f, berryPaint)
            }
        }
        
        canvas.restore()
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Random seed for consistent placement
        randomSeed = prefs.getInt("pref_random_seed", 42)
        
        hillCount = prefs.getInt("pref_hill_count", 5)  // 5 hill layers default, range 0-20
        
        // Tree density: 0-200 percentage (50 = normal, 200 = dense forest)
        treeDensity = prefs.getInt("pref_tree_density", 50)
        
        // Tree spread: how far along hills trees spread (0=peaks only, 100=full hillside)
        treeSpread = prefs.getInt("pref_tree_spread", 80) / 100f
        
        // Tree sparsity: gap probability between trees (0=dense, 100=sparse)
        treeSparsity = prefs.getInt("pref_tree_sparsity", 30) / 100f
        
        // Tree size randomness: variation in tree sizes (0=uniform, 100=very varied)
        treeSizeRandomness = prefs.getInt("pref_tree_size_randomness", PreferenceDefaults.TREE_SIZE_RANDOMNESS) / 100f
        
        hillStartY = prefs.getInt("pref_hill_lower_start", 60) / 100f  // 60% instead of 50%
        // Hills extend below screen (1.0 = bottom edge, >1.0 = below screen)
        // This allows parallax movement to reveal more terrain
        hillEndY = prefs.getInt("pref_hill_upper_end", 95) / 100f      // 95% - closer to bottom for parallax room
        
        // Hill roughness: controls sine wave detail (0-200 -> 0.0-2.0)
        val roughVal = prefs.getInt("pref_hill_roughness", 100)
        hillRoughness = roughVal / 100f
        
        // Hill amplitude: controls hill vertical height (0-1000)
        // 0 = flat, 100 = normal (1.0), 1000 = extreme (10.0)
        val ampVal = prefs.getInt("pref_hill_amplitude", PreferenceDefaults.HILL_AMPLITUDE)
        hillAmplitude = ampVal / 100f  // 0 -> 0.0, 100 -> 1.0, 1000 -> 10.0
        
        // Hill frequency: controls how many hills visible per layer (0-1000 -> 0.0-10.0)
        val freqVal = prefs.getInt("pref_hill_frequency", PreferenceDefaults.HILL_FREQUENCY)
        hillFrequency = freqVal / 100f
        
        // Hill distance: pushes hills away from camera (0=at camera, 100=near mountains)
        val distVal = prefs.getInt("pref_hill_distance", 0)
        hillDistance = distVal / 100f
        
        // Hill depth spread: controls z-axis spread (0=flat layer, 100=deep range)
        val spreadVal = prefs.getInt("pref_hill_depth_spread", 50)
        hillDepthSpread = spreadVal / 100f
        
        val sizeVal = prefs.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f
        
        val widthVal = prefs.getInt("pref_tree_width", 100)
        treeWidthScale = widthVal / 100f
        
        treeColorVariation = prefs.getInt("pref_tree_color_variation", PreferenceDefaults.TREE_COLOR_VARIATION) / 100f
        treeSegments = prefs.getInt("pref_tree_segments", 1).coerceIn(1, 5)
        
        // Seasonal terrain settings
        // Only use terrain season override when debug/simulation mode is enabled
        val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        terrainSeasonOverride = if (debugEnabled) {
            prefs.getString("pref_terrain_season", "auto") ?: "auto"
        } else {
            "auto"  // Always use real season when not in debug mode
        }
        springFlowersEnabled = prefs.getBoolean("pref_spring_flowers", true)
        springFlowerDensity = prefs.getInt("pref_spring_flower_density", 500)
        springFlowerSpread = prefs.getInt("pref_spring_flower_spread", 50) / 100f
        autumnGrassEnabled = prefs.getBoolean("pref_autumn_grass", true)
        autumnGrassIntensity = prefs.getInt("pref_autumn_grass_intensity", 50)
        winterSnowEnabled = prefs.getBoolean("pref_winter_snow_hills", true)
        winterSnowCoverage = prefs.getInt("pref_winter_snow_coverage", 50)
        useWeatherSnow = prefs.getBoolean("pref_weather_snow_enabled", true)
        seasonTransitionDays = prefs.getInt("pref_season_transition_days", PreferenceDefaults.SEASON_TRANSITION_DAYS)
        
        // New seasonal decoration settings
        pineconesEnabled = prefs.getBoolean("pref_pinecones_enabled", PreferenceDefaults.PINECONES_ENABLED)
        pineconeDensity = prefs.getInt("pref_pinecone_density", PreferenceDefaults.PINECONE_DENSITY)
        fallenLeavesEnabled = prefs.getBoolean("pref_fallen_leaves_enabled", PreferenceDefaults.FALLEN_LEAVES_ENABLED)
        fallenLeavesDensity = prefs.getInt("pref_fallen_leaves_density", PreferenceDefaults.FALLEN_LEAVES_DENSITY)
        mushroomsEnabled = prefs.getBoolean("pref_mushrooms_enabled", PreferenceDefaults.MUSHROOMS_ENABLED)
        mushroomDensity = prefs.getInt("pref_mushroom_density", PreferenceDefaults.MUSHROOM_DENSITY)
        berriesEnabled = prefs.getBoolean("pref_berries_enabled", PreferenceDefaults.BERRIES_ENABLED)
        berryDensity = prefs.getInt("pref_berry_density", PreferenceDefaults.BERRY_DENSITY)
        frostEnabled = prefs.getBoolean("pref_frost_enabled", PreferenceDefaults.FROST_ENABLED)
        
        // Wind is now from SimulationState, not a separate setting
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, density=$treeDensity%, freq=$hillFrequency, amp=$hillAmplitude, dist=$hillDistance, spread=$hillDepthSpread")
        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
