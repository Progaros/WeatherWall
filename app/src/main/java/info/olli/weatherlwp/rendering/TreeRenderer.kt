package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withRotation
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.util.ColorMath
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Handles tree generation, placement on hills, and rendering with wind-based animation.
 * Supports both needle (conifer) and leaf (deciduous) tree types with seasonal behavior.
 */
class TreeRenderer {

    // ---- Data classes ----

    /**
     * Tree types with different visual shapes and seasonal behavior.
     * NEEDLE: Conifer/evergreen - triangular shape, stays green year-round.
     * LEAF: Deciduous - round crown, turns brown/orange in autumn, bare in winter.
     */
    enum class TreeType { NEEDLE, LEAF }

    /**
     * Pre-calculated circle for leaf tree crown or bare branch for winter.
     */
    data class CrownCircle(
        val xOffset: Float,      // relative to crown center (-1..1)
        val yOffset: Float,      // relative to crown center (-1..1)
        val radiusScale: Float,  // 0.3-1.0 relative to crown size
        val darkenFactor: Float  // 0.85-1.0 color variation
    )

    /**
     * Pre-calculated bare branch line for winter deciduous trees. Coordinates are fractions of
     * the tree height: x from the trunk (+ = right), y above the ground.
     */
    data class BranchLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val thickness: Float  // fraction of the trunk width
    )

    data class TreeData(
        val worldX: Float,    // World-space x coordinate (0 = center)
        val yRatio: Float,
        val scale: Float,
        val depthFactor: Float,
        val dayColor: Int,
        val index: Int = -1,   // Global index for decoration lookup
        val treeType: TreeType = TreeType.NEEDLE,
        val crownCircles: List<CrownCircle> = emptyList(),  // For LEAF trees
        val branches: List<BranchLine> = emptyList(),        // Winter bare branches for LEAF trees
        val crownAspectX: Float = 1f,  // crown width multiplier (LEAF)
        val crownAspectY: Float = 1f,  // crown height multiplier (LEAF)
        val crownLean: Float = 0f      // crown offset from the trunk, fraction of crown radius (LEAF)
    )

    // Pre-generated pinecone positions (per tree index)
    data class PineconeData(
        val xOffset: Float,   // Horizontal offset from tree trunk
        val yRatio: Float,    // Vertical position on tree (0=top, 1=bottom)
        val size: Float
    )

    /**
     * Minimal hill info needed for tree placement.
     * Mirrors the fields from TerrainLayer.HillData that tree generation requires.
     */
    data class HillInfo(
        val noiseSeed: Int,
        val depthAmplitudeScale: Float,
        val depthFactor: Float,
        val heightOffset: Float,
        val hillFrequency: Float,
        val hillRoughness: Float,
        val hillAmplitude: Float
    )

    // ---- Pre-allocated Paint/Path objects (zero-allocation rendering) ----

    companion object {
        /** World-space cell size for tree placement. ~10 world units per cell. */
        const val TREE_CELL_SIZE = 10f
        private const val TREE_GROVE_SIZE = 45f
        private const val GROVE_BIAS = 0.8f
        /** Max blend of the lit side of foliage toward warm sunlight. */
        private const val CROWN_HIGHLIGHT = 0.28f
        private const val SUNLIT_FOLIAGE = 0xFFFFF2C8.toInt()
        private const val BARK_COLOR = 0xFF5A4128.toInt()      // rgb(90, 65, 40)
        private const val PINECONE_COLOR = 0xFF78501E.toInt()  // rgb(120, 80, 30)

        /**
         * Minimum world-space distance between adjacent trees on the same hill.
         * Prevents visual clustering when cells are small relative to tree crown size.
         */
        fun minimumTreeSpacingWorld(density: Int, sparsity: Float): Float {
            // Higher density → smaller spacing. Sparsity adds breathing room.
            val base = TREE_CELL_SIZE * 0.55f
            val sparsityBonus = sparsity * TREE_CELL_SIZE * 0.3f
            val densityReduction = (density / 200f).coerceIn(0f, 0.4f) * base
            return (base + sparsityBonus - densityReduction).coerceAtLeast(TREE_CELL_SIZE * 0.3f)
        }
    }

    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val shadowPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.argb(60, 0, 0, 0)  // Semi-transparent black for shadows
    }
    private val treePath = Path()
    private val trunkPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val crownPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val pineconePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treeSnowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true; color = Color.WHITE }
    private val treeSnowStrokePaint = Paint().apply {
        style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val treeSnowPath = Path()

    // ---- Settings (loaded from SharedPreferences) ----

    private var treeDensity = 50        // 0-200 coverage scale (50 = normal, 200 = dense forest)
    private var leafTreeRatio = 50      // 0-100: percentage of trees that are leaf (deciduous)
    private var treeSizeScale = 1.0f
    private var treeWidthScale = 1.0f
    private var treeSizeRandomness = 0.3f  // 0-1, how much size varies between trees
    private var treeSpread = 0.8f         // 0-1, how far along hills trees spread (0=peaks, 1=full)
    private var treeSparsity = 0.3f       // 0-1, gap probability between trees (0=dense, 1=sparse)
    private var treeColorVariation = 1.0f
    private var treeSegments = 1

    // Pinecone settings
    private var pineconesEnabled = true
    private var pineconeDensity = 30  // 0-100

    // Snow settings (needed for tree snow caps; TerrainLayer also reads these for hills)
    private var winterSnowEnabled = true
    private var winterSnowCoverage = PreferenceDefaults.WINTER_SNOW_COVERAGE   // 0-100 how much of hill tops are covered
    private var useWeatherSnow = true     // Use weather history for snow level

    // ---- Thread-safe state (volatile + immutable lists) ----

    @Volatile private var trees: List<TreeData> = emptyList()

    /** Pre-grouped trees by depth factor (built once during generation, avoids per-frame filter). */
    @Volatile var treesByDepth: Map<Float, List<TreeData>> = emptyMap()
        private set

    @Volatile private var pineconesByTree: Map<Int, List<PineconeData>> = emptyMap()

    // ---- Screen dimensions (stored from generateTrees) ----

    private var screenWidth = 0f
    private var height = 0f

    /**
     * Silhouette of the hill the trees currently being drawn stand on, in the active canvas
     * space. Ground shadows are clipped to it so a shadow cast from a ridge cannot spill
     * over the skyline. Set by [TerrainLayer] per hill layer; null means "no clip".
     */
    var groundClipPath: Path? = null

    /** Cached world-to-screen transform, rebuilt on dimension change. */
    private var cachedTransform = WorldViewportTransform(1f, 1f)

    /**
     * Update render-time dimensions without regenerating tree positions.
     * Called on height-only resizes.
     */
    fun updateDimensions(screenWidth: Float, height: Float) {
        this.screenWidth = screenWidth
        this.height = height
        cachedTransform = WorldViewportTransform(screenWidth, height)
    }

    // ---- Season state (set by TerrainLayer before each draw frame) ----

    @Volatile var cachedSeason: String = "summer"
    @Volatile var cachedSeasonBlend: Float = 1f  // 1 = fully in cached season
    @Volatile var cachedPrevSeason: String = "summer"
    var dynamicSnowLevel: Float = 0f  // Snow from weather history (0-1)

    /** Snow tint for the hill band being drawn (ambient-blended so it darkens at night). */
    var snowColor: Int = Color.WHITE
        set(value) {
            field = value
            treeSnowPaint.color = value
        }

    // Frame-global ambient (time-of-day) tint, set once per frame by TerrainLayer.
    // Avoids recomputing RenderConstants.getAmbientColor() for every drawn tree.
    @Volatile var cachedAmbientColor: Int = 0

    /** Overcast flattening and night factor of the current frame, set by TerrainLayer. */
    var dullness: Float = 0f
    var night: Float = 0f

    /** Band haze of the tree being drawn, for bark and cones. */
    private var barkHaze = 0.2f

    private fun barkColor(base: Int): Int =
        RenderConstants.sceneLight(base, cachedAmbientColor, maxOf(0.15f, barkHaze), dullness, night)

    // ========================================================================
    // Public helpers
    // ========================================================================

    /**
     * Compute smooth weight (0-1) for a given season based on current transition state.
     * Returns 1.0 when fully in the target season, 0.0 when not in it at all,
     * and intermediate values during transitions into/out of the season.
     */
    fun getSeasonWeight(season: String): Float {
        return VegetationUtil.getSeasonWeight(season, cachedSeason, cachedPrevSeason, cachedSeasonBlend)
    }

    /**
     * Blend tree foliage toward autumn colours based on weight.
     * Shifts green toward orange/red/golden tones per tree.
     */
    fun autumnTreeColor(baseColor: Int, weight: Float, treeIndex: Int): Int =
        VegetationUtil.autumnColor(baseColor, weight, treeIndex)

    // ========================================================================
    // Private helpers
    // ========================================================================

    private fun darkenColor(color: Int, factor: Float = 0.65f): Int = ColorMath.darkenColor(color, factor)

    /**
     * Evaluate hill Y at a world-space X position using noise.
     */
    private fun getHillY(worldX: Float, hill: HillInfo): Float =
        VegetationUtil.getHillY(worldX, hill)

    // ========================================================================
    // Generation methods
    // ========================================================================

    /**
     * Generate trees placed along hills.
     *
     * @param hills Hill information (noise params, depth, height offset) for tree placement.
     * @param screenWidth Actual visible screen width in pixels.
     * @param height Screen height in pixels.
     * @param randomSeed Seed for deterministic placement.
     */
    fun generateTrees(
        hills: List<HillInfo>,
        screenWidth: Float,
        height: Float,
        randomSeed: Int
    ) {
        // Store dimensions for draw methods
        this.screenWidth = screenWidth
        this.height = height

        if (hills.isEmpty() || treeDensity <= 0 || screenWidth <= 0) {
            trees = emptyList()
            treesByDepth = emptyMap()
            return
        }

        // Viewport-based generation: generate cells visible on screen + parallax padding.
        // The world is infinite — cells extend in both directions deterministically.
        val visibleHalf = RenderConstants.getVisibleWorldHalf(screenWidth, height)
        val treeCellSize = TREE_CELL_SIZE
        val indexRange = WorldViewportTransform.indexRangeForWorldRange(
            -visibleHalf, visibleHalf, treeCellSize
        )
        // Density fraction: what fraction of cells actually produce a tree
        // Slider range is 0-200 (50 = normal, 200 = dense forest)
        val densityFraction = (treeDensity / 200f).coerceIn(0f, 1f)
        if (densityFraction <= 0f) {
            trees = emptyList()
            treesByDepth = emptyMap()
            return
        }

        // Build new list (thread-safe: atomic swap at end)
        val newTrees = ArrayList<TreeData>()

        for (hillIndex in hills.indices) {
            val hill = hills[hillIndex]
            val depthFactor = hill.depthFactor
            val hillRandom = DeterministicLayout.random(randomSeed + 2000, section = 300, index = hillIndex)
            val minTreeSpacingWorld = minimumTreeSpacingWorld(treeDensity, treeSparsity)
            val nearness = RenderConstants.bandNearness(hillIndex, hills.size)
            var lastPlacedWorldX = Float.NEGATIVE_INFINITY

            // Generate clearings (Lichtungen) for this hill layer
            // Clearings use worldX ranges instead of ratios for infinite-world compatibility
            val numClearings = ((treeSparsity * 5).toInt() + if (hillRandom.nextFloat() < treeSparsity) 1 else 0)
                .coerceIn(0, 6)
            val clearings = mutableListOf<Pair<Float, Float>>()
            for (c in 0 until numClearings) {
                val clearingRandom = DeterministicLayout.random(randomSeed + 2000 + hillIndex, section = 301, index = c)
                val clearingHalfWidth = (0.05f + clearingRandom.nextFloat() * 0.15f * treeSparsity) *
                    RenderConstants.REFERENCE_EXTENDED_WIDTH * 0.5f
                val clearingCenter = (clearingRandom.nextFloat() - 0.5f) * RenderConstants.REFERENCE_EXTENDED_WIDTH
                clearings.add((clearingCenter - clearingHalfWidth) to (clearingCenter + clearingHalfWidth))
            }

            // Place trees using stable world-cell indices
            for (cellIndex in indexRange) {
                val treeRandom = DeterministicLayout.random(randomSeed + 2000 + hillIndex, section = 310, index = cellIndex)

                val densityRoll = treeRandom.nextFloat()

                // World-space position: cell center + jitter
                val worldX = cellIndex * treeCellSize +
                    (treeRandom.nextFloat() - 0.5f) * treeCellSize * 0.8f

                // Forests get denser, meadows keep only the odd solitary tree
                val forest = VegetationUtil.forestMask(worldX, randomSeed, hillIndex)
                if (densityRoll > densityFraction * (0.1f + 1.9f * forest)) continue

                // Enforce minimum spacing to prevent visual clustering
                if (worldX - lastPlacedWorldX < minTreeSpacingWorld) continue

                // Skip tree if it falls within a clearing (Lichtung)
                val inClearing = clearings.any { (start, end) -> worldX in start..end }
                if (inClearing) continue

                // Additional random skip for some extra variation
                if (treeRandom.nextFloat() < treeSparsity * 0.3f) continue

                // Get the hill surface Y at this world-space X position
                val groundY = getHillY(worldX, hill)

                // Kept well below the band spacing so trees don't bleed into the next ridge
                val maxVerticalOffset = treeSpread * 0.05f
                val verticalOffset = treeRandom.nextFloat() * maxVerticalOffset
                val yRatio = groundY + verticalOffset

                val baseScale = RenderConstants.TREE_SCALE_MIN + nearness * RenderConstants.TREE_SCALE_RANGE
                val sizeVariation = 1f + (treeRandom.nextFloat() - 0.5f) * 2f * treeSizeRandomness
                val scale = baseScale * sizeVariation.coerceIn(0.3f, 2.0f)

                val variationAmount = (20 * treeColorVariation).toInt()
                val varR = if (variationAmount > 0) treeRandom.nextInt(variationAmount * 2 + 1) - variationAmount else 0
                val varG = if (variationAmount > 0) treeRandom.nextInt(variationAmount * 2 + 1) - variationAmount else 0
                val dayColor = Color.rgb(
                    (Color.red(RenderConstants.TREE_BASE_COLOR) + varR).coerceIn(0, 255),
                    (Color.green(RenderConstants.TREE_BASE_COLOR) + varG).coerceIn(0, 255),
                    Color.blue(RenderConstants.TREE_BASE_COLOR)
                )

                // Groves: neighbouring trees mostly share a type; overall ratio still matches the setting
                val leafRatio = leafTreeRatio / 100f
                val groveIndex = kotlin.math.floor(worldX / TREE_GROVE_SIZE).toInt()
                val leafyGrove = DeterministicLayout.hashToFloat(randomSeed + 2100 + hillIndex, groveIndex, 0) < leafRatio
                val leafChance = if (leafyGrove) leafRatio + (1f - leafRatio) * GROVE_BIAS else leafRatio * (1f - GROVE_BIAS)
                val isLeaf = treeRandom.nextInt(100) < (leafChance * 100f).toInt()
                val type = if (isLeaf) TreeType.LEAF else TreeType.NEEDLE

                // Generate crown circles for leaf trees, branches for winter bare look
                val crown = if (isLeaf) generateCrownCircles(treeRandom) else emptyList()
                val branches = if (isLeaf) generateBranches(treeRandom) else emptyList()

                // Crown silhouette from its own stream so placement and branches stay unchanged
                val shapeRandom = DeterministicLayout.random(randomSeed + 2000 + hillIndex, section = 311, index = cellIndex)
                val shapeRoll = shapeRandom.nextFloat()
                val (aspectX, aspectY) = when {
                    shapeRoll < 0.45f -> 1f to 1f                                              // round
                    shapeRoll < 0.75f -> (1.15f + shapeRandom.nextFloat() * 0.2f) to 0.85f    // broad
                    else -> 0.82f to (1.15f + shapeRandom.nextFloat() * 0.2f)                 // tall
                }
                val lean = (shapeRandom.nextFloat() - 0.5f) * 0.3f

                // Stable identity: derived from hill and cell, not list position
                val stableId = hillIndex * 100003 + cellIndex

                newTrees.add(TreeData(worldX, yRatio, scale, depthFactor, dayColor, stableId,
                    type, crown, branches, aspectX, aspectY, lean))
                lastPlacedWorldX = worldX
            }
        }
        // Sort by depthFactor: LOWER depthFactor (background) draws first
        newTrees.sortBy { it.depthFactor }

        // Build pre-grouped tree map (avoids per-frame filter allocations)
        val groupedByDepth = newTrees.groupBy { it.depthFactor }
            .mapValues { (_, treesInGroup) -> treesInGroup.sortedBy { it.yRatio } }

        // Atomic reference swap (volatile ensures visibility to render thread)
        trees = newTrees
        treesByDepth = groupedByDepth
    }

    /**
     * Generate tree decorations (pinecones) after trees are generated.
     * Must be called after [generateTrees].
     */
    fun generateTreeDecorations(randomSeed: Int) {
        val currentTrees = trees

        // Always pre-generate all tree decorations.
        // Visibility/opacity controlled at draw time using season blend.

        // Generate pinecones (always, visibility controlled at draw time)
        if (pineconesEnabled) {
            val newPinecones = mutableMapOf<Int, List<PineconeData>>()
            val pineconeProbability = pineconeDensity / 100f

            for (tree in currentTrees) {
                val stableId = tree.index  // stable cell-based identity
                val treeRandom = DeterministicLayout.random(randomSeed + 7000, section = 320, index = stableId)
                if (treeRandom.nextFloat() > pineconeProbability) continue
                val cones = mutableListOf<PineconeData>()
                val numCones = 1 + treeRandom.nextInt(3)  // 1-3 pinecones per tree
                for (c in 0 until numCones) {
                    cones.add(PineconeData(
                        xOffset = (treeRandom.nextFloat() - 0.5f) * 0.6f,  // -0.3 to 0.3 of tree width
                        yRatio = 0.3f + treeRandom.nextFloat() * 0.5f,     // mid to lower portion
                        size = 0.003f + treeRandom.nextFloat() * 0.003f
                    ))
                }
                newPinecones[stableId] = cones
            }
            pineconesByTree = newPinecones
        } else {
            pineconesByTree = emptyMap()
        }
    }

    /**
     * Generate 3-5 overlapping circles forming a deciduous tree crown.
     * Precalculated during generation for zero-allocation rendering.
     */
    private fun generateCrownCircles(random: Random): List<CrownCircle> {
        val count = 3 + random.nextInt(3)  // 3-5 circles
        val circles = ArrayList<CrownCircle>(count)

        // Main central crown circle
        circles.add(CrownCircle(0f, 0f, 0.8f + random.nextFloat() * 0.2f, 0.92f))

        for (i in 1 until count) {
            val angle = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val dist = 0.15f + random.nextFloat() * 0.4f
            val xOff = kotlin.math.cos(angle.toDouble()).toFloat() * dist
            val yOff = kotlin.math.sin(angle.toDouble()).toFloat() * dist * 0.7f
            val radius = 0.4f + random.nextFloat() * 0.45f
            // Lower puffs sit in the crown's own shade, upper ones catch the sky light
            val lowness = (yOff / 0.385f + 1f) * 0.5f
            val darken = (0.9f + random.nextFloat() * 0.1f) * (1f - 0.2f * lowness)
            circles.add(CrownCircle(xOff, yOff, radius, darken))
        }
        return circles.sortedByDescending { it.yOffset }
    }

    /**
     * Bare winter crown: 3-5 limbs leave the upper trunk at an upward angle and fork twice
     * into branches and twigs, so the outline reads as a rounded deciduous crown.
     */
    private fun generateBranches(random: Random): List<BranchLine> {
        val branches = ArrayList<BranchLine>(32)
        val limbs = 3 + random.nextInt(3)
        val firstSide = if (random.nextBoolean()) 1f else -1f
        for (i in 0 until limbs) {
            val t = i.toFloat() / (limbs - 1)  // 0 = lowest limb, 1 = highest
            val startY = 0.45f + t * 0.33f + (random.nextFloat() - 0.5f) * 0.04f
            val side = if (i % 2 == 0) firstSide else -firstSide
            // Low limbs spread wide, high ones rise steeply (radians from vertical)
            val angle = side * (1.0f - 0.55f * t + (random.nextFloat() - 0.5f) * 0.25f)
            val length = 0.17f + random.nextFloat() * 0.07f - t * 0.04f
            addBranch(branches, random, 0f, startY, angle, length, 0.55f, depth = 0)
        }
        // Leader continuing the trunk
        addBranch(branches, random, 0f, 0.8f, (random.nextFloat() - 0.5f) * 0.3f, 0.12f, 0.4f, depth = 1)
        return branches
    }

    private fun addBranch(
        out: MutableList<BranchLine>, random: Random,
        x0: Float, y0: Float, angle: Float, length: Float, thickness: Float, depth: Int
    ) {
        val x1 = x0 + kotlin.math.sin(angle) * length
        val y1 = y0 + kotlin.math.cos(angle) * length
        out.add(BranchLine(x0, y0, x1, y1, thickness))
        if (depth >= 2) return
        val sign = if (angle >= 0f) 1f else -1f
        // One child keeps the direction, one splays outward; twigs are sparser
        val children = if (depth == 0) 2 else if (random.nextFloat() < 0.7f) 2 else 1
        for (c in 0 until children) {
            val forkAt = 0.55f + random.nextFloat() * 0.25f
            val fx = x0 + (x1 - x0) * forkAt
            val fy = y0 + (y1 - y0) * forkAt
            val turn = if (c == 0) (random.nextFloat() - 0.5f) * 0.4f else sign * (0.35f + random.nextFloat() * 0.3f)
            val childAngle = (angle - sign * 0.15f + turn).coerceIn(-1.4f, 1.4f)
            val childLength = length * (0.55f + random.nextFloat() * 0.2f)
            addBranch(out, random, fx, fy, childAngle, childLength, thickness * 0.6f, depth + 1)
        }
    }

    // ========================================================================
    // Rendering methods
    // ========================================================================

    /**
     * Draw a single tree. Delegates to [drawLeafTree] for deciduous trees.
     *
     * The canvas should already have parallax translation applied (via canvas.translate)
     * before calling this method.
     *
     * @param canvas The canvas to draw on (already translated for parallax).
     * @param tree The tree data to render.
     * @param state Current simulation state (wind, time, shadows, etc.).
     * @param treeIndex Global tree index for decoration lookup.
     * @param treeColor Pre-computed tree color (with ambient tint already blended).
     * @param haze Band haze shared with the hill, applied to bark and cones.
     */
    fun drawTree(canvas: Canvas, tree: TreeData, state: SimulationState, treeIndex: Int, treeColor: Int, haze: Float) {
        treePaint.color = treeColor
        barkHaze = haze

        if (tree.treeType == TreeType.LEAF) {
            drawLeafTree(canvas, tree, state)
            return
        }
        // --- NEEDLE tree (conifer) rendering below ---
        // World-space to screen projection via cached transform
        val x = cachedTransform.worldToScreenX(tree.worldX)

        // yRatio is the ground level where tree BASE sits
        val groundY = tree.yRatio * height

        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        // Base width is calculated from total height, not segment height,
        // so it stays consistent regardless of segment count
        val baseTreeWidth = totalHeight * 0.25f * treeWidthScale

        val objectHalfWidth = baseTreeWidth * 1.6f
        if (!ViewportCulling.isVisibleHorizontally(
                x = x,
                objectHalfWidth = objectHalfWidth,
                screenWidth = screenWidth,
                height = height,
                extraMargin = totalHeight * 0.2f
            )) {
            return
        }

        // Wind system: base wind + gusts + multiplier
        val baseWindStrength = state.windStrength.coerceIn(0f, 1f)
        val gustIntensity = state.windGust  // 0-1 current gust strength
        val windMultiplier = state.windMultiplier  // User multiplier
        val swaySpeed = state.treeSwaySpeed        // User sway speed multiplier (0-2)

        // Combine base wind with gust (gust adds on top)
        val effectiveWind = (baseWindStrength + gustIntensity * 0.5f).coerceIn(0f, 1f) * windMultiplier
        val timeSec = state.timeSeconds

        // Base sway frequency: affected by sway speed setting
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * swaySpeed

        // TRAVELING WIND WAVE: Gusts move across the scene left-to-right
        // Spatial phase from world position for traveling wind wave
        val normalizedPos = (tree.worldX / RenderConstants.REFERENCE_EXTENDED_WIDTH) + 0.5f
        val spatialPhase = normalizedPos * kotlin.math.PI.toFloat() * 1.0f
        val waveSpeed = 1.2f + baseWindStrength * 1.0f

        // The gust wave: a traveling sine wave that affects tree intensity
        val gustWavePhase = timeSec * waveSpeed - spatialPhase
        val gustWave = (sin(gustWavePhase) * 0.5f + 0.5f)

        // Additional secondary wave for more natural variation
        val secondaryWave = (sin(gustWavePhase * 0.6f + 1.5f) * 0.3f + 0.5f)
        val combinedWave = gustWave * 0.7f + secondaryWave * 0.3f

        // When gust is active, the wave causes trees to lean in sequence
        val localGust = gustIntensity * combinedWave

        // Wind direction for gust lean (-1 to 1, positive = rightward)
        val windX = state.getWindDirX()

        // Gust impact: quadratic for snappy response, follows wind direction
        val gustPush = localGust * localGust * 1.5f * windX

        // Maximum sway amplitude at tree top
        val ambientSway = 0.08f * swaySpeed  // Subtle ambient movement
        val maxAmplitude = totalHeight * (ambientSway + 0.15f)

        // Get shadow offset from light source (sun/moon position)
        val shadowStrength = if (state.treeShadowsEnabled) state.getEffectiveShadowStrength() else 0f

        // Draw shadow first (if shadows enabled)
        if (shadowStrength > 0.01f) {
            // Calculate tree tip sway for shadow
            val tipPhase = spatialPhase + 0.4f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.5f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f
            val ambientFactor = swaySpeed * 0.4f
            val shadowWindFactor = ambientFactor + effectiveWind * 0.6f
            val shadowGustLean = gustPush * RenderConstants.GUST_LEAN_FACTOR
            val tipSway = tipWave * shadowWindFactor * maxAmplitude * 0.5f + shadowGustLean * totalHeight * 0.5f

            GroundShadow.draw(
                canvas, shadowPaint, state, x, groundY, totalHeight, baseTreeWidth,
                tipSway, groundClipPath
            )
        }

        // Draw tree segments from bottom to top with wind-based rotation
        val ambientFactor = swaySpeed * 0.4f
        val windFactor = ambientFactor + effectiveWind * 0.6f

        // Primary wave for tree rotation
        val treeWave = sin(timeSec * baseFreq + spatialPhase) * 0.5f +
            sin(timeSec * baseFreq * 0.4f + spatialPhase) * 0.3f

        // Calculate rotation angle in degrees
        val baseRotationDeg = treeWave * windFactor * 12f
        val gustRotationDeg = gustPush * RenderConstants.GUST_LEAN_FACTOR * 8f
        val totalRotationDeg = (baseRotationDeg + gustRotationDeg).coerceIn(-25f, 25f)

        // Apply rotation transform around tree base
        canvas.withRotation(totalRotationDeg, x, groundY) {
            for (seg in 0 until treeSegments) {
                val segBaseY = groundY - seg * segmentHeight * 0.7f
                val segTipY = segBaseY - segmentHeight

                // Width decreases with each segment (tapers towards top)
                val widthMultiplier = 1.0f - (seg.toFloat() / treeSegments * 0.6f)
                val segWidth = baseTreeWidth * widthMultiplier

                val baseHeightRatio = seg.toFloat() / treeSegments
                val tipHeightRatio = (seg + 1).toFloat() / treeSegments

                // Phase varies up the tree for wave-like bending
                val basePhase = spatialPhase + baseHeightRatio * 0.4f
                val tipPhase = spatialPhase + tipHeightRatio * 0.4f

                // Multi-frequency oscillation for organic feel
                val baseWave = sin(timeSec * baseFreq + basePhase) * 0.3f +
                    sin(timeSec * baseFreq * 2.5f + basePhase) * 0.15f
                val tipWaveSeg = sin(timeSec * baseFreq + tipPhase) * 0.3f +
                    sin(timeSec * baseFreq * 2.5f + tipPhase) * 0.15f

                // Additional local sway within the rotated frame (creates curve)
                val localBaseSway = baseWave * windFactor * maxAmplitude * baseHeightRatio * 0.3f
                val localTipSway = tipWaveSeg * windFactor * maxAmplitude * tipHeightRatio * tipHeightRatio * 0.3f

                treePath.reset()
                treePath.moveTo(x + localTipSway, segTipY)
                treePath.lineTo(x - segWidth + localBaseSway, segBaseY)
                treePath.lineTo(x + segWidth + localBaseSway, segBaseY)
                treePath.close()

                canvas.drawPath(treePath, treePaint)

                // Sunlit flank: the half of the cone facing the light is a shade brighter
                val flankLight = sunlitHighlight(state) * kotlin.math.abs(state.lightDirX)
                if (flankLight > 0.01f) {
                    val side = if (state.lightDirX >= 0f) 1f else -1f
                    treePath.reset()
                    treePath.moveTo(x + localTipSway, segTipY)
                    treePath.lineTo(x + side * segWidth + localBaseSway, segBaseY)
                    treePath.lineTo(x + localBaseSway, segBaseY)
                    treePath.close()
                    crownPaint.color = RenderConstants.lerpColor(treePaint.color, SUNLIT_FOLIAGE, flankLight)
                    crownPaint.alpha = 255
                    canvas.drawPath(treePath, crownPaint)
                }

                // Snow on the tip and along the bough tiers, so green needles show between the layers
                val needleSnow = treeSnowAmount()
                if (needleSnow > 0f) {
                    val snow = needleSnow.coerceIn(0.01f, 0.8f)
                    val swayDelta = localBaseSway - localTipSway

                    // Tip cap with a drooping lower rim
                    val capF = 0.15f + 0.3f * snow
                    val capY = segTipY + segmentHeight * capF
                    val capX = x + localTipSway + swayDelta * capF
                    val capHalf = segWidth * capF * 1.05f
                    treeSnowPath.reset()
                    treeSnowPath.moveTo(x + localTipSway, segTipY)
                    treeSnowPath.lineTo(capX - capHalf, capY)
                    treeSnowPath.quadTo(capX, capY + segmentHeight * 0.08f * snow, capX + capHalf, capY)
                    treeSnowPath.close()
                    canvas.drawPath(treeSnowPath, treeSnowPaint)

                    // Shelves on the boughs below: arched on top, sagging underneath
                    val shelfThickness = segmentHeight * (0.02f + 0.05f * snow)
                    for (tier in 1..3) {
                        val f = capF + (0.92f - capF) * tier / 3f
                        val shelfY = segTipY + segmentHeight * f
                        val shelfX = x + localTipSway + swayDelta * f
                        val half = segWidth * f * 0.97f
                        treeSnowPath.reset()
                        treeSnowPath.moveTo(shelfX - half, shelfY)
                        treeSnowPath.quadTo(shelfX, shelfY - shelfThickness * 1.4f, shelfX + half, shelfY)
                        treeSnowPath.quadTo(shelfX, shelfY + shelfThickness * 1.2f, shelfX - half, shelfY)
                        treeSnowPath.close()
                        canvas.drawPath(treeSnowPath, treeSnowPaint)
                    }
                }
            }

            // Draw pinecones hanging from branches: visible in autumn + winter, hidden under heavy snow
            val pineconeAlpha = (getSeasonWeight("autumn") + getSeasonWeight("winter")).coerceIn(0f, 1f) *
                (1f - treeSnowAmount() * 1.2f).coerceIn(0f, 1f)
            if (pineconeAlpha > 0.01f) {
                pineconesByTree[treeIndex]?.let { cones ->
                    val coneColor = barkColor(PINECONE_COLOR)
                    for (cone in cones) {
                        val segIndex = (cone.yRatio * treeSegments).toInt().coerceIn(0, treeSegments - 1)
                        val segBaseY = groundY - segIndex * segmentHeight * 0.7f
                        val widthMultiplier = 1.0f - (segIndex.toFloat() / treeSegments * 0.6f)
                        val segWidth = baseTreeWidth * widthMultiplier

                        val coneX = x + cone.xOffset * segWidth * 2f
                        val coneY = segBaseY - segmentHeight * 0.3f
                        // Qualified: inside this Canvas-receiver lambda a bare `height` would be Canvas.height.
                        val coneSize = cone.size * this@TreeRenderer.height * (0.5f + pineconeAlpha * 0.5f)

                        // Brown pinecone
                        pineconePaint.color = coneColor
                        pineconePaint.alpha = (255 * pineconeAlpha).toInt()
                        // Teardrop shape: oval body
                        canvas.drawOval(
                            coneX - coneSize * 0.4f, coneY - coneSize,
                            coneX + coneSize * 0.4f, coneY + coneSize * 0.3f,
                            pineconePaint)
                    }
                    pineconePaint.alpha = 255
                }
            }
        }
    }

    /**
     * Draw a deciduous (leaf) tree: trunk + round crown in spring-autumn, bare branches in winter.
     * Crown fades out in late autumn and regrows in spring using season transition weights.
     */
    private fun drawLeafTree(canvas: Canvas, tree: TreeData, state: SimulationState) {
        val x = cachedTransform.worldToScreenX(tree.worldX)
        val groundY = tree.yRatio * height

        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val trunkWidth = totalHeight * 0.06f * treeWidthScale
        val crownRadius = totalHeight * 0.32f
        val crownCenterY = groundY - totalHeight * 0.65f

        val objectHalfWidth = crownRadius * 1.8f
        if (!ViewportCulling.isVisibleHorizontally(
                x = x,
                objectHalfWidth = objectHalfWidth,
                screenWidth = screenWidth,
                height = height,
                extraMargin = totalHeight * 0.2f
            )) {
            return
        }

        // Wind sway (shared with needle trees)
        val baseWindStrength = state.windStrength.coerceIn(0f, 1f)
        val gustIntensity = state.windGust
        val windMultiplier = state.windMultiplier
        val swaySpeed = state.treeSwaySpeed
        val effectiveWind = (baseWindStrength + gustIntensity * 0.5f).coerceIn(0f, 1f) * windMultiplier
        val timeSec = state.timeSeconds
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * swaySpeed
        val normalizedPos = (tree.worldX / RenderConstants.REFERENCE_EXTENDED_WIDTH) + 0.5f
        val spatialPhase = normalizedPos * kotlin.math.PI.toFloat() * 1.0f
        val waveSpeed = 1.2f + baseWindStrength * 1.0f
        val gustWavePhase = timeSec * waveSpeed - spatialPhase
        val gustWave = (sin(gustWavePhase) * 0.5f + 0.5f)
        val secondaryWave = (sin(gustWavePhase * 0.6f + 1.5f) * 0.3f + 0.5f)
        val combinedWave = gustWave * 0.7f + secondaryWave * 0.3f
        val localGust = gustIntensity * combinedWave
        val windX = state.getWindDirX()
        val gustPush = localGust * localGust * 1.5f * windX

        val ambientFactor = swaySpeed * 0.4f
        val windFactor = ambientFactor + effectiveWind * 0.6f
        val treeWave = sin(timeSec * baseFreq + spatialPhase) * 0.5f +
            sin(timeSec * baseFreq * 0.4f + spatialPhase) * 0.3f
        val baseRotationDeg = treeWave * windFactor * 10f
        val gustRotationDeg = gustPush * RenderConstants.GUST_LEAN_FACTOR * 6f
        val totalRotationDeg = (baseRotationDeg + gustRotationDeg).coerceIn(-20f, 20f)

        // Shadow (same shared model as needle trees)
        val shadowStrength = if (state.treeShadowsEnabled) state.getEffectiveShadowStrength() else 0f
        if (shadowStrength > 0.01f) {
            GroundShadow.draw(
                canvas, shadowPaint, state, x, groundY, totalHeight, crownRadius * 2f,
                0f, groundClipPath
            )
        }

        canvas.withRotation(totalRotationDeg, x, groundY) {
            // Season weights for leaf behavior
            val winterWeight = getSeasonWeight("winter")
            // Crown visibility: full in summer/autumn, fades in late autumn -> winter, regrows spring
            val crownScale = (1f - winterWeight * 0.95f).coerceIn(0.05f, 1f)

            // Bark follows the same haze, overcast and night light as the foliage
            trunkPaint.color = barkColor(BARK_COLOR)
            trunkPaint.strokeWidth = trunkWidth
            trunkPaint.style = Paint.Style.STROKE

            // Draw trunk — extend higher when bare so branch attachment looks natural
            val trunkTopRatio = if (crownScale < 0.3f) 0.82f else 0.55f
            canvas.drawLine(x, groundY, x, groundY - totalHeight * trunkTopRatio, trunkPaint)

            // Draw bare branches when crown is partially or fully absent (winter/transition)
            if (crownScale < 0.8f) {
                val branchAlpha = ((1f - crownScale) * 255).toInt().coerceIn(0, 255)
                trunkPaint.strokeCap = Paint.Cap.ROUND
                for (branch in tree.branches) {
                    trunkPaint.strokeWidth = trunkWidth * branch.thickness
                    trunkPaint.alpha = branchAlpha
                    canvas.drawLine(
                        x + branch.startX * totalHeight, groundY - branch.startY * totalHeight,
                        x + branch.endX * totalHeight, groundY - branch.endY * totalHeight,
                        trunkPaint
                    )
                }
                trunkPaint.alpha = 255
                trunkPaint.strokeCap = Paint.Cap.BUTT

                // Snow lying on the branches, thicker on flat ones than on steep ones
                val snowAmount = treeSnowAmount()
                if (snowAmount > 0f) {
                    treeSnowStrokePaint.color = snowColor
                    treeSnowStrokePaint.alpha = branchAlpha
                    for (branch in tree.branches) {
                        val dx = branch.endX - branch.startX
                        val dy = branch.endY - branch.startY
                        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
                        val flatness = kotlin.math.abs(dx) / len
                        val branchWidth = trunkWidth * branch.thickness
                        val snowWidth = branchWidth * (0.3f + 0.7f * snowAmount.coerceAtMost(0.8f)) * (0.3f + 0.7f * flatness)
                        val lift = (branchWidth + snowWidth) * 0.4f
                        treeSnowStrokePaint.strokeWidth = snowWidth
                        canvas.drawLine(
                            x + branch.startX * totalHeight, groundY - branch.startY * totalHeight - lift,
                            x + branch.endX * totalHeight, groundY - branch.endY * totalHeight - lift,
                            treeSnowStrokePaint
                        )
                    }
                }
            }

            // Draw crown circles: scaled by crownScale (shrinks into winter, grows in spring)
            // Skip when too small to avoid floating green dots above bare trees
            if (crownScale > 0.15f) {
                // Fade crown alpha smoothly in the 0.15–0.4 range so it doesn't pop in/out
                val crownAlpha = if (crownScale < 0.4f)
                    ((crownScale - 0.15f) / 0.25f * 255f).toInt().coerceIn(0, 255)
                else 255
                // Sunlit side of each puff gives the crown volume; fades out in diffuse light
                val highlight = sunlitHighlight(state)
                val lightOffX = state.lightDirX * 0.18f
                val lightOffY = -0.1f - 0.1f * state.lightElevation01
                val radiusX = crownRadius * crownScale * tree.crownAspectX
                val radiusY = crownRadius * crownScale * tree.crownAspectY
                val crownX = x + tree.crownLean * crownRadius * crownScale
                val crownY = crownCenterY - crownRadius * crownScale * (tree.crownAspectY - 1f) * 0.5f
                for (circle in tree.crownCircles) {
                    val cx = crownX + circle.xOffset * radiusX
                    val cy = crownY + circle.yOffset * radiusY
                    val rx = radiusX * circle.radiusScale
                    val ry = radiusY * circle.radiusScale

                    crownPaint.color = darkenColor(treePaint.color, circle.darkenFactor)
                    crownPaint.alpha = crownAlpha
                    canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, crownPaint)
                }
                // Highlights after all puffs: one lit mass, shade only along the crown's far rim
                if (highlight > 0.01f) {
                    for (circle in tree.crownCircles) {
                        val cx = crownX + circle.xOffset * radiusX + lightOffX * radiusX * circle.radiusScale
                        val cy = crownY + circle.yOffset * radiusY + lightOffY * radiusY * circle.radiusScale
                        val rx = radiusX * circle.radiusScale * 0.72f
                        val ry = radiusY * circle.radiusScale * 0.72f
                        crownPaint.color = RenderConstants.lerpColor(
                            darkenColor(treePaint.color, circle.darkenFactor), SUNLIT_FOLIAGE, highlight
                        )
                        crownPaint.alpha = crownAlpha
                        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, crownPaint)
                    }
                }
                crownPaint.alpha = 255

                // Snow on crown
                val crownSnow = treeSnowAmount()
                if (crownSnow > 0f && crownScale > 0.3f) {
                    val snowR = radiusX * crownSnow.coerceIn(0.01f, 0.5f)
                    canvas.drawOval(
                        crownX - snowR, crownY - radiusY * 0.6f,
                        crownX + snowR, crownY - radiusY * 0.3f,
                        treeSnowPaint
                    )
                }
            }
        }
    }

    /** Blend of sunlit foliage toward warm light: direct sun only, none by moonlight or under clouds. */
    private fun sunlitHighlight(state: SimulationState): Float {
        val dayFactor = ((state.getLightLevel() - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return state.getEffectiveShadowStrength() * CROWN_HIGHLIGHT * dayFactor
    }

    /** Snow on trees (0 = none). Weather-driven when enabled, otherwise calendar winter coverage. */
    private fun treeSnowAmount(): Float = when {
        useWeatherSnow -> if (dynamicSnowLevel > 0.005f) dynamicSnowLevel else 0f
        winterSnowEnabled && cachedSeason == "winter" ->
            (winterSnowCoverage / 100f) * (if (cachedPrevSeason != "winter") cachedSeasonBlend else 1f)
        cachedPrevSeason == "winter" && cachedSeasonBlend < 1f ->
            (winterSnowCoverage / 100f) * (1f - cachedSeasonBlend)
        else -> 0f
    }

    // ========================================================================
    // Settings
    // ========================================================================

    /**
     * Reload tree-related settings from immutable settings snapshot.
     * Must be called whenever preferences change (and on init).
     * @return true if any generation-affecting setting changed (requires re-generation)
     */
    fun reloadSettings(settings: SimulationSettings): Boolean {
        // Snapshot previous generation-affecting values
        val prevDensity = treeDensity
        val prevRatio = leafTreeRatio
        val prevSpread = treeSpread
        val prevSparsity = treeSparsity
        val prevSizeRandom = treeSizeRandomness
        val prevColorVar = treeColorVariation
        val prevPinecones = pineconesEnabled
        val prevPineconeDens = pineconeDensity

        // Tree density: 0-200 percentage (50 = normal, 200 = dense forest)
        treeDensity = settings.getInt("pref_tree_density", PreferenceDefaults.TREE_DENSITY)

        // Leaf tree ratio: percentage of deciduous vs needle/conifer trees
        leafTreeRatio = settings.getInt("pref_leaf_tree_ratio", PreferenceDefaults.LEAF_TREE_RATIO)

        // Tree spread: how far along hills trees spread (0=peaks only, 100=full hillside)
        treeSpread = settings.getInt("pref_tree_spread", PreferenceDefaults.TREE_SPREAD) / 100f

        // Tree sparsity: gap probability between trees (0=dense, 100=sparse)
        treeSparsity = settings.getInt("pref_tree_sparsity", PreferenceDefaults.TREE_SPARSITY) / 100f

        // Tree size randomness: variation in tree sizes (0=uniform, 100=very varied)
        treeSizeRandomness = settings.getInt("pref_tree_size_randomness", PreferenceDefaults.TREE_SIZE_RANDOMNESS) / 100f

        val sizeVal = settings.getInt("pref_tree_size", PreferenceDefaults.TREE_SIZE)
        treeSizeScale = sizeVal / 200f

        val widthVal = settings.getInt("pref_tree_width", PreferenceDefaults.TREE_WIDTH)
        treeWidthScale = widthVal / 100f

        treeColorVariation = settings.getInt("pref_tree_color_variation", PreferenceDefaults.TREE_COLOR_VARIATION) / 100f
        treeSegments = settings.getInt("pref_tree_segments", PreferenceDefaults.TREE_SEGMENTS).coerceIn(1, 5)

        // Pinecone settings
        pineconesEnabled = settings.getBoolean("pref_pinecones_enabled", PreferenceDefaults.PINECONES_ENABLED)
        pineconeDensity = settings.getInt("pref_pinecone_density", PreferenceDefaults.PINECONE_DENSITY)

        // Snow settings (also read by TerrainLayer for hills, both need them)
        winterSnowEnabled = settings.getBoolean("pref_winter_snow_hills", PreferenceDefaults.WINTER_SNOW_HILLS_ENABLED)
        winterSnowCoverage = settings.getInt("pref_winter_snow_coverage", PreferenceDefaults.WINTER_SNOW_COVERAGE)
        useWeatherSnow = settings.getBoolean("pref_weather_snow_enabled", PreferenceDefaults.WEATHER_SNOW_ENABLED)

        return prevDensity != treeDensity ||
            prevRatio != leafTreeRatio ||
            kotlin.math.abs(prevSpread - treeSpread) > 0.001f ||
            kotlin.math.abs(prevSparsity - treeSparsity) > 0.001f ||
            kotlin.math.abs(prevSizeRandom - treeSizeRandomness) > 0.001f ||
            kotlin.math.abs(prevColorVar - treeColorVariation) > 0.001f ||
            prevPinecones != pineconesEnabled ||
            prevPineconeDens != pineconeDensity
    }
}
