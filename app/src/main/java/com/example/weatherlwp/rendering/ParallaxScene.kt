package com.example.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxSensorManager
import com.example.weatherlwp.weather.WeatherRepository
import java.util.Calendar
import kotlin.random.Random

/**
 * Low-poly parallax scene with layered mountains and trees.
 * Uses night-time color multiplier system from ai.md guidelines.
 * 
 * Parallax depth logic:
 * - Sky: slight opposite movement (creates depth illusion)
 * - Far mountains: barely move
 * - Near mountains: move more
 * - Far trees: move less
 * - Near trees: move most (they're closest to viewer)
 */
class ParallaxScene(private val context: Context) : ParallaxSensorManager.Listener {
    companion object {
        private const val TAG = "ParallaxScene"
        
        // Unified terrain baseline - all ground elements share this reference
        const val TERRAIN_BASELINE_RATIO = 0.70f  // 70% down the screen is the "ground line"
        const val TREE_FOOT_OFFSET = 0.02f        // Trees sit slightly into the ground
        
        // Mountain colors by time of day (from ai.md reference)
        // Night: Deep indigo/purple
        private val MOUNTAIN_NIGHT = Color.parseColor("#283593")      // Deep indigo
        private val MOUNTAIN_NIGHT_FAR = Color.parseColor("#3949AB")  // Lighter indigo for far
        
        // Dawn/Sunrise: Pinkish tones
        private val MOUNTAIN_DAWN = Color.parseColor("#8E6593")       // Pink-purple
        private val MOUNTAIN_DAWN_FAR = Color.parseColor("#B085A5")   // Lighter pink for far
        
        // Day: Natural gray-blue
        private val MOUNTAIN_DAY = Color.parseColor("#607D8B")        // Blue-gray
        private val MOUNTAIN_DAY_FAR = Color.parseColor("#90A4AE")    // Lighter gray for far
        
        // Dusk/Sunset: Warm pink-orange tones
        private val MOUNTAIN_DUSK = Color.parseColor("#8D5A6C")       // Warm mauve
        private val MOUNTAIN_DUSK_FAR = Color.parseColor("#B0849C")   // Lighter mauve for far
        
        // Tree colors by time of day
        private val TREE_NIGHT = Color.parseColor("#1A2F0F")          // Very dark green
        private val TREE_DAWN = Color.parseColor("#3A5F2F")           // Dark olive
        private val TREE_DAY = Color.parseColor("#4A6B28")            // Olive green
        private val TREE_DUSK = Color.parseColor("#3D5B28")           // Darker olive
        
        // Sky gradients
        private val SKY_DAY_TOP = Color.parseColor("#87CEEB")
        private val SKY_DAY_BOTTOM = Color.parseColor("#B0E0E6")
        private val SKY_NIGHT_TOP = Color.parseColor("#050510")
        private val SKY_NIGHT_BOTTOM = Color.parseColor("#101525")
        private val SKY_SUNSET_TOP = Color.parseColor("#FF8C42")    // Orange sunset
        private val SKY_SUNSET_BOTTOM = Color.parseColor("#FFD93D") // Golden horizon
        private val SKY_DUSK_TOP = Color.parseColor("#2D1B4E")
        private val SKY_DUSK_BOTTOM = Color.parseColor("#FF6B6B")
        private val SKY_DAWN_TOP = Color.parseColor("#4A2545")
        private val SKY_DAWN_BOTTOM = Color.parseColor("#FFB088")
        private val SKY_RAIN_TOP = Color.parseColor("#455A64")
        private val SKY_RAIN_BOTTOM = Color.parseColor("#607D8B")
    }
    
    private val sensor = ParallaxSensorManager(context)
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // Pre-allocated paints
    private val skyPaint = Paint()
    private val cloudPaint = Paint().apply { 
        color = Color.WHITE 
        style = Paint.Style.FILL 
        isAntiAlias = true 
        alpha = 200
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    private val mountainPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val starPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.FILL }

    // Reusable paths
    private val mountainPathLeft = Path()
    private val mountainPathRight = Path()
    private val treePath = Path()
    private val cloudPath = Path()

    private var ox = 0f
    private var oy = 0f
    private var targetOx = 0f  // Raw sensor input
    private var targetOy = 0f  // Raw sensor input
    private var centerDriftSpeed = 0.002f  // How fast offset drifts toward center (per frame)
    private var parallaxStrength = 1.0f
    private var treeCount = 20
    private var treeScale = 1.0f
    private var treeSegments = 3  // Global setting: 1-5 segments per tree
    private var treeColorVariation = 0.3f  // 0-1: how much color varies between trees
    private var mountainCount = 3
    private var lastMountainCount = 3         // Track for live updates
    private var mountainWidthScale = 1.0f
    private var lastMountainWidthScale = 1.0f  // Track for live updates
    private var mountainHeightScale = 1.0f     // Height multiplier (0.1 - 10.0)
    private var lastMountainHeightScale = 1.0f // Track for live updates
    private var mountainRoughness = 0.5f       // 0-1: jaggedness of mountain silhouette
    private var lastMountainRoughness = 0.5f   // Track for live updates
    
    // Wind sway for trees (from Breezy Weather)
    private var windSpeed = 0f  // 0-1 normalized wind strength
    private var windTime = 0L   // Animation time
    
    // Cloud opacity multiplier based on density setting
    private var cloudOpacityMultiplier = 0.75f
    
    // Hill vertical range settings
    private var hillLowerStart = 0.55f  // Far hills start here (default)
    private var hillUpperEnd = 0.75f    // Near hills end here (default)
    private var lastHillLowerStart = 0.55f  // Track for live updates
    private var lastHillUpperEnd = 0.75f    // Track for live updates
    
    // World generation bounds (wider than 0..1 to support landscape preview)
    // Extended to -4..5 to cover very wide aspect ratios (e.g. 4:1 preview strip)
    private val WORLD_MIN_X = -4.0f
    private val WORLD_MAX_X = 5.0f

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastTheme = ""
    private var dayNightRatio = 0f  // 0 = day, 1 = full night

    // Star positions (x ratio, y ratio, size) - tiny white dots only (per ai.md)
    private val stars = Array(100) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.5f, 0.5f + Random.nextFloat() * 2f) }

    // Cloud data (x, y, size, speed, alpha)
    private data class CloudData(
        var x: Float, 
        val y: Float, 
        val size: Float, 
        val speed: Float,
        val alpha: Int,
        val shapeSeed: Long  // Unique seed for consistent cloud shape
    )
    private val clouds = mutableListOf<CloudData>()
    private var cloudCount = 0

    private data class MountainShape(
        val stepsLeft: Int,
        val stepsRight: Int,
        val leftJitter: FloatArray,
        val rightJitter: FloatArray,
        val ridgeSteps: Int,
        val ridgeJitterX: FloatArray,
        val ridgeJitterY: FloatArray,
        // Bias in [-0.125, 0.125] (roughly). Used to shift the ridge base away from center.
        val baseCenterBias: Float,
    )

    // Mountain data (peakX, peakHeight, slopeWidth, depthFactor, shape)
    private data class MountainData(
        val peakX: Float,
        val peakHeight: Float,
        val slopeWidth: Float,
        val depthFactor: Float,
        val shape: MountainShape,
    )
    private val mountains = mutableListOf<MountainData>()

    // Tree data (x ratio, y ratio, scale, depth factor)
    private data class TreeData(val xRatio: Float, val yRatio: Float, val scale: Float, val depthFactor: Float)
    private val trees = mutableListOf<TreeData>()
    
    // Hill data (rolling terrain between mountains and trees)
    private data class HillData(val controlPoints: List<Float>, val depthFactor: Float, val heightOffset: Float, val hillIndex: Int)
    private val hills = mutableListOf<HillData>()
    private var hillCount = 3
    private val hillPath = Path()
    private val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Thread safety: lock for terrain data that may be modified from UI thread (prefs listener)
    // while render thread is iterating
    private val terrainLock = Any()

    init {
        sensor.setListener(this)
        loadSettings()
        generateMountains()
        generateHills()
        generateTrees()
    }

    fun loadSettings() {
        try {
            parallaxStrength = prefs.getInt("pref_parallax_strength", 50) / 50f
            
            // Mountain count (0-10) - use raw value
            mountainCount = prefs.getInt("pref_mountain_count", 3).coerceIn(0, 10)
            
            // Hill count (0-5) - rolling terrain layers
            hillCount = prefs.getInt("pref_hill_count", 3).coerceIn(0, 25)
            
            // Tree count (0-100) - use raw value directly
            val rawTreeCount = prefs.getInt("pref_tree_count", 20)
            treeCount = rawTreeCount.coerceIn(0, 100)
            
            // Tree size (0-1000) - 100 = 1.0x scale
            val rawTreeSize = prefs.getInt("pref_tree_size", 100)
            treeScale = if (rawTreeSize <= 0) 0.1f else {
                (rawTreeSize.toFloat() / 100f).coerceIn(0.1f, 10f)
            }
            
            // Tree segments (1-5) - global setting for all trees
            treeSegments = prefs.getInt("pref_tree_segments", 3).coerceIn(1, 5)
            
            // Mountain width (50-300) - 100 = 1.0x, default 175 = 1.75x
            val rawMountainWidth = prefs.getInt("pref_mountain_width", 175)
            mountainWidthScale = (rawMountainWidth.toFloat() / 100f).coerceIn(0.5f, 3.0f)
            
            // Mountain height (10-1000) - 100 = 1.0x scale
            val rawMountainHeight = prefs.getInt("pref_mountain_height", 100)
            mountainHeightScale = (rawMountainHeight.toFloat() / 100f).coerceIn(0.1f, 10f)
            
            // Mountain roughness (0-100) - affects jaggedness of silhouette
            val rawRoughness = prefs.getInt("pref_mountain_roughness", 50)
            mountainRoughness = (rawRoughness.toFloat() / 100f).coerceIn(0f, 1f)
            
            // Tree color variation (0-100) - how much colors differ between trees
            val rawTreeColorVar = prefs.getInt("pref_tree_color_variation", 30)
            treeColorVariation = (rawTreeColorVar.toFloat() / 100f).coerceIn(0f, 1f)
            
            // Hill vertical range settings
            // Lower start (30-70%) - where far hills appear on screen
            val rawHillLower = prefs.getInt("pref_hill_lower_start", 55)
            hillLowerStart = (rawHillLower.toFloat() / 100f).coerceIn(0.30f, 0.70f)
            
            // Upper end (60-90%) - where near hills appear on screen
            val rawHillUpper = prefs.getInt("pref_hill_upper_end", 75)
            hillUpperEnd = (rawHillUpper.toFloat() / 100f).coerceIn(0.60f, 0.90f)
            
            // Ensure proper ordering (lower < upper)
            if (hillLowerStart >= hillUpperEnd) {
                hillUpperEnd = (hillLowerStart + 0.10f).coerceAtMost(0.90f)
            }
            
            // Wind speed - check debug override first, then Breezy Weather
            val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
            val useBreezyWeather = prefs.getBoolean("pref_use_breezy_weather", true)
            windSpeed = if (debugEnabled) {
                val debugWind = prefs.getInt("pref_debug_wind_speed", 0)
                if (debugWind > 0) debugWind / 100f else if (useBreezyWeather) WeatherRepository.getNormalizedWindStrength(context) else 0f
            } else if (useBreezyWeather) {
                WeatherRepository.getNormalizedWindStrength(context)
            } else {
                0f
            }
            
            // Cloud settings - check debug override first
            val cloudsEnabled = prefs.getBoolean("pref_clouds_enabled", true)
            val cloudDensity = if (debugEnabled) {
                val debugCloud = prefs.getString("pref_debug_cloud_override", "auto") ?: "auto"
                if (debugCloud != "auto") debugCloud else prefs.getString("pref_cloud_density", "auto") ?: "auto"
            } else {
                prefs.getString("pref_cloud_density", "auto") ?: "auto"
            }
            
            // Cloud count AND opacity multiplier based on density
            // Light = few wispy clouds, Overcast = many thick clouds
            val (count, opacityMult) = if (!cloudsEnabled) Pair(0, 1.0f) else {
                when (cloudDensity) {
                    "none" -> Pair(0, 1.0f)
                    "light" -> Pair(8, 0.4f)       // Few, very transparent
                    "medium" -> Pair(15, 0.65f)   // Medium, semi-transparent
                    "heavy" -> Pair(25, 0.9f)     // Many, mostly opaque
                    "overcast" -> Pair(40, 1.1f)  // Lots, very opaque
                    "auto" -> Pair(12, 0.6f)
                    else -> Pair(12, 0.6f)
                }
            }
            cloudCount = count
            cloudOpacityMultiplier = opacityMult
            
            Log.d(TAG, "Settings loaded: trees=$treeCount, segments=$treeSegments, mountains=$mountainCount, hills=$hillCount, parallax=$parallaxStrength, mtnWidth=$mountainWidthScale, mtnHeight=$mountainHeightScale, mtnRough=$mountainRoughness, treeColorVar=$treeColorVariation, hillRange=$hillLowerStart-$hillUpperEnd, clouds=$cloudCount")

            // Regenerate mountains if count, width, height, or roughness changed
            if (mountainCount != lastMountainCount || mountainWidthScale != lastMountainWidthScale || mountainHeightScale != lastMountainHeightScale || mountainRoughness != lastMountainRoughness) {
                lastMountainCount = mountainCount
                lastMountainWidthScale = mountainWidthScale
                lastMountainHeightScale = mountainHeightScale
                lastMountainRoughness = mountainRoughness
                generateMountains()
            }
            // Regenerate hills if count OR vertical range changed
            if (hills.size != hillCount || hillLowerStart != lastHillLowerStart || hillUpperEnd != lastHillUpperEnd) {
                lastHillLowerStart = hillLowerStart
                lastHillUpperEnd = hillUpperEnd
                generateHills()
                // Trees sit on hills, so regenerate them too when hills change
                generateTrees()
            }
            if (trees.size != treeCount) {
                generateTrees()
            }
            // Regenerate clouds if count changed (happens when density setting changes)
            if (clouds.size != cloudCount) {
                generateClouds()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings", e)
        }
    }

    private fun generateMountains() {
        synchronized(terrainLock) {
            try {
                mountains.clear()
            if (mountainCount <= 0) return
            
            // Use a time-based seed for variety per session. Shapes are precomputed so they remain
            // stable frame-to-frame (no crawling/flicker).
            val random = Random(System.currentTimeMillis() / 60000)
            
            // Generate mountains with truly random positions across the screen
            for (i in 0 until mountainCount) {
                // Assign depth randomly, not based on order
                val depth = random.nextFloat()
                
                // Random X position across the wider world
                val peakX = WORLD_MIN_X + random.nextFloat() * (WORLD_MAX_X - WORLD_MIN_X)
                
                // Far mountains (low depth) are taller and more prominent
                // Near mountains (high depth) are lower to create foreground hills
                // NOTE: peakHeight is a Y ratio (0 = top). Larger value => peak is lower => mountain is shorter.
                // Keep mountains a bit broader and less tall to avoid “needle” peaks (especially in short previews).
                val baseHeight = when {
                    depth < 0.3f -> 0.32f + random.nextFloat() * 0.14f  // Far: prominent but not too tall (32-46%)
                    depth < 0.6f -> 0.38f + random.nextFloat() * 0.12f  // Mid: medium (38-50%)
                    else -> 0.45f + random.nextFloat() * 0.10f          // Near: low foothills (45-55%)
                }
                
                // Wider slopes for far mountains, narrower for near
                val slopeWidth = (when {
                    depth < 0.3f -> 0.40f + random.nextFloat() * 0.18f  // Far: wide
                    depth < 0.6f -> 0.32f + random.nextFloat() * 0.16f  // Mid: medium
                    else -> 0.24f + random.nextFloat() * 0.14f          // Near: still broad, avoids slim spikes
                }) * mountainWidthScale

                val shape = createMountainShape(random, slopeWidth)
                
                mountains.add(MountainData(
                    peakX = peakX,
                    peakHeight = baseHeight,
                    slopeWidth = slopeWidth,
                    depthFactor = 0.10f + depth * 0.50f,  // Far = 0.10, near = 0.60 (stronger parallax)
                    shape = shape,
                ))
            }
            
            // Sort by depth (far mountains first = drawn first = behind)
            mountains.sortBy { it.depthFactor }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating mountains", e)
            }
        }
    }

    private fun createMountainShape(random: Random, slopeWidth: Float): MountainShape {
        // Wider mountains get more facets; narrower get fewer.
        val stepsBase = (14 + (slopeWidth * 20f).toInt()).coerceIn(14, 26)
        val stepsLeft = (stepsBase + random.nextInt(5)).coerceIn(14, 28)
        val stepsRight = (stepsBase + random.nextInt(5)).coerceIn(14, 28)
        val ridgeSteps = (7 + random.nextInt(5)).coerceIn(7, 12)

        // Roughness affects jitter magnitude (0 = smooth, 1 = very jagged)
        val roughnessMult = 0.3f + mountainRoughness * 1.4f  // 0.3 to 1.7
        
        fun nextJitter(): Float {
            // Bias towards smaller jitter; still allows sharp spikes.
            val signed = random.nextFloat() * 2f - 1f
            return signed * random.nextFloat() * roughnessMult
        }

        val leftJitter = FloatArray((stepsLeft - 1).coerceAtLeast(0)) { nextJitter() }
        val rightJitter = FloatArray((stepsRight - 1).coerceAtLeast(0)) { nextJitter() }
        val ridgeJitterX = FloatArray((ridgeSteps - 1).coerceAtLeast(0)) { nextJitter() }
        val ridgeJitterY = FloatArray((ridgeSteps - 1).coerceAtLeast(0)) { nextJitter() }

        // Keep the ridge base slightly off-center for asymmetry.
        val baseCenterBias = ((random.nextFloat() - 0.5f) * 0.25f).coerceIn(-0.125f, 0.125f)

        return MountainShape(
            stepsLeft = stepsLeft,
            stepsRight = stepsRight,
            leftJitter = leftJitter,
            rightJitter = rightJitter,
            ridgeSteps = ridgeSteps,
            ridgeJitterX = ridgeJitterX,
            ridgeJitterY = ridgeJitterY,
            baseCenterBias = baseCenterBias,
        )
    }

    private fun multiplyColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun envelope(t: Float): Float {
        // 0 at endpoints, 1 near center. (t*(1-t)*4) peaks at 1 when t=0.5
        return (t * (1f - t) * 4f).coerceIn(0f, 1f)
    }

    /**
     * Generate rolling hills between mountains and trees (per ai.md layer order).
     * Uses simple sine wave composition for smooth terrain.
     * Hills are generated with consistent seeds so trees can sample their heights.
     */
    private fun generateHills() {
        synchronized(terrainLock) {
            try {
                hills.clear()
                if (hillCount <= 0) return
                
                // Use fixed seed for consistent terrain that trees can reference
                val random = Random(12345)
            
            // Generate multiple hill layers with different depths
            for (i in 0 until hillCount) {
                // Control points for the rolling hill shape (WORLD_MIN_X to WORLD_MAX_X range)
                // These define height offsets at evenly spaced x positions
                // Increase points to cover wider area (~9x original density for -4..5 range)
                val numPoints = 60 + random.nextInt(20)
                val points = mutableListOf<Float>()
                
                for (j in 0 until numPoints) {
                    // Smooth random heights using sin combination
                    val baseHeight = 0.3f + random.nextFloat() * 0.2f  // 30-50% of remaining space
                    points.add(baseHeight)
                }
                
                // Hills go from far (back) to near (front)
                // Near hills have higher depthFactor (more parallax)
                val depth = (i.toFloat() / hillCount.coerceAtLeast(1)) // 0.0 to ~1.0
                val depthFactor = 0.3f + depth * 0.2f  // 0.3 (far) to 0.5 (near) - matching tree parallax
                
                // Far hills (depth=0) are at hillLowerStart, near hills (depth=1) at hillUpperEnd
                // This creates proper depth perception with configurable vertical range
                val heightOffset = hillLowerStart + depth * (hillUpperEnd - hillLowerStart)
                
                hills.add(HillData(
                    controlPoints = points,
                    depthFactor = depthFactor,
                    heightOffset = heightOffset,
                    hillIndex = i
                ))
            }
            
            // Sort by depth (far hills first = drawn first = behind)
            hills.sortBy { it.depthFactor }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating hills", e)
            }
        }
    }

    /**
     * Get the Y position of a hill at a specific X ratio (WORLD_MIN_X to WORLD_MAX_X).
     * Uses linear interpolation between control points to match drawHill's geometry.
     */
    private fun getHillY(xRatio: Float, hill: HillData): Float {
        val points = hill.controlPoints
        if (points.isEmpty()) return hill.heightOffset
        
        // Map xRatio (WORLD_MIN_X to WORLD_MAX_X) to segment index
        val normalizedX = (xRatio - WORLD_MIN_X) / (WORLD_MAX_X - WORLD_MIN_X)
        val indexFloat = normalizedX * (points.size - 1)
        val index = indexFloat.toInt()
        val fraction = indexFloat - index
        
        if (index >= points.size - 1) return hill.heightOffset + 0.12f * (1f - points.last())
        
        val p1 = points[index]
        val p2 = points[index + 1]
        
        // Linear interpolation of the height control value (0.0-1.0)
        // 1.0 = top of hill variance, 0.0 = bottom
        val interpolatedControl = p1 + fraction * (p2 - p1)
        
        // Convert to actual Y ratio
        // hillHeight is 0.12f (from drawHill)
        // value is inverted in drawHill: baseY + hillHeight * (1f - controlPoint)
        return hill.heightOffset + 0.12f * (1f - interpolatedControl)
    }

    private fun generateTrees() {
        synchronized(terrainLock) {
            try {
                trees.clear()
                if (treeCount <= 0) return
                
                val random = Random(42)

                // Generate trees that sit on the terrain
                // Trees are placed relative to the unified TERRAIN_BASELINE_RATIO
                for (i in 0 until treeCount) {
                    val xRatio = WORLD_MIN_X + random.nextFloat() * (WORLD_MAX_X - WORLD_MIN_X)
                    
                    // Pick a random hill layer to sit on
                    // Bias towards nearer hills (higher index)
                    val hillIndex = random.nextInt(hills.size)
                    val hill = hills[hillIndex]
                    
                    // Get exact Y on the hill surface
                    val groundY = getHillY(xRatio, hill)
                    
                    // Tree sits on this ground Y
                    // Add offset to make tree sit slightly INTO the ground (larger Y = lower)
                    val yRatio = groundY + TREE_FOOT_OFFSET
                    
                    // Use the hill's depth factor so they move together
                    val depthFactor = hill.depthFactor
                    
                    // Scale based on depth (nearer = larger)
                    // Normalize depth from 0.3-0.5 range
                    val normalizedDepth = ((depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                    val scale = 0.3f + normalizedDepth * 0.7f
                    
                    trees.add(TreeData(
                        xRatio = xRatio,
                        yRatio = yRatio,
                        scale = scale,
                        depthFactor = depthFactor
                    ))
                }

                // Sort by depth (far trees drawn first, behind near trees)
                trees.sortBy { it.depthFactor }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating trees", e)
            }
        }
    }

    private fun generateClouds() {
        synchronized(terrainLock) {
            try {
                clouds.clear()
                if (cloudCount <= 0) return

                val random = Random(System.currentTimeMillis())
                
                for (i in 0 until cloudCount) {
                    // Random position in the wider world
                    val x = WORLD_MIN_X + random.nextFloat() * (WORLD_MAX_X - WORLD_MIN_X)
                    // Height: Top 40% of screen
                    val y = 0.05f + random.nextFloat() * 0.35f
                    
                    // Size variation - more variety (small wispy to large fluffy)
                    val size = 0.08f + random.nextFloat() * 0.25f
                    
                    // Speed: smaller clouds drift faster (appear higher/further)
                    val speed = 0.0003f + (1f - size / 0.33f) * 0.001f + random.nextFloat() * 0.0005f
                    
                    // Alpha based on size: smaller = more transparent (distant), larger = more opaque
                    // Small clouds (0.08): ~60 alpha, Large clouds (0.33): ~200 alpha
                    val sizeNormalized = ((size - 0.08f) / 0.25f).coerceIn(0f, 1f)
                    val alpha = (60 + sizeNormalized * 140).toInt() + random.nextInt(30)
                    
                    clouds.add(CloudData(x, y, size, speed, alpha.coerceIn(50, 220), random.nextLong()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating clouds", e)
            }
        }
    }

    // Zeichnet eine "fluffige" Wolke statt eines Kreises (from ai.md)
    // Added shape variation for more natural-looking clouds
    private fun drawFluffyCloud(canvas: Canvas, centerX: Float, centerY: Float, size: Float, paint: Paint, shapeSeed: Long) {
        cloudPath.reset()  // Reuse pre-allocated path
        
        // Use stable seed for consistent shape (doesn't change as cloud moves)
        val shapeRandom = Random(shapeSeed)
        
        // Basis-Form: Ein breites Oval in der Mitte (für die flache Unterseite)
        // Add variation to base shape
        val widthMult = 1.3f + shapeRandom.nextFloat() * 0.4f  // 1.3-1.7
        val heightMult = 0.6f + shapeRandom.nextFloat() * 0.4f  // 0.6-1.0
        val width = size * widthMult
        val height = size * heightMult
        val rect = RectF(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
        cloudPath.addOval(rect, Path.Direction.CW)

        // "Fluffiness" hinzufügen: 3-5 zufällige Kreise oben drauf setzen
        // Number of puffs varies per cloud
        val numPuffs = 3 + shapeRandom.nextInt(3)  // 3-5 puffs
        
        for (i in 0 until numPuffs) {
            // Randomize position around top of cloud
            val angle = -0.8f + shapeRandom.nextFloat() * 1.6f  // -0.8 to 0.8 (mostly top half)
            val dist = 0.3f + shapeRandom.nextFloat() * 0.3f   // 0.3-0.6 of size
            val puffX = centerX + kotlin.math.cos(angle.toDouble()).toFloat() * size * dist
            val puffY = centerY - size * (0.1f + shapeRandom.nextFloat() * 0.4f)  // Above center
            val puffSize = size * (0.35f + shapeRandom.nextFloat() * 0.35f)  // 0.35-0.7 of size
            
            cloudPath.addCircle(puffX, puffY, puffSize, Path.Direction.CW)
        }

        canvas.drawPath(cloudPath, paint)
    }

    private fun getThemeName(): String {
        // Check for debug override
        val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        if (debugEnabled) {
            val debugTime = prefs.getString("pref_debug_time", "auto") ?: "auto"
            if (debugTime != "auto") {
                return debugTime
            }
        }
        
        // Use time of day (either real or manual)
        val hour = getTimeOfDay().toInt()
        val weather = prefs.getString("pref_weather_type", "none") ?: "none"

        return when {
            weather == "rain" || weather == "snow" -> "rain"
            hour in 21..23 || hour in 0..4 -> "night"
            hour in 5..6 || hour in 18..20 -> "dusk"
            else -> "day"
        }
    }
    
    /**
     * Linear interpolation between two colors.
     */
    private fun lerpColor(startColor: Int, endColor: Int, ratio: Float): Int {
        val r = (Color.red(startColor) + ratio * (Color.red(endColor) - Color.red(startColor))).toInt()
        val g = (Color.green(startColor) + ratio * (Color.green(endColor) - Color.green(startColor))).toInt()
        val b = (Color.blue(startColor) + ratio * (Color.blue(endColor) - Color.blue(startColor))).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
    
    /**
     * Apply night-time blue overlay multiplier to a color.
     * Per ai.md: "deep blue overlay applied to landscape elements at night"
     */
    private fun applyNightMultiplier(baseColor: Int, nightRatio: Float): Int {
        if (nightRatio <= 0f) return baseColor
        
        // Deep blue overlay - intensity based on nightRatio
        val blueR = 0.1f    // Slight red reduction
        val blueG = 0.15f   // More green reduction  
        val blueB = 0.4f    // Boost blue channel
        
        val r = (Color.red(baseColor) * (1 - nightRatio * blueR)).toInt()
        val g = (Color.green(baseColor) * (1 - nightRatio * blueG)).toInt()
        val b = (Color.blue(baseColor) + (255 - Color.blue(baseColor)) * nightRatio * blueB).toInt()
        
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
    
    /**
     * Calculate day/night ratio (0.0 = day, 1.0 = night) based on actual sunrise/sunset
     * from Breezy Weather if available, otherwise falls back to getTimeOfDay().
     * Uses 45-minute transition periods for smooth dawn/dusk.
     */
    private fun updateDayNightRatio() {
        val autoDayNight = prefs.getBoolean("pref_daynight_enabled", true)
        if (!autoDayNight) {
            // Use manual time-based calculation
            val time = getTimeOfDay()
            dayNightRatio = when {
                time >= 21f || time < 5f -> 1.0f
                time < 7f -> 1.0f - (time - 5f) / 2f  // Dawn
                time < 17f -> 0.0f
                else -> (time - 17f) / 4f  // Dusk
            }
            return
        }
        
        // Try to use Breezy Weather sunrise/sunset data
        val sunriseSec = WeatherRepository.getSunrise(context)
        val sunsetSec = WeatherRepository.getSunset(context)
        
        // Fallback to time-based if no valid data (stored as Unix seconds)
        if (sunriseSec <= 0L || sunsetSec <= 0L) {
            val time = getTimeOfDay()
            dayNightRatio = when {
                time >= 21f || time < 5f -> 1.0f
                time < 7f -> 1.0f - (time - 5f) / 2f
                time < 17f -> 0.0f
                else -> (time - 17f) / 4f
            }
            return
        }
        
        val now = System.currentTimeMillis()
        val sunrise = sunriseSec * 1000L
        val sunset = sunsetSec * 1000L
        val transitionDuration = 45 * 60 * 1000L  // 45 minutes for smooth dawn/dusk
        
        dayNightRatio = when {
            // Night (before dawn)
            now < (sunrise - transitionDuration) -> 1.0f
            
            // Dawn (sunrise transition: night -> day)
            now < (sunrise + transitionDuration) -> {
                val progress = (now - (sunrise - transitionDuration)).toFloat() / (transitionDuration * 2)
                1.0f - progress.coerceIn(0f, 1f)
            }
            
            // Day
            now < (sunset - transitionDuration) -> 0.0f
            
            // Dusk (sunset transition: day -> night)
            now < (sunset + transitionDuration) -> {
                val progress = (now - (sunset - transitionDuration)).toFloat() / (transitionDuration * 2)
                progress.coerceIn(0f, 1f)
            }
            
            // Night (after dusk)
            else -> 1.0f
        }
    }
    
    /**
     * Get continuous time progress through the day (0.0 to 24.0)
     * Uses real time if auto day/night is enabled, otherwise manual time setting.
     */
    private fun getTimeOfDay(): Float {
        val autoDayNight = prefs.getBoolean("pref_daynight_enabled", true)
        return if (autoDayNight) {
            val cal = Calendar.getInstance()
            cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        } else {
            // Use manual time setting (0-1439 minutes) -> convert to hours
            val minutes = prefs.getInt("pref_manual_time", 720)  // Default noon (12:00)
            minutes / 60f
        }
    }
    
    /**
     * Get mountain color based on continuous time with smooth transitions.
     * Times: Night (21-5), Dawn (5-7), Day (7-17), Dusk (17-21)
     */
    private fun getMountainColor(isFar: Boolean): Int {
        val time = getTimeOfDay()
        
        // Define time boundaries
        val nightEnd = 5f
        val dawnEnd = 7f
        val dayEnd = 17f
        val duskEnd = 21f
        
        val nearColor: Int
        val farColor: Int
        
        when {
            // Night (21:00 - 5:00)
            time >= duskEnd || time < nightEnd -> {
                nearColor = MOUNTAIN_NIGHT
                farColor = MOUNTAIN_NIGHT_FAR
            }
            // Dawn transition (5:00 - 7:00)
            time < dawnEnd -> {
                val progress = (time - nightEnd) / (dawnEnd - nightEnd)
                nearColor = lerpColor(MOUNTAIN_NIGHT, MOUNTAIN_DAWN, progress)
                farColor = lerpColor(MOUNTAIN_NIGHT_FAR, MOUNTAIN_DAWN_FAR, progress)
            }
            // Morning transition to day (7:00 - 9:00)
            time < 9f -> {
                val progress = (time - dawnEnd) / 2f
                nearColor = lerpColor(MOUNTAIN_DAWN, MOUNTAIN_DAY, progress)
                farColor = lerpColor(MOUNTAIN_DAWN_FAR, MOUNTAIN_DAY_FAR, progress)
            }
            // Day (9:00 - 17:00)
            time < dayEnd -> {
                nearColor = MOUNTAIN_DAY
                farColor = MOUNTAIN_DAY_FAR
            }
            // Dusk transition (17:00 - 21:00)
            else -> {
                val progress = (time - dayEnd) / (duskEnd - dayEnd)
                if (progress < 0.5f) {
                    // First half: day to dusk
                    val p = progress * 2f
                    nearColor = lerpColor(MOUNTAIN_DAY, MOUNTAIN_DUSK, p)
                    farColor = lerpColor(MOUNTAIN_DAY_FAR, MOUNTAIN_DUSK_FAR, p)
                } else {
                    // Second half: dusk to night
                    val p = (progress - 0.5f) * 2f
                    nearColor = lerpColor(MOUNTAIN_DUSK, MOUNTAIN_NIGHT, p)
                    farColor = lerpColor(MOUNTAIN_DUSK_FAR, MOUNTAIN_NIGHT_FAR, p)
                }
            }
        }
        
        return if (isFar) farColor else nearColor
    }
    
    /**
     * Get tree color based on continuous time.
     */
    private fun getTreeColor(): Int {
        val time = getTimeOfDay()
        
        return when {
            time >= 21f || time < 5f -> TREE_NIGHT
            time < 7f -> {
                val progress = (time - 5f) / 2f
                lerpColor(TREE_NIGHT, TREE_DAWN, progress)
            }
            time < 9f -> {
                val progress = (time - 7f) / 2f
                lerpColor(TREE_DAWN, TREE_DAY, progress)
            }
            time < 17f -> TREE_DAY
            time < 19f -> {
                val progress = (time - 17f) / 2f
                lerpColor(TREE_DAY, TREE_DUSK, progress)
            }
            else -> {
                val progress = (time - 19f) / 2f
                lerpColor(TREE_DUSK, TREE_NIGHT, progress)
            }
        }
    }
    
    /**
     * Get hill color based on continuous time (medium green, between mountains and trees).
     */
    private fun getHillColor(isFar: Boolean): Int {
        val time = getTimeOfDay()
        
        // Hill colors are lighter/less saturated than trees
        // Day:   #4A7D3F (far) to #5D9B4E (near)
        // Night: #1A3020 (far) to #264030 (near)
        // Dawn/Dusk: #3A6535 (far) to #4A8045 (near)
        
        val (far, near) = when {
            time >= 21f || time < 5f -> 0xFF1A3020.toInt() to 0xFF264030.toInt() // Night
            time < 7f -> {
                val progress = (time - 5f) / 2f
                val farColor = lerpColor(0xFF1A3020.toInt(), 0xFF3A6535.toInt(), progress)
                val nearColor = lerpColor(0xFF264030.toInt(), 0xFF4A8045.toInt(), progress)
                farColor to nearColor
            }
            time < 9f -> {
                val progress = (time - 7f) / 2f
                val farColor = lerpColor(0xFF3A6535.toInt(), 0xFF4A7D3F.toInt(), progress)
                val nearColor = lerpColor(0xFF4A8045.toInt(), 0xFF5D9B4E.toInt(), progress)
                farColor to nearColor
            }
            time < 17f -> 0xFF4A7D3F.toInt() to 0xFF5D9B4E.toInt() // Day
            time < 19f -> {
                val progress = (time - 17f) / 2f
                val farColor = lerpColor(0xFF4A7D3F.toInt(), 0xFF3A6535.toInt(), progress)
                val nearColor = lerpColor(0xFF5D9B4E.toInt(), 0xFF4A8045.toInt(), progress)
                farColor to nearColor
            }
            else -> {
                val progress = (time - 19f) / 2f
                val farColor = lerpColor(0xFF3A6535.toInt(), 0xFF1A3020.toInt(), progress)
                val nearColor = lerpColor(0xFF4A8045.toInt(), 0xFF264030.toInt(), progress)
                farColor to nearColor
            }
        }
        
        return if (isFar) far else near
    }
    
    /**
     * Get sky gradient colors based on continuous time.
     * Includes distinct sunset (golden/orange) phase between day and dusk.
     */
    private fun getSkyColors(): Pair<Int, Int> {
        val time = getTimeOfDay()
        
        return when {
            // Night (21:00 - 5:00)
            time >= 21f || time < 5f -> SKY_NIGHT_TOP to SKY_NIGHT_BOTTOM
            // Pre-dawn (5:00 - 6:00)
            time < 6f -> {
                val progress = (time - 5f)
                lerpColor(SKY_NIGHT_TOP, SKY_DAWN_TOP, progress) to
                    lerpColor(SKY_NIGHT_BOTTOM, SKY_DAWN_BOTTOM, progress)
            }
            // Dawn to day (6:00 - 8:00)
            time < 8f -> {
                val progress = (time - 6f) / 2f
                lerpColor(SKY_DAWN_TOP, SKY_DAY_TOP, progress) to
                    lerpColor(SKY_DAWN_BOTTOM, SKY_DAY_BOTTOM, progress)
            }
            // Day (8:00 - 17:00)
            time < 17f -> SKY_DAY_TOP to SKY_DAY_BOTTOM
            // Sunset golden hour (17:00 - 18:30)
            time < 18.5f -> {
                val progress = (time - 17f) / 1.5f
                lerpColor(SKY_DAY_TOP, SKY_SUNSET_TOP, progress) to
                    lerpColor(SKY_DAY_BOTTOM, SKY_SUNSET_BOTTOM, progress)
            }
            // Sunset to dusk (18:30 - 20:00)
            time < 20f -> {
                val progress = (time - 18.5f) / 1.5f
                lerpColor(SKY_SUNSET_TOP, SKY_DUSK_TOP, progress) to
                    lerpColor(SKY_SUNSET_BOTTOM, SKY_DUSK_BOTTOM, progress)
            }
            // Dusk to night (20:00 - 21:00)
            else -> {
                val progress = (time - 20f)
                lerpColor(SKY_DUSK_TOP, SKY_NIGHT_TOP, progress) to
                    lerpColor(SKY_DUSK_BOTTOM, SKY_NIGHT_BOTTOM, progress)
            }
        }
    }

    fun start() = sensor.start()
    fun stop() = sensor.stop()

    override fun onOffsetChanged(x: Float, y: Float) {
        // Store raw sensor offset - actual ox/oy will drift toward center
        targetOx = x * parallaxStrength
        targetOy = y * parallaxStrength
        
        // Immediately follow sensor but with a slight bias toward center
        // This creates a "floating" effect where the scene slowly returns to center
        ox = targetOx * 0.85f  // Reduce immediate effect slightly
        oy = targetOy * 0.85f
    }

    fun render(canvas: Canvas) {
        try {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "Invalid canvas size: ${w}x${h}")
                return
            }
            
            // Slowly drift offset toward center so movement is always visible
            // This prevents the parallax from getting "stuck" at an extreme angle
            ox *= (1f - centerDriftSpeed)
            oy *= (1f - centerDriftSpeed)
            
            // Update wind animation time
            windTime = System.currentTimeMillis()
            
            val theme = getThemeName()
            val timeOfDay = getTimeOfDay()

            // Calculate horizontal scale to maintain aspect ratio
            // Always base scaling on height to maintain consistent proportions
            // regardless of canvas width (preview strip vs actual wallpaper).
            // 0.5625 = 9/16 ratio, so on a 9:16 phone, scaleX ≈ screen width
            val scaleX = h * 0.5625f

            // Update day/night ratio using Breezy Weather sunrise/sunset when available
            updateDayNightRatio()

            // Get continuous sky colors based on time
            val (skyTop, skyBottom) = if (theme == "rain") {
                SKY_RAIN_TOP to SKY_RAIN_BOTTOM
            } else {
                getSkyColors()
            }
            
            // Always update sky gradient for smooth transitions
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                skyTop, skyBottom,
                Shader.TileMode.CLAMP
            )
            
            if (theme != lastTheme || w.toInt() != lastWidth || h.toInt() != lastHeight) {
                lastTheme = theme
                lastWidth = w.toInt()
                lastHeight = h.toInt()
                Log.d(TAG, "Scene update: theme=$theme, time=$timeOfDay, dayNight=${prefs.getBoolean("pref_daynight_enabled", true)}, manualTime=${prefs.getInt("pref_manual_time", 12)}, size=${w.toInt()}x${h.toInt()}, mountains=${mountains.size}, trees=${trees.size}")
            }

            // Layer 0: Sky gradient (moves OPPOSITE direction for depth illusion - ai.md spec)
            // Sky should appear far behind, moving opposite to foreground
            val overscan = 300f + kotlin.math.abs(ox) * 0.4f + kotlin.math.abs(oy) * 0.4f
            canvas.save()
            canvas.translate(-ox * 0.15f, -oy * 0.08f)  // Opposite direction, subtle movement
            canvas.drawRect(-overscan, -overscan, w + overscan, h + overscan, skyPaint)
            canvas.restore()

            // Stars (night/dusk only) - tiny white dots per ai.md with parallax similar to mountains
            if (theme == "night" || theme == "dusk") {
                val time = System.currentTimeMillis()
                val starAlphaBase = if (theme == "night") 150 else 80
                // Stars move with mountains (far background, noticeable parallax)
                val starParallaxX = ox * 0.30f
                val starParallaxY = oy * 0.15f
                for (star in stars) {
                    val twinkle = kotlin.math.sin((time * 0.001 + star[0] * 10).toDouble())
                    val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(30, 220)
                    starPaint.alpha = alpha
                    canvas.drawCircle(star[0] * w + starParallaxX, star[1] * h + starParallaxY, star[2], starPaint)
                }
            }

            // Clouds (Layer 0.5) - Behind mountains
            // Synchronized to prevent ConcurrentModificationException from preference changes
            synchronized(terrainLock) {
                if (cloudCount > 0) {
                    val cloudParallaxX = -ox * 0.08f // Move slightly with sky
                    val cloudParallaxY = -oy * 0.04f
                    
                    for (cloud in clouds) {
                        // Animate cloud position (drift right)
                        cloud.x += cloud.speed
                        if (cloud.x > WORLD_MAX_X) {
                        cloud.x = WORLD_MIN_X
                    }
                    
                    // Apply density-based opacity multiplier to base cloud alpha
                    val effectiveAlpha = (cloud.alpha * cloudOpacityMultiplier).toInt().coerceIn(20, 255)
                    cloudPaint.alpha = effectiveAlpha
                    // Apply night dimming to clouds too
                    val cloudColor = applyNightMultiplier(Color.WHITE, dayNightRatio)
                    cloudPaint.color = Color.argb(effectiveAlpha, Color.red(cloudColor), Color.green(cloudColor), Color.blue(cloudColor))
                    
                    val cx = (cloud.x - 0.5f) * scaleX + w / 2f + cloudParallaxX
                    val cy = cloud.y * h + cloudParallaxY
                    val cSize = cloud.size * h * 0.5f // Scale relative to screen height
                    
                    drawFluffyCloud(canvas, cx, cy, cSize, cloudPaint, cloud.shapeSeed)
                }
            }

            // Layer 1-N: Mountains (sorted by depth)
            // Per ai.md: Mountains have very subtle parallax (0.05-0.15), almost static
            val mountainNearColor = getMountainColor(isFar = false)
            val mountainFarColor = getMountainColor(isFar = true)
            
            for (mountain in mountains) {
                // Atmospheric perspective: far mountains (low depthFactor) are lighter/hazier
                // depthFactor ranges from ~0.10 (far) to ~0.60 (near) for color blending
                val normalizedDepth = ((mountain.depthFactor - 0.10f) / 0.50f).coerceIn(0f, 1f)
                
                // Blend between far and near mountain colors based on depth
                val blendedColor = lerpColor(mountainFarColor, mountainNearColor, normalizedDepth)
                val mountainColor = applyNightMultiplier(blendedColor, dayNightRatio)
                
                mountainPaint.color = mountainColor
                
                // Mountains have noticeable parallax (0.25-0.45) - more than trees
                val mountainParallax = 0.25f + normalizedDepth * 0.20f
                canvas.save()
                canvas.translate(ox * mountainParallax, oy * mountainParallax * 0.3f)
                drawMountain(canvas, w, h, mountain, scaleX)
                canvas.restore()
            }

            // Hills layer (rolling terrain between mountains and trees)
            // Hills and trees share the same parallax range (0.3-0.5) so they move together
            val hillNearColor = getHillColor(isFar = false)
            val hillFarColor = getHillColor(isFar = true)
            
            for (hill in hills) {
                // Normalize depth within the hill range (0.3-0.5)
                val normalizedDepth = ((hill.depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                val blendedColor = lerpColor(hillFarColor, hillNearColor, normalizedDepth)
                val hillColor = applyNightMultiplier(blendedColor, dayNightRatio)
                
                hillPaint.color = hillColor
                
                // Hills barely move like trees (0.05-0.12) - foreground terrain
                val normalizedHillParallax = ((hill.depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                val hillParallax = 0.05f + normalizedHillParallax * 0.07f
                canvas.save()
                canvas.translate(ox * hillParallax, oy * hillParallax * 0.3f)
                drawHill(canvas, w, h, hill.controlPoints, hill.heightOffset, scaleX)
                canvas.restore()
            }

            // Layer N+: Trees (sorted by depth, use same parallax as hills so they move together)
            val baseTreeColor = getTreeColor()
            for ((treeIndex, tree) in trees.withIndex()) {
                // Far trees (low depthFactor) are darker, near trees (high depthFactor) are brighter
                // tree.depthFactor ranges from 0.3 to 0.5 (same as hills)
                val normalizedTreeDepth = ((tree.depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                val brightnessMultiplier = 0.6f + normalizedTreeDepth * 0.4f  // 0.6 (far/dark) to 1.0 (near/bright)
                
                // Add color variation per tree (uses tree index as seed for consistency)
                val treeRandom = Random(treeIndex * 12345L)
                val variation = (treeRandom.nextFloat() - 0.5f) * 2f * treeColorVariation  // -var to +var
                
                // Mostly green, but can shift towards brown (reduce green, increase red slightly)
                val brownShift = if (variation > 0) variation * 0.3f else 0f  // Only positive variation adds brown
                val greenShift = variation * 0.15f  // Slight green variation
                
                val brightnessAdjusted = Color.rgb(
                    ((Color.red(baseTreeColor) * brightnessMultiplier) * (1f + brownShift)).toInt().coerceIn(0, 255),
                    ((Color.green(baseTreeColor) * brightnessMultiplier) * (1f + greenShift - brownShift * 0.5f)).toInt().coerceIn(0, 255),
                    (Color.blue(baseTreeColor) * brightnessMultiplier).toInt().coerceIn(0, 255)
                )
                // Apply night-time blue overlay
                val adjustedColor = applyNightMultiplier(brightnessAdjusted, dayNightRatio)
                treePaint.color = adjustedColor

                // Trees barely move (0.05-0.12) - they're in foreground, closest to viewer
                // Normalized from depthFactor 0.3-0.5 to parallax 0.05-0.12
                val normalizedTreeParallax = ((tree.depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                val treeParallax = 0.05f + normalizedTreeParallax * 0.07f
                canvas.save()
                canvas.translate(ox * treeParallax, oy * treeParallax * 0.3f)
                drawTree(canvas, tree.xRatio, h * tree.yRatio, tree.scale * treeScale, treeSegments, w, scaleX, tree.depthFactor)
                canvas.restore()
            }
            } // End synchronized(terrainLock)
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering scene", e)
        }
    }

    /**
     * Draw a jagged low-poly mountain with a split ridge (light/shadow sides).
     * The silhouette and ridge are deterministic (precomputed in MountainData.shape).
     */
    private fun drawMountain(canvas: Canvas, w: Float, h: Float, mountain: MountainData, scaleX: Float) {
        mountainPathLeft.reset()
        mountainPathRight.reset()

        val peakCenterX = (mountain.peakX - 0.5f) * scaleX + w / 2f
        val baseY = h
        
        // Apply height scale: mountainHeightScale affects how far the peak extends upward
        // peakHeight is a Y ratio (0 = top, 1 = bottom). Lower value = taller mountain.
        // With heightScale > 1, we reduce peakY to make the mountain extend higher.
        // Formula: original height = baseY - (h * peakHeight) = h * (1 - peakHeight)
        // New height = original * heightScale, so new peakY = baseY - height * heightScale
        val originalHeight = h * (1f - mountain.peakHeight)
        val scaledHeight = originalHeight * mountainHeightScale
        val peakY = (baseY - scaledHeight).coerceAtLeast(h * 0.02f)  // Don't go above 2% of screen

        val halfWidthPx = scaleX * mountain.slopeWidth * 0.5f
        val leftBaseX = (peakCenterX - halfWidthPx).coerceIn(-100f, w + 100f)
        val rightBaseX = (peakCenterX + halfWidthPx).coerceIn(-100f, w + 100f)

        val baseCenterX = (leftBaseX + (rightBaseX - leftBaseX) * (0.5f + mountain.shape.baseCenterBias))
            .coerceIn(-100f, w + 100f)

        val mountainHeightPx = (baseY - peakY).coerceAtLeast(1f)
        val silhouetteRoughY = mountainHeightPx * 0.10f
        val ridgeRoughX = (rightBaseX - leftBaseX).coerceAtLeast(1f) * 0.06f
        val ridgeRoughY = mountainHeightPx * 0.05f

        // Left (lit) side polygon
        mountainPathLeft.moveTo(leftBaseX, baseY)
        for (i in 1 until mountain.shape.stepsLeft) {
            val t = i.toFloat() / mountain.shape.stepsLeft.toFloat()
            val x = leftBaseX + (peakCenterX - leftBaseX) * t
            val idealY = baseY + (peakY - baseY) * t
            val jitter = mountain.shape.leftJitter.getOrNull(i - 1) ?: 0f
            val y = idealY + jitter * silhouetteRoughY * envelope(t)
            mountainPathLeft.lineTo(x, y)
        }
        mountainPathLeft.lineTo(peakCenterX, peakY)
        for (i in 1 until mountain.shape.ridgeSteps) {
            val t = i.toFloat() / mountain.shape.ridgeSteps.toFloat()
            val idealX = peakCenterX + (baseCenterX - peakCenterX) * t
            val idealY = peakY + (baseY - peakY) * t
            val jitterX = mountain.shape.ridgeJitterX.getOrNull(i - 1) ?: 0f
            val jitterY = mountain.shape.ridgeJitterY.getOrNull(i - 1) ?: 0f
            val x = idealX + jitterX * ridgeRoughX * envelope(t)
            val y = idealY + jitterY * ridgeRoughY * envelope(t)
            mountainPathLeft.lineTo(x, y)
        }
        mountainPathLeft.lineTo(baseCenterX, baseY)
        mountainPathLeft.close()

        // Right (shadow) side polygon
        mountainPathRight.moveTo(baseCenterX, baseY)
        for (i in (mountain.shape.ridgeSteps - 1) downTo 1) {
            val t = i.toFloat() / mountain.shape.ridgeSteps.toFloat()
            val idealX = peakCenterX + (baseCenterX - peakCenterX) * t
            val idealY = peakY + (baseY - peakY) * t
            val jitterX = mountain.shape.ridgeJitterX.getOrNull(i - 1) ?: 0f
            val jitterY = mountain.shape.ridgeJitterY.getOrNull(i - 1) ?: 0f
            val x = idealX + jitterX * ridgeRoughX * envelope(t)
            val y = idealY + jitterY * ridgeRoughY * envelope(t)
            mountainPathRight.lineTo(x, y)
        }
        mountainPathRight.lineTo(peakCenterX, peakY)
        for (i in 1 until mountain.shape.stepsRight) {
            val t = i.toFloat() / mountain.shape.stepsRight.toFloat()
            val x = peakCenterX + (rightBaseX - peakCenterX) * t
            val idealY = peakY + (baseY - peakY) * t
            val jitter = mountain.shape.rightJitter.getOrNull(i - 1) ?: 0f
            val y = idealY + jitter * silhouetteRoughY * envelope(t)
            mountainPathRight.lineTo(x, y)
        }
        mountainPathRight.lineTo(rightBaseX, baseY)
        mountainPathRight.close()

        val lightColor = mountainPaint.color
        val darkColor = multiplyColor(lightColor, 0.82f)

        mountainPaint.color = lightColor
        canvas.drawPath(mountainPathLeft, mountainPaint)
        mountainPaint.color = darkColor
        canvas.drawPath(mountainPathRight, mountainPaint)
        mountainPaint.color = lightColor
    }

    /**
     * Draw a rolling hill using linear segments between control points.
     * Creates a low-poly terrain look that matches getHillY() for perfect tree placement.
     * Uses TERRAIN_BASELINE_RATIO as reference for consistent ground level.
     */
    private fun drawHill(canvas: Canvas, w: Float, h: Float, controlPoints: List<Float>, heightOffset: Float, scaleX: Float) {
        if (controlPoints.isEmpty()) return
        
        hillPath.reset()
        
        val baseY = h * heightOffset  // Where the hill baseline is (based on terrain constant)
        val hillHeight = h * 0.12f    // Maximum hill height variation (slightly reduced)
        
        // Start from far left of world
        val startX = (WORLD_MIN_X - 0.5f) * scaleX + w / 2f
        val endX = (WORLD_MAX_X - 0.5f) * scaleX + w / 2f
        
        hillPath.moveTo(startX - 100f, h + 50f)
        
        // Go up to the starting point
        hillPath.lineTo(startX - 100f, baseY + hillHeight * (1f - controlPoints[0]))
        
        // Draw linear segments through control points (Low Poly style)
        val segmentWidth = (endX - startX) / (controlPoints.size - 1)
        
        for (i in 0 until controlPoints.size - 1) {
            val x2 = startX + (i + 1) * segmentWidth
            val y2 = baseY + hillHeight * (1f - controlPoints[i + 1])
            
            // Linear line to next point
            hillPath.lineTo(x2, y2)
        }
        
        // Close along bottom
        hillPath.lineTo(endX + 100f, h + 50f)
        hillPath.close()
        
        canvas.drawPath(hillPath, hillPaint)
    }

    /**
     * Draw a pine tree silhouette with variable segments (1-5).
     * Fewer segments = taller individual tiers.
     * Wind sway: 2D sine wave - closer trees (higher depth) sway more.
     * 
     * @param depthFactor Tree's depth (0.3-0.5 range), used for wind sway intensity
     */
    private fun drawTree(canvas: Canvas, xRatio: Float, baseY: Float, scale: Float, segments: Int, w: Float, scaleX: Float, depthFactor: Float = 0.4f) {
        if (scale <= 0f || segments <= 0) return
        
        val x = (xRatio - 0.5f) * scaleX + w / 2f
        
        // Total tree height - fewer segments means each tier is taller
        val baseHeight = 160f * scale
        val treeHeight = baseHeight * (1f + (5 - segments) * 0.15f)  // 1 segment = 1.6x height, 5 segments = 1x
        val baseWidth = 50f * scale
        
        // Height per segment - fewer segments = taller tiers
        val tierHeight = treeHeight / segments
        
        // Wind sway calculation
        // - Closer trees (higher depthFactor) sway more
        // - Use tree's X position for phase offset (so trees don't sway in sync)
        // - Sway amount increases with height on tree (top sways more than base)
        val normalizedDepth = ((depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)  // 0 = far, 1 = near
        val windIntensity = windSpeed * (0.3f + normalizedDepth * 0.7f)  // Far: 30%, Near: 100% of wind
        val phaseOffset = xRatio * 3.14159f * 2f  // Different phase per tree position
        val timeSeconds = windTime / 1000f
        
        // Draw each segment from bottom to top
        // seg=0 is the BOTTOM (widest), seg=segments-1 is the TOP (narrowest)
        for (seg in 0 until segments) {
            // Width ratio: bottom segments are wide, top segments are narrow
            // seg=0 (bottom) → widthRatio=1.0, seg=segments-1 (top) → widthRatio=0.4
            val segProgress = if (segments > 1) seg.toFloat() / (segments - 1) else 0f
            val widthRatio = 1f - segProgress * 0.6f
            val tierWidth = baseWidth * widthRatio
            
            // Position for this tier
            val tierBottomY = baseY - seg * tierHeight * 0.65f  // Overlap tiers
            val tierTopY = tierBottomY - tierHeight
            
            // Wind sway: Higher tiers sway more (top of tree moves most)
            // Using sine wave with phase offset based on position and tier
            val heightRatio = (seg + 1).toFloat() / segments  // 0 at base, 1 at top
            val swayAmount = windIntensity * heightRatio * 25f * scale  // Max ~25px sway at full wind/scale
            
            // 2D sine wave: primary horizontal sway + subtle secondary wobble
            val primarySway = kotlin.math.sin(timeSeconds * 2.5f + phaseOffset) * swayAmount
            val secondarySway = kotlin.math.sin(timeSeconds * 4f + phaseOffset + 1.5f) * swayAmount * 0.3f
            val totalSway = primarySway + secondarySway
            
            // Apply sway to X position (more at top, less at bottom)
            val swayedX = x + totalSway
            val bottomSwayedX = x + totalSway * 0.6f  // Bottom corners sway less
            
            // Draw triangular tier with wind sway
            treePath.reset()
            treePath.moveTo(swayedX, tierTopY)
            treePath.lineTo(bottomSwayedX - tierWidth, tierBottomY)
            treePath.lineTo(bottomSwayedX + tierWidth, tierBottomY)
            treePath.close()
            canvas.drawPath(treePath, treePaint)
        }
    }
}
