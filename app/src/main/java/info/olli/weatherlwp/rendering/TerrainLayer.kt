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
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f  // Offset to center extended content
    
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
        if (currentHills.isEmpty() || treeDensity <= 0 || extendedWidth <= 0) {
            trees = emptyList()
            return
        }
        
        // Calculate tree count based on density and extended width
        // More width = more trees to maintain density
        // Density 50 = normal (50 trees base), 200 = dense forest (200 trees base)
        val widthRatio = extendedWidth / (height * RenderConstants.REFERENCE_ASPECT_RATIO).coerceAtLeast(1f)
        val baseTrees = 100  // Base number for standard density (at 100% = 100 trees)
        val treeCount = ((treeDensity / 100f) * baseTrees * widthRatio).toInt().coerceIn(0, 1000)
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
            
            // Place trees along the extended width
            for (t in 0 until hillTreeCount) {
                // Apply sparsity: randomly skip some tree positions to create gaps
                if (random.nextFloat() < treeSparsity) {
                    continue  // Skip this tree (creates a gap)
                }
                
                // xRatio is 0-1 across the extended width
                val xRatio: Float
                if (treeSpread < 0.1f) {
                    // Very low spread: only on peaks with tiny scatter
                    val peakX = peakPositions[random.nextInt(peakPositions.size)]
                    xRatio = (peakX + (random.nextFloat() - 0.5f) * 0.05f).coerceIn(0f, 1f)
                } else {
                    // Normal spread: distribute along entire extended width
                    val baseSpacing = 1.0f / hillTreeCount
                    val baseX = t * baseSpacing + random.nextFloat() * baseSpacing
                    
                    // At low spread, bias toward peaks; at high spread, allow full range
                    val nearestPeak = peakPositions.minByOrNull { kotlin.math.abs(it - baseX) } ?: 0.5f
                    xRatio = (nearestPeak + (baseX - nearestPeak) * treeSpread).coerceIn(0f, 1f)
                }
                
                // Get the hill surface Y at this X position
                val groundY = getHillY(xRatio, hill)
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

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Take local snapshots for thread-safe iteration
        val currentHills = hills
        val currentTrees = trees
        
        // Get ambient color for current time of day (global tint system)
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
            drawHill(canvas, hill, hillColor)
            
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
            drawTree(canvas, tree, state)
            
            canvas.restore()
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
    
    private fun drawTree(canvas: Canvas, tree: TreeData, state: SimulationState) {
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
        val spatialPhase = tree.xRatio * kotlin.math.PI.toFloat() * 4f  // 4 wave cycles across screen
        val waveSpeed = 0.8f + baseWindStrength * 0.6f  // Wave travels faster with more wind
        
        // The gust wave: a traveling sine wave that affects tree intensity
        // Trees at similar X positions have similar phase, creating "waves" of wind
        val gustWavePhase = timeSec * waveSpeed - spatialPhase
        val gustWave = (sin(gustWavePhase) * 0.5f + 0.5f)  // 0-1 wave intensity at this tree
        
        // When gust is active, the wave causes trees to lean in sequence
        // Local gust intensity = base gust * wave position (so gust "passes through")
        val localGust = gustIntensity * gustWave
        
        // Gust impact: when gustIntensity is high, trees lean strongly then recover
        // Use a stronger one-sided push during gusts
        val gustPush = localGust * localGust * 1.8f  // Quadratic for snappy response
        
        // Maximum sway amplitude at tree top
        // ALWAYS have ambient sway (affected by swaySpeed), plus wind effects
        // When swaySpeed=0, amplitude is 0 (frozen). When swaySpeed=1, normal ambient sway.
        val ambientSway = 0.15f * swaySpeed  // Visible ambient movement controlled by speed
        val windSway = effectiveWind * 0.3f * swaySpeed  // Wind sway also affected by speed
        val maxAmplitude = totalHeight * (ambientSway + windSway + gustPush * 0.3f)
        
        // Draw tree segments from bottom to top
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
            // Primary wave + secondary harmonic + slow drift
            val baseWave = sin(timeSec * baseFreq + basePhase) * 0.5f + 
                sin(timeSec * baseFreq * 0.4f + basePhase) * 0.3f +
                sin(timeSec * baseFreq * 2.5f + basePhase) * 0.2f
            val tipWave = sin(timeSec * baseFreq + tipPhase) * 0.5f +
                sin(timeSec * baseFreq * 0.4f + tipPhase) * 0.3f +
                sin(timeSec * baseFreq * 2.5f + tipPhase) * 0.2f
            
            // Gust push is one-directional (trees lean in wind direction then spring back)
            // The wave effect is already in gustPush via localGust
            val gustLean = gustPush
            
            // Sway increases with height squared (natural bending curve)
            // Include ambient sway even without wind (baseWave still oscillates)
            // swaySpeed directly affects the ambient movement factor
            val ambientFactor = swaySpeed * 0.4f  // Scale ambient movement by sway speed
            val baseSway = (baseWave * (effectiveWind + ambientFactor) + gustLean) * maxAmplitude * baseHeightRatio * baseHeightRatio
            val tipSway = (tipWave * (effectiveWind + ambientFactor) + gustLean) * maxAmplitude * tipHeightRatio * tipHeightRatio
            
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
        
        hillCount = prefs.getInt("pref_hill_count", 5)  // 5 hill layers default, range 0-20
        
        // Tree density: 0-200 percentage (50 = normal, 200 = dense forest)
        treeDensity = prefs.getInt("pref_tree_density", 50)
        
        // Tree spread: how far along hills trees spread (0=peaks only, 100=full hillside)
        treeSpread = prefs.getInt("pref_tree_spread", 80) / 100f
        
        // Tree sparsity: gap probability between trees (0=dense, 100=sparse)
        treeSparsity = prefs.getInt("pref_tree_sparsity", 30) / 100f
        
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
