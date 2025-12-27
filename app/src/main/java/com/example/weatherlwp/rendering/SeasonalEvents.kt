package com.example.weatherlwp.rendering

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
        var maxLife: Int = 0
    )

    // Pre-allocated pools
    private val stars = Array(30) { Star() }
    private val fireworks = Array(100) { Firework() }
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
        Color.MAGENTA, Color.WHITE, 0xFFFF6600.toInt()
    )

    private val prefs: SharedPreferences? = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    fun getCurrentSeason(): Season {
        // Check for debug override
        try {
            if (prefs != null) {
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
        // Initialize background twinkling stars in upper portion of screen
        for (s in stars) {
            s.x = Random.nextFloat() * screenWidth
            s.y = Random.nextFloat() * screenHeight * 0.4f
            s.size = 2f + Random.nextFloat() * 4f  // Smaller background stars
            s.twinkle = Random.nextFloat() * 360f
            s.twinkleSpeed = 2f + Random.nextFloat() * 4f
        }
        
        // Initialize the main Christmas star (Bethlehem star) - upper right area
        christmasStar = ChristmasStar(
            x = screenWidth * 0.75f + Random.nextFloat() * screenWidth * 0.15f,
            y = screenHeight * 0.08f + Random.nextFloat() * screenHeight * 0.08f,
            size = 25f + Random.nextFloat() * 15f,
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
    }

    private fun renderChristmasStars(canvas: Canvas) {
        // Render background twinkling stars
        for (s in stars) {
            s.twinkle += s.twinkleSpeed
            val alpha = (120 + 80 * kotlin.math.sin(Math.toRadians(s.twinkle.toDouble()))).toInt()
                .coerceIn(80, 200)

            starPaint.alpha = alpha
            starPaint.color = Color.WHITE
            canvas.drawCircle(s.x, s.y, s.size, starPaint)
        }
        
        // Render the main Christmas star (Bethlehem star) with trail
        val star = christmasStar ?: return
        star.twinkle += star.twinkleSpeed
        
        // Trail/Schweif - diagonal line going down-left from the star
        val trailAngle = Math.toRadians(225.0)  // Down-left direction
        val trailLength = star.size * 6f
        
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
            canvas.drawCircle(particle.x, particle.y, particle.size * twinkleFactor, trailPaint)
        }
        
        // Main star - large with twinkle
        val starAlpha = (200 + 55 * kotlin.math.sin(Math.toRadians(star.twinkle.toDouble()))).toInt()
            .coerceIn(180, 255)
        christmasStarPaint.alpha = starAlpha
        
        // Draw star as overlapping shapes for a "star burst" effect
        // Central bright circle
        canvas.drawCircle(star.x, star.y, star.size * 0.4f, christmasStarPaint)
        
        // 4-point star rays
        val rayLength = star.size * 1.2f
        val rayWidth = star.size * 0.15f
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

        // Launch new firework every 60-120 frames
        if (fireworkTimer > 60 + Random.nextInt(60)) {
            fireworkTimer = 0
            launchFirework()
        }

        // Update and render fireworks
        for (f in fireworks) {
            if (f.life > 0) {
                f.x += f.vx
                f.y += f.vy
                f.vy += 0.15f // gravity
                f.life--

                val alpha = (255 * f.life / f.maxLife.toFloat()).toInt().coerceIn(0, 255)
                fireworkPaint.color = f.color
                fireworkPaint.alpha = alpha

                val size = 3f + (f.life / f.maxLife.toFloat()) * 4f
                canvas.drawCircle(f.x, f.y, size, fireworkPaint)
            }
        }
    }

    private fun launchFirework() {
        val cx = screenWidth * 0.2f + Random.nextFloat() * screenWidth * 0.6f
        val cy = screenHeight * 0.2f + Random.nextFloat() * screenHeight * 0.3f
        val color = fireworkColors[Random.nextInt(fireworkColors.size)]

        // Burst particles
        for (f in fireworks) {
            if (f.life <= 0) {
                val angle = Random.nextFloat() * 360f
                val speed = 3f + Random.nextFloat() * 5f

                f.x = cx
                f.y = cy
                f.vx = (speed * kotlin.math.cos(Math.toRadians(angle.toDouble()))).toFloat()
                f.vy = (speed * kotlin.math.sin(Math.toRadians(angle.toDouble()))).toFloat()
                f.color = color
                f.maxLife = 40 + Random.nextInt(30)
                f.life = f.maxLife

                // Only spawn 15-25 particles per burst
                if (Random.nextFloat() > 0.75f) break
            }
        }
    }
}
