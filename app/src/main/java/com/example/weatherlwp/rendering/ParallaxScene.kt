package com.example.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxSensorManager
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
    private val mountainPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val treePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val starPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.FILL }

    // Reusable paths
    private val mountainPath = Path()
    private val treePath = Path()

    private var ox = 0f
    private var oy = 0f
    private var parallaxStrength = 1.0f
    private var treeCount = 20
    private var treeScale = 1.0f
    private var treeSegments = 3  // Global setting: 1-5 segments per tree
    private var mountainCount = 3

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastTheme = ""
    private var dayNightRatio = 0f  // 0 = day, 1 = full night

    // Star positions (x ratio, y ratio, size) - tiny white dots only (per ai.md)
    private val stars = Array(100) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.5f, 0.5f + Random.nextFloat() * 2f) }

    // Mountain data (peakX, peakHeight, slopeWidth, depthFactor)
    private data class MountainData(val peakX: Float, val peakHeight: Float, val slopeWidth: Float, val depthFactor: Float)
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
            hillCount = prefs.getInt("pref_hill_count", 3).coerceIn(0, 5)
            
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
            
            Log.d(TAG, "Settings loaded: trees=$treeCount, segments=$treeSegments, mountains=$mountainCount, hills=$hillCount, parallax=$parallaxStrength")

            if (mountains.size != mountainCount) {
                generateMountains()
            }
            if (hills.size != hillCount) {
                generateHills()
            }
            if (trees.size != treeCount) {
                generateTrees()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings", e)
        }
    }

    private fun generateMountains() {
        try {
            mountains.clear()
            if (mountainCount <= 0) return
            
            // Use timestamp for variety but store it so it's consistent during a session
            val random = Random(System.currentTimeMillis() / 60000)  // Changes every minute
            
            // Generate mountains with truly random positions across the screen
            for (i in 0 until mountainCount) {
                // Assign depth randomly, not based on order
                val depth = random.nextFloat()
                
                // Random X position across the entire screen (10-90%)
                val peakX = 0.1f + random.nextFloat() * 0.8f
                
                // Far mountains (low depth) are taller and more prominent
                // Near mountains (high depth) are lower to create foreground hills
                val baseHeight = when {
                    depth < 0.3f -> 0.25f + random.nextFloat() * 0.15f  // Far: tall peaks (25-40%)
                    depth < 0.6f -> 0.18f + random.nextFloat() * 0.12f  // Mid: medium (18-30%)
                    else -> 0.12f + random.nextFloat() * 0.10f          // Near: low hills (12-22%)
                }
                
                // Wider slopes for far mountains, narrower for near
                val slopeWidth = when {
                    depth < 0.3f -> 0.30f + random.nextFloat() * 0.15f  // Far: wide
                    depth < 0.6f -> 0.22f + random.nextFloat() * 0.12f  // Mid: medium
                    else -> 0.15f + random.nextFloat() * 0.10f          // Near: narrow
                }
                
                mountains.add(MountainData(
                    peakX = peakX,
                    peakHeight = baseHeight,
                    slopeWidth = slopeWidth,
                    depthFactor = 0.10f + depth * 0.50f  // Far = 0.10, near = 0.60 (stronger parallax)
                ))
            }
            
            // Sort by depth (far mountains first = drawn first = behind)
            mountains.sortBy { it.depthFactor }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating mountains", e)
        }
    }

    /**
     * Generate rolling hills between mountains and trees (per ai.md layer order).
     * Uses simple sine wave composition for smooth terrain.
     * Hills are generated with consistent seeds so trees can sample their heights.
     */
    private fun generateHills() {
        try {
            hills.clear()
            if (hillCount <= 0) return
            
            // Use fixed seed for consistent terrain that trees can reference
            val random = Random(12345)
            
            // Generate multiple hill layers with different depths
            for (i in 0 until hillCount) {
                // Control points for the rolling hill shape (0.0 to 1.0 range)
                // These define height offsets at evenly spaced x positions
                val numPoints = 8 + random.nextInt(5)  // 8-12 control points
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
                
                // All hills use TERRAIN_BASELINE_RATIO as reference
                // Near hills sit slightly lower (closer to viewer)
                val heightOffset = TERRAIN_BASELINE_RATIO - 0.05f + depth * 0.08f
                
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

    private fun generateTrees() {
        try {
            trees.clear()
            if (treeCount <= 0) return
            
            val random = Random(42)

            // Generate trees that sit on the terrain
            // Trees are placed relative to the unified TERRAIN_BASELINE_RATIO
            for (i in 0 until treeCount) {
                val xRatio = random.nextFloat()
                
                // Trees are distributed across depth layers (far to near)
                // More trees in foreground (nearer) for visual interest
                val depthBias = random.nextFloat().let { it * it } // Bias towards higher values (nearer)
                
                // Parallax factor matches hill range (0.3-0.5) so trees move with terrain
                val depthFactor = 0.3f + depthBias * 0.2f
                
                // Normalized depth for visual properties (0=far, 1=near)
                val normalizedDepth = depthBias
                
                // Y position based on unified terrain baseline
                // Far trees sit higher (smaller Y), near trees lower (larger Y)
                // This creates the illusion of depth in the landscape
                val baseY = TERRAIN_BASELINE_RATIO - 0.08f + normalizedDepth * 0.15f
                
                // Add gentle wave variation for organic placement (simulates rolling terrain)
                // Use consistent formula so it matches hill undulation
                val hillWave = kotlin.math.sin(xRatio * kotlin.math.PI.toFloat() * 4) * 0.025f
                val yRatio = (baseY + hillWave - TREE_FOOT_OFFSET).coerceIn(0.55f, 0.88f)
                
                // Scale: far trees smaller, near trees larger (atmospheric perspective)
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
     * Get continuous time progress through the day (0.0 to 24.0)
     * Uses real time if auto day/night is enabled, otherwise manual time setting.
     */
    private fun getTimeOfDay(): Float {
        val autoDayNight = prefs.getBoolean("pref_daynight_enabled", true)
        return if (autoDayNight) {
            val cal = Calendar.getInstance()
            cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        } else {
            // Use manual time setting (0-23 hours)
            prefs.getInt("pref_manual_time", 12).toFloat()
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
        ox = x * parallaxStrength
        oy = y * parallaxStrength
    }

    fun render(canvas: Canvas) {
        try {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "Invalid canvas size: ${w}x${h}")
                return
            }
            
            val theme = getThemeName()
            val timeOfDay = getTimeOfDay()

            // Calculate day/night ratio for star visibility
            dayNightRatio = when (theme) {
                "night" -> 1.0f
                "dusk", "dawn" -> 0.5f
                "rain" -> 0.3f
                else -> 0.0f
            }

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
            canvas.save()
            canvas.translate(-ox * 0.15f, -oy * 0.08f)  // Opposite direction, subtle movement
            canvas.drawRect(-100f, -100f, w + 100f, h + 100f, skyPaint)
            canvas.restore()

            // Stars (night/dusk only) - tiny white dots per ai.md with subtle parallax
            if (theme == "night" || theme == "dusk") {
                val time = System.currentTimeMillis()
                val starAlphaBase = if (theme == "night") 150 else 80
                // Stars move with sky (opposite direction, very subtle)
                val starParallaxX = -ox * 0.10f
                val starParallaxY = -oy * 0.05f
                for (star in stars) {
                    val twinkle = kotlin.math.sin((time * 0.001 + star[0] * 10).toDouble())
                    val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(30, 220)
                    starPaint.alpha = alpha
                    canvas.drawCircle(star[0] * w + starParallaxX, star[1] * h + starParallaxY, star[2], starPaint)
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
                
                // Mountains are mostly static - very subtle parallax (0.05-0.15)
                val mountainParallax = 0.05f + normalizedDepth * 0.10f
                canvas.save()
                canvas.translate(ox * mountainParallax, oy * mountainParallax * 0.3f)
                drawMountain(canvas, w, h, mountain.peakX, mountain.peakHeight, mountain.slopeWidth)
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
                
                // Hills use same parallax range as trees (0.3-0.5)
                val hillParallax = hill.depthFactor  // Already in 0.3-0.5 range
                canvas.save()
                canvas.translate(ox * hillParallax, oy * hillParallax * 0.3f)
                drawHill(canvas, w, h, hill.controlPoints, hill.heightOffset)
                canvas.restore()
            }

            // Layer N+: Trees (sorted by depth, use same parallax as hills so they move together)
            val baseTreeColor = getTreeColor()
            for (tree in trees) {
                // Far trees (low depthFactor) are darker, near trees (high depthFactor) are brighter
                // tree.depthFactor ranges from 0.3 to 0.5 (same as hills)
                val normalizedTreeDepth = ((tree.depthFactor - 0.3f) / 0.2f).coerceIn(0f, 1f)
                val brightnessMultiplier = 0.6f + normalizedTreeDepth * 0.4f  // 0.6 (far/dark) to 1.0 (near/bright)
                val brightnessAdjusted = Color.rgb(
                    (Color.red(baseTreeColor) * brightnessMultiplier).toInt().coerceIn(0, 255),
                    (Color.green(baseTreeColor) * brightnessMultiplier).toInt().coerceIn(0, 255),
                    (Color.blue(baseTreeColor) * brightnessMultiplier).toInt().coerceIn(0, 255)
                )
                // Apply night-time blue overlay
                val adjustedColor = applyNightMultiplier(brightnessAdjusted, dayNightRatio)
                treePaint.color = adjustedColor

                // Trees use the SAME parallax as their depth factor (0.3-0.5)
                // This matches the hill parallax so trees move with the terrain
                val treeParallax = tree.depthFactor
                canvas.save()
                canvas.translate(ox * treeParallax, oy * treeParallax * 0.3f)
                drawTree(canvas, w * tree.xRatio, h * tree.yRatio, tree.scale * treeScale, treeSegments)
                canvas.restore()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering scene", e)
        }
    }

    /**
     * Draw a single Fuji-style mountain peak with smooth curves.
     */
    private fun drawMountain(canvas: Canvas, w: Float, h: Float,
                              peakX: Float, peakHeight: Float, slopeWidth: Float) {
        mountainPath.reset()
        
        val peakCenterX = w * peakX
        val peakY = h * peakHeight
        val baseY = h  // Bottom of screen
        
        // Start from bottom left, outside screen
        mountainPath.moveTo(-100f, baseY)
        
        // Left side rise with gentle curve
        mountainPath.quadTo(
            peakCenterX - w * slopeWidth * 0.5f, baseY * 0.85f,
            peakCenterX, peakY
        )
        
        // Right side descent with gentle curve
        mountainPath.quadTo(
            peakCenterX + w * slopeWidth * 0.5f, baseY * 0.85f,
            w + 100f, baseY
        )
        
        // Close along bottom
        mountainPath.close()
        
        canvas.drawPath(mountainPath, mountainPaint)
    }

    /**
     * Draw a rolling hill using smooth curves between control points.
     * Creates organic terrain like in the reference app.
     * Uses TERRAIN_BASELINE_RATIO as reference for consistent ground level.
     */
    private fun drawHill(canvas: Canvas, w: Float, h: Float, controlPoints: List<Float>, heightOffset: Float) {
        if (controlPoints.isEmpty()) return
        
        hillPath.reset()
        
        val baseY = h * heightOffset  // Where the hill baseline is (based on terrain constant)
        val hillHeight = h * 0.12f    // Maximum hill height variation (slightly reduced)
        
        // Start from bottom left, outside screen
        hillPath.moveTo(-100f, h + 50f)
        
        // Go up to the starting point
        hillPath.lineTo(-100f, baseY + hillHeight * (1f - controlPoints[0]))
        
        // Draw smooth curves through control points
        val segmentWidth = (w + 200f) / (controlPoints.size - 1)
        
        for (i in 0 until controlPoints.size - 1) {
            val x1 = -100f + i * segmentWidth
            val y1 = baseY + hillHeight * (1f - controlPoints[i])
            val x2 = -100f + (i + 1) * segmentWidth
            val y2 = baseY + hillHeight * (1f - controlPoints[i + 1])
            
            // Control points for bezier curve (smooth rolling effect)
            val cx1 = x1 + segmentWidth * 0.5f
            val cx2 = x2 - segmentWidth * 0.5f
            
            hillPath.cubicTo(cx1, y1, cx2, y2, x2, y2)
        }
        
        // Close along bottom
        hillPath.lineTo(w + 100f, h + 50f)
        hillPath.close()
        
        canvas.drawPath(hillPath, hillPaint)
    }

    /**
     * Draw a pine tree silhouette with variable segments (1-5).
     * Fewer segments = taller individual tiers.
     */
    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, scale: Float, segments: Int) {
        if (scale <= 0f || segments <= 0) return
        
        // Total tree height - fewer segments means each tier is taller
        val baseHeight = 160f * scale
        val treeHeight = baseHeight * (1f + (5 - segments) * 0.15f)  // 1 segment = 1.6x height, 5 segments = 1x
        val baseWidth = 50f * scale
        
        // Height per segment - fewer segments = taller tiers
        val tierHeight = treeHeight / segments
        
        // Draw each segment from bottom to top
        for (seg in 0 until segments) {
            val tierIndex = segments - 1 - seg  // 0 = top, segments-1 = bottom
            
            // Width narrows as we go up
            val widthRatio = 1f - (tierIndex.toFloat() / segments) * 0.6f  // Bottom = 1.0, Top = 0.4
            val tierWidth = baseWidth * widthRatio
            
            // Position for this tier
            val tierBottomY = baseY - seg * tierHeight * 0.65f  // Overlap tiers
            val tierTopY = tierBottomY - tierHeight
            
            // Draw triangular tier
            treePath.reset()
            treePath.moveTo(x, tierTopY)
            treePath.lineTo(x - tierWidth, tierBottomY)
            treePath.lineTo(x + tierWidth, tierBottomY)
            treePath.close()
            canvas.drawPath(treePath, treePaint)
        }
    }
}
