package com.example.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.random.Random

/**
 * Renders weather effects: Rain, Snow, Lightning
 * Uses particle pooling to avoid per-frame allocations.
 */
class WeatherLayer {

    enum class WeatherType {
        NONE, RAIN, SNOW, LIGHTNING
    }

    private data class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var speed: Float = 0f,
        var size: Float = 0f,
        var alpha: Int = 255
    )

    // Pre-allocated particle pools (no runtime allocations)
    private val rainParticles = Array(150) { Particle() }
    private val snowParticles = Array(80) { Particle() }

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
        for (p in rainParticles) {
            rainPaint.alpha = p.alpha
            canvas.drawLine(p.x, p.y, p.x - 2f, p.y + p.size, rainPaint)

            // Update position
            p.y += p.speed
            p.x -= 1f // Slight wind effect

            if (p.y > screenHeight) {
                resetRainParticle(p)
            }
        }
    }

    private fun renderSnow(canvas: Canvas) {
        for (p in snowParticles) {
            snowPaint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.size, snowPaint)

            // Update position with gentle sway
            p.y += p.speed
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
}
