package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.example.weatherlwp.parallax.ParallaxCalculator
import com.example.weatherlwp.simulation.SimulationState
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the sky background with gradient colors and stars.
 * 
 * Uses SimulationState for:
 * - Time of day (colors, star visibility)
 * - absoluteTimeMs (star twinkle animation)
 */
class SkyLayer : SceneLayer {
    private val skyPaint = Paint()
    private val starPaint = Paint().apply { 
        color = Color.WHITE 
        isAntiAlias = true 
        style = Paint.Style.FILL 
    }
    
    private var width = 0f
    private var height = 0f
    
    // Stars - generated once with fixed seed for consistency
    private val stars = ArrayList<FloatArray>()
    private val starCount = 100
    
    // Current gradient colors
    private var skyTop = Color.BLUE
    private var skyBottom = Color.CYAN
    
    companion object {
        private const val SKY_PARALLAX_FACTOR = -0.15f
        private const val STAR_PARALLAX_FACTOR = 0.30f
        
        // Sky colors for different times
        private val NIGHT_TOP = Color.parseColor("#0B1026")
        private val NIGHT_BOTTOM = Color.parseColor("#1A1A3A")
        
        private val DAWN_TOP = Color.parseColor("#FF8C42")
        private val DAWN_BOTTOM = Color.parseColor("#FFD166")
        
        private val DAY_TOP = Color.parseColor("#1E90FF")
        private val DAY_BOTTOM = Color.parseColor("#87CEEB")
        
        private val DUSK_TOP = Color.parseColor("#FF6B35")
        private val DUSK_BOTTOM = Color.parseColor("#FFB347")
    }

    init {
        generateStars()
    }

    private fun generateStars() {
        stars.clear()
        val random = Random(42) // Fixed seed for consistency
        for (i in 0 until starCount) {
            stars.add(floatArrayOf(
                random.nextFloat(), // x (0-1)
                random.nextFloat() * 0.6f, // y (0-0.6, top 60%)
                random.nextFloat() * 3f + 1f // radius
            ))
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        updateGradient()
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Update sky colors based on time of day
        updateColorsForTime(state)
    }
    
    private fun updateColorsForTime(state: SimulationState) {
        val hour = state.timeOfDayHours
        
        val (newTop, newBottom) = when {
            hour < 5f -> Pair(NIGHT_TOP, NIGHT_BOTTOM)
            hour < 6f -> {
                // Transition from night to dawn
                val t = hour - 5f
                Pair(lerpColor(NIGHT_TOP, DAWN_TOP, t), lerpColor(NIGHT_BOTTOM, DAWN_BOTTOM, t))
            }
            hour < 7.5f -> {
                // Dawn
                val t = (hour - 6f) / 1.5f
                Pair(lerpColor(DAWN_TOP, DAY_TOP, t), lerpColor(DAWN_BOTTOM, DAY_BOTTOM, t))
            }
            hour < 17f -> Pair(DAY_TOP, DAY_BOTTOM)
            hour < 18.5f -> {
                // Transition to dusk
                val t = (hour - 17f) / 1.5f
                Pair(lerpColor(DAY_TOP, DUSK_TOP, t), lerpColor(DAY_BOTTOM, DUSK_BOTTOM, t))
            }
            hour < 20f -> {
                // Dusk to night
                val t = (hour - 18.5f) / 1.5f
                Pair(lerpColor(DUSK_TOP, NIGHT_TOP, t), lerpColor(DUSK_BOTTOM, NIGHT_BOTTOM, t))
            }
            else -> Pair(NIGHT_TOP, NIGHT_BOTTOM)
        }
        
        if (newTop != skyTop || newBottom != skyBottom) {
            skyTop = newTop
            skyBottom = newBottom
            updateGradient()
        }
    }
    
    private fun lerpColor(start: Int, end: Int, ratio: Float): Int {
        val r = ((Color.red(start) + ratio * (Color.red(end) - Color.red(start))).toInt()).coerceIn(0, 255)
        val g = ((Color.green(start) + ratio * (Color.green(end) - Color.green(start))).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(start) + ratio * (Color.blue(end) - Color.blue(start))).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateGradient() {
        if (height > 0) {
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                skyTop, skyBottom,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        val (px, py) = parallaxCalculator.getOffset()
        
        // Sky moves opposite to create depth illusion (infinite distance)
        val overscan = 300f + abs(px) * 0.4f + abs(py) * 0.4f
        
        canvas.save()
        canvas.translate(px * SKY_PARALLAX_FACTOR, py * SKY_PARALLAX_FACTOR)
        canvas.drawRect(-overscan, -overscan, width + overscan, height + overscan, skyPaint)
        canvas.restore()

        // Stars visible at night and dusk
        if (state.isNight || state.isDusk) {
            val time = state.absoluteTimeMs
            val starAlphaBase = if (state.isNight) 150 else 80
            
            val starParallaxX = px * STAR_PARALLAX_FACTOR
            val starParallaxY = py * STAR_PARALLAX_FACTOR
            
            for (star in stars) {
                val twinkle = sin((time * 0.001 + star[0] * 10).toDouble())
                val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(30, 220)
                starPaint.alpha = alpha
                
                val x = star[0] * width + starParallaxX
                val y = star[1] * height + starParallaxY
                
                canvas.drawCircle(x, y, star[2], starPaint)
            }
        }
    }

    override fun reloadSettings() {
        // No user settings for sky yet
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
