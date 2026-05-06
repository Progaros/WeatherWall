package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        val xRatio: Float,    // 0-1 across extended width
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
    private val berryPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

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

    private var extendedWidth = 0f
    private var extendedOffset = 0f
    private var height = 0f

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
        return when {
            cachedSeason == season && cachedPrevSeason == season -> 1f
            cachedSeason == season -> cachedSeasonBlend           // Transitioning INTO this season
            cachedPrevSeason == season -> 1f - cachedSeasonBlend  // Transitioning OUT of this season
            else -> 0f
        }
    }

    /**
     * Blend foliage toward autumn colours based on weight.
     * Shifts green toward orange/red/golden tones per bush.
     */
    fun autumnTreeColor(baseColor: Int, weight: Float, bushIndex: Int): Int {
        if (weight <= 0f) return baseColor
        // Each bush gets a slightly different autumn hue based on its index
        val hueShift = (bushIndex * 37) % 3  // 0, 1, or 2
        val (targetR, targetG, targetB) = when (hueShift) {
            0 -> Triple(200, 90, 20)    // Orange
            1 -> Triple(180, 140, 30)   // Golden
            else -> Triple(190, 60, 25) // Red-orange
        }
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        return Color.rgb(
            (r + (targetR - r) * weight).toInt().coerceIn(0, 255),
            (g + (targetG - g) * weight).toInt().coerceIn(0, 255),
            (b + (targetB - b) * weight).toInt().coerceIn(0, 255)
        )
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    private fun darkenColor(color: Int, factor: Float = 0.65f): Int = ColorMath.darkenColor(color, factor)

    /**
     * Interpolate hill Y at a given xRatio (0-1), same algorithm as TerrainLayer.getHillY().
     */
    private fun getHillY(xRatio: Float, hill: TreeRenderer.HillInfo): Float {
        val points = hill.controlPoints
        if (points.size < 2) return hill.heightOffset

        val indexFloat = xRatio * (points.size - 1)
        val index = indexFloat.toInt().coerceIn(0, points.size - 2)
        val fraction = indexFloat - index

        val p1 = points[index]
        val p2 = points[index + 1]
        val interpolated = p1 + fraction * (p2 - p1)

        return hill.heightOffset + 0.12f * (1f - interpolated)
    }

    // ========================================================================
    // Generation methods
    // ========================================================================

    /**
     * Generate bushes placed along hills, similar to trees but smaller and rounder.
     *
     * @param hills Hill information (control points, depth, height offset) for bush placement.
     * @param extendedWidth Extended canvas width for parallax content.
     * @param extendedOffset Offset to center extended content on screen.
     * @param height Screen height in pixels.
     * @param randomSeed Seed for deterministic placement.
     */
    fun generateBushes(
        hills: List<TreeRenderer.HillInfo>,
        extendedWidth: Float,
        extendedOffset: Float,
        height: Float,
        randomSeed: Int
    ) {
        // Store dimensions for draw methods
        this.extendedWidth = extendedWidth
        this.extendedOffset = extendedOffset
        this.height = height

        if (hills.isEmpty() || !bushesEnabled || bushDensity <= 0 || extendedWidth <= 0) {
            bushes = emptyList()
            bushesByDepth = emptyMap()
            return
        }

        val widthRatio = RenderConstants.getHorizontalDensityScale(extendedWidth)
        val bushCount = ((bushDensity / 100f) * 60 * widthRatio).toInt().coerceIn(0, 500)
        if (bushCount <= 0) {
            bushes = emptyList()
            bushesByDepth = emptyMap()
            return
        }

        val newBushes = ArrayList<BushData>(bushCount)
        val random = Random(randomSeed.toLong() + 5000)  // Different seed from trees

        val bushesPerHill = (bushCount.toFloat() / hills.size).toInt().coerceAtLeast(1)

        for (hillIndex in hills.indices) {
            val hill = hills[hillIndex]
            val depthFactor = hill.depthFactor

            val hillBushCount = bushesPerHill.coerceAtMost(bushCount - newBushes.size)

            for (b in 0 until hillBushCount) {
                val xRatio = random.nextFloat()
                val groundY = getHillY(xRatio, hill)

                // Bushes sit on or slightly below the hill line
                val verticalOffset = random.nextFloat() * 0.06f
                val yRatio = groundY + verticalOffset

                // Normalize depth for scale
                val hillsRange = RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN
                val normalizedDepth = ((depthFactor - RenderConstants.PARALLAX_HILLS_MIN) / hillsRange).coerceIn(0f, 1f)

                // Bushes are smaller than trees, scale with depth
                val baseScale = 0.4f + normalizedDepth * 0.6f
                val sizeVariation = 0.7f + random.nextFloat() * 0.6f
                val scale = baseScale * sizeVariation * bushSize

                // Green/dark green color for bush
                val varR = random.nextInt(20) - 10
                val varG = random.nextInt(30)
                val dayColor = Color.rgb(
                    (40 + varR).coerceIn(0, 255),
                    (80 + varG).coerceIn(0, 255),
                    30
                )

                newBushes.add(BushData(xRatio, yRatio, scale, depthFactor, dayColor, newBushes.size,
                    generateBushCircles(random)))
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
        val random = Random(randomSeed.toLong() + 8000)

        if (berriesEnabled && currentBushes.isNotEmpty()) {
            val berryColors = intArrayOf(
                Color.rgb(200, 30, 30),    // Red berries
                Color.rgb(60, 60, 180),    // Blueberries
                Color.rgb(180, 50, 100),   // Dark pink
                Color.rgb(100, 20, 60)     // Dark berry
            )
            val newBerries = mutableMapOf<Int, List<BerryData>>()
            val berryProbability = berryDensity / 100f

            for ((index, _) in currentBushes.withIndex()) {
                if (random.nextFloat() > berryProbability) continue
                val bushBerries = mutableListOf<BerryData>()
                val numBerries = 2 + random.nextInt(4)  // 2-5 berries per bush
                for (b in 0 until numBerries) {
                    bushBerries.add(BerryData(
                        xOffset = (random.nextFloat() - 0.5f) * 0.8f,
                        yRatio = 0.2f + random.nextFloat() * 0.6f,
                        color = berryColors[random.nextInt(berryColors.size)],
                        size = 0.002f + random.nextFloat() * 0.002f
                    ))
                }
                newBerries[index] = bushBerries
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
     */
    fun drawBush(canvas: Canvas, bush: BushData, state: SimulationState, ambientColor: Int) {
        val x = bush.xRatio * extendedWidth - extendedOffset
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

        val yBasedDepth = ((bush.yRatio - 0.6f) / 0.35f).coerceIn(0f, 1f)
        val timeMultiplier = if (!state.isNight && !state.isDawn && !state.isDusk) 0.15f else 1.0f
        val blendAmount = RenderConstants.applyAmbientTint(effectiveColor, ambientColor, yBasedDepth) * timeMultiplier
        val blendedColor = RenderConstants.blendWithAmbient(effectiveColor, ambientColor, blendAmount)

        // Slight wind sway (less than trees)
        val windStrength = state.windStrength.coerceIn(0f, 1f) * state.windMultiplier
        val swaySpeed = state.treeSwaySpeed
        val timeSec = state.timeSeconds
        val sway = sin((timeSec * 1.5f * swaySpeed + bush.xRatio * 3f).toDouble()).toFloat() * windStrength * bushWidth * 0.1f

        val centerY = groundY - bushHeight * 0.5f

        // Draw precalculated circles for organic bush shape
        for (circle in bush.circles) {
            val cx = x + circle.xOffset * bushWidth + sway
            val cy = centerY + circle.yOffset * bushHeight
            val rx = bushWidth * circle.radiusScale
            val ry = bushHeight * circle.radiusScale * 0.8f  // Slightly flatten

            bushPaint.color = darkenColor(blendedColor, circle.darkenFactor)
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, bushPaint)
        }

        // Draw berries on bush (summer season)
        val berryAlpha = getSeasonWeight("summer")
        if (berryAlpha > 0.01f) {
            berriesByBush[bush.index]?.let { berries ->
                for (berry in berries) {
                    val berryX = x + berry.xOffset * bushWidth * 2f + sway
                    val berryY = centerY + (berry.yRatio - 0.5f) * bushHeight
                    val berrySize = berry.size * height * (0.5f + berryAlpha * 0.5f)

                    val berryColor = RenderConstants.blendWithAmbient(berry.color, ambientColor, 0.15f)
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
     */
    fun reloadSettings(settings: SimulationSettings) {
        bushesEnabled = settings.getBoolean("pref_bushes_enabled", PreferenceDefaults.BUSHES_ENABLED)
        bushDensity = settings.getInt("pref_bush_density", PreferenceDefaults.BUSH_DENSITY)
        bushSize = settings.getInt("pref_bush_size", PreferenceDefaults.BUSH_SIZE) / 100f

        berriesEnabled = settings.getBoolean("pref_berries_enabled", PreferenceDefaults.BERRIES_ENABLED)
        berryDensity = settings.getInt("pref_berry_density", PreferenceDefaults.BERRY_DENSITY)
    }
}
