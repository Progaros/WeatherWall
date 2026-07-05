package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
     * Pre-calculated bare branch line for winter deciduous trees.
     */
    data class BranchLine(
        val startXOffset: Float,
        val startYRatio: Float,  // 0=trunk top, 1=trunk bottom
        val endXOffset: Float,
        val endYRatio: Float
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
        val branches: List<BranchLine> = emptyList()         // Winter bare branches for LEAF trees
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
    private var winterSnowCoverage = 50   // 0-100 how much of hill tops are covered
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

    // Frame-global ambient (time-of-day) tint, set once per frame by TerrainLayer.
    // Avoids recomputing RenderConstants.getAmbientColor() for every drawn tree.
    @Volatile var cachedAmbientColor: Int = 0

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

                // Density gating: deterministic skip based on density setting
                if (treeRandom.nextFloat() > densityFraction) continue

                // World-space position: cell center + jitter
                val worldX = cellIndex * treeCellSize +
                    (treeRandom.nextFloat() - 0.5f) * treeCellSize * 0.8f

                // Enforce minimum spacing to prevent visual clustering
                if (worldX - lastPlacedWorldX < minTreeSpacingWorld) continue

                // Skip tree if it falls within a clearing (Lichtung)
                val inClearing = clearings.any { (start, end) -> worldX in start..end }
                if (inClearing) continue

                // Additional random skip for some extra variation
                if (treeRandom.nextFloat() < treeSparsity * 0.3f) continue

                // Get the hill surface Y at this world-space X position
                val groundY = getHillY(worldX, hill)

                // Tree spread controls vertical distribution DOWN from the hill line
                val maxVerticalOffset = treeSpread * 0.15f
                val verticalOffset = treeRandom.nextFloat() * maxVerticalOffset
                val yRatio = groundY + verticalOffset

                // Normalize depth to 0-1 range for scale
                val hillsRange = RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN
                val normalizedDepth = ((depthFactor - RenderConstants.PARALLAX_HILLS_MIN) / hillsRange).coerceIn(0f, 1f)

                // Tree scale: foreground trees are larger
                val baseScale = RenderConstants.TREE_SCALE_MIN + normalizedDepth * RenderConstants.TREE_SCALE_RANGE
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

                // Determine tree type based on ratio setting
                val isLeaf = (treeRandom.nextInt(100) < leafTreeRatio)
                val type = if (isLeaf) TreeType.LEAF else TreeType.NEEDLE

                // Generate crown circles for leaf trees, branches for winter bare look
                val crown = if (isLeaf) generateCrownCircles(treeRandom) else emptyList()
                val branches = if (isLeaf) generateBranches(treeRandom) else emptyList()

                // Stable identity: derived from hill and cell, not list position
                val stableId = hillIndex * 100003 + cellIndex

                newTrees.add(TreeData(worldX, yRatio, scale, depthFactor, dayColor, stableId,
                    type, crown, branches))
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
        circles.add(CrownCircle(0f, 0f, 0.8f + random.nextFloat() * 0.2f, 1.0f))

        for (i in 1 until count) {
            val angle = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val dist = 0.15f + random.nextFloat() * 0.4f
            val xOff = kotlin.math.cos(angle.toDouble()).toFloat() * dist
            val yOff = kotlin.math.sin(angle.toDouble()).toFloat() * dist * 0.7f
            val radius = 0.4f + random.nextFloat() * 0.45f
            val darken = 0.85f + random.nextFloat() * 0.15f
            circles.add(CrownCircle(xOff, yOff, radius, darken))
        }
        return circles.sortedByDescending { it.yOffset }
    }

    /**
     * Generate 6-10 bare branches forming a rounded crown silhouette for winter.
     * Includes primary branches evenly distributed with sub-branches and twigs.
     */
    private fun generateBranches(random: Random): List<BranchLine> {
        val count = 6 + random.nextInt(5)  // 6-10 main branches
        val branches = ArrayList<BranchLine>(count * 3)

        for (i in 0 until count) {
            // Distribute evenly along the upper trunk with some jitter
            val baseRatio = i.toFloat() / (count - 1).coerceAtLeast(1)
            val startY = 0.10f + baseRatio * 0.65f + (random.nextFloat() - 0.5f) * 0.06f

            // Alternate sides so branches balance left/right
            val side = if (i % 2 == 0) 1f else -1f
            val sideStrength = 0.85f + random.nextFloat() * 0.30f

            // Upper branches spread wider (crown shape); lower ones tighter
            val heightFactor = 1f - baseRatio  // 1 at top, 0 at bottom
            val spread = 0.20f + random.nextFloat() * 0.30f + heightFactor * 0.20f
            val endX = side * sideStrength * spread

            // Upper branches barely droop or angle upward; lower branches droop more
            val droop = 0.04f + random.nextFloat() * 0.10f - heightFactor * 0.08f
            val endY = startY - droop
            branches.add(BranchLine(0f, startY, endX, endY))

            // Sub-branch 1 (main fork, 75% chance)
            if (random.nextFloat() < 0.75f) {
                val forkT = 0.45f + random.nextFloat() * 0.30f
                val forkX = endX * forkT
                val forkY = startY + (endY - startY) * forkT
                val subEndX = forkX + side * sideStrength * (0.08f + random.nextFloat() * 0.18f)
                val subEndY = forkY - 0.03f - random.nextFloat() * 0.08f
                branches.add(BranchLine(forkX, forkY, subEndX, subEndY))

                // Twig off sub-branch (30%)
                if (random.nextFloat() < 0.30f) {
                    val twigT = 0.5f + random.nextFloat() * 0.3f
                    val twigX = forkX + (subEndX - forkX) * twigT
                    val twigY = forkY + (subEndY - forkY) * twigT
                    val twigDir = if (random.nextBoolean()) 1f else -1f
                    branches.add(BranchLine(twigX, twigY,
                        twigX + twigDir * (0.04f + random.nextFloat() * 0.08f),
                        twigY - 0.02f - random.nextFloat() * 0.04f))
                }
            }

            // Sub-branch 2 (opposite fork, 40% chance)
            if (random.nextFloat() < 0.40f) {
                val forkT = 0.30f + random.nextFloat() * 0.25f
                val forkX = endX * forkT
                val forkY = startY + (endY - startY) * forkT
                val subEndX = forkX - side * 0.5f * (0.08f + random.nextFloat() * 0.12f)
                val subEndY = forkY - 0.02f - random.nextFloat() * 0.06f
                branches.add(BranchLine(forkX, forkY, subEndX, subEndY))
            }
        }
        return branches
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
     */
    fun drawTree(canvas: Canvas, tree: TreeData, state: SimulationState, treeIndex: Int, treeColor: Int) {
        treePaint.color = treeColor

        if (tree.treeType == TreeType.LEAF) {
            drawLeafTree(canvas, tree, state, treeIndex)
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
        val shadowDx = state.getShadowDx(tree.scale)

        // Draw shadow first (if shadows enabled)
        if (shadowStrength > 0.01f) {
            val shadowAlpha = (20f + 130f * shadowStrength).toInt().coerceIn(20, 150)
            shadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)

            // Calculate shadow skew direction based on light angle
            val skewDirection = if (shadowDx > 0) 1f else -1f
            val skewFactor = kotlin.math.abs(shadowDx).coerceIn(0.2f, 2.0f)

            // Calculate tree tip sway for shadow
            val tipPhase = spatialPhase + 0.4f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.5f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f
            val ambientFactor = swaySpeed * 0.4f
            val shadowWindFactor = ambientFactor + effectiveWind * 0.6f
            val shadowGustLean = gustPush * RenderConstants.GUST_LEAN_FACTOR
            val tipSway = tipWave * shadowWindFactor * maxAmplitude * 0.5f + shadowGustLean * totalHeight * 0.5f

            // Shadow as a thin wedge lying on the ground
            val shadowLength = totalHeight * skewFactor * 0.7f
            val shadowBaseWidth = baseTreeWidth * 0.35f
            val shadowHeight = totalHeight * 0.025f  // Thin ground-hugging wedge

            val shadowTipX = x + shadowLength * skewDirection + tipSway
            val shadowTipY = groundY + 1f
            val shadowBaseX = x
            val shadowBaseY = groundY + shadowHeight

            treePath.reset()
            treePath.moveTo(shadowTipX, shadowTipY)
            treePath.lineTo(shadowBaseX - shadowBaseWidth, shadowBaseY)
            treePath.lineTo(shadowBaseX + shadowBaseWidth, shadowBaseY)
            treePath.close()

            // Clip shadow to not extend beyond ground level
            canvas.save()
            canvas.clipRect(0f, 0f, screenWidth + screenWidth, groundY + 10f)
            canvas.drawPath(treePath, shadowPaint)
            canvas.restore()
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
        canvas.save()
        canvas.rotate(totalRotationDeg, x, groundY)

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

            // Draw snow on tree if snow is present (weather-driven or calendar)
            // When useWeatherSnow is true, use ONLY weather snow level to avoid
            // falling back to calendar-based snow (which causes sudden jumps)
            val treeSnowActive = if (useWeatherSnow) {
                dynamicSnowLevel > 0.005f
            } else {
                (winterSnowEnabled && cachedSeason == "winter") ||
                (cachedPrevSeason == "winter" && cachedSeasonBlend < 1f)
            }
            if (treeSnowActive) {
                val treeSnowAmount = if (useWeatherSnow) {
                    dynamicSnowLevel
                } else if (cachedSeason == "winter") {
                    (winterSnowCoverage / 100f) * (if (cachedPrevSeason != "winter") cachedSeasonBlend else 1f)
                } else {
                    (winterSnowCoverage / 100f) * (1f - cachedSeasonBlend)
                }
                val snowCoverage = treeSnowAmount.coerceIn(0.01f, 0.8f)
                val segmentSnowFactor = (1f - baseHeightRatio) * 0.3f + 0.5f
                val snowHeight = segmentHeight * snowCoverage * segmentSnowFactor
                val snowTipY = segTipY
                val snowBaseY = segTipY + snowHeight
                val snowBaseWidth = segWidth * (snowHeight / segmentHeight).coerceIn(0.2f, 0.6f)

                treeSnowPath.reset()
                treeSnowPath.moveTo(x + localTipSway, snowTipY)
                treeSnowPath.lineTo(x - snowBaseWidth + localTipSway * 0.7f, snowBaseY)
                treeSnowPath.lineTo(x + snowBaseWidth + localTipSway * 0.7f, snowBaseY)
                treeSnowPath.close()

                canvas.drawPath(treeSnowPath, treeSnowPaint)
            }
        }

        // Draw pinecones hanging from branches: visible in autumn + winter
        val pineconeAlpha = (getSeasonWeight("autumn") + getSeasonWeight("winter")).coerceIn(0f, 1f)
        if (pineconeAlpha > 0.01f) {
            pineconesByTree[treeIndex]?.let { cones ->
                val ambientColor = cachedAmbientColor
                for (cone in cones) {
                    val segIndex = (cone.yRatio * treeSegments).toInt().coerceIn(0, treeSegments - 1)
                    val segBaseY = groundY - segIndex * segmentHeight * 0.7f
                    val widthMultiplier = 1.0f - (segIndex.toFloat() / treeSegments * 0.6f)
                    val segWidth = baseTreeWidth * widthMultiplier

                    val coneX = x + cone.xOffset * segWidth * 2f
                    val coneY = segBaseY - segmentHeight * 0.3f
                    val coneSize = cone.size * height * (0.5f + pineconeAlpha * 0.5f)

                    // Brown pinecone
                    val coneColor = RenderConstants.blendWithAmbient(
                        Color.rgb(120, 80, 30), ambientColor, 0.2f)
                    pineconePaint.color = coneColor
                    pineconePaint.alpha = (Color.alpha(coneColor) * pineconeAlpha).toInt()
                    // Teardrop shape: oval body
                    canvas.drawOval(
                        coneX - coneSize * 0.4f, coneY - coneSize,
                        coneX + coneSize * 0.4f, coneY + coneSize * 0.3f,
                        pineconePaint)
                }
                pineconePaint.alpha = 255
            }
        }

        canvas.restore()
    }

    /**
     * Draw a deciduous (leaf) tree: trunk + round crown in spring-autumn, bare branches in winter.
     * Crown fades out in late autumn and regrows in spring using season transition weights.
     */
    private fun drawLeafTree(canvas: Canvas, tree: TreeData, state: SimulationState, @Suppress("UNUSED_PARAMETER") treeIndex: Int) {
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

        // Shadow (same style as needle trees)
        val shadowStrength = if (state.treeShadowsEnabled) state.getEffectiveShadowStrength() else 0f
        val shadowDx = state.getShadowDx(tree.scale)
        if (shadowStrength > 0.01f) {
            val shadowAlpha = (20f + 130f * shadowStrength).toInt().coerceIn(20, 150)
            shadowPaint.color = Color.argb(shadowAlpha, 0, 0, 0)
            val skewDirection = if (shadowDx > 0) 1f else -1f
            val skewFactor = kotlin.math.abs(shadowDx).coerceIn(0.2f, 2.0f)
            // Thin ground-hugging shadow projected to one side
            val shadowLength = crownRadius * skewFactor * 1.2f
            val shadowHeight = totalHeight * 0.025f  // Very thin ellipse
            val shadowCenterX = x + shadowLength * skewDirection * 0.5f
            canvas.drawOval(
                shadowCenterX - shadowLength * 0.5f,
                groundY - shadowHeight * 0.5f,
                shadowCenterX + shadowLength * 0.5f,
                groundY + shadowHeight * 0.5f,
                shadowPaint
            )
        }

        canvas.save()
        canvas.rotate(totalRotationDeg, x, groundY)

        // Season weights for leaf behavior
        val winterWeight = getSeasonWeight("winter")
        // Crown visibility: full in summer/autumn, fades in late autumn -> winter, regrows spring
        val crownScale = (1f - winterWeight * 0.95f).coerceIn(0.05f, 1f)

        // Trunk color (brown)
        val trunkColor = Color.rgb(90, 65, 40)
        val ambientColor = cachedAmbientColor
        trunkPaint.color = RenderConstants.blendWithAmbient(trunkColor, ambientColor, 0.2f)
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
                val bStartX = x + branch.startXOffset * crownRadius
                val bStartY = groundY - totalHeight * (0.20f + branch.startYRatio * 0.62f)
                val bEndX = x + branch.endXOffset * crownRadius * 1.8f
                val bEndY = groundY - totalHeight * (0.20f + branch.endYRatio * 0.62f)
                // Main branches thicker (startXOffset==0), sub-branches thinner
                val isSub = branch.startXOffset != 0f
                trunkPaint.strokeWidth = trunkWidth * (if (isSub) 0.25f else 0.42f)
                trunkPaint.alpha = branchAlpha
                canvas.drawLine(bStartX, bStartY, bEndX, bEndY, trunkPaint)
            }
            trunkPaint.alpha = 255
            trunkPaint.strokeCap = Paint.Cap.BUTT
        }

        // Draw crown circles: scaled by crownScale (shrinks into winter, grows in spring)
        // Skip when too small to avoid floating green dots above bare trees
        if (crownScale > 0.15f) {
            // Fade crown alpha smoothly in the 0.15–0.4 range so it doesn't pop in/out
            val crownAlpha = if (crownScale < 0.4f)
                ((crownScale - 0.15f) / 0.25f * 255f).toInt().coerceIn(0, 255)
            else 255
            for (circle in tree.crownCircles) {
                val cx = x + circle.xOffset * crownRadius * crownScale
                val cy = crownCenterY + circle.yOffset * crownRadius * crownScale
                val r = crownRadius * circle.radiusScale * crownScale

                crownPaint.color = darkenColor(treePaint.color, circle.darkenFactor)
                crownPaint.alpha = crownAlpha
                canvas.drawCircle(cx, cy, r, crownPaint)
            }
            crownPaint.alpha = 255

            // Snow on crown
            // When useWeatherSnow is true, use ONLY weather snow level (no calendar fallback)
            val treeSnowActive = if (useWeatherSnow) {
                dynamicSnowLevel > 0.005f
            } else {
                (winterSnowEnabled && cachedSeason == "winter") ||
                (cachedPrevSeason == "winter" && cachedSeasonBlend < 1f)
            }
            if (treeSnowActive && crownScale > 0.3f) {
                val treeSnowAmount = if (useWeatherSnow) {
                    dynamicSnowLevel
                } else if (cachedSeason == "winter") {
                    (winterSnowCoverage / 100f) * (if (cachedPrevSeason != "winter") cachedSeasonBlend else 1f)
                } else {
                    (winterSnowCoverage / 100f) * (1f - cachedSeasonBlend)
                }
                val snowR = crownRadius * crownScale * treeSnowAmount.coerceIn(0.01f, 0.5f)
                canvas.drawOval(
                    x - snowR, crownCenterY - crownRadius * crownScale * 0.6f,
                    x + snowR, crownCenterY - crownRadius * crownScale * 0.3f,
                    treeSnowPaint
                )
            }
        }

        canvas.restore()
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

        val sizeVal = settings.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f

        val widthVal = settings.getInt("pref_tree_width", 100)
        treeWidthScale = widthVal / 100f

        treeColorVariation = settings.getInt("pref_tree_color_variation", PreferenceDefaults.TREE_COLOR_VARIATION) / 100f
        treeSegments = settings.getInt("pref_tree_segments", 1).coerceIn(1, 5)

        // Pinecone settings
        pineconesEnabled = settings.getBoolean("pref_pinecones_enabled", PreferenceDefaults.PINECONES_ENABLED)
        pineconeDensity = settings.getInt("pref_pinecone_density", PreferenceDefaults.PINECONE_DENSITY)

        // Snow settings (also read by TerrainLayer for hills, both need them)
        winterSnowEnabled = settings.getBoolean("pref_winter_snow_hills", true)
        winterSnowCoverage = settings.getInt("pref_winter_snow_coverage", 50)
        useWeatherSnow = settings.getBoolean("pref_weather_snow_enabled", true)

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
