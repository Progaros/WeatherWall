package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.sin
import kotlin.random.Random

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
        val color: Int
    )
    
    private data class TreeData(
        val xRatio: Float,
        val yRatio: Float,
        val scale: Float,
        val depthFactor: Float,
        val color: Int
    )
    
    private val hills = ArrayList<HillData>()
    private val trees = ArrayList<TreeData>()
    
    // Settings
    private var treeCount = 100
    private var hillCount = 5
    private var treeSizeScale = 1.0f
    private var hillStartY = 0.50f  // Where far hills appear (0.30-0.70)
    private var hillEndY = 0.85f    // Where near hills appear (0.60-0.90)
    private var treeColorVariation = 1.0f  // 0.0 = uniform, 1.0 = full variation
    private var treeSegments = 1    // Number of tiers per tree (1-5)
    
    companion object {
        private const val REFERENCE_ASPECT_RATIO = 0.5625f // 9:16
        private const val WORLD_MIN_X = -4.0f
        private const val WORLD_MAX_X = 5.0f
    }
    
    // Wind animation
    private var windTime = 0L
    private var windSpeed = 1.0f

    init {
        reloadSettings()
    }
    
    // ... (generateTerrain, generateHills, generateTrees omitted) ...

    private fun generateTerrain() {
        generateHills()
        generateTrees()
    }
    
    private fun generateHills() {
        hills.clear()
        val random = Random(12345)
        
        // Colors
        val baseGreen = Color.rgb(30, 80, 40)
        
        for (i in 0 until hillCount) {
            // Depth: 0 (far) to 1 (near)
            val depth = i.toFloat() / hillCount.coerceAtLeast(1)
            val depthFactor = 0.5f + depth * 0.5f // Foreground parallax (0.5 to 1.0)
            
            // Height: Use hillStartY for far hills, hillEndY for near hills
            val heightOffset = hillStartY + depth * (hillEndY - hillStartY)
            
            // Control points for shape
            val numPoints = 60 + random.nextInt(20)
            val points = ArrayList<Float>()
            for (j in 0 until numPoints) {
                points.add(0.3f + random.nextFloat() * 0.2f)
            }
            
            // Color: Darker in back, lighter in front
            val brightness = 0.6f + depth * 0.4f
            val color = Color.rgb(
                (Color.red(baseGreen) * brightness).toInt(),
                (Color.green(baseGreen) * brightness).toInt(),
                (Color.blue(baseGreen) * brightness).toInt()
            )
            
            hills.add(HillData(points, depthFactor, heightOffset, color))
        }
        // Sort: Far hills first
        hills.sortBy { it.depthFactor }
    }
    
    private fun generateTrees() {
        trees.clear()
        // Use fixed seed to prevent trees jumping when other settings change
        val random = Random(67890)
        
        for (i in 0 until treeCount) {
            // X Position: Concentrate in visible area (-0.2 to 1.2)
            // This ensures density in the viewport
            val xRatio = -0.2f + random.nextFloat() * 1.4f
            
            // Pick a hill to sit on
            val hill = if (hills.isNotEmpty()) hills[random.nextInt(hills.size)] else null
            
            val (groundY, depthFactor) = if (hill != null) {
                Pair(getHillY(xRatio, hill), hill.depthFactor)
            } else {
                Pair(0.8f, 0.5f)
            }
            
            // Y Position: On ground + slight offset
            val yRatio = groundY + 0.02f
            
            // Scale: Near trees larger
            val normalizedDepth = ((depthFactor - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val scale = 0.5f + normalizedDepth * 0.5f
            
            // Color variation - controlled by treeColorVariation setting
            val baseColor = Color.rgb(20, 60, 30)
            val variationAmount = (20 * treeColorVariation).toInt()
            val varR = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val varG = if (variationAmount > 0) random.nextInt(variationAmount * 2 + 1) - variationAmount else 0
            val color = Color.rgb(
                (Color.red(baseColor) + varR).coerceIn(0, 255),
                (Color.green(baseColor) + varG).coerceIn(0, 255),
                Color.blue(baseColor)
            )
            
            trees.add(TreeData(xRatio, yRatio, scale, depthFactor, color))
        }
        // Sort: Far trees first
        trees.sortBy { it.depthFactor }
    }
    
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        if (points.isEmpty()) return hill.heightOffset
        
        // Map xRatio (WORLD_MIN_X to WORLD_MAX_X) to segment index
        val normalizedX = (xRatio - WORLD_MIN_X) / (WORLD_MAX_X - WORLD_MIN_X)
        val indexFloat = normalizedX * (points.size - 1)
        val index = indexFloat.toInt().coerceIn(0, points.size - 2)
        val fraction = indexFloat - index
        
        val p1 = points[index]
        val p2 = points[index + 1]
        val interpolated = p1 + fraction * (p2 - p1)
        
        // Hill height amplitude is 0.12 (relative to screen height)
        return hill.heightOffset + 0.12f * (1f - interpolated)
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(deltaTimeMs: Long) {
        windTime += deltaTimeMs
    }

    override fun draw(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Scale X based on height for consistent aspect ratio
        val scaleX = height * REFERENCE_ASPECT_RATIO
        
        // Draw Hills
        for (hill in hills) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(hill.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            hillPaint.color = hill.color
            drawHill(canvas, hill, scaleX)
            
            canvas.restore()
        }
        
        // Draw Trees
        // We need to interleave trees with hills? 
        // Currently hills are drawn first (background), then trees.
        // Ideally trees should be sorted with hills, but for now drawing all trees on top of all hills is okay
        // as long as trees are sorted by depth.
        
        for (tree in trees) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(tree.depthFactor)
            canvas.save()
            canvas.translate(px, py)
            
            treePaint.color = tree.color
            drawTree(canvas, tree, scaleX)
            
            canvas.restore()
        }
    }
    
    private fun drawHill(canvas: Canvas, hill: HillData, scaleX: Float) {
        hillPath.reset()
        
        val startX = (WORLD_MIN_X - 0.5f) * scaleX + width / 2f
        val endX = (WORLD_MAX_X - 0.5f) * scaleX + width / 2f
        val stepX = (endX - startX) / (hill.controlPoints.size - 1)
        
        hillPath.moveTo(startX, height) // Bottom left
        
        for (i in hill.controlPoints.indices) {
            val x = startX + i * stepX
            // Calculate Y based on control point
            val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[i])
            val y = yRatio * height
            hillPath.lineTo(x, y)
        }
        
        hillPath.lineTo(endX, height) // Bottom right
        hillPath.close()
        
        canvas.drawPath(hillPath, hillPaint)
    }
    
    private fun drawTree(canvas: Canvas, tree: TreeData, scaleX: Float) {
        val x = (tree.xRatio - 0.5f) * scaleX + width / 2f
        val y = tree.yRatio * height
        
        // Tree size relative to screen height
        // Base height 15% of screen height, divided by segments for tiered look
        val totalHeight = height * 0.15f * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        
        // Wind sway
        val timeSec = windTime / 1000f
        val sway = sin(timeSec * 2f + tree.xRatio * 10f) * (totalHeight * 0.05f * windSpeed)
        
        treePaint.color = tree.color
        
        // Draw each segment (tier) of the tree
        for (seg in 0 until treeSegments) {
            val segY = y - seg * segmentHeight * 0.7f  // Overlap segments by 30%
            val segTop = segY - segmentHeight
            // Width decreases for higher segments
            val widthMultiplier = 1.0f - (seg * 0.15f)
            val segWidth = segmentHeight * 0.5f * widthMultiplier
            // Sway increases for higher segments
            val segSway = sway * (1.0f + seg * 0.3f)
            
            treePath.reset()
            treePath.moveTo(x + segSway, segTop) // Top
            treePath.lineTo(x - segWidth, segY) // Bottom Left
            treePath.lineTo(x + segWidth, segY) // Bottom Right
            treePath.close()
            
            canvas.drawPath(treePath, treePaint)
        }
    }

    override fun updateTheme(theme: String, isNight: Boolean) {
        // Adjust colors
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        hillCount = prefs.getInt("pref_hill_count", 10)
        treeCount = prefs.getInt("pref_tree_count", 100)
        
        // Hill positions: pref values are percentages (30-70 for start, 60-90 for end)
        hillStartY = prefs.getInt("pref_hill_lower_start", 50) / 100f
        hillEndY = prefs.getInt("pref_hill_upper_end", 85) / 100f
        
        // Tree Size: 10-1000 -> 0.05 - 5.0
        val sizeVal = prefs.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f // Default 200 is 1.0x
        
        // Tree Color Variation: 0-100 -> 0.0 - 1.0
        treeColorVariation = prefs.getInt("pref_tree_color_variation", 100) / 100f
        
        // Tree Segments: 1-5 tiers per tree
        treeSegments = prefs.getInt("pref_tree_segments", 1).coerceIn(1, 5)
        
        // Wind Strength: 0-100 -> 0.0 - 4.0
        val windVal = prefs.getInt("pref_wind_strength", 50)
        windSpeed = windVal / 25f // Default 50 is 2.0x, 100 is 4.0x for stronger effect
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, trees=$treeCount, hillY=$hillStartY-$hillEndY, colorVar=$treeColorVariation, wind=$windSpeed")

        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
