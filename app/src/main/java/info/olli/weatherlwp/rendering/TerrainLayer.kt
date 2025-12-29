package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
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
 */
class TerrainLayer(private val context: Context) : SceneLayer {
    private val hillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePath = Path()
    private val hillPath = Path()

    private var width = 0f
    private var height = 0f
    
    // Data structures (immutable after creation)
    private data class HillData(
        val controlPoints: List<Float>,
        val depthFactor: Float,
        val heightOffset: Float,
        val dayColor: Int
    )
    
    private data class TreeData(
        val xRatio: Float,
        val yRatio: Float,
        val scale: Float,
        val depthFactor: Float,
        val dayColor: Int
    )
    
    // Thread-safe terrain data using volatile references + immutable lists
    // Settings thread creates new lists, render thread reads current snapshots
    @Volatile private var hills: List<HillData> = emptyList()
    @Volatile private var trees: List<TreeData> = emptyList()
    
    // Settings
    private var treeCount = 100
    private var hillCount = 5
    private var treeSizeScale = 1.0f
    private var treeWidthScale = 1.0f
    private var hillStartY = 0.50f
    private var hillEndY = 0.85f
    private var treeColorVariation = 1.0f
    private var treeSegments = 1
    private var randomSeed = 42

    init {
        reloadSettings()
    }

    private fun generateTerrain() {
        generateHills()
        generateTrees()
    }
    
    private fun generateHills() {
        // Build new list (thread-safe: atomic swap at end)
        val newHills = ArrayList<HillData>(hillCount)
        val random = Random(randomSeed.toLong() + 1000)  // Offset from mountains
        
        for (i in 0 until hillCount) {
            val depth = i.toFloat() / hillCount.coerceAtLeast(1)
            // Use centralized parallax constants
            val depthFactor = RenderConstants.PARALLAX_HILLS_MIN + 
                depth * (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)
            val heightOffset = hillStartY + depth * (hillEndY - hillStartY)
            
            // More points for smoother curves
            val numPoints = RenderConstants.HILL_CURVE_POINTS_MIN + random.nextInt(RenderConstants.HILL_CURVE_POINTS_RANGE)
            val points = ArrayList<Float>()
            
            // Smooth curve generation with gradual changes
            var lastValue = RenderConstants.HILL_BASE_HEIGHT + random.nextFloat() * RenderConstants.HILL_HEIGHT_RANGE
            for (j in 0 until numPoints) {
                val change = (random.nextFloat() - 0.5f) * RenderConstants.HILL_SMOOTHING_FACTOR
                val newValue = (lastValue + change).coerceIn(0.1f, 0.6f)
                points.add(newValue)
                lastValue = newValue
            }
            
            // Improved brightness calculation
            val brightness = 0.5f + depth * 0.5f
            val dayColor = Color.rgb(
                (Color.red(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.green(RenderConstants.HILL_BASE_COLOR) * brightness).toInt(),
                (Color.blue(RenderConstants.HILL_BASE_COLOR) * brightness).toInt()
            )
            
            newHills.add(HillData(points, depthFactor, heightOffset, dayColor))
        }
        newHills.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        hills = newHills
    }
    
    private fun generateTrees() {
        // Take snapshot of hills for tree placement (thread-safe read)
        val currentHills = hills
        
        // Build new list (thread-safe: atomic swap at end)
        val effectiveTreeCount = treeCount / 2  // Less dense forest
        val newTrees = ArrayList<TreeData>(effectiveTreeCount)
        val random = Random(randomSeed.toLong() + 2000)  // Offset from hills
        
        for (i in 0 until effectiveTreeCount) {
            val xRatio = -0.2f + random.nextFloat() * 1.4f
            
            // Always place trees on hills
            val hill = if (currentHills.isNotEmpty()) {
                currentHills[random.nextInt(currentHills.size)]
            } else {
                HillData(listOf(0.4f), 0.85f, 0.70f, Color.rgb(30, 80, 40))
            }
            
            val groundY = getHillY(xRatio, hill)
            val depthFactor = hill.depthFactor
            val yRatio = groundY + RenderConstants.TREE_GROUND_OFFSET
            
            val normalizedDepth = ((depthFactor - RenderConstants.PARALLAX_HILLS_MIN) /
                (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)).coerceIn(0f, 1f)
            
            // Tree scale: closer trees are larger
            val scale = RenderConstants.TREE_SCALE_MIN + normalizedDepth * RenderConstants.TREE_SCALE_RANGE
            
            val variationAmount = (20 * treeColorVariation).toInt()
            val varR = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val varG = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val dayColor = Color.rgb(
                (Color.red(RenderConstants.TREE_BASE_COLOR) + varR).coerceIn(0, 255),
                (Color.green(RenderConstants.TREE_BASE_COLOR) + varG).coerceIn(0, 255),
                Color.blue(RenderConstants.TREE_BASE_COLOR)
            )
            
            newTrees.add(TreeData(xRatio, yRatio, scale, depthFactor, dayColor))
        }
        newTrees.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        trees = newTrees
    }
    
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        if (points.isEmpty()) return hill.heightOffset
        
        val normalizedX = (xRatio - RenderConstants.WORLD_MIN_X) / RenderConstants.WORLD_WIDTH
        val indexFloat = normalizedX * (points.size - 1)
        val index = indexFloat.toInt().coerceIn(0, points.size - 2)
        val fraction = indexFloat - index
        
        val p1 = points[index]
        val p2 = points[index + 1]
        val interpolated = p1 + fraction * (p2 - p1)
        
        return hill.heightOffset + 0.12f * (1f - interpolated)
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Animation state comes from SimulationState.timeSeconds
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Take local snapshots for thread-safe iteration
        val currentHills = hills
        val currentTrees = trees
        
        val scaleX = height * RenderConstants.REFERENCE_ASPECT_RATIO
        
        // Get ambient color for current time of day (per ai.md: global tint system)
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk
        )
        
        // Draw Hills with ambient tinting
        for (hill in currentHills) {
            val px = parallaxCalculator.getOffsetXForDepth(hill.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(hill.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            // Blend with ambient color based on depth (distant hills blend more)
            val blendAmount = RenderConstants.applyAmbientTint(hill.dayColor, ambientColor, hill.depthFactor)
            hillPaint.color = RenderConstants.blendWithAmbient(hill.dayColor, ambientColor, blendAmount)
            drawHill(canvas, hill, scaleX)
            
            canvas.restore()
        }
        
        // Draw Trees with ambient tinting
        for (tree in currentTrees) {
            val px = parallaxCalculator.getOffsetXForDepth(tree.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(tree.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            // Trees in foreground get less tint, background trees get more
            val normalizedDepth = ((tree.depthFactor - RenderConstants.PARALLAX_HILLS_MIN) / 
                (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN)).coerceIn(0f, 1f)
            
            // During day, minimize ambient tinting to preserve green color
            // At night/dawn/dusk, apply stronger tinting for atmospheric effect
            val timeMultiplier = if (!state.isNight && !state.isDawn && !state.isDusk) {
                0.15f  // Minimal tinting during day
            } else {
                1.0f   // Full tinting at night/dawn/dusk
            }
            val blendAmount = RenderConstants.applyAmbientTint(tree.dayColor, ambientColor, normalizedDepth) * timeMultiplier
            treePaint.color = RenderConstants.blendWithAmbient(tree.dayColor, ambientColor, blendAmount)
            drawTree(canvas, tree, scaleX, state)
            
            canvas.restore()
        }
    }
    
    private fun drawHill(canvas: Canvas, hill: HillData, scaleX: Float) {
        hillPath.reset()
        
        val startX = (RenderConstants.WORLD_MIN_X - 0.5f) * scaleX + width / 2f
        val endX = (RenderConstants.WORLD_MAX_X - 0.5f) * scaleX + width / 2f
        val stepX = (endX - startX) / (hill.controlPoints.size - 1)
        
        hillPath.moveTo(startX, height)
        
        for (i in hill.controlPoints.indices) {
            val x = startX + i * stepX
            val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[i])
            val y = yRatio * height
            hillPath.lineTo(x, y)
        }
        
        hillPath.lineTo(endX, height)
        hillPath.close()
        
        canvas.drawPath(hillPath, hillPaint)
    }
    
    private fun drawTree(canvas: Canvas, tree: TreeData, scaleX: Float, state: SimulationState) {
        val x = (tree.xRatio - 0.5f) * scaleX + width / 2f
        val y = tree.yRatio * height
        
        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        
        // Wind sway: smooth multi-harmonic motion
        // state.windStrength ranges 0-1
        val windStrength = state.windStrength.coerceIn(0f, 1f)
        val timeSec = state.timeSeconds
        
        // Base sway frequency increases with wind strength (slow breeze -> fast gusts)
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * (0.5f + windStrength)
        
        // Multiple harmonics for natural movement
        // Each tree has unique phase offset based on position
        val phase = tree.xRatio * 10f + tree.yRatio * 5f
        
        // Primary sway: main oscillation
        val primarySway = sin(timeSec * baseFreq + phase)
        
        // Secondary sway: slower, larger gust effect
        val gustSway = sin(timeSec * baseFreq * 0.3f + phase * 0.5f) * 0.4f
        
        // Tertiary: small high-frequency flutter
        val flutter = sin(timeSec * baseFreq * 2.5f + phase * 2f) * 0.15f
        
        // Combined sway normalized to -1..1 range
        val combinedSway = (primarySway + gustSway + flutter).coerceIn(-1.5f, 1.5f) / 1.5f
        
        // Amplitude scales smoothly with wind strength (squared for more natural feel)
        val amplitude = totalHeight * RenderConstants.TREE_SWAY_AMPLITUDE * windStrength * windStrength * 3f
        val sway = combinedSway * amplitude
        
        for (seg in 0 until treeSegments) {
            val segY = y - seg * segmentHeight * 0.7f
            val segTop = segY - segmentHeight
            val widthMultiplier = 1.0f - (seg * 0.15f)
            val segWidth = segmentHeight * 0.5f * widthMultiplier * treeWidthScale
            // Sway increases with height - base stays planted, tip sways more
            val baseSway = sway * (seg * 0.3f)
            val tipSway = sway * ((seg + 1) * 0.3f)
            
            treePath.reset()
            treePath.moveTo(x + tipSway, segTop)
            treePath.lineTo(x - segWidth + baseSway, segY)
            treePath.lineTo(x + segWidth + baseSway, segY)
            treePath.close()
            
            canvas.drawPath(treePath, treePaint)
        }
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Random seed for consistent placement
        randomSeed = prefs.getInt("pref_random_seed", 42)
        
        hillCount = prefs.getInt("pref_hill_count", 3)  // 3 hills instead of 10
        treeCount = prefs.getInt("pref_tree_count", 100)
        hillStartY = prefs.getInt("pref_hill_lower_start", 60) / 100f  // 60% instead of 50%
        hillEndY = prefs.getInt("pref_hill_upper_end", 80) / 100f      // 80% instead of 85%
        
        val sizeVal = prefs.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f
        
        val widthVal = prefs.getInt("pref_tree_width", 100)
        treeWidthScale = widthVal / 100f
        
        treeColorVariation = prefs.getInt("pref_tree_color_variation", 100) / 100f
        treeSegments = prefs.getInt("pref_tree_segments", 1).coerceIn(1, 5)
        
        // Wind is now from SimulationState, not a separate setting
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, trees=$treeCount")
        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
