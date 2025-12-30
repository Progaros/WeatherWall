package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
    private var treeSizeRandomness = 0.3f  // 0-1, how much size varies between trees
    private var treeSpread = 0.5f         // 0-1, how far down hills trees spread
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
            
            // Depth-based amplitude scaling: background hills have smaller amplitude
            // depth=0 (background): 40% amplitude, depth=1 (foreground): 100% amplitude
            val depthAmplitudeScale = 0.4f + depth * 0.6f
            
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
                val normalizedValue = (RenderConstants.HILL_BASE_HEIGHT + 
                    (noiseVal - 0.5f) * RenderConstants.HILL_HEIGHT_RANGE * effectiveAmplitude)
                    .coerceIn(0.1f, 0.6f)
                
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
        
        // Find peak positions (local maxima) for each hill
        // Peaks are where height values are higher than neighbors
        val hillPeaks = currentHills.map { hill ->
            val points = hill.controlPoints
            val peaks = mutableListOf<Float>()
            if (points.size >= 3) {
                for (j in 1 until points.size - 1) {
                    // Check if this point is higher (lower Y value) than neighbors
                    if (points[j] > points[j - 1] && points[j] > points[j + 1]) {
                        // Convert point index to x position ratio
                        // Hills span from WORLD_MIN_X - 1.0 to WORLD_MAX_X + 0.5
                        val hillWorldStart = RenderConstants.WORLD_MIN_X - 1.0f
                        val hillWorldEnd = RenderConstants.WORLD_MAX_X + 0.5f
                        val hillWorldWidth = hillWorldEnd - hillWorldStart
                        
                        val normalizedX = j.toFloat() / (points.size - 1)
                        val worldX = hillWorldStart + normalizedX * hillWorldWidth
                        // Convert world X to screen xRatio (worldX = xRatio - 0.5)
                        val xRatio = worldX + 0.5f
                        
                        // Only include peaks in visible range
                        if (xRatio in 0f..1f) {
                            peaks.add(xRatio)
                        }
                    }
                }
            }
            // Fallback: if no peaks found, use center positions
            if (peaks.isEmpty()) {
                peaks.add(0.25f)
                peaks.add(0.5f)
                peaks.add(0.75f)
            }
            peaks
        }
        
        // Distribute trees across all hills proportionally
        val treesPerHill = (treeCount.toFloat() / currentHills.size).toInt().coerceAtLeast(1)
        
        for (hillIndex in currentHills.indices) {
            val hill = currentHills[hillIndex]
            val peaks = hillPeaks[hillIndex]
            
            // Trees per this hill (distribute extras to foreground hills)
            val extraTrees = if (hillIndex >= currentHills.size - 2) 2 else 0  // More trees on foreground
            val hillTreeCount = (treesPerHill + extraTrees).coerceAtMost(treeCount - newTrees.size)
            
            for (t in 0 until hillTreeCount) {
                // Pick a random peak for this tree
                val peakX = peaks[random.nextInt(peaks.size)]
                
                // treeSpread controls how far from peak trees can be placed
                // 0 = exactly on peak, 1 = can spread far down the slope
                val maxSpread = 0.15f * treeSpread  // Max offset from peak
                val spreadOffset = (random.nextFloat() - 0.5f) * 2f * maxSpread
                val xRatio = (peakX + spreadOffset).coerceIn(-0.3f, 1.3f)
                
                // Get the EXACT hill surface Y at this x position
                val groundY = getHillY(xRatio, hill)
                val depthFactor = hill.depthFactor
                
                // Tree base sits ON the hill surface
                val yRatio = groundY
                
                // With new parallax: depthFactor goes from -0.5 (background) to +0.05 (foreground)
                // Normalize to 0-1 range for scale: 0 = background (small), 1 = foreground (large)
                val normalizedDepth = ((depthFactor + 0.5f) / 0.55f).coerceIn(0f, 1f)
                
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
                
                newTrees.add(TreeData(xRatio, yRatio, scale, depthFactor, dayColor))
            }
        }
        // Sort by depthFactor: LOW depthFactor (background, negative) draws first
        newTrees.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        trees = newTrees
    }
    
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        if (points.isEmpty()) return hill.heightOffset
        
        // Tree xRatio is in screen space: -0.3 to 1.3 (centered at 0.5)
        // Hill drawing uses world coordinates: WORLD_MIN_X - 1.0 to WORLD_MAX_X + 0.5
        // We need to map tree screen space to the same normalized range as hill drawing
        
        // Hill drawing spans from (WORLD_MIN_X - 1.0) to (WORLD_MAX_X + 0.5) = -5.0 to 5.5
        // That's a total width of 10.5 world units
        val hillWorldStart = RenderConstants.WORLD_MIN_X - 1.0f  // -5.0
        val hillWorldEnd = RenderConstants.WORLD_MAX_X + 0.5f    // 5.5
        val hillWorldWidth = hillWorldEnd - hillWorldStart        // 10.5
        
        // Tree drawing uses: x = (xRatio - 0.5) * scaleX + width/2
        // This maps xRatio to screen coordinates
        // scaleX = height * REFERENCE_ASPECT_RATIO, so normalized:
        // At xRatio = 0.5 -> world X = 0
        // At xRatio = 0.0 -> world X = -0.5
        // At xRatio = 1.0 -> world X = 0.5
        // So tree xRatio maps to world X as: worldX = xRatio - 0.5
        val worldX = xRatio - 0.5f
        
        // Now normalize worldX relative to hill world range
        val normalizedX = (worldX - hillWorldStart) / hillWorldWidth
        
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
            val hillColor = RenderConstants.blendWithAmbient(hill.dayColor, ambientColor, blendAmount)
            drawHill(canvas, hill, scaleX, hillColor)
            
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
    
    private fun drawHill(canvas: Canvas, hill: HillData, scaleX: Float, hillColor: Int) {
        hillPath.reset()
        
        // Extend hills beyond screen for parallax movement
        // Extra margin on all sides to prevent visible edges during parallax
        val parallaxMargin = height * 0.3f  // 30% extra for parallax movement
        
        val startX = (RenderConstants.WORLD_MIN_X - 1.0f) * scaleX + width / 2f  // Extra left margin
        val endX = (RenderConstants.WORLD_MAX_X + 0.5f) * scaleX + width / 2f    // Extra right margin
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
        
        // Apply linear gradient from top (light) to bottom (dark) for natural shading
        val gradient = LinearGradient(
            0f, minY,           // Start: top of hill (lighter)
            0f, bottomY,        // End: bottom (darker)
            hillColor,          // Top color (original)
            darkenColor(hillColor, 0.7f),  // Bottom color (darkened)
            Shader.TileMode.CLAMP
        )
        hillPaint.shader = gradient
        
        canvas.drawPath(hillPath, hillPaint)
        
        // Clear shader after drawing
        hillPaint.shader = null
    }
    
    private fun drawTree(canvas: Canvas, tree: TreeData, scaleX: Float, state: SimulationState) {
        val x = (tree.xRatio - 0.5f) * scaleX + width / 2f
        // yRatio is the ground level where tree BASE sits
        val groundY = tree.yRatio * height
        
        val totalHeight = height * RenderConstants.TREE_HEIGHT_FACTOR * tree.scale * treeSizeScale
        val segmentHeight = totalHeight / treeSegments
        
        // Wind system: base wind + gusts + multiplier
        val baseWindStrength = state.windStrength.coerceIn(0f, 1f)
        val gustIntensity = state.windGust  // 0-1 current gust strength
        val windMultiplier = state.windMultiplier  // User multiplier
        val swaySpeed = state.treeSwaySpeed        // User sway speed multiplier
        
        // Combine base wind with gust (gust adds on top)
        val effectiveWind = (baseWindStrength + gustIntensity * 0.5f).coerceIn(0f, 1f) * windMultiplier
        val timeSec = state.timeSeconds
        
        // Base sway frequency: slower for calm, faster for windy, multiplied by user speed
        val baseFreq = RenderConstants.TREE_SWAY_FREQUENCY * (0.6f + effectiveWind * 1.0f) * swaySpeed
        
        // Spatial coherence: nearby trees sway together (wind gusts are spatial)
        val spatialPhase = tree.xRatio * 2f
        
        // Time-traveling wave: wind gusts move across the scene
        val gustWaveSpeed = 0.2f + baseWindStrength * 0.4f
        val gustWave = sin(timeSec * gustWaveSpeed - spatialPhase * 3f) * 0.5f + 0.5f
        
        // Gust impact: when gustIntensity is high, trees lean strongly then recover
        // Use a stronger one-sided push during gusts
        val gustPush = gustIntensity * gustIntensity * 1.5f  // Quadratic for snappy response
        
        // Maximum sway amplitude at tree top
        // Base ambient sway + gust push
        val ambientSway = 0.08f  // Minimal ambient movement
        val windSway = effectiveWind * 0.25f  // Wind-based sway
        val maxAmplitude = totalHeight * (ambientSway + windSway + gustPush * 0.3f)
        
        // Draw tree segments from bottom to top
        for (seg in 0 until treeSegments) {
            val segBaseY = groundY - seg * segmentHeight * 0.7f
            val segTipY = segBaseY - segmentHeight
            
            val widthMultiplier = 1.0f - (seg * 0.15f)
            val segWidth = segmentHeight * 0.5f * widthMultiplier * treeWidthScale
            
            val baseHeightRatio = seg.toFloat() / treeSegments
            val tipHeightRatio = (seg + 1).toFloat() / treeSegments
            
            // Phase varies up the tree for wave-like bending
            val basePhase = spatialPhase + baseHeightRatio * 0.4f
            val tipPhase = spatialPhase + tipHeightRatio * 0.4f
            
            // Multi-frequency oscillation for organic feel
            // Primary wave + secondary harmonic + slow drift
            val baseWave = sin(timeSec * baseFreq + basePhase) * 0.5f + 
                sin(timeSec * baseFreq * 0.4f + basePhase) * 0.3f +
                sin(timeSec * baseFreq * 2.5f + basePhase) * 0.2f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.5f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f +
                sin(timeSec * baseFreq * 2.5f + tipPhase) * 0.2f
            
            // Gust push is one-directional (trees lean in wind direction then spring back)
            // During gust: lean right strongly. Recovery: oscillate back to center
            val gustLean = gustPush * (1f + gustWave * 0.3f)
            
            // Sway increases with height squared (natural bending curve)
            val baseSway = (baseWave * effectiveWind + gustLean) * maxAmplitude * baseHeightRatio * baseHeightRatio
            val tipSway = (tipWave * effectiveWind + gustLean) * maxAmplitude * tipHeightRatio * tipHeightRatio
            
            treePath.reset()
            treePath.moveTo(x + tipSway, segTipY)
            treePath.lineTo(x - segWidth + baseSway, segBaseY)
            treePath.lineTo(x + segWidth + baseSway, segBaseY)
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
        
        // Tree spread: how far down hills trees spread (0=foreground only, 100=all hills)
        treeSpread = prefs.getInt("pref_tree_spread", 50) / 100f
        
        // Tree size randomness: variation in tree sizes (0=uniform, 100=very varied)
        treeSizeRandomness = prefs.getInt("pref_tree_size_randomness", 30) / 100f
        
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
