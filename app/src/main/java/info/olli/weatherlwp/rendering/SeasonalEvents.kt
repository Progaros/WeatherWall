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
    
    // Rainbow settings (St. Patrick's Day)
    private var rainbowPositionX = 50    // 0-100, horizontal position (50 = center)
    private var rainbowPositionY = 50    // 0-100, vertical position (0 = top, 100 = bottom)
    private var rainbowSize = 100        // 0-200, size percentage
    
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
            
            // Rainbow settings
            rainbowPositionX = it.getInt("pref_rainbow_position_x", 50).coerceIn(0, 100)
            rainbowPositionY = it.getInt("pref_rainbow_position_y", 40).coerceIn(0, 100)
            rainbowSize = it.getInt("pref_rainbow_size", 100).coerceIn(20, 200)
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
                initParticles()
                initialized = true
            }

            when (season) {
                Season.CHRISTMAS -> renderChristmasStars(canvas)
                Season.NEW_YEAR -> renderNewYearFireworks(canvas)
                Season.CN_NEW_YEAR -> renderChineseNewYear(canvas)
                Season.ST_PATRICKS -> renderStPatricksDay(canvas)
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
        
        // Twinkle factor for the whole star
        val twinkleFactor = 0.85f + 0.15f * kotlin.math.sin(Math.toRadians(star.twinkle.toDouble())).toFloat()
        
        // Trail/Schweif - gradient path going down-left from the star
        val trailAngle = Math.toRadians(225.0)
        val trailLength = effectiveSize * 7f * trailLengthMultiplier
        
        // Only render trail if length > 0
        if (trailLengthMultiplier > 0.01f) {
            // Smooth gradient trail using LinearGradient instead of discrete circles
            val trailEndX = star.x + (trailLength * kotlin.math.cos(trailAngle)).toFloat()
            val trailEndY = star.y + (trailLength * kotlin.math.sin(trailAngle)).toFloat()
            
            // Create a tapered trail path (wider near star, narrower at end)
            val perpAngle = trailAngle + Math.PI / 2
            val startWidth = effectiveSize * 0.35f
            val endWidth = effectiveSize * 0.05f
            
            val path = Path()
            // Starting edge (near star) - wider
            path.moveTo(
                star.x + (startWidth * kotlin.math.cos(perpAngle)).toFloat(),
                star.y + (startWidth * kotlin.math.sin(perpAngle)).toFloat()
            )
            path.lineTo(
                star.x - (startWidth * kotlin.math.cos(perpAngle)).toFloat(),
                star.y - (startWidth * kotlin.math.sin(perpAngle)).toFloat()
            )
            // End edge (far from star) - narrower
            path.lineTo(
                trailEndX - (endWidth * kotlin.math.cos(perpAngle)).toFloat(),
                trailEndY - (endWidth * kotlin.math.sin(perpAngle)).toFloat()
            )
            path.lineTo(
                trailEndX + (endWidth * kotlin.math.cos(perpAngle)).toFloat(),
                trailEndY + (endWidth * kotlin.math.sin(perpAngle)).toFloat()
            )
            path.close()
            
            // Linear gradient from star (bright gold) to end (transparent)
            val goldColor = Color.parseColor("#FFFFD700")
            trailPaint.shader = LinearGradient(
                star.x, star.y, trailEndX, trailEndY,
                intArrayOf(
                    Color.argb((220 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb((120 * twinkleFactor).toInt(), 255, 215, 0),
                    Color.argb(0, 255, 215, 0)
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            trailPaint.alpha = 255
            canvas.drawPath(path, trailPaint)
            trailPaint.shader = null
        }
        
        // Outer soft glow - radial gradient
        val glowRadius = effectiveSize * 2.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            star.x, star.y, glowRadius,
            intArrayOf(
                Color.argb(80, 255, 230, 150),
                Color.argb(40, 255, 215, 0),
                Color.argb(0, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(star.x, star.y, glowRadius, christmasStarPaint)
        christmasStarPaint.shader = null
        
        // 8-point star rays as filled triangles with alpha falloff
        val primaryRayLength = effectiveSize * 1.6f * twinkleFactor
        val secondaryRayLength = effectiveSize * 0.9f * twinkleFactor
        val rayBaseWidth = effectiveSize * 0.12f
        
        val rayPath = Path()
        for ((index, angle) in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0).withIndex()) {
            val isPrimary = index % 2 == 0  // 0, 90, 180, 270 are primary
            val rayLen = if (isPrimary) primaryRayLength else secondaryRayLength
            val rad = Math.toRadians(angle)
            val perpRad = rad + Math.PI / 2
            
            val tipX = star.x + (rayLen * kotlin.math.cos(rad)).toFloat()
            val tipY = star.y + (rayLen * kotlin.math.sin(rad)).toFloat()
            
            val baseOffset = rayBaseWidth * (if (isPrimary) 1f else 0.7f)
            val baseX1 = star.x + (baseOffset * kotlin.math.cos(perpRad)).toFloat()
            val baseY1 = star.y + (baseOffset * kotlin.math.sin(perpRad)).toFloat()
            val baseX2 = star.x - (baseOffset * kotlin.math.cos(perpRad)).toFloat()
            val baseY2 = star.y - (baseOffset * kotlin.math.sin(perpRad)).toFloat()
            
            rayPath.moveTo(baseX1, baseY1)
            rayPath.lineTo(tipX, tipY)
            rayPath.lineTo(baseX2, baseY2)
            rayPath.close()
        }
        
        // Fill rays with gradient from center
        christmasStarPaint.shader = RadialGradient(
            star.x, star.y, primaryRayLength,
            intArrayOf(
                Color.argb(255, 255, 255, 240),
                Color.argb(220, 255, 220, 100),
                Color.argb(150, 255, 200, 50)
            ),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(rayPath, christmasStarPaint)
        christmasStarPaint.shader = null
        
        // Bright central core with radial gradient
        val coreRadius = effectiveSize * 0.5f * twinkleFactor
        christmasStarPaint.shader = RadialGradient(
            star.x, star.y, coreRadius,
            intArrayOf(
                Color.argb(255, 255, 255, 255),
                Color.argb(255, 255, 240, 200),
                Color.argb(200, 255, 215, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(star.x, star.y, coreRadius, christmasStarPaint)
        christmasStarPaint.shader = null
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
    
    private fun renderStPatricksDay(canvas: Canvas) {
        rainbowPhase += 0.02f
        
        // Position and size based on settings
        val centerX = screenWidth * (rainbowPositionX / 100f)
        val baseY = screenHeight * (rainbowPositionY / 100f)
        val sizeMultiplier = rainbowSize / 100f
        val arcRadius = screenWidth * 0.45f * sizeMultiplier
        
        // Draw rainbow arc with gradient bands
        for (i in rainbowColors.indices.reversed()) {
            val bandWidth = arcRadius * 0.06f
            val bandRadius = arcRadius - i * bandWidth
            
            // Shimmer effect
            val shimmer = (kotlin.math.sin(rainbowPhase + i * 0.5f) * 0.15f + 0.85f).toFloat()
            
            // Create paint for this band
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bandWidth
                strokeCap = Paint.Cap.ROUND
            }
            
            val baseAlpha = (100 * shimmer).toInt()  // Lower base alpha for subtle effect
            paint.color = Color.argb(
                baseAlpha,
                Color.red(rainbowColors[i]),
                Color.green(rainbowColors[i]),
                Color.blue(rainbowColors[i])
            )
            
            // Only draw the upper portion of the arc (behind terrain)
            // Draw arc from 200° to 340° (upper portion, skipping bottom)
            canvas.drawArc(
                centerX - bandRadius,
                baseY - bandRadius,
                centerX + bandRadius,
                baseY + bandRadius,
                200f, 140f, false, paint  // Draw upper arc only
            )
        }
        
        // No sparkles - keep it subtle as a background element
    }
    
    // ========== CHINESE NEW YEAR ==========
    
    private var dragonPhase = 0f
    private val dragonColors = intArrayOf(
        Color.rgb(200, 30, 30),    // Red
        Color.rgb(255, 200, 50)    // Gold
    )
    
    private fun renderChineseNewYear(canvas: Canvas) {
        dragonPhase += 0.03f
        
        // Render New Year fireworks as base
        renderNewYearFireworks(canvas)
        
        // Add simple dragon silhouette moving across screen
        val dragonX = (dragonPhase * 50f) % (screenWidth + 200f) - 100f
        val dragonY = screenHeight * 0.2f + kotlin.math.sin(dragonPhase * 2f).toFloat() * 30f
        
        // Dragon body segments
        val segmentCount = 8
        val segmentSize = 15f
        
        for (i in 0 until segmentCount) {
            val segPhase = dragonPhase - i * 0.3f
            val segX = dragonX - i * segmentSize * 1.2f
            val segY = dragonY + kotlin.math.sin(segPhase * 3f).toFloat() * 15f
            
            // Alternate colors
            starPaint.color = if (i % 2 == 0) dragonColors[0] else dragonColors[1]
            canvas.drawCircle(segX, segY, segmentSize - i * 0.5f, starPaint)
        }
        
        // Dragon head (larger)
        starPaint.color = dragonColors[0]
        canvas.drawCircle(dragonX, dragonY, segmentSize * 1.5f, starPaint)
        
        // Eyes
        starPaint.color = Color.WHITE
        canvas.drawCircle(dragonX + 5f, dragonY - 5f, 4f, starPaint)
        starPaint.color = Color.BLACK
        canvas.drawCircle(dragonX + 6f, dragonY - 5f, 2f, starPaint)
    }
}
