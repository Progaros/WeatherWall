package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.example.weatherlwp.parallax.ParallaxCalculator
import kotlin.math.abs
import kotlin.random.Random

class SkyLayer : SceneLayer {
    private val skyPaint = Paint()
    private val starPaint = Paint().apply { 
        color = Color.WHITE 
        isAntiAlias = true 
        style = Paint.Style.FILL 
    }
    
    private var width = 0f
    private var height = 0f
    private var currentTheme = "day"
    
    // Stars
    private val stars = ArrayList<FloatArray>()
    private val starCount = 100
    
    // Colors
    private var skyTop = Color.BLUE
    private var skyBottom = Color.CYAN
    
    companion object {
        private const val SKY_PARALLAX_FACTOR = -0.15f
        private const val STAR_PARALLAX_FACTOR = 0.30f
    }

    init {
        generateStars()
    }

    private fun generateStars() {
        stars.clear()
        val random = Random(42)
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

    override fun update(deltaTimeMs: Long) {
        // Sky doesn't animate per frame usually, unless we do smooth color transitions
    }

    fun setColors(top: Int, bottom: Int) {
        skyTop = top
        skyBottom = bottom
        updateGradient()
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

    override fun draw(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        val (px, py) = parallaxCalculator.getOffset()
        
        // Sky moves opposite to create depth illusion (infinite distance)
        val overscan = 300f + abs(px) * 0.4f + abs(py) * 0.4f
        
        canvas.save()
        canvas.translate(px * SKY_PARALLAX_FACTOR, py * SKY_PARALLAX_FACTOR)
        canvas.drawRect(-overscan, -overscan, width + overscan, height + overscan, skyPaint)
        canvas.restore()

        // Stars (night/dusk only)
        if (currentTheme == "night" || currentTheme == "dusk") {
            val time = System.currentTimeMillis()
            val starAlphaBase = if (currentTheme == "night") 150 else 80
            
            val starParallaxX = px * STAR_PARALLAX_FACTOR
            val starParallaxY = py * STAR_PARALLAX_FACTOR
            
            for (star in stars) {
                val twinkle = kotlin.math.sin((time * 0.001 + star[0] * 10).toDouble())
                val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(30, 220)
                starPaint.alpha = alpha
                
                // Draw star
                val x = star[0] * width + starParallaxX
                val y = star[1] * height + starParallaxY
                
                // Wrap around if parallax moves it off screen? 
                // For now just draw.
                canvas.drawCircle(x, y, star[2], starPaint)
            }
        }
    }

    override fun updateTheme(theme: String, isNight: Boolean) {
        currentTheme = theme
    }

    override fun reloadSettings() {
        // No settings for sky yet
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
