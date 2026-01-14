package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.Calendar
import kotlin.random.Random

// Java Random is used for nextGaussian() (normal distribution)
private val gaussianRandom = java.util.Random()

// Pre-calculated trigonometry for 8-point star (avoids Math.toRadians in render loop)
// Angles: 0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°
private val STAR_RAY_ANGLES_RAD = doubleArrayOf(
    0.0, Math.PI / 4, Math.PI / 2, 3 * Math.PI / 4,
    Math.PI, 5 * Math.PI / 4, 3 * Math.PI / 2, 7 * Math.PI / 4
)
private val STAR_RAY_COS = STAR_RAY_ANGLES_RAD.map { kotlin.math.cos(it).toFloat() }.toFloatArray()
private val STAR_RAY_SIN = STAR_RAY_ANGLES_RAD.map { kotlin.math.sin(it).toFloat() }.toFloatArray()

// Pre-calculated trail angle (225° = down-left from star)
private const val TRAIL_ANGLE_RAD = 225.0 * Math.PI / 180.0
private val TRAIL_COS = kotlin.math.cos(TRAIL_ANGLE_RAD).toFloat()
private val TRAIL_SIN = kotlin.math.sin(TRAIL_ANGLE_RAD).toFloat()
private const val TRAIL_PERP_RAD = TRAIL_ANGLE_RAD + Math.PI / 2
private val TRAIL_PERP_COS = kotlin.math.cos(TRAIL_PERP_RAD).toFloat()
private val TRAIL_PERP_SIN = kotlin.math.sin(TRAIL_PERP_RAD).toFloat()

// Conversion constant
private const val DEG_TO_RAD = Math.PI / 180.0

/**
 * Seasonal event effects: Christmas star with trail and New Year fireworks.
 * Active during December 15 - January 7, or when debug override is set.
 */
class SeasonalEvents(private val context: Context? = null) {
    companion object {
        private const val TAG = "SeasonalEvents"
    }

    enum class Season {
        NONE,
        CHRISTMAS,      // Dec 15-26
        NEW_YEAR,       // Dec 27 - Jan 7
        CN_NEW_YEAR,    // Chinese New Year (late Jan - mid Feb, varies)
        ST_PATRICKS     // St. Patrick's Day (Mar 14-18)
    }

    // Christmas star (Bethlehem star with trail/Schweif)
    private data class ChristmasStar(
        val x: Float,      // Fixed position (top right area)
        val y: Float,
        val size: Float,
        var twinkle: Float = 0f,
        var twinkleSpeed: Float = 2f
    )
    
    // Shooting star / trail particles
    private data class TrailParticle(
        var x: Float = 0f,
        var y: Float = 0f,
        var alpha: Int = 255,
        var size: Float = 2f
    )

    // Background twinkling stars
    private data class Star(
        var x: Float = 0f,
        var y: Float = 0f,
        var size: Float = 0f,
        var twinkle: Float = 0f,
        var twinkleSpeed: Float = 0f
    )

    // Firework particles for New Year
    private data class Firework(
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var color: Int = 0,
        var life: Int = 0,
        var maxLife: Int = 0,
        var size: Float = 3f,
        var hasSparkle: Boolean = false,
        var sparkleTrailX: Float = 0f,
        var sparkleTrailY: Float = 0f
    )
    
    // Rocket that shoots up before exploding
    private data class Rocket(
        var x: Float = 0f,
        var y: Float = 0f,
        var vy: Float = 0f,
        var targetY: Float = 0f,
        var colors: IntArray = intArrayOf(),  // 1-3 colors for this rocket's explosion
        var active: Boolean = false,
        var explodeAfterFrame: Int = 0,  // Frame number when rocket should explode
        var trailX1: Float = 0f, var trailY1: Float = 0f,
        var trailX2: Float = 0f, var trailY2: Float = 0f,
        var trailX3: Float = 0f, var trailY3: Float = 0f
    )

    // Pre-allocated pools
    private val stars = Array(30) { Star() }
    private val fireworks = Array(800) { Firework() }  // Large pool for shake fireworks (50 rockets × ~15 particles each)
    private val rockets = Array(50) { Rocket() }  // Up to 50 rockets in flight for shake fireworks
    private val trailParticles = Array(20) { TrailParticle() }
    private var christmasStar: ChristmasStar? = null
    
    // Pre-allocated paths (avoid allocations in render loop)
    private val christmasTrailPath = Path()
    private val christmasRayPath = Path()
    private val dragonHornPath = Path()

    // Pre-allocated paints
    private val starPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    
    private val christmasStarPaint = Paint().apply {
        color = Color.parseColor("#FFFFD700")  // Golden
        isAntiAlias = true
    }
    
    private val trailPaint = Paint().apply {
        color = Color.parseColor("#FFFFD700")
        isAntiAlias = true
    }

    private val fireworkPaint = Paint().apply {
        isAntiAlias = true
    }

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0
    
    // Scale factor for height-relative sizing (uses RenderConstants.REFERENCE_HEIGHT)
    // All pixel values are designed for 1920px height and scaled proportionally
    private var heightScale = 1f
    
    // Global frame counter for time-based rocket explosions
    private var frameCounter = 0

    private var fireworkTimer = 0
    private val fireworkColors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
        Color.MAGENTA, Color.WHITE, 0xFFFF6600.toInt(),  // Orange
        0xFFFF69B4.toInt(),  // Hot pink
        0xFF00FF7F.toInt(),  // Spring green
        0xFFFFD700.toInt(),  // Gold
        0xFF7B68EE.toInt()   // Medium slate blue
    )
    
    private val rocketPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    
    private val sparklePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
    }

    private val prefs: SharedPreferences? = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
    
    // Settings-controlled values
    private var starSizeMultiplier = 1.0f
    private var trailLengthMultiplier = 1.0f
    private var fireworkFrequency = 50  // 0-100, default 50
    private var fireworkAmount = 100    // 10-200, controls particles per explosion
    
    // Rainbow settings (St. Patrick's Day)
    private var rainbowPositionX = 50    // 0-100, horizontal position (50 = center)
    private var rainbowPositionY = 50    // 0-100, vertical position (0 = top, 100 = bottom)
    private var rainbowSize = 100        // 0-200, size percentage
    
    // Shake-to-fireworks settings
    private var shakeFireworksEnabled = true
    private var shakeFireworksCount = 12  // Number of fireworks to launch on shake (10-15)
    
    // Track last settings load time to avoid polling SharedPreferences every frame
    private var lastSettingsLoad = 0L
    private val SETTINGS_RELOAD_INTERVAL_MS = 1000L  // Reload settings every 1 second
    
    init {
        // Load settings on initialization
        loadSettings()
    }
    
    /**
     * Reload settings from SharedPreferences.
     * Called periodically to pick up changes without requiring a preference listener.
     */
    fun loadSettings() {
        prefs?.let {
            starSizeMultiplier = (it.getInt("pref_christmas_star_size", 100) / 100f).coerceIn(0.5f, 2.0f)
            trailLengthMultiplier = (it.getInt("pref_christmas_star_trail", 100) / 100f).coerceIn(0f, 2.0f)
            fireworkFrequency = it.getInt("pref_firework_frequency", 50).coerceIn(0, 100)
            fireworkAmount = it.getInt("pref_firework_amount", 100).coerceIn(10, 200)
            
            // Rainbow settings
            rainbowPositionX = it.getInt("pref_rainbow_position_x", 50).coerceIn(0, 100)
            rainbowPositionY = it.getInt("pref_rainbow_position_y", 40).coerceIn(0, 100)
            rainbowSize = it.getInt("pref_rainbow_size", 100).coerceIn(20, 200)
            
            // Shake-to-fireworks settings
            shakeFireworksEnabled = it.getBoolean("pref_shake_fireworks_enabled", true)
            shakeFireworksCount = it.getInt("pref_shake_fireworks_count", 15).coerceIn(5, 50)
        }
        lastSettingsLoad = System.currentTimeMillis()
    }
    
    /**
     * Check if shake-to-fireworks is enabled.
     */
    fun isShakeFireworksEnabled(): Boolean = shakeFireworksEnabled
    
    /**
     * Ensure shake fireworks can work even when seasonal events are disabled.
     * Initializes screen dimensions if needed.
     */
    private fun ensureInitializedForShake(width: Int, height: Int) {
        if (!initialized || screenWidth != width || screenHeight != height) {
            screenWidth = width
            screenHeight = height
            heightScale = RenderConstants.getHeightScale(screenHeight.toFloat())
            initParticles()
            initialized = true
        }
    }
    
    /**
     * Trigger fireworks on shake gesture.
     * Launches multiple rockets in quick succession for a burst effect.
     * Uses a normal distribution around the target count for variety.
     * Works regardless of seasonal settings being enabled.
     */
    fun triggerShakeFireworks() {
        if (!shakeFireworksEnabled) return
        
        // Ensure initialization even if seasonal events are disabled
        // This allows shake fireworks to work year-round
        if (!initialized) {
            Log.d(TAG, "Shake triggered but not initialized - waiting for next frame")
            return
        }
        
        // Use normal distribution for variety in firework count
        // Mean = shakeFireworksCount, stdDev = 15% of count
        val stdDev = shakeFireworksCount * 0.15
        val normalValue = gaussianRandom.nextGaussian() * stdDev + shakeFireworksCount
        val actualCount = normalValue.toInt().coerceIn(3, 50)
        
        Log.d(TAG, "Triggering shake fireworks: $actualCount rockets (target: $shakeFireworksCount)")
        
        // Launch rockets up to the requested count (capped by available rocket slots)
        for (i in 0 until actualCount.coerceAtMost(rockets.size)) {
            launchRocket()
        }
    }
    
    /**
     * Initialize for shake fireworks if not already done.
     * Called from ParallaxScene to ensure shake works even without seasonal rendering.
     */
    fun ensureShakeReady(width: Int, height: Int) {
        if (shakeFireworksEnabled && !initialized) {
            ensureInitializedForShake(width, height)
        }
    }

    fun getCurrentSeason(): Season {
        // Check if seasonal events are enabled
        try {
            if (prefs != null) {
                val seasonalEnabled = prefs.getBoolean("pref_seasonal_enabled", true)
                if (!seasonalEnabled) {
                    return Season.NONE
                }
                
                val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
                if (debugEnabled) {
                    val debugSeason = prefs.getString("pref_debug_season", "auto") ?: "auto"
                    when (debugSeason) {
                        "christmas" -> return Season.CHRISTMAS
                        "newyear" -> return Season.NEW_YEAR
                        "cn_newyear" -> return Season.CN_NEW_YEAR
                        "st_patricks" -> return Season.ST_PATRICKS
                        "none" -> return Season.NONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading debug prefs", e)
        }

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) // 0-indexed (0=Jan, 11=Dec)
        val day = cal.get(Calendar.DAY_OF_MONTH)

        return when {
            // Christmas: Dec 15-26
            month == 11 && day in 15..26 -> Season.CHRISTMAS
            // New Year: Dec 27 - Jan 7
            month == 11 && day >= 27 -> Season.NEW_YEAR
            month == 0 && day <= 7 -> Season.NEW_YEAR
            // Chinese New Year: Late Jan - Mid Feb (simplified: Jan 20 - Feb 15)
            (month == 0 && day >= 20) || (month == 1 && day <= 15) -> Season.CN_NEW_YEAR
            // St. Patrick's Day: Mar 14-18
            month == 2 && day in 14..18 -> Season.ST_PATRICKS
            else -> Season.NONE
        }
    }

    fun render(canvas: Canvas, enabled: Boolean) {
        if (!enabled) return

        try {
            // Periodically reload settings to pick up preference changes
            val now = System.currentTimeMillis()
            if (now - lastSettingsLoad > SETTINGS_RELOAD_INTERVAL_MS) {
                loadSettings()
            }
            
            val season = getCurrentSeason()
            if (season == Season.NONE) return

            if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
                screenWidth = canvas.width
                screenHeight = canvas.height
                heightScale = RenderConstants.getHeightScale(screenHeight.toFloat())
                initParticles()
                initialized = true
            }

            when (season) {
                // Christmas star is now drawn in renderBehindMountains() to appear in the sky
                Season.CHRISTMAS -> {}  // handled by renderBehindMountains
                // NEW_YEAR and CN_NEW_YEAR fireworks are now drawn via renderBehindTerrain()
                // to appear behind hills but in front of mountains
                Season.NEW_YEAR -> {}  // handled by renderBehindTerrain
                Season.CN_NEW_YEAR -> {}  // handled by renderBehindTerrain
                // ST_PATRICKS (rainbow) is drawn behind mountains via renderBehindMountains()
                Season.ST_PATRICKS -> {}  // handled elsewhere
                Season.NONE -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering seasonal events", e)
        }
    }

    /**
     * Render elements that belong behind the terrain/mountains (e.g. St. Patrick's rainbow, Christmas star).
     * This is called from the scene renderer just before MountainLayer is drawn.
     * These elements appear in the sky layer.
     * @param parallaxOffsetX X offset from parallax calculator for depth effect (default 0f)
     * @param parallaxOffsetY Y offset from parallax calculator for depth effect (default 0f)
     */
    fun renderBehindMountains(canvas: Canvas, enabled: Boolean, parallaxOffsetX: Float = 0f, parallaxOffsetY: Float = 0f) {
        if (!enabled) return
        try {
            // Ensure settings are loaded and screen size is initialized
            val now = System.currentTimeMillis()
            if (now - lastSettingsLoad > SETTINGS_RELOAD_INTERVAL_MS) {
                loadSettings()
            }
            val season = getCurrentSeason()
            if (season == Season.NONE) return
            if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
                screenWidth = canvas.width
                screenHeight = canvas.height
                heightScale = RenderConstants.getHeightScale(screenHeight.toFloat())
                initParticles()
                initialized = true
            }
            when (season) {
                Season.ST_PATRICKS -> renderStPatricksDay(canvas)
                Season.CHRISTMAS -> renderChristmasStars(canvas, parallaxOffsetX, parallaxOffsetY)  // Star in sky behind mountains
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering behind-mountain seasonal events", e)
        }
    }
    
    /**
     * Render elements that belong in front of mountains but behind terrain/hills.
     * This includes New Year fireworks and Chinese New Year effects.
     * Called from scene renderer after mountains but before terrain layer.
     */
    fun renderBehindTerrain(canvas: Canvas, enabled: Boolean) {
        // Check for active fireworks first - shake fireworks should work independent of season/master switch
        // if user has enabled the specific shake setting
        val hasActiveFireworks = rockets.any { it.active } || fireworks.any { it.life > 0 }

        if (!enabled && !hasActiveFireworks) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSettingsLoad > SETTINGS_RELOAD_INTERVAL_MS) {
                loadSettings()
            }
            val season = getCurrentSeason()
            
            // Allow rendering if we have active fireworks (from shake) even if season is NONE
            if (season == Season.NONE && !hasActiveFireworks) return
            
            if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
                screenWidth = canvas.width
                screenHeight = canvas.height
                heightScale = RenderConstants.getHeightScale(screenHeight.toFloat())
                initParticles()
                initialized = true
            }
            // Fireworks should appear behind hills but in front of mountains
            when {
                season == Season.NEW_YEAR -> renderNewYearFireworks(canvas, autoLaunch = true)
                season == Season.CN_NEW_YEAR -> renderChineseNewYear(canvas)
                // If not in season but we have active fireworks (e.g. from shake), render them without auto-launch
                hasActiveFireworks -> renderNewYearFireworks(canvas, autoLaunch = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering behind-terrain seasonal events", e)
        }
    }

    private fun initParticles() {
        // Use fixed seed for consistent star placement
        val starRandom = Random(54321)
        
        // Scale factor for sizes (min 50% for visibility on small screens)
        val sizeScale = heightScale.coerceAtLeast(0.5f)
        
        // Initialize background twinkling stars in upper portion of screen
        // Avoid the christmas star area (upper right)
        val christmasStarX = screenWidth * 0.82f
        val christmasStarY = screenHeight * 0.12f
        val exclusionRadius = screenWidth * 0.15f
        
        for (s in stars) {
            // Keep regenerating until we get a position not too close to christmas star
            var attempts = 0
            do {
                s.x = starRandom.nextFloat() * screenWidth
                s.y = starRandom.nextFloat() * screenHeight * 0.4f
                attempts++
            } while (attempts < 5 && 
                     kotlin.math.abs(s.x - christmasStarX) < exclusionRadius && 
                     kotlin.math.abs(s.y - christmasStarY) < exclusionRadius)
            
            // Size scaled relative to height
            s.size = (1.5f + starRandom.nextFloat() * 2.5f) * sizeScale
            s.twinkle = starRandom.nextFloat() * 360f
            s.twinkleSpeed = 2f + starRandom.nextFloat() * 4f
        }
        
        // Initialize the main Christmas star (Bethlehem star) - fixed position in upper right
        // Size is relative to reference height (35f at 1920px)
        christmasStar = ChristmasStar(
            x = christmasStarX,
            y = christmasStarY,
            size = 35f * heightScale.coerceAtLeast(0.5f),  // Scale with height, min 50%
            twinkle = 0f,
            twinkleSpeed = 3f
        )
        
        // Initialize trail particles (emanating from the star)
        // Sizes are relative to reference height
        val trailScale = heightScale.coerceAtLeast(0.5f)
        for (i in trailParticles.indices) {
            val t = i.toFloat() / trailParticles.size
            trailParticles[i].apply {
                x = 0f  // Will be set relative to star
                y = 0f
                alpha = (255 * (1f - t * 0.7f)).toInt()  // Fade along trail
                size = (3f + (1f - t) * 8f) * trailScale  // Larger near star, scaled
            }
        }

        // Reset fireworks
        for (f in fireworks) {
            f.life = 0
        }
        
        // Reset rockets
        for (r in rockets) {
            r.active = false
        }
    }

    private fun renderChristmasStars(canvas: Canvas, parallaxOffsetX: Float = 0f, parallaxOffsetY: Float = 0f) {
        // Note: Background twinkling stars are now handled by SkyLayer
        // SeasonalEvents only renders the main Christmas star with trail
        // This prevents duplicate star rendering at night during Christmas
        
        // Render the main Christmas star (Bethlehem star) with trail
        val star = christmasStar ?: return
        star.twinkle += star.twinkleSpeed
        
        // Apply parallax offset for sky-layer depth effect
        val starX = star.x + parallaxOffsetX
        val starY = star.y + parallaxOffsetY
        
        // Apply size multiplier
        val effectiveSize = star.size * starSizeMultiplier
        
        // Twinkle factor for the whole star (use precalculated DEG_TO_RAD)
        val twinkleFactor = 0.85f + 0.15f * kotlin.math.sin(star.twinkle * DEG_TO_RAD).toFloat()
        
        // Trail/Schweif - gradient path going down-left from the star (use precalculated angles)
        val trailLength = effectiveSize * 7f * trailLengthMultiplier
        
        // Only render trail if length > 0
        if (trailLengthMultiplier > 0.01f) {
            // Smooth gradient trail using LinearGradient instead of discrete circles
            val trailEndX = starX + trailLength * TRAIL_COS
            val trailEndY = starY + trailLength * TRAIL_SIN
            
            // Create a tapered trail path (wider near star, narrower at end)
            val startWidth = effectiveSize * 0.35f
            val endWidth = effectiveSize * 0.05f
            
            // Use pre-allocated path (avoid allocation in render loop)
            christmasTrailPath.reset()
            // Starting edge (near star) - wider
            christmasTrailPath.moveTo(
                starX + startWidth * TRAIL_PERP_COS,
                starY + startWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.lineTo(
                starX - startWidth * TRAIL_PERP_COS,
                starY - startWidth * TRAIL_PERP_SIN
            )
            // End edge (far from star) - narrower
            christmasTrailPath.lineTo(
                trailEndX - endWidth * TRAIL_PERP_COS,
                trailEndY - endWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.lineTo(
                trailEndX + endWidth * TRAIL_PERP_COS,
                trailEndY + endWidth * TRAIL_PERP_SIN
            )
            christmasTrailPath.close()
            
            // Linear gradient from star (bright gold) to end (transparent)
            trailPaint.shader = LinearGradient(
                starX, starY, trailEndX, trailEndY,
                intArrayOf(
                    Color.argb((220 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb((120 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb(0, 255, 215, 0)
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            trailPaint.alpha = 255
            canvas.drawPath(christmasTrailPath, trailPaint)
            trailPaint.shader = null
        }
        
        // Outer soft glow - radial gradient
        val glowRadius = effectiveSize * 2.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            starX, starY, glowRadius,
            intArrayOf(
                Color.argb(80, 255, 230, 150),
                Color.argb(40, 255, 215, 0),
                Color.argb(0, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(starX, starY, glowRadius, christmasStarPaint)
        christmasStarPaint.shader = null
        
        // 8-point star rays as filled triangles with alpha falloff (use precalculated sin/cos)
        val primaryRayLength = effectiveSize * 1.6f * twinkleFactor
        val secondaryRayLength = effectiveSize * 0.9f * twinkleFactor
        val rayBaseWidth = effectiveSize * 0.12f
        
        // Use pre-allocated path (avoid allocation in render loop)
        christmasRayPath.reset()
        for (index in 0 until 8) {
            val isPrimary = index % 2 == 0  // 0, 90, 180, 270 are primary
            val rayLen = if (isPrimary) primaryRayLength else secondaryRayLength
            // Use precalculated cos/sin arrays
            val cosVal = STAR_RAY_COS[index]
            val sinVal = STAR_RAY_SIN[index]
            // Perpendicular is 90° ahead in the array (with wrap)
            val perpIndex = (index + 2) % 8
            val perpCos = STAR_RAY_COS[perpIndex]
            val perpSin = STAR_RAY_SIN[perpIndex]
            
            val tipX = starX + rayLen * cosVal
            val tipY = starY + rayLen * sinVal
            
            val baseOffset = rayBaseWidth * (if (isPrimary) 1f else 0.7f)
            val baseX1 = starX + baseOffset * perpCos
            val baseY1 = starY + baseOffset * perpSin
            val baseX2 = starX - baseOffset * perpCos
            val baseY2 = starY - baseOffset * perpSin
            
            christmasRayPath.moveTo(baseX1, baseY1)
            christmasRayPath.lineTo(tipX, tipY)
            christmasRayPath.lineTo(baseX2, baseY2)
            christmasRayPath.close()
        }
        
        // Fill rays with gradient from center
        christmasStarPaint.shader = RadialGradient(
            starX, starY, primaryRayLength,
            intArrayOf(
                Color.argb(255, 255, 255, 240),
                Color.argb(220, 255, 220, 100),
                Color.argb(150, 255, 200, 50)
            ),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(christmasRayPath, christmasStarPaint)
        christmasStarPaint.shader = null
        
        // Bright central core with radial gradient
        val coreRadius = effectiveSize * 0.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            starX, starY, coreRadius,
            intArrayOf(
                Color.argb(255, 255, 255, 255),
                Color.argb(255, 255, 240, 200),
                Color.argb(200, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(starX, starY, coreRadius, christmasStarPaint)
        christmasStarPaint.shader = null
    }

    private fun renderNewYearFireworks(canvas: Canvas, autoLaunch: Boolean = true) {
        if (autoLaunch) {
            fireworkTimer++

            // Launch new rocket based on frequency setting
            // At 0: every 200+ frames (rare), at 50: every 40-90 frames, at 100: every 10-30 frames
            val minInterval = when {
                fireworkFrequency >= 80 -> 10
                fireworkFrequency >= 50 -> 30
                fireworkFrequency >= 20 -> 60
                else -> 150
            }
            val intervalRange = when {
                fireworkFrequency >= 80 -> 20
                fireworkFrequency >= 50 -> 50
                fireworkFrequency >= 20 -> 80
                else -> 100
            }
            
            if (fireworkFrequency > 0 && fireworkTimer > minInterval + Random.nextInt(intervalRange)) {
                fireworkTimer = 0
                launchRocket()
            }
        }

        // Increment frame counter for time-based rocket explosions
        frameCounter++

        // Update and render rockets
        for (r in rockets) {
            if (r.active) {
                // Store trail positions (previous positions for trail effect)
                r.trailX3 = r.trailX2
                r.trailY3 = r.trailY2
                r.trailX2 = r.trailX1
                r.trailY2 = r.trailY1
                r.trailX1 = r.x
                r.trailY1 = r.y
                
                // Move rocket upward (scale velocity by height for consistent physics)
                r.y += r.vy * heightScale
                r.vy += 0.06f * heightScale  // Gentle deceleration
                
                // Draw rocket trail (fading dots behind, scaled by height)
                val trailScale = heightScale.coerceAtLeast(0.5f)  // Don't go below 50% size
                rocketPaint.color = Color.argb(60, 255, 200, 100)
                canvas.drawCircle(r.trailX3, r.trailY3, 1.5f * trailScale, rocketPaint)
                rocketPaint.color = Color.argb(120, 255, 220, 150)
                canvas.drawCircle(r.trailX2, r.trailY2, 2f * trailScale, rocketPaint)
                rocketPaint.color = Color.argb(200, 255, 240, 200)
                canvas.drawCircle(r.trailX1, r.trailY1, 2.5f * trailScale, rocketPaint)
                
                // Draw rocket head (scaled by height)
                rocketPaint.color = Color.WHITE
                canvas.drawCircle(r.x, r.y, 3f * trailScale, rocketPaint)
                
                // Simple time-based explosion: explode when scheduled frame is reached
                // This prevents slow-motion lingering at apex
                if (frameCounter >= r.explodeAfterFrame) {
                    explodeRocket(r)
                    r.active = false
                }
            }
        }

        // Update and render explosion particles
        // Scale factor for particle sizes (min 50% to keep visible on very small screens)
        val particleScale = heightScale.coerceAtLeast(0.5f)
        
        for (f in fireworks) {
            if (f.life > 0) {
                // Store previous position for sparkle trail
                f.sparkleTrailX = f.x
                f.sparkleTrailY = f.y
                
                // Update physics (scale velocity and gravity by height for consistent motion)
                f.x += f.vx * heightScale
                f.y += f.vy * heightScale
                f.vy += 0.12f // gravity (in reference pixels per frame)
                f.vx *= 0.98f // air resistance
                f.life--

                val lifeRatio = f.life / f.maxLife.toFloat()
                val alpha = (255 * lifeRatio).toInt().coerceIn(0, 255)
                
                // Scaled size for this particle
                val scaledSize = f.size * particleScale
                
                // Draw sparkle trail behind the ball (fine particles)
                if (f.hasSparkle && lifeRatio > 0.3f) {
                    sparklePaint.color = f.color
                    sparklePaint.alpha = (alpha * 0.4f).toInt()
                    canvas.drawCircle(f.sparkleTrailX, f.sparkleTrailY, scaledSize * 0.4f, sparklePaint)
                    
                    // Additional tiny sparkles (spread scaled by height)
                    val sparkleAlpha = (alpha * 0.25f).toInt()
                    sparklePaint.alpha = sparkleAlpha
                    canvas.drawCircle(
                        f.sparkleTrailX + (Random.nextFloat() - 0.5f) * 4f * heightScale,
                        f.sparkleTrailY + (Random.nextFloat() - 0.5f) * 4f * heightScale,
                        scaledSize * 0.2f, sparklePaint
                    )
                }
                
                // Draw main colorful ball
                fireworkPaint.color = f.color
                fireworkPaint.alpha = alpha
                val size = scaledSize * (0.5f + lifeRatio * 0.5f)  // Shrink as it fades
                canvas.drawCircle(f.x, f.y, size, fireworkPaint)
                
                // Bright core
                fireworkPaint.alpha = (alpha * 0.7f).toInt()
                fireworkPaint.color = Color.WHITE
                canvas.drawCircle(f.x, f.y, size * 0.4f, fireworkPaint)
            }
        }
    }
    
    private fun launchRocket() {
        // Find an inactive rocket slot
        for (r in rockets) {
            if (!r.active) {
                r.x = screenWidth * 0.15f + Random.nextFloat() * screenWidth * 0.7f
                r.y = screenHeight.toFloat()
                // Upward velocity: -14 to -20 (negative = up, faster to reach height)
                r.vy = -14f - Random.nextFloat() * 6f
                r.targetY = screenHeight * 0.08f + Random.nextFloat() * screenHeight * 0.22f
                
                // Explode after 75-120 frames (~1.25-2.0 seconds at 60fps)
                // Longer flight time ensures rockets clear mountains (which are at 30-60% from bottom)
                r.explodeAfterFrame = frameCounter + 75 + Random.nextInt(45)
                
                // Choose 1-3 colors for this rocket's explosion
                val numColors = 1 + Random.nextInt(3)  // 1, 2, or 3 colors
                r.colors = IntArray(numColors) { fireworkColors[Random.nextInt(fireworkColors.size)] }
                
                r.active = true
                r.trailX1 = r.x
                r.trailY1 = r.y
                r.trailX2 = r.x
                r.trailY2 = r.y
                r.trailX3 = r.x
                r.trailY3 = r.y
                break
            }
        }
    }
    
    private fun explodeRocket(rocket: Rocket) {
        val cx = rocket.x
        val cy = rocket.y
        val colors = rocket.colors
        
        // Spawn explosion particles based on fireworkAmount setting
        // Reduced particle count for shake to ensure pool isn't exhausted
        // At 10: 8-12 particles, at 100: 25-35 particles, at 200: 45-55 particles
        var particleCount = 0
        val baseParticles = (fireworkAmount * 0.2f + 5).toInt()   // 10→7, 100→25, 200→45
        val varianceParticles = (fireworkAmount * 0.1f + 3).toInt()  // 10→4, 100→13, 200→23
        val targetParticles = baseParticles + Random.nextInt(varianceParticles.coerceAtLeast(1))
        
        for (f in fireworks) {
            if (f.life <= 0 && particleCount < targetParticles) {
                val angle = Random.nextFloat() * 360f
                // Starburst effect: clearer shell by reducing speed variance
                // Was: 4f + Random.nextFloat() * 6f (4-10 range, very messy)
                // New: 6f + Random.nextFloat() * 2f (6-8 range, clearer ring/star)
                val speed = 6f + Random.nextFloat() * 2f

                f.x = cx
                f.y = cy
                // Use precalculated DEG_TO_RAD constant
                val angleRad = angle * DEG_TO_RAD
                f.vx = (speed * kotlin.math.cos(angleRad)).toFloat()
                f.vy = (speed * kotlin.math.sin(angleRad)).toFloat()
                
                // Randomly pick one of the rocket's colors
                f.color = colors[Random.nextInt(colors.size)]
                f.maxLife = 50 + Random.nextInt(40)
                f.life = f.maxLife
                f.size = 2.5f + Random.nextFloat() * 3f
                f.hasSparkle = Random.nextFloat() > 0.4f  // 60% have sparkle trails
                f.sparkleTrailX = cx
                f.sparkleTrailY = cy
                
                particleCount++
            }
        }
    }
    
    // ========== ST. PATRICK'S DAY ==========
    
    private val rainbowColors = intArrayOf(
        Color.rgb(255, 0, 0),      // Red
        Color.rgb(255, 127, 0),    // Orange  
        Color.rgb(255, 255, 0),    // Yellow
        Color.rgb(0, 255, 0),      // Green
        Color.rgb(0, 0, 255),      // Blue
        Color.rgb(75, 0, 130),     // Indigo
        Color.rgb(148, 0, 211)     // Violet
    )
    
    private var rainbowPhase = 0f
    
    // Pre-allocated paint for rainbow bands (avoid allocation in render loop)
    private val rainbowBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    
    private fun renderStPatricksDay(canvas: Canvas) {
        rainbowPhase += 0.02f
        
        // Position and size based on settings
        val centerX = screenWidth * (rainbowPositionX / 100f)
        val baseY = screenHeight * (rainbowPositionY / 100f)
        val sizeMultiplier = rainbowSize / 100f
        val arcRadius = screenWidth * 0.45f * sizeMultiplier
        
        // Full semi-circle: 180° to 360° (or equivalently 180° sweep starting at 180°)
        val startAngle = 180f
        val sweepAngle = 180f
        
        // Create a vertical fade shader (fade out toward bottom)
        val fadeHeight = arcRadius * 0.6f  // How much of bottom portion fades
        val fadeTop = baseY - fadeHeight
        val fadeShader = android.graphics.LinearGradient(
            centerX, fadeTop,
            centerX, baseY + arcRadius * 0.1f,  // Extend slightly below to ensure bottom fades
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        
        // Draw rainbow arc bands from outer (red) to inner (violet)
        for (i in rainbowColors.indices.reversed()) {
            val bandWidth = arcRadius * 0.055f
            val bandRadius = arcRadius - i * bandWidth
            
            // Shimmer effect - subtle pulsing
            val shimmer = (kotlin.math.sin(rainbowPhase + i * 0.5f) * 0.1f + 0.9f).toFloat()
            
            // Base alpha for the center of the band
            val centerAlpha = (90 * shimmer).toInt()
            
            // Create a sweep gradient that fades at the edges of the arc
            // The gradient goes around the arc: transparent -> color -> color -> transparent
            val bandColor = rainbowColors[i]
            val transparentColor = Color.argb(0, Color.red(bandColor), Color.green(bandColor), Color.blue(bandColor))
            val visibleColor = Color.argb(centerAlpha, Color.red(bandColor), Color.green(bandColor), Color.blue(bandColor))
            
            // SweepGradient with fade at edges (positions 0.0=3 o'clock, going counter-clockwise)
            // For 180° arc starting at 180° (9 o'clock), we need fade at 180° and 360°
            // Map: 0.0=0°, 0.25=90°, 0.5=180° (start), 0.75=270° (top), 1.0=360° (end)
            val sweepGradient = android.graphics.SweepGradient(
                centerX, baseY,
                intArrayOf(
                    transparentColor,   // 0° (right) - not visible
                    transparentColor,   // ~170° approaching start
                    visibleColor,       // ~195° just after start - fade in
                    visibleColor,       // ~270° top - full color
                    visibleColor,       // ~345° approaching end - still full
                    transparentColor,   // ~355° fade out
                    transparentColor    // 360° end
                ),
                floatArrayOf(0f, 0.47f, 0.54f, 0.75f, 0.96f, 0.995f, 1f)
            )
            
            // Combine sweep gradient with vertical fade using ComposeShader
            val compositeShader = android.graphics.ComposeShader(
                sweepGradient, 
                fadeShader,
                android.graphics.PorterDuff.Mode.DST_IN
            )
            
            rainbowBandPaint.strokeWidth = bandWidth
            rainbowBandPaint.shader = compositeShader
            
            // Draw full semi-circle arc (180° sweep from 180°)
            canvas.drawArc(
                centerX - bandRadius,
                baseY - bandRadius,
                centerX + bandRadius,
                baseY + bandRadius,
                startAngle, sweepAngle, false, rainbowBandPaint
            )
        }
        
        // Clear shader to avoid affecting other drawing
        rainbowBandPaint.shader = null
    }
    
    // ========== CHINESE NEW YEAR ==========
    
    private var dragonPhase = 0f
    private val dragonColors = intArrayOf(
        Color.rgb(200, 30, 30),    // Red (body)
        Color.rgb(255, 200, 50),   // Gold (accents)
        Color.rgb(255, 80, 30),    // Orange-red (scales)
        Color.rgb(180, 20, 20)     // Dark red (outline)
    )
    
    private val dragonPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val dragonOutlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = dragonColors[3]
    }
    
    private fun renderChineseNewYear(canvas: Canvas) {
        dragonPhase += 0.025f
        
        // Render New Year fireworks as base
        renderNewYearFireworks(canvas)
        
        // Draw a traditional Chinese paper dragon flying across the sky
        // Dragon moves right-to-left, undulating up and down
        val baseX = screenWidth + 150f * heightScale - (dragonPhase * 40f * heightScale) % (screenWidth + 400f * heightScale)
        val baseY = screenHeight * 0.18f
        
        // Scale based on screen height for consistent look
        val scale = heightScale.coerceIn(0.5f, 1.5f)
        val segmentRadius = 18f * scale
        val segmentCount = 12
        val segmentSpacing = segmentRadius * 1.6f
        
        // Pre-calculate segment positions for smooth body curve
        // Dragon moves LEFT, so tail segments trail to the RIGHT (higher X)
        val segmentPositions = Array(segmentCount) { i ->
            val t = i.toFloat()
            // Primary undulation wave
            val wave1 = kotlin.math.sin((dragonPhase * 3f - t * 0.5f).toDouble()).toFloat() * 25f * scale
            // Secondary faster ripple
            val wave2 = kotlin.math.sin((dragonPhase * 5f - t * 0.8f).toDouble()).toFloat() * 8f * scale
            val x = baseX + t * segmentSpacing  // Tail trails BEHIND (to the right)
            val y = baseY + wave1 + wave2
            Pair(x, y)
        }
        
        // Draw body segments from tail to head (so head overlaps body)
        for (i in (segmentCount - 1) downTo 1) {
            val (x, y) = segmentPositions[i]
            // Segment size tapers toward tail
            val sizeFactor = 1f - (i.toFloat() / segmentCount) * 0.4f
            val radius = segmentRadius * sizeFactor
            
            // Body: alternating red/orange-red with gold trim
            dragonPaint.color = if (i % 2 == 0) dragonColors[0] else dragonColors[2]
            canvas.drawCircle(x, y, radius, dragonPaint)
            
            // Gold scale accent on every other segment
            if (i % 2 == 1) {
                dragonPaint.color = dragonColors[1]
                val scaleSize = radius * 0.5f
                canvas.drawCircle(x, y - radius * 0.3f, scaleSize, dragonPaint)
            }
            
            // Outline
            canvas.drawCircle(x, y, radius, dragonOutlinePaint)
        }
        
        // Draw dragon head (first segment, larger and more detailed)
        // Dragon is moving LEFT, so face points LEFT (negative X direction)
        val (headX, headY) = segmentPositions[0]
        val headRadius = segmentRadius * 1.8f
        
        // Head base (red)
        dragonPaint.color = dragonColors[0]
        canvas.drawCircle(headX, headY, headRadius, dragonPaint)
        
        // Snout/muzzle (elongated to the LEFT - direction of movement)
        val snoutLength = headRadius * 1.2f
        dragonPaint.color = dragonColors[0]
        canvas.drawOval(
            headX - snoutLength - headRadius * 0.3f, headY - headRadius * 0.4f,
            headX + headRadius * 0.3f, headY + headRadius * 0.4f,
            dragonPaint
        )
        
        // Nostrils on snout (at the front/left of the snout)
        dragonPaint.color = dragonColors[3]
        val nostrilRadius = headRadius * 0.1f
        canvas.drawCircle(headX - snoutLength * 0.8f, headY - headRadius * 0.15f, nostrilRadius, dragonPaint)
        canvas.drawCircle(headX - snoutLength * 0.8f, headY + headRadius * 0.15f, nostrilRadius, dragonPaint)
        
        // Gold whiskers/mane (flowing back from face, toward the RIGHT/behind)
        // Pre-calculated sin values for whisker angles: -60°, -30°, 30°, 60°
        dragonPaint.color = dragonColors[1]
        val whiskerLength = headRadius * 1.0f
        val whiskerSinValues = floatArrayOf(-0.866f, -0.5f, 0.5f, 0.866f)  // sin(-60°), sin(-30°), sin(30°), sin(60°)
        dragonOutlinePaint.color = dragonColors[1]
        dragonOutlinePaint.strokeWidth = 3f * scale
        for (sinVal in whiskerSinValues) {
            val wx = headX + headRadius * 0.6f  // Start from back of head (right side)
            val wy = headY + sinVal * headRadius * 0.4f
            val wxEnd = wx + whiskerLength  // Flow to the right (trailing behind dragon)
            val wyEnd = wy + sinVal * whiskerLength * 0.3f
            canvas.drawLine(wx, wy, wxEnd, wyEnd, dragonOutlinePaint)
        }
        dragonOutlinePaint.color = dragonColors[3]
        dragonOutlinePaint.strokeWidth = 2f * scale
        
        // Eyes (sideways profile view - only one eye visible, on top side of head)
        val eyeRadius = headRadius * 0.3f
        val eyeOffsetX = -headRadius * 0.3f  // Toward the front (left)
        val eyeOffsetY = -headRadius * 0.25f  // Above center
        
        // Single visible eye (profile view)
        dragonPaint.color = Color.WHITE
        canvas.drawCircle(headX + eyeOffsetX, headY + eyeOffsetY, eyeRadius, dragonPaint)
        // Pupil looking forward (left)
        dragonPaint.color = Color.BLACK
        canvas.drawCircle(headX + eyeOffsetX - eyeRadius * 0.2f, headY + eyeOffsetY, eyeRadius * 0.5f, dragonPaint)
        // Gold outline
        dragonOutlinePaint.color = dragonColors[1]
        canvas.drawCircle(headX + eyeOffsetX, headY + eyeOffsetY, eyeRadius, dragonOutlinePaint)
        
        // Brow ridge above eye
        dragonPaint.color = dragonColors[2]
        canvas.drawArc(
            headX + eyeOffsetX - eyeRadius * 1.2f, headY + eyeOffsetY - eyeRadius * 1.5f,
            headX + eyeOffsetX + eyeRadius * 1.2f, headY + eyeOffsetY - eyeRadius * 0.3f,
            180f, 180f, true, dragonPaint
        )
        
        // Horns/antlers (gold) - pointing up and back
        // Use pre-allocated path (avoid allocation in render loop)
        dragonPaint.color = dragonColors[1]
        dragonHornPath.reset()
        // Main horn
        dragonHornPath.moveTo(headX, headY - headRadius * 0.8f)
        dragonHornPath.lineTo(headX + headRadius * 0.3f, headY - headRadius * 1.6f)
        dragonHornPath.lineTo(headX + headRadius * 0.5f, headY - headRadius * 0.9f)
        dragonHornPath.close()
        canvas.drawPath(dragonHornPath, dragonPaint)
        
        // Secondary horn (smaller, behind)
        dragonHornPath.reset()
        dragonHornPath.moveTo(headX + headRadius * 0.4f, headY - headRadius * 0.6f)
        dragonHornPath.lineTo(headX + headRadius * 0.7f, headY - headRadius * 1.2f)
        dragonHornPath.lineTo(headX + headRadius * 0.8f, headY - headRadius * 0.7f)
        dragonHornPath.close()
        canvas.drawPath(dragonHornPath, dragonPaint)
        
        // Head outline
        dragonOutlinePaint.color = dragonColors[3]
        canvas.drawCircle(headX, headY, headRadius, dragonOutlinePaint)
    }
}
