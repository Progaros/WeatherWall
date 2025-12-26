package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Calendar
import kotlin.random.Random

/**
 * Seasonal event effects: Christmas stars and New Year fireworks.
 * Active during December 15 - January 7.
 */
class SeasonalEvents {

    enum class Season {
        NONE,
        CHRISTMAS,     // Dec 15-26
        NEW_YEAR       // Dec 27 - Jan 7
    }

    // Star particles for Christmas
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

    // Pre-allocated paints
    private val starPaint = Paint().apply {
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val fireworkPaint = Paint().apply {
        isAntiAlias = true
    }

    private val starPath = Path()

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0

    private var fireworkTimer = 0
    private val fireworkColors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
        Color.MAGENTA, Color.WHITE, 0xFFFF6600.toInt()
    )

    fun getCurrentSeason(): Season {
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
    }

    private fun initParticles() {
        // Initialize stars in upper portion of screen
        for (s in stars) {
            s.x = Random.nextFloat() * screenWidth
            s.y = Random.nextFloat() * screenHeight * 0.4f
            s.size = 8f + Random.nextFloat() * 12f
            s.twinkle = Random.nextFloat() * 360f
            s.twinkleSpeed = 2f + Random.nextFloat() * 4f
        }

        // Reset fireworks
        for (f in fireworks) {
            f.life = 0
        }
    }

    private fun renderChristmasStars(canvas: Canvas) {
        for (s in stars) {
            s.twinkle += s.twinkleSpeed
            val alpha = (155 + 100 * kotlin.math.sin(Math.toRadians(s.twinkle.toDouble()))).toInt()
                .coerceIn(100, 255)

            starPaint.alpha = alpha
            drawStar(canvas, s.x, s.y, s.size, starPaint)
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        starPath.reset()
        val innerRadius = size * 0.4f

        for (i in 0 until 5) {
            val outerAngle = Math.toRadians((i * 72 - 90).toDouble())
            val innerAngle = Math.toRadians((i * 72 + 36 - 90).toDouble())

            val outerX = cx + (size * kotlin.math.cos(outerAngle)).toFloat()
            val outerY = cy + (size * kotlin.math.sin(outerAngle)).toFloat()
            val innerX = cx + (innerRadius * kotlin.math.cos(innerAngle)).toFloat()
            val innerY = cy + (innerRadius * kotlin.math.sin(innerAngle)).toFloat()

            if (i == 0) {
                starPath.moveTo(outerX, outerY)
            } else {
                starPath.lineTo(outerX, outerY)
            }
            starPath.lineTo(innerX, innerY)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
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
