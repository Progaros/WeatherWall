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
    private var treeDensity = 50        // 0-100 percentage of tree coverage
    private var hillCount = 5
    private var treeSizeScale = 1.0f
    private var treeWidthScale = 1.0f
    private var hillStartY = 0.50f
    private var hillEndY = 0.85f
    private var treeColorVariation = 1.0f
    private var treeSegments = 1
    private var randomSeed = 42
    private var hillRoughness = 1.0f  // Controls sine wave detail/roughness
    private var hillAmplitude = 1.0f  // Controls hill vertical height (0-10 range)
    private var hillFrequency = 2.0f  // Controls how many hills visible per layer (0-10 range)

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
            
            // Parallax with inverted background movement:
            // - Background hills (depth=0): NEGATIVE factor = moves opposite to tilt (creates depth)
            // - Foreground hills (depth=1): near-zero factor = nearly static
            // Range: -0.5 (background) to 0.05 (foreground)
            val depthFactor = -0.5f + depth * 0.55f  // -0.5 to +0.05
            
            val heightOffset = hillStartY + depth * (hillEndY - hillStartY)
            
            // More points for smoother curves
            val numPoints = RenderConstants.HILL_CURVE_POINTS_MIN + random.nextInt(RenderConstants.HILL_CURVE_POINTS_RANGE)
            val points = ArrayList<Float>()
            
            // Sine-based smooth curve generation
            // Frequency controls how many hills are visible on screen per layer
            // Add small random variation to make each layer slightly different
            val baseFreq = hillFrequency * (0.8f + random.nextFloat() * 0.4f)  // ±20% variation
            val secondaryFreq = baseFreq * 2.5f  // Higher frequency detail
            val phaseOffset = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            
            // Amplitude controls vertical height of hills
            val amplitude = RenderConstants.HILL_HEIGHT_RANGE * hillAmplitude * (0.8f + random.nextFloat() * 0.4f)
            val secondaryAmp = amplitude * 0.3f * hillRoughness  // Roughness controls detail
            
            for (j in 0 until numPoints) {
                val t = j.toFloat() / (numPoints - 1)  // 0 to 1 across hill
                val angle = t * 2f * kotlin.math.PI.toFloat() * baseFreq + phaseOffset
                val secondaryAngle = t * 2f * kotlin.math.PI.toFloat() * secondaryFreq + phaseOffset * 0.7f
                
                // Primary sine wave + secondary detail
                val sineValue = kotlin.math.sin(angle) * amplitude + 
                    kotlin.math.sin(secondaryAngle) * secondaryAmp
                
                // Normalize to 0.1-0.6 range (hill height values)
                val normalizedValue = (RenderConstants.HILL_BASE_HEIGHT + sineValue * 0.5f).coerceIn(0.1f, 0.6f)
                points.add(normalizedValue)
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
        // Sort by depthFactor: most negative (background) draws first
        newHills.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        hills = newHills
    }
    
    private fun generateTrees() {
        // Take snapshot of hills for tree placement (thread-safe read)
        val currentHills = hills
        if (currentHills.isEmpty() || treeDensity <= 0) {
            trees = emptyList()
            return
        }
        
        // Calculate tree count from density (0-100 -> 0-150 trees)
        val maxTrees = 150
        val treeCount = (treeDensity * maxTrees / 100f).toInt()
        if (treeCount <= 0) {
            trees = emptyList()
            return
        }
        
        // Build new list (thread-safe: atomic swap at end)
        val newTrees = ArrayList<TreeData>(treeCount)
        val random = Random(randomSeed.toLong() + 2000)  // Offset from hills
        
        // Evenly space trees across the screen width using grid placement
        // Calculate spacing based on density
        val screenWidth = 1.6f  // -0.3 to 1.3 range
        val spacing = screenWidth / (treeCount + 1)  // +1 to avoid edges
        
        for (i in 0 until treeCount) {
            // Evenly spaced x position with small random jitter (±10% of spacing)
            val baseX = -0.3f + (i + 1) * spacing
            val jitter = (random.nextFloat() - 0.5f) * spacing * 0.2f
            val xRatio = baseX + jitter
            
            // Find the best hill for this x position (foreground preferred for visibility)
            // Pick a random hill but weight towards foreground
            val hillIndex = (random.nextFloat() * random.nextFloat() * currentHills.size).toInt()
                .coerceIn(0, currentHills.size - 1)
            val hill = currentHills[hillIndex]
            
            // Get the EXACT hill surface Y at this x position
            val groundY = getHillY(xRatio, hill)
            val depthFactor = hill.depthFactor
            
            // Tree base sits ON the hill surface (no offset - tree trunk base is at ground level)
            val yRatio = groundY
            
            // With new parallax: depthFactor goes from -0.5 (background) to +0.05 (foreground)
            // Normalize to 0-1 range for scale: 0 = background (small), 1 = foreground (large)
            val normalizedDepth = ((depthFactor + 0.5f) / 0.55f).coerceIn(0f, 1f)
            
            // Tree scale: foreground trees (normalizedDepth near 1) are larger
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
        // Sort by depthFactor: LOW depthFactor (background, negative) draws first
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
        
        // Extend hills beyond screen for parallax movement
        // Extra margin on all sides to prevent visible edges during parallax
        val parallaxMargin = height * 0.3f  // 30% extra for parallax movement
        
        val startX = (RenderConstants.WORLD_MIN_X - 1.0f) * scaleX + width / 2f  // Extra left margin
        val endX = (RenderConstants.WORLD_MAX_X + 0.5f) * scaleX + width / 2f    // Extra right margin
        val stepX = (endX - startX) / (hill.controlPoints.size - 1)
        val bottomY = height + parallaxMargin  // Extend below screen
        
        hillPath.moveTo(startX, bottomY)
        
        for (i in hill.controlPoints.indices) {
            val x = startX + i * stepX
            val yRatio = hill.heightOffset + 0.12f * (1f - hill.controlPoints[i])
            val y = yRatio * height
            hillPath.lineTo(x, y)
        }
        
        hillPath.lineTo(endX, bottomY)
        hillPath.close()
        
        canvas.drawPath(hillPath, hillPaint)
    }
    
    private fun drawTree(canvas: Canvas, tree: TreeData, scaleX: Float, state: SimulationState) {
        val x = (tree.xRatio - 0.5f) * scaleX + width / 2f
        // yRatio is the ground level where tree BASE sits
        val groundY = tree.yRatio * height
        
        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        
        // Wind sway: oscillating back and forth
        // Add minimum wind for subtle ambient motion even at wind=0
        // This creates a "living" feel to the trees
        val baseWindStrength = state.windStrength.coerceIn(0f, 1f)
        val ambientWind = 0.15f  // Minimum subtle movement
        val effectiveWind = ambientWind + baseWindStrength * (1f - ambientWind)
        val timeSec = state.timeSeconds
        
        // Base sway frequency: slower for calm, faster for windy
        // Range: 0.8x to 2.5x base frequency
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * (0.8f + effectiveWind * 1.7f)
        
        // Spatial coherence: trees close on X axis sway together (like wind gusts)
        // Use low-frequency spatial variation so nearby trees have similar phases
        // tree.xRatio * 2 means trees within 0.5 screen width sway similarly
        val spatialPhase = tree.xRatio * 2f
        
        // Time-traveling wave: wind gusts move across the scene
        // Wave moves from left to right at wind-dependent speed
        val gustWaveSpeed = 0.3f + effectiveWind * 0.5f
        val gustWave = sin(timeSec * gustWaveSpeed - spatialPhase * 3f) * 0.5f + 0.5f
        
        // Maximum sway amplitude at tree top, modulated by gusts
        // Even at low wind, allow subtle movement from ambient wind
        val gustModulation = 0.5f + gustWave * 0.5f  // 0.5 to 1.0
        val maxAmplitude = totalHeight * RenderConstants.TREE_SWAY_AMPLITUDE * effectiveWind * 2.5f * gustModulation
        
        // Draw tree segments from bottom to top
        // Each segment is a triangle with base at bottom, tip pointing up
        for (seg in 0 until treeSegments) {
            // Base of this segment (starts at ground for seg=0)
            val segBaseY = groundY - seg * segmentHeight * 0.7f
            // Tip of this segment (top)
            val segTipY = segBaseY - segmentHeight
            
            val widthMultiplier = 1.0f - (seg * 0.15f)
            val segWidth = segmentHeight * 0.5f * widthMultiplier * treeWidthScale
            
            // Height ratio for this segment (0 = ground, 1 = top)
            val baseHeightRatio = seg.toFloat() / treeSegments
            val tipHeightRatio = (seg + 1).toFloat() / treeSegments
            
            // Wavy motion up the tree (slight phase delay at top)
            val basePhase = spatialPhase + baseHeightRatio * 0.3f
            val tipPhase = spatialPhase + tipHeightRatio * 0.3f
            
            // Primary oscillation + secondary harmonic for organic feel
            // sin wave oscillates back and forth naturally
            val baseWave = sin(timeSec * baseFreq + basePhase) * 0.7f + 
                sin(timeSec * baseFreq * 0.4f + basePhase) * 0.3f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.7f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f
            
            // Sway increases with height (squared for natural bending curve)
            // Base of tree (on ground) has zero sway
            val baseSway = baseWave * maxAmplitude * baseHeightRatio * baseHeightRatio
            val tipSway = tipWave * maxAmplitude * tipHeightRatio * tipHeightRatio
            
            treePath.reset()
            // Triangle: tip at top (with sway), base corners at bottom (with less sway)
            treePath.moveTo(x + tipSway, segTipY)  // Top tip
            treePath.lineTo(x - segWidth + baseSway, segBaseY)  // Bottom left
            treePath.lineTo(x + segWidth + baseSway, segBaseY)  // Bottom right
            treePath.close()
            
            canvas.drawPath(treePath, treePaint)
        }
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Random seed for consistent placement
        randomSeed = prefs.getInt("pref_random_seed", 42)
        
        hillCount = prefs.getInt("pref_hill_count", 2)  // 2 hill layers
        
        // Tree density: 0-100 percentage
        treeDensity = prefs.getInt("pref_tree_density", 50)
        
        hillStartY = prefs.getInt("pref_hill_lower_start", 60) / 100f  // 60% instead of 50%
        // Hills extend below screen (1.0 = bottom edge, >1.0 = below screen)
        // This allows parallax movement to reveal more terrain
        hillEndY = prefs.getInt("pref_hill_upper_end", 95) / 100f      // 95% - closer to bottom for parallax room
        
        // Hill roughness: controls sine wave detail (0-200 -> 0.0-2.0)
        val roughVal = prefs.getInt("pref_hill_roughness", 100)
        hillRoughness = roughVal / 100f
        
        // Hill amplitude: controls hill vertical height (0-1000 -> 0.0-10.0)
        val ampVal = prefs.getInt("pref_hill_amplitude", 100)
        hillAmplitude = ampVal / 100f
        
        // Hill frequency: controls how many hills visible per layer (0-1000 -> 0.0-10.0)
        val freqVal = prefs.getInt("pref_hill_frequency", 100)
        hillFrequency = freqVal / 100f
        
        val sizeVal = prefs.getInt("pref_tree_size", 200)
        treeSizeScale = sizeVal / 200f
        
        val widthVal = prefs.getInt("pref_tree_width", 100)
        treeWidthScale = widthVal / 100f
        
        treeColorVariation = prefs.getInt("pref_tree_color_variation", 100) / 100f
        treeSegments = prefs.getInt("pref_tree_segments", 1).coerceIn(1, 5)
        
        // Wind is now from SimulationState, not a separate setting
        
        android.util.Log.d("TerrainLayer", "Reloaded: hills=$hillCount, density=$treeDensity%, freq=$hillFrequency, amp=$hillAmplitude")
        generateTerrain()
    }

    override fun destroy() {
        // Cleanup
    }
}
