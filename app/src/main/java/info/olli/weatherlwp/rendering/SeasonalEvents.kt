package info.olli.weatherlwp.rendering

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.Calendar
import kotlin.random.Random

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
        CHRISTMAS,     // Dec 15-26
        NEW_YEAR       // Dec 27 - Jan 7
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
        var trailX1: Float = 0f, var trailY1: Float = 0f,
        var trailX2: Float = 0f, var trailY2: Float = 0f,
        var trailX3: Float = 0f, var trailY3: Float = 0f
    )

    // Pre-allocated pools
    private val stars = Array(30) { Star() }
    private val fireworks = Array(250) { Firework() }  // Larger pool for multiple simultaneous explosions
    private val rockets = Array(5) { Rocket() }  // Up to 5 rockets in flight
    private val trailParticles = Array(20) { TrailParticle() }
    private var christmasStar: ChristmasStar? = null

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
    private var fireworkAmount = 50     // 10-100, controls particles per explosion
    
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
            fireworkAmount = it.getInt("pref_firework_amount", 50).coerceIn(10, 100)
        }
        lastSettingsLoad = System.currentTimeMillis()
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
                        "none" -> return Season.NONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading debug prefs", e)
        }

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) // 0-indexed
        val day = cal.get(Calendar.DAY_OF_MONTH)

        return when {
            month == 11 && day >= 15 && day <= 26 -> Season.CHRISTMAS
            month == 11 && day >= 27 -> Season.NEW_YEAR
            month == 0 && day <= 7 -> Season.NEW_YEAR
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
                initParticles()
                initialized = true
            }

            when (season) {
                Season.CHRISTMAS -> renderChristmasStars(canvas)
                Season.NEW_YEAR -> renderNewYearFireworks(canvas)
                Season.NONE -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering seasonal events", e)
        }
    }

    private fun initParticles() {
        // Use fixed seed for consistent star placement
        val starRandom = Random(54321)
        
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
            
            s.size = 1.5f + starRandom.nextFloat() * 2.5f  // Smaller background stars
            s.twinkle = starRandom.nextFloat() * 360f
            s.twinkleSpeed = 2f + starRandom.nextFloat() * 4f
        }
        
        // Initialize the main Christmas star (Bethlehem star) - fixed position in upper right
        christmasStar = ChristmasStar(
            x = christmasStarX,
            y = christmasStarY,
            size = 35f,
            twinkle = 0f,
            twinkleSpeed = 3f
        )
        
        // Initialize trail particles (emanating from the star)
        for (i in trailParticles.indices) {
            val t = i.toFloat() / trailParticles.size
            trailParticles[i].apply {
                x = 0f  // Will be set relative to star
                y = 0f
                alpha = (255 * (1f - t * 0.7f)).toInt()  // Fade along trail
                size = 3f + (1f - t) * 8f  // Larger near star
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

    private fun renderChristmasStars(canvas: Canvas) {
        // Note: Background twinkling stars are now handled by SkyLayer
        // SeasonalEvents only renders the main Christmas star with trail
        // This prevents duplicate star rendering at night during Christmas
        
        // Render the main Christmas star (Bethlehem star) with trail
        val star = christmasStar ?: return
        star.twinkle += star.twinkleSpeed
        
        // Apply size multiplier
        val effectiveSize = star.size * starSizeMultiplier
        
        // Trail/Schweif - diagonal line going down-left from the star
        val trailAngle = Math.toRadians(225.0)  // Down-left direction
        val trailLength = effectiveSize * 6f * trailLengthMultiplier
        
        // Only render trail if length > 0
        if (trailLengthMultiplier > 0.01f) {
            for (i in trailParticles.indices) {
                val t = i.toFloat() / trailParticles.size
                val particle = trailParticles[i]
                
                // Position along the trail
                val dist = t * trailLength
                particle.x = star.x + (dist * kotlin.math.cos(trailAngle)).toFloat()
                particle.y = star.y + (dist * kotlin.math.sin(trailAngle)).toFloat()
                
                // Twinkle effect on trail
                val twinkleFactor = 0.8f + 0.2f * kotlin.math.sin(star.twinkle * 0.1 + t * 3.14159).toFloat()
                val alpha = (particle.alpha * twinkleFactor).toInt().coerceIn(30, 255)
                
                trailPaint.alpha = alpha
                canvas.drawCircle(particle.x, particle.y, particle.size * twinkleFactor * starSizeMultiplier, trailPaint)
            }
        }
        
        // Main star - large with twinkle
        val starAlpha = (200 + 55 * kotlin.math.sin(Math.toRadians(star.twinkle.toDouble()))).toInt()
            .coerceIn(180, 255)
        christmasStarPaint.alpha = starAlpha
        
        // Draw star as overlapping shapes for a "star burst" effect
        // Central bright circle
        canvas.drawCircle(star.x, star.y, effectiveSize * 0.4f, christmasStarPaint)
        
        // 4-point star rays
        val rayLength = effectiveSize * 1.2f
        val rayWidth = effectiveSize * 0.15f
        for (angle in listOf(0.0, 90.0, 45.0, 135.0)) {
            val rad = Math.toRadians(angle)
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            
            // Draw ray as a thin line
            val startX = star.x - rayLength * cos
            val startY = star.y - rayLength * sin
            val endX = star.x + rayLength * cos
            val endY = star.y + rayLength * sin
            
            christmasStarPaint.strokeWidth = if (angle == 0.0 || angle == 90.0) rayWidth else rayWidth * 0.7f
            christmasStarPaint.style = Paint.Style.STROKE
            canvas.drawLine(startX, startY, endX, endY, christmasStarPaint)
        }
        christmasStarPaint.style = Paint.Style.FILL
        
        // Outer glow
        christmasStarPaint.alpha = (starAlpha * 0.3f).toInt()
        canvas.drawCircle(star.x, star.y, star.size * 0.8f, christmasStarPaint)
    }

    private fun renderNewYearFireworks(canvas: Canvas) {
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
                
                // Move rocket upward
                r.y += r.vy
                r.vy += 0.05f  // Slight deceleration
                
                // Draw rocket trail (fading dots behind)
                rocketPaint.color = Color.argb(60, 255, 200, 100)
                canvas.drawCircle(r.trailX3, r.trailY3, 1.5f, rocketPaint)
                rocketPaint.color = Color.argb(120, 255, 220, 150)
                canvas.drawCircle(r.trailX2, r.trailY2, 2f, rocketPaint)
                rocketPaint.color = Color.argb(200, 255, 240, 200)
                canvas.drawCircle(r.trailX1, r.trailY1, 2.5f, rocketPaint)
                
                // Draw rocket head
                rocketPaint.color = Color.WHITE
                canvas.drawCircle(r.x, r.y, 3f, rocketPaint)
                
                // Explode when reaching target or when rocket starts falling (vy > 0)
                // This ensures rockets always explode even if they don't quite reach target
                if (r.y <= r.targetY || r.vy > 0) {
                    explodeRocket(r)
                    r.active = false
                }
            }
        }

        // Update and render explosion particles
        for (f in fireworks) {
            if (f.life > 0) {
                // Store previous position for sparkle trail
                f.sparkleTrailX = f.x
                f.sparkleTrailY = f.y
                
                f.x += f.vx
                f.y += f.vy
                f.vy += 0.12f // gravity
                f.vx *= 0.98f // air resistance
                f.life--

                val lifeRatio = f.life / f.maxLife.toFloat()
                val alpha = (255 * lifeRatio).toInt().coerceIn(0, 255)
                
                // Draw sparkle trail behind the ball (fine particles)
                if (f.hasSparkle && lifeRatio > 0.3f) {
                    sparklePaint.color = f.color
                    sparklePaint.alpha = (alpha * 0.4f).toInt()
                    canvas.drawCircle(f.sparkleTrailX, f.sparkleTrailY, f.size * 0.4f, sparklePaint)
                    
                    // Additional tiny sparkles
                    val sparkleAlpha = (alpha * 0.25f).toInt()
                    sparklePaint.alpha = sparkleAlpha
                    canvas.drawCircle(
                        f.sparkleTrailX + (Random.nextFloat() - 0.5f) * 4f,
                        f.sparkleTrailY + (Random.nextFloat() - 0.5f) * 4f,
                        f.size * 0.2f, sparklePaint
                    )
                }
                
                // Draw main colorful ball
                fireworkPaint.color = f.color
                fireworkPaint.alpha = alpha
                val size = f.size * (0.5f + lifeRatio * 0.5f)  // Shrink as it fades
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
                r.vy = -12f - Random.nextFloat() * 6f  // Upward velocity
                r.targetY = screenHeight * 0.15f + Random.nextFloat() * screenHeight * 0.25f
                
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
        // At 10: 10-15 particles, at 50: 25-45 particles, at 100: 60-85 particles
        var particleCount = 0
        val baseParticles = (fireworkAmount * 0.5f + 5).toInt()  // 10→10, 50→30, 100→55
        val varianceParticles = (fireworkAmount * 0.3f).toInt()   // 10→3, 50→15, 100→30
        val targetParticles = baseParticles + Random.nextInt(varianceParticles.coerceAtLeast(1))
        
        for (f in fireworks) {
            if (f.life <= 0 && particleCount < targetParticles) {
                val angle = Random.nextFloat() * 360f
                val speed = 4f + Random.nextFloat() * 6f

                f.x = cx
                f.y = cy
                f.vx = (speed * kotlin.math.cos(Math.toRadians(angle.toDouble()))).toFloat()
                f.vy = (speed * kotlin.math.sin(Math.toRadians(angle.toDouble()))).toFloat()
                
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
}
