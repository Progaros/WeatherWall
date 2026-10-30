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
 * Handles bush generation, placement on hills, and rendering with wind-based animation.
 * Bushes are small, round/oval plant shapes placed along hill surfaces, optionally bearing berries.
 */
class BushRenderer {

    // ---- Data classes ----

    companion object {
        /** World-space cell size for bush placement. ~24 world units per cell. */
        const val BUSH_CELL_SIZE = 24f
    }

    /**
     * Pre-calculated circle arrangement for organic bush shape.
     */
    data class BushCircle(
        val xOffset: Float,      // relative to bush center (-1..1)
        val yOffset: Float,      // relative to bush center (-1..1)
        val radiusScale: Float,  // 0.3-1.0 relative to bush base size
        val darkenFactor: Float  // 0.8-1.0 color variation
    )

    /**
     * Bush data — small round/oval bushes placed on hills.
     */
    data class BushData(
        val worldX: Float,    // World-space x coordinate (0 = center)
        val yRatio: Float,    // Ground Y position
        val scale: Float,
        val depthFactor: Float,
        val dayColor: Int,
        val index: Int = -1,
        val circles: List<BushCircle> = emptyList()  // Precalculated shape
    )

    /**
     * Pre-generated berry positions (per bush index).
     */
    data class BerryData(
        val xOffset: Float,
        val yRatio: Float,
        val color: Int,
        val size: Float
    )

    // ---- Pre-allocated Paint objects (zero-allocation rendering) ----

    private val bushPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val shadowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val berryPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val snowPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val snowRect = android.graphics.RectF()

    /** Ground snow (0-1) and its ambient-tinted colour, set by TerrainLayer per frame / hill band. */
    var snowCover: Float = 0f
    var snowColor: Int = Color.WHITE
    var dullness: Float = 0f

    // ---- Settings (loaded from SharedPreferences) ----

    private var bushesEnabled = true
    private var bushDensity = 30      // 0-100
    private var bushSize = 1.0f       // Scale factor
    private var berriesEnabled = true
    private var berryDensity = 30     // 0-100

    // ---- Thread-safe state (volatile + immutable lists) ----

    @Volatile private var bushes: List<BushData> = emptyList()

    /** Pre-grouped bushes by depth factor (built once during generation, avoids per-frame filter). */
    @Volatile var bushesByDepth: Map<Float, List<BushData>> = emptyMap()
        private set

    @Volatile private var berriesByBush: Map<Int, List<BerryData>> = emptyMap()

    // ---- Screen dimensions (stored from generateBushes) ----

    private var screenWidth = 0f
    private var height = 0f

    /**
     * Silhouette of the hill the bushes currently being drawn stand on, in the active canvas
     * space. Ground shadows are clipped to it so they cannot spill over the skyline.
     */
    var groundClipPath: Path? = null

    /** Cached world-to-screen transform, rebuilt on dimension change. */
    private var cachedTransform = WorldViewportTransform(1f, 1f)

    /**
     * Update render-time dimensions without regenerating bush positions.
     */
    fun updateDimensions(screenWidth: Float, height: Float) {
        this.screenWidth = screenWidth
        this.height = height
        cachedTransform = WorldViewportTransform(screenWidth, height)
    }

    // ---- Season state (set by TerrainLayer before each draw frame) ----

    @Volatile var cachedSeason: String = "summer"
    @Volatile var cachedSeasonBlend: Float = 1f   // 1 = fully in cached season
    @Volatile var cachedPrevSeason: String = "summer"

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
     * Blend foliage toward autumn colours based on weight.
     * Shifts green toward orange/red/golden tones per bush.
     */
    fun autumnTreeColor(baseColor: Int, weight: Float, bushIndex: Int): Int =
        VegetationUtil.autumnColor(baseColor, weight, bushIndex)

    // ========================================================================
    // Private helpers
    // ========================================================================

    private fun darkenColor(color: Int, factor: Float = 0.65f): Int = ColorMath.darkenColor(color, factor)

    /**
     * Evaluate hill Y at a world-space X position using noise.
     */
    private fun getHillY(worldX: Float, hill: TreeRenderer.HillInfo): Float =
        VegetationUtil.getHillY(worldX, hill)

    // ========================================================================
    // Generation methods
    // ========================================================================

    /**
     * Generate bushes placed along hills, similar to trees but smaller and rounder.
     *
     * @param hills Hill information (noise params, depth, height offset) for bush placement.
     * @param screenWidth Actual visible screen width in pixels.
     * @param height Screen height in pixels.
     * @param randomSeed Seed for deterministic placement.
     */
    fun generateBushes(
        hills: List<TreeRenderer.HillInfo>,
        screenWidth: Float,
        height: Float,
        randomSeed: Int
    ) {
        // Store dimensions for draw methods
        this.screenWidth = screenWidth
        this.height = height

        if (hills.isEmpty() || !bushesEnabled || bushDensity <= 0 || screenWidth <= 0) {
            bushes = emptyList()
            bushesByDepth = emptyMap()
            return
        }

        // Viewport-based generation: generate cells visible on screen + parallax padding.
        val visibleHalf = RenderConstants.getVisibleWorldHalf(screenWidth, height)

        val bushCellSize = BUSH_CELL_SIZE
        val indexRange = WorldViewportTransform.indexRangeForWorldRange(
            -visibleHalf, visibleHalf, bushCellSize
        )
        val densityFraction = (bushDensity / 100f).coerceIn(0f, 1f)
        if (densityFraction <= 0f) {
            bushes = emptyList()
            bushesByDepth = emptyMap()
            return
        }

        val newBushes = ArrayList<BushData>()

        for (hillIndex in hills.indices) {
            val hill = hills[hillIndex]
            val depthFactor = hill.depthFactor
            val nearness = RenderConstants.bandNearness(hillIndex, hills.size)

            for (cellIndex in indexRange) {
                // Per-bush deterministic seed (stable per cell index)
                val bushRandom = DeterministicLayout.random(randomSeed + 5000 + hillIndex, section = 500, index = cellIndex)

                val densityRoll = bushRandom.nextFloat()

                // World-space position: cell center + jitter
                val worldX = cellIndex * bushCellSize +
                    (bushRandom.nextFloat() - 0.5f) * bushCellSize * 0.8f

                // Mostly in and along forests, a few scattered across meadows
                val forest = VegetationUtil.forestMask(worldX, randomSeed, hillIndex)
                if (densityRoll > densityFraction * (0.3f + 1.2f * forest)) continue

                // Get hill Y at world-space position using noise
                val groundY = getHillY(worldX, hill)

                // Bushes sit on or slightly below the hill line
                val verticalOffset = bushRandom.nextFloat() * 0.03f
                val yRatio = groundY + verticalOffset

                // Bushes are smaller than trees, scale with depth
                val baseScale = 0.35f + nearness * 0.65f
                val sizeVariation = 0.7f + bushRandom.nextFloat() * 0.6f
                val scale = baseScale * sizeVariation * bushSize

                // Close to the tree green so bushes don't read as bright spots
                val varR = bushRandom.nextInt(20) - 10
                val varG = bushRandom.nextInt(20)
                val dayColor = Color.rgb(
                    (32 + varR).coerceIn(0, 255),
                    (68 + varG).coerceIn(0, 255),
                    32
                )

                newBushes.add(BushData(worldX, yRatio, scale, depthFactor, dayColor,
                    hillIndex * 100003 + cellIndex,
                    generateBushCircles(bushRandom)))
            }
        }

        newBushes.sortBy { it.depthFactor }

        val groupedByDepth = newBushes.groupBy { it.depthFactor }
            .mapValues { (_, b) -> b.sortedBy { it.yRatio } }

        // Atomic reference swap (volatile ensures visibility to render thread)
        bushes = newBushes
        bushesByDepth = groupedByDepth
    }

    /**
     * Generate berry decorations for bushes.
     * Must be called after [generateBushes].
     */
    fun generateBushDecorations(randomSeed: Int) {
        val currentBushes = bushes

        if (berriesEnabled && currentBushes.isNotEmpty()) {
            val berryColors = intArrayOf(
                Color.rgb(200, 30, 30),    // Red berries
                Color.rgb(60, 60, 180),    // Blueberries
                Color.rgb(180, 50, 100),   // Dark pink
                Color.rgb(100, 20, 60)     // Dark berry
            )
            val newBerries = mutableMapOf<Int, List<BerryData>>()
            val berryProbability = berryDensity / 100f

            for (bush in currentBushes) {
                val stableId = bush.index  // stable cell-based identity
                val bushRandom = DeterministicLayout.random(randomSeed + 8000, section = 510, index = stableId)
                if (bushRandom.nextFloat() > berryProbability) continue
                val bushBerries = mutableListOf<BerryData>()
                val numBerries = 1 + bushRandom.nextInt(3)  // 1-3 berries per bush (lowered from 2-5 to calm the scene)
                for (b in 0 until numBerries) {
                    bushBerries.add(BerryData(
                        xOffset = (bushRandom.nextFloat() - 0.5f) * 0.8f,
                        yRatio = 0.2f + bushRandom.nextFloat() * 0.6f,
                        color = berryColors[bushRandom.nextInt(berryColors.size)],
                        size = 0.002f + bushRandom.nextFloat() * 0.002f
                    ))
                }
                newBerries[stableId] = bushBerries
            }
            berriesByBush = newBerries
        } else {
            berriesByBush = emptyMap()
        }
    }

    /**
     * Generate a random arrangement of 4-7 overlapping circles for an organic bush shape.
     * Precalculated during generation for zero-allocation rendering.
     */
    private fun generateBushCircles(random: Random): List<BushCircle> {
        val count = 4 + random.nextInt(4)  // 4-7 circles
        val circles = ArrayList<BushCircle>(count)

        // Always start with a centered large base circle
        circles.add(BushCircle(0f, 0f, 0.85f + random.nextFloat() * 0.15f, 1.0f))

        for (i in 1 until count) {
            val angle = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val dist = 0.2f + random.nextFloat() * 0.5f  // offset from center
            val xOff = kotlin.math.cos(angle.toDouble()).toFloat() * dist
            val yOff = kotlin.math.sin(angle.toDouble()).toFloat() * dist * 0.7f  // flatten vertically
            val radius = 0.35f + random.nextFloat() * 0.55f
            val darken = 0.82f + random.nextFloat() * 0.18f
            circles.add(BushCircle(xOff, yOff, radius, darken))
        }

        // Sort bottom-to-top so upper circles draw on top
        return circles.sortedByDescending { it.yOffset }
    }

    // ========================================================================
    // Rendering methods
    // ========================================================================

    /**
     * Draw a bush as precalculated overlapping circles with optional berries.
     *
     * The canvas should already have parallax translation applied (via canvas.translate)
     * before calling this method.
     *
     * @param canvas The canvas to draw on (already translated for parallax).
     * @param bush The bush data to render.
     * @param state Current simulation state (wind, time, etc.).
     * @param ambientColor Pre-computed ambient tint color for the current time of day.
     * @param haze Band haze from [RenderConstants.bandHaze], shared with the hill and its trees.
     */
    fun drawBush(canvas: Canvas, bush: BushData, state: SimulationState, ambientColor: Int, haze: Float) {
        // World-space to screen projection via cached transform
        val x = cachedTransform.worldToScreenX(bush.worldX)
        val groundY = bush.yRatio * height

        // Bush size: smaller than trees
        val bushHeight = height * 0.025f * bush.scale * bushSize
        val bushWidth = bushHeight * (1.2f + (bush.index % 3) * 0.15f)  // Slightly varied width

        // Blend bush color with ambient and add autumn tint
        val autumnWeight = getSeasonWeight("autumn")
        val effectiveColor = if (autumnWeight > 0f) {
            autumnTreeColor(bush.dayColor, autumnWeight * 0.7f, bush.index)
        } else {
            bush.dayColor
        }

        val blendedColor = RenderConstants.applyOvercastLight(
            RenderConstants.blendWithAmbient(effectiveColor, ambientColor, haze), dullness
        ).let {
            // Snow dusted into the foliage
            if (snowCover > 0f) RenderConstants.lerpColor(it, snowColor, snowCover * 0.5f) else it
        }

        // Slight wind sway (less than trees)
        val windStrength = state.windStrength.coerceIn(0f, 1f) * state.windMultiplier
        val swaySpeed = state.treeSwaySpeed
        val timeSec = state.timeSeconds
        // Use world position for spatial phase
        val normalizedPos = (bush.worldX / RenderConstants.REFERENCE_EXTENDED_WIDTH) + 0.5f
        val sway = sin((timeSec * 1.5f * swaySpeed + normalizedPos * 3f).toDouble()).toFloat() * windStrength * bushWidth * 0.1f

        val centerY = groundY - bushHeight * 0.5f

        // Bushes cast the same ground shadow as trees; without one they looked lit from
        // a different direction than everything standing next to them.
        if (state.treeShadowsEnabled) {
            GroundShadow.draw(
                canvas, shadowPaint, state, x, groundY, bushHeight, bushWidth * 2f,
                sway, groundClipPath
            )
        }

        // Draw precalculated circles for organic bush shape
        val capSweep = if (snowCover > 0.02f) 70f + 140f * snowCover.coerceAtMost(1f) else 0f
        snowPaint.color = snowColor
        for (circle in bush.circles) {
            val cx = x + circle.xOffset * bushWidth + sway
            val cy = centerY + circle.yOffset * bushHeight
            val rx = bushWidth * circle.radiusScale
            val ry = bushHeight * circle.radiusScale * 0.8f  // Slightly flatten

            bushPaint.color = darkenColor(blendedColor, circle.darkenFactor)
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, bushPaint)

            // Snow cap: the upper segment of each puff, so it hugs the outline without clipping
            if (capSweep > 0f) {
                snowRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawArc(snowRect, 270f - capSweep / 2f, capSweep, false, snowPaint)
            }
        }

        // Deep snow buries the base so the bush sits in the snow instead of floating on it
        if (snowCover > 0.25f) {
            val mound = ((snowCover - 0.25f) / 0.5f).coerceIn(0f, 1f)
            val rx = bushWidth * (1f + 0.3f * mound)
            val ry = bushHeight * 0.3f * mound
            snowPaint.color = ColorMath.darkenColor(snowColor, 0.97f)
            canvas.drawOval(x + sway * 0.3f - rx, groundY - ry, x + sway * 0.3f + rx, groundY + ry * 0.6f, snowPaint)
        }

        // Draw berries on bush (summer season)
        val berryAlpha = getSeasonWeight("summer")
        if (berryAlpha > 0.01f) {
            berriesByBush[bush.index]?.let { berries ->
                for (berry in berries) {
                    val berryX = x + berry.xOffset * bushWidth * 2f + sway
                    val berryY = centerY + (berry.yRatio - 0.5f) * bushHeight
                    val berrySize = berry.size * height * (0.5f + berryAlpha * 0.5f)

                    val nightDim = 0.45f + 0.55f * ((state.getLightLevel() - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val berryColor = ColorMath.darkenColor(
                        RenderConstants.applyOvercastLight(
                            RenderConstants.blendWithAmbient(berry.color, ambientColor, maxOf(0.15f, haze)), dullness
                        ),
                        nightDim
                    )
                    berryPaint.color = berryColor
                    berryPaint.alpha = (Color.alpha(berryColor) * berryAlpha).toInt()
                    canvas.drawCircle(berryX, berryY, berrySize, berryPaint)
                    canvas.drawCircle(berryX + berrySize * 0.7f, berryY + berrySize * 0.3f, berrySize * 0.7f, berryPaint)
                }
                berryPaint.alpha = 255
            }
        }
    }

    // ========================================================================
    // Settings
    // ========================================================================

    /**
     * Reload bush-related settings from immutable settings snapshot.
     * Must be called whenever preferences change (and on init).
     * @return true if any generation-affecting setting changed (requires re-generation)
     */
    fun reloadSettings(settings: SimulationSettings): Boolean {
        val prevEnabled = bushesEnabled
        val prevDensity = bushDensity
        val prevSize = bushSize
        val prevBerries = berriesEnabled
        val prevBerryDens = berryDensity

        bushesEnabled = settings.getBoolean("pref_bushes_enabled", PreferenceDefaults.BUSHES_ENABLED)
        bushDensity = settings.getInt("pref_bush_density", PreferenceDefaults.BUSH_DENSITY)
        bushSize = settings.getInt("pref_bush_size", PreferenceDefaults.BUSH_SIZE) / 100f

        berriesEnabled = settings.getBoolean("pref_berries_enabled", PreferenceDefaults.BERRIES_ENABLED)
        berryDensity = settings.getInt("pref_berry_density", PreferenceDefaults.BERRY_DENSITY)

        return prevEnabled != bushesEnabled ||
            prevDensity != bushDensity ||
            kotlin.math.abs(prevSize - bushSize) > 0.001f ||
            prevBerries != berriesEnabled ||
            prevBerryDens != berryDensity
    }
}
