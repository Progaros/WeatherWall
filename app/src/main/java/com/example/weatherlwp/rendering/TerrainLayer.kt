package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import com.example.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders hills and trees with wind-based animation.
 * 
 * Uses SimulationState for:
 * - windStrength (affects tree sway amplitude)
 * - timeSeconds (for consistent wind animation across reloads)
 * - isNight (affects color tinting)
 */
class TerrainLayer(private val context: Context) : SceneLayer {
    private val hillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePath = Path()
    private val hillPath = Path()

    private var width = 0f
    private var height = 0f
    
    // Data structures
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
    
    private val hills = ArrayList<HillData>()
    private val trees = ArrayList<TreeData>()
    
    // Settings
    private var treeCount = 100
    private var hillCount = 5
    private var treeSizeScale = 1.0f
    private var treeWidthScale = 1.0f
    private var hillStartY = 0.50f
    private var hillEndY = 0.85f
    private var treeColorVariation = 1.0f
    private var treeSegments = 1
    private var windStrengthSetting = 0.5f
    
    companion object {
        private const val REFERENCE_ASPECT_RATIO = 0.5625f
        private const val WORLD_MIN_X = -4.0f
        private const val WORLD_MAX_X = 5.0f
    }

    init {
        reloadSettings()
    }

    private fun generateTerrain() {
        generateHills()
        generateTrees()
    }
    
    private fun generateHills() {
        hills.clear()
        val random = Random(12345) // Fixed seed
        val baseGreen = Color.rgb(30, 80, 40)
        
        for (i in 0 until hillCount) {
            val depth = i.toFloat() / hillCount.coerceAtLeast(1)
            val depthFactor = 0.5f + depth * 0.5f
            val heightOffset = hillStartY + depth * (hillEndY - hillStartY)
            
            val numPoints = 60 + random.nextInt(20)
            val points = ArrayList<Float>()
            for (j in 0 until numPoints) {
                points.add(0.3f + random.nextFloat() * 0.2f)
            }
            
            val brightness = 0.6f + depth * 0.4f
            val dayColor = Color.rgb(
                (Color.red(baseGreen) * brightness).toInt(),
                (Color.green(baseGreen) * brightness).toInt(),
                (Color.blue(baseGreen) * brightness).toInt()
            )
            
            hills.add(HillData(points, depthFactor, heightOffset, dayColor))
        }
        hills.sortBy { it.depthFactor }
    }
    
    private fun generateTrees() {
        trees.clear()
        val random = Random(67890) // Fixed seed
        
        for (i in 0 until treeCount) {
            val xRatio = -0.2f + random.nextFloat() * 1.4f
            val hill = if (hills.isNotEmpty()) hills[random.nextInt(hills.size)] else null
            
            val (groundY, depthFactor) = if (hill != null) {
                Pair(getHillY(xRatio, hill), hill.depthFactor)
            } else {
                Pair(0.8f, 0.5f)
            }
            
            val yRatio = groundY + 0.02f
            val normalizedDepth = ((depthFactor - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val scale = 0.5f + normalizedDepth * 0.5f
            
            val baseColor = Color.rgb(20, 60, 30)
            val variationAmount = (20 * treeColorVariation).toInt()
            val varR = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val varG = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val dayColor = Color.rgb(
                (Color.red(baseColor) + varR).coerceIn(0, 255),
                (Color.green(baseColor) + varG).coerceIn(0, 255),
                Color.blue(baseColor)
            )
            
            trees.add(TreeData(xRatio, yRatio, scale, depthFactor, dayColor))
        }
        trees.sortBy { it.depthFactor }
    }
    
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        if (points.isEmpty()) return hill.heightOffset
        
        val normalizedX = (xRatio - WORLD_MIN_X) / (WORLD_MAX_X - WORLD_MIN_X)
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
        
        val scaleX = height * REFERENCE_ASPECT_RATIO
        val isNight = state.isNight
        
        // Draw Hills
        for (hill in hills) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(hill.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            hillPaint.color = if (isNight) applyNightTint(hill.dayColor) else hill.dayColor
            drawHill(canvas, hill, scaleX)
            
            canvas.restore()
        }
        
        // Draw Trees
        for (tree in trees) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(tree.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            treePaint.color = if (isNight) applyNightTint(tree.dayColor) else tree.dayColor
            drawTree(canvas, tree, scaleX, state)
            
            canvas.restore()
        }
    }
    
    private fun applyNightTint(color: Int): Int {
        // Add blue tint for night
        return Color.rgb(
            (Color.red(color) * 0.6f).toInt(),
            (Color.green(color) * 0.7f).toInt(),
            (Color.blue(color) + 20).coerceAtMost(255)
        )
    }
    
    private fun drawHill(canvas: Canvas, hill: HillData, scaleX: Float) {
        hillPath.reset()
        
        val startX = (WORLD_MIN_X - 0.5f) * scaleX + width / 2f
        val endX = (WORLD_MAX_X - 0.5f) * scaleX + width / 2f
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
        
        val totalHeight = height * 0.15f * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        
        // Wind sway using simulation time for consistency
        // Combine settings wind with state wind
        val effectiveWind = (windStrengthSetting + state.windStrength) / 2f * 4f
        val timeSec = state.timeSeconds
        val sway = sin(timeSec * 2f + tree.xRatio * 10f) * (totalHeight * 0.05f * effectiveWind)
        
        for (seg in 0 until treeSegments) {
            val segY = y - seg * segmentHeight * 0.7f
            val segTop = segY - segmentHeight
            val widthMultiplier = 1.0f - (seg * 0.15f)
            val segWidth = segmentHeight * 0.5f * widthMultiplier * treeWidthScale
            val segSway = sway * (1.0f + seg * 0.3f)
            
            treePath.reset()
            treePath.moveTo(x + segSway, segTop)
            treePath.lineTo(x - segWidth, segY)
            treePath.lineTo(x + segWidth, segY)
            treePath.close()
            
            canvas.drawPath(treePath, treePaint)
        }
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        hillCount = prefs.getInt("pref_hill_count", 10)
        treeCount = prefs.getInt("pref_tree_count", 100)
        hillStartY = prefs.getInt("pref_hill_lower_start", 50) / 100f
        hillEndY = prefs.getInt("pref_hill_upper_end", 85) / 100f
        
        val sizeVal = prefs.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f
        
        val widthVal = prefs.getInt("pref_tree_width", 100)
        treeWidthScale = widthVal / 100f
        
        treeColorVariation = prefs.getInt("pref_tree_color_variation", 100) / 100f
        treeSegments = prefs.getInt("pref_tree_segments", 1).coerceIn(1, 5)
        
        val windVal = prefs.getInt("pref_wind_strength", 50)
        windStrengthSetting = windVal / 100f
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, trees=$treeCount")
        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
