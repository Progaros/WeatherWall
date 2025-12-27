package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.random.Random

/**
 * Renders weather effects: Rain, Snow, Lightning, Fog
 * Uses particle pooling to avoid per-frame allocations.
 * Supports intensity control for rain/snow (0.0-1.0).
 */
class WeatherLayer {

    enum class WeatherType {
        NONE, RAIN, SNOW, LIGHTNING, FOG
    }

    private data class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var speed: Float = 0f,
        var size: Float = 0f,
        var alpha: Int = 255
    )

    // Pre-allocated particle pools (no runtime allocations)
    // Max pool sizes - actual active count depends on intensity
    private val rainParticles = Array(300) { Particle() }
    private val snowParticles = Array(200) { Particle() }
    
    // Intensity (0.0-1.0): controls how many particles are active
    private var intensity = 0.5f  // Default medium intensity

    // Pre-allocated paints
    private val rainPaint = Paint().apply {
        color = Color.argb(180, 150, 180, 220)
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val snowPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    private val lightningPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val flashPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
    }
    
    private val fogPaint = Paint().apply {
        isAntiAlias = true
    }

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0

    private var lightningTimer = 0
    private var lightningFlash = false
    private var lightningX = 0f
    private var lightningPoints = FloatArray(20)

    private var currentWeather = WeatherType.NONE

    fun setWeather(type: WeatherType) {
        if (currentWeather != type) {
            currentWeather = type
            initialized = false
        }
    }
    
    /**
     * Set precipitation intensity (0.0 = light, 1.0 = heavy).
     * Affects number of active particles for rain/snow.
     */
    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun render(canvas: Canvas, enabled: Boolean) {
        if (!enabled || currentWeather == WeatherType.NONE) return

        if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
            screenWidth = canvas.width
            screenHeight = canvas.height
            initParticles()
            initialized = true
        }

        when (currentWeather) {
            WeatherType.RAIN -> renderRain(canvas)
            WeatherType.SNOW -> renderSnow(canvas)
            WeatherType.LIGHTNING -> renderLightning(canvas)
            WeatherType.FOG -> renderFog(canvas)
            WeatherType.NONE -> {}
        }
    }

    private fun initParticles() {
        // Initialize rain particles
        for (p in rainParticles) {
            resetRainParticle(p)
            p.y = Random.nextFloat() * screenHeight // Spread across screen initially
        }

        // Initialize snow particles
        for (p in snowParticles) {
            resetSnowParticle(p)
            p.y = Random.nextFloat() * screenHeight
        }
    }

    private fun resetRainParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -20f
        p.speed = 15f + Random.nextFloat() * 10f
        p.size = 15f + Random.nextFloat() * 25f
        p.alpha = 120 + Random.nextInt(80)
    }

    private fun resetSnowParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -10f
        p.speed = 1.5f + Random.nextFloat() * 2f
        p.size = 3f + Random.nextFloat() * 5f
        p.alpha = 180 + Random.nextInt(75)
    }

    private fun renderRain(canvas: Canvas) {
        // Active particle count based on intensity (30% to 100% of pool)
        val activeCount = (rainParticles.size * (0.3f + intensity * 0.7f)).toInt()
        
        for (i in 0 until activeCount) {
            val p = rainParticles[i]
            rainPaint.alpha = p.alpha
            canvas.drawLine(p.x, p.y, p.x - 2f, p.y + p.size, rainPaint)

            // Speed increases with intensity
            val speedMult = 0.7f + intensity * 0.6f
            p.y += p.speed * speedMult
            p.x -= 1f * speedMult // Slight wind effect

            if (p.y > screenHeight) {
                resetRainParticle(p)
            }
        }
    }

    private fun renderSnow(canvas: Canvas) {
        // Active particle count based on intensity (20% to 100% of pool)
        val activeCount = (snowParticles.size * (0.2f + intensity * 0.8f)).toInt()
        
        for (i in 0 until activeCount) {
            val p = snowParticles[i]
            snowPaint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.size, snowPaint)

            // Speed increases with intensity
            val speedMult = 0.6f + intensity * 0.8f
            p.y += p.speed * speedMult
            p.x += kotlin.math.sin((p.y * 0.02f).toDouble()).toFloat() * 0.8f

            if (p.y > screenHeight) {
                resetSnowParticle(p)
            }
        }
    }

    private fun renderLightning(canvas: Canvas) {
        lightningTimer++

        // Random lightning flash every 120-300 frames
        if (lightningTimer > 120 + Random.nextInt(180)) {
            lightningTimer = 0
            lightningFlash = true
            lightningX = screenWidth * 0.2f + Random.nextFloat() * screenWidth * 0.6f
            generateLightningBolt()
        }

        if (lightningFlash) {
            // Screen flash
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), flashPaint)

            // Draw lightning bolt
            var prevX = lightningX
            var prevY = 0f
            for (i in lightningPoints.indices step 2) {
                val nextX = lightningPoints[i]
                val nextY = lightningPoints[i + 1]
                canvas.drawLine(prevX, prevY, nextX, nextY, lightningPaint)
                prevX = nextX
                prevY = nextY
            }

            // Flash duration
            if (lightningTimer > 3) {
                lightningFlash = false
            }
        }
    }

    private fun generateLightningBolt() {
        var x = lightningX
        var y = 0f
        val segmentHeight = screenHeight / (lightningPoints.size / 2f)

        for (i in lightningPoints.indices step 2) {
            x += Random.nextFloat() * 60f - 30f
            y += segmentHeight
            lightningPoints[i] = x
            lightningPoints[i + 1] = y
        }
    }
    
    /**
     * Render fog as layered semi-transparent bands.
     * Denser at bottom, thinner at top. Intensity affects opacity.
     */
    private fun renderFog(canvas: Canvas) {
        // Base fog opacity depends on intensity (20-60%)
        val baseAlpha = (50 + intensity * 100).toInt().coerceIn(30, 150)
        
        // Draw several fog layers from bottom to top
        val layers = 5
        for (i in 0 until layers) {
            val layerRatio = i.toFloat() / layers
            
            // Lower layers are denser (higher alpha)
            val layerAlpha = (baseAlpha * (1f - layerRatio * 0.7f)).toInt()
            
            // Fog color: grayish-white
            fogPaint.color = Color.argb(layerAlpha, 200, 210, 220)
            
            // Each layer covers the bottom portion of screen
            // Lower layers extend higher
            val layerTop = screenHeight * (0.3f + layerRatio * 0.5f)
            val layerBottom = screenHeight.toFloat()
            
            canvas.drawRect(0f, layerTop, screenWidth.toFloat(), layerBottom, fogPaint)
        }
        
        // Add some wispy horizontal streaks
        val time = System.currentTimeMillis()
        for (i in 0 until 8) {
            val yBase = screenHeight * (0.5f + i * 0.06f)
            val xOffset = kotlin.math.sin((time * 0.0001 + i * 0.5).toDouble()).toFloat() * 50f
            val streakAlpha = (baseAlpha * 0.4f).toInt()
            
            fogPaint.color = Color.argb(streakAlpha, 220, 225, 230)
            canvas.drawRect(
                -100f + xOffset, 
                yBase - 10f, 
                screenWidth + 100f + xOffset, 
                yBase + 20f, 
                fogPaint
            )
        }
    }
}
