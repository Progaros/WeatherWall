package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.weatherlwp.parallax.ParallaxCalculator
import kotlin.random.Random

/**
 * Renders weather effects: Rain, Snow, Lightning, Fog
 * Uses particle pooling to avoid per-frame allocations.
 * Supports intensity control for rain/snow (0.0-1.0).
 */
class WeatherLayer : SceneLayer {

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
        // Draw dark storm clouds first
        drawStormClouds(canvas)
        
        lightningTimer++

        // Random lightning flash every 90-240 frames (more frequent)
        if (lightningTimer > 90 + Random.nextInt(150)) {
            lightningTimer = 0
            lightningFlash = true
            lightningX = screenWidth * 0.15f + Random.nextFloat() * screenWidth * 0.7f
            generateLightningBolt()
        }

        if (lightningFlash) {
            // Screen flash - brighter flash
            flashPaint.color = Color.argb(100, 255, 255, 255)
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), flashPaint)
            flashPaint.color = Color.argb(60, 255, 255, 255)

            // Draw lightning bolt with glow effect
            // Outer glow
            lightningPaint.strokeWidth = 8f
            lightningPaint.color = Color.argb(80, 180, 200, 255)
            drawLightningBolt(canvas)
            
            // Inner bright bolt
            lightningPaint.strokeWidth = 3f
            lightningPaint.color = Color.WHITE
            drawLightningBolt(canvas)

            // Flash duration
            if (lightningTimer > 4) {
                lightningFlash = false
            }
        }
    }
    
    private fun drawLightningBolt(canvas: Canvas) {
        var prevX = lightningX
        var prevY = 0f
        for (i in lightningPoints.indices step 2) {
            val nextX = lightningPoints[i]
            val nextY = lightningPoints[i + 1]
            canvas.drawLine(prevX, prevY, nextX, nextY, lightningPaint)
            prevX = nextX
            prevY = nextY
        }
    }
    
    private fun drawStormClouds(canvas: Canvas) {
        // Dark, ominous storm clouds at the top of screen
        val stormCloudPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        
        // Multiple layers of dark clouds
        val cloudLayers = listOf(
            Triple(0.08f, Color.argb(180, 30, 35, 45), 0.5f),   // Back layer - darkest
            Triple(0.12f, Color.argb(160, 40, 45, 55), 0.6f),   // Middle layer
            Triple(0.18f, Color.argb(140, 50, 55, 65), 0.7f)    // Front layer - lighter
        )
        
        val time = System.currentTimeMillis()
        
        for ((baseY, color, sizeMultiplier) in cloudLayers) {
            stormCloudPaint.color = color
            
            // Draw multiple cloud puffs
            val numPuffs = 8
            for (i in 0 until numPuffs) {
                val xPos = (i.toFloat() / numPuffs) * screenWidth * 1.3f - screenWidth * 0.15f
                val yPos = screenHeight * baseY
                val cloudSize = screenWidth * 0.25f * sizeMultiplier
                
                // Slight animated movement
                val drift = kotlin.math.sin((time / 3000.0 + i * 0.5).toDouble()).toFloat() * 10f
                
                canvas.drawCircle(xPos + drift, yPos, cloudSize, stormCloudPaint)
                canvas.drawCircle(xPos + cloudSize * 0.6f + drift, yPos + cloudSize * 0.2f, cloudSize * 0.8f, stormCloudPaint)
                canvas.drawCircle(xPos - cloudSize * 0.4f + drift, yPos + cloudSize * 0.3f, cloudSize * 0.7f, stormCloudPaint)
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
     * Render fog as a smooth gradient overlay.
     * Dense at bottom, fading to transparent at top. Intensity affects opacity.
     */
    private fun renderFog(canvas: Canvas) {
        // Base fog opacity depends on intensity (30-80%)
        val baseAlpha = (75 + intensity * 180).toInt().coerceIn(60, 200)
        
        // Fog color components for gradient
        val fogR = 200
        val fogG = 210
        val fogB = 220
        
        // Create a smooth vertical gradient from bottom (dense) to top (clear)
        val gradientShader = android.graphics.LinearGradient(
            0f, screenHeight * 0.2f,  // Start: 20% from top (clear)
            0f, screenHeight.toFloat(),  // End: bottom (dense)
            Color.argb(0, fogR, fogG, fogB),  // Transparent at top
            Color.argb(baseAlpha, fogR, fogG, fogB),  // Dense at bottom
            android.graphics.Shader.TileMode.CLAMP
        )
        
        fogPaint.shader = gradientShader
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
        
        // Add subtle animated wisps for depth
        val time = System.currentTimeMillis()
        val wispAlpha = (baseAlpha * 0.3f).toInt()
        
        for (i in 0 until 6) {
            // Each wisp has its own phase and speed
            val phase = i * 1.047f  // 60 degrees apart
            val speed = 0.00008f + i * 0.00002f
            val yBase = screenHeight * (0.4f + i * 0.08f)
            
            // Horizontal drift
            val xOffset = kotlin.math.sin((time * speed + phase).toDouble()).toFloat() * screenWidth * 0.15f
            
            // Wisp height varies over time
            val wispHeight = 30f + kotlin.math.sin((time * 0.0002 + i).toDouble()).toFloat() * 15f
            
            // Use a gradient for each wisp too
            val wispGradient = android.graphics.LinearGradient(
                xOffset - screenWidth * 0.2f, yBase,
                xOffset + screenWidth * 1.2f, yBase,
                intArrayOf(
                    Color.argb(0, 220, 225, 230),
                    Color.argb(wispAlpha, 220, 225, 230),
                    Color.argb(wispAlpha, 220, 225, 230),
                    Color.argb(0, 220, 225, 230)
                ),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            
            fogPaint.shader = wispGradient
            canvas.drawRect(
                0f,
                yBase - wispHeight / 2f,
                screenWidth.toFloat(),
                yBase + wispHeight / 2f,
                fogPaint
            )
        }
        
        fogPaint.shader = null
    }

    override fun resize(w: Float, h: Float) {
        screenWidth = w.toInt()
        screenHeight = h.toInt()
        initialized = false
    }

    override fun update(deltaTimeMs: Long) {
        // Animation handled in render for now
    }

    override fun draw(canvas: Canvas, parallaxCalculator: ParallaxCalculator) {
        render(canvas, true)
    }

    override fun updateTheme(theme: String, isNight: Boolean) {
        val type = when {
            theme.contains("rain") -> WeatherType.RAIN
            theme.contains("snow") -> WeatherType.SNOW
            else -> WeatherType.NONE
        }
        setWeather(type)
    }

    override fun reloadSettings() {
        // No-op
    }

    override fun destroy() {
        // No-op
    }
}
