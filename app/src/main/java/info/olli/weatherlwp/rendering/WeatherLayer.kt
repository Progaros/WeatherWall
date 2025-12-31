package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.weather.WeatherType as AppWeatherType
import kotlin.random.Random

/**
 * Renders weather effects: Rain, Snow, Lightning, Fog
 * 
 * Uses SimulationState for:
 * - weather (what effects to show)
 * - windStrength (affects particle drift)
 * - timeSeconds (for storm cloud animation)
 * 
 * Uses particle pooling to avoid per-frame allocations.
 * Supports intensity control for rain/snow (0.0-1.0).
 * 
 * Design notes:
 * - Rain should include a semi-transparent grey dimming overlay
 * - Fog uses smooth gradients, not solid colors
 * - Weather effects should "sell the feeling" of the weather
 */
class WeatherLayer : SceneLayer {

    /**
     * Effect type for weather rendering.
     * @deprecated Use EffectType instead. This typealias is kept for backwards compatibility.
     */
    enum class WeatherType {
        NONE, RAIN, SNOW, HAIL, LIGHTNING, FOG
    }
    
    // Internal effect type
    enum class EffectType {
        NONE, RAIN, SNOW, HAIL, LIGHTNING, FOG
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
    private val hailParticles = Array(150) { Particle() }  // Hail is larger, needs fewer
    
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
    
    private val hailPaint = Paint().apply {
        color = Color.argb(220, 200, 220, 255)  // Icy blue-white
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
    
    private val rainDimPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0

    private var lightningTimer = 0
    private var lightningFlash = false
    private var lightningX = 0f
    private var lightningPoints = FloatArray(20)

    private var currentEffect = EffectType.NONE

    fun setEffect(type: EffectType) {
        if (currentEffect != type) {
            currentEffect = type
            initialized = false
        }
    }
    
    /**
     * Set weather for backwards compatibility.
     * @deprecated Use SimulationState for weather instead
     */
    fun setWeather(type: WeatherType) {
        val effect = when (type) {
            WeatherType.RAIN -> EffectType.RAIN
            WeatherType.SNOW -> EffectType.SNOW
            WeatherType.HAIL -> EffectType.HAIL
            WeatherType.LIGHTNING -> EffectType.LIGHTNING
            WeatherType.FOG -> EffectType.FOG
            WeatherType.NONE -> EffectType.NONE
        }
        setEffect(effect)
    }
    
    /**
     * Convert app WeatherType to render effect type.
     */
    private fun weatherToEffect(weather: AppWeatherType): EffectType {
        return when (weather) {
            AppWeatherType.RAIN -> EffectType.RAIN
            AppWeatherType.SNOW -> EffectType.SNOW
            AppWeatherType.HAIL -> EffectType.HAIL
            AppWeatherType.THUNDERSTORM -> EffectType.LIGHTNING
            AppWeatherType.FOG -> EffectType.FOG
            else -> EffectType.NONE
        }
    }
    
    /**
     * Set precipitation intensity (0.0 = light, 1.0 = heavy).
     * Affects number of active particles for rain/snow.
     */
    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }
    
    /**
     * Render with enabled flag for backwards compatibility.
     * @deprecated Use render(canvas, state) instead
     */
    fun render(canvas: Canvas, enabled: Boolean) {
        if (!enabled || currentEffect == EffectType.NONE) return

        if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
            screenWidth = canvas.width
            screenHeight = canvas.height
            initParticles()
            initialized = true
        }

        // Use default state values for wind
        val defaultWindStrength = 0.5f
        val defaultTimeSeconds = System.currentTimeMillis() / 1000f

        when (currentEffect) {
            EffectType.RAIN -> renderRainLegacy(canvas, defaultWindStrength)
            EffectType.SNOW -> renderSnowLegacy(canvas, defaultWindStrength)
            EffectType.HAIL -> renderHailLegacy(canvas, defaultWindStrength)
            EffectType.LIGHTNING -> renderLightningLegacy(canvas, defaultTimeSeconds)
            EffectType.FOG -> renderFogLegacy(canvas)
            EffectType.NONE -> {}
        }
    }
    
    // Legacy render methods that don't use SimulationState
    private fun renderRainLegacy(canvas: Canvas, windStrength: Float) {
        // Dim the world during rain
        val dimAlpha = (20 + intensity * 40).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 100, 105, 115)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)
        
        val activeCount = (rainParticles.size * (0.3f + intensity * 0.7f)).toInt()
        val windDrift = windStrength * 3f
        
        for (i in 0 until activeCount) {
            val p = rainParticles[i]
            rainPaint.alpha = p.alpha
            canvas.drawLine(p.x, p.y, p.x - 2f - windDrift, p.y + p.size, rainPaint)
            val speedMult = 0.7f + intensity * 0.6f
            p.y += p.speed * speedMult
            p.x -= (1f + windDrift) * speedMult
            if (p.y > screenHeight) resetRainParticle(p)
        }
    }
    
    private fun renderSnowLegacy(canvas: Canvas, windStrength: Float) {
        val activeCount = (snowParticles.size * (0.2f + intensity * 0.8f)).toInt()
        val windDrift = windStrength * 2f
        
        for (i in 0 until activeCount) {
            val p = snowParticles[i]
            snowPaint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.size, snowPaint)
            val speedMult = 0.6f + intensity * 0.8f
            p.y += p.speed * speedMult
            p.x += kotlin.math.sin((p.y * 0.02f).toDouble()).toFloat() * 0.8f + windDrift * 0.5f
            if (p.y > screenHeight) resetSnowParticle(p)
        }
    }
    
    private fun renderHailLegacy(canvas: Canvas, windStrength: Float) {
        // Dim the world during hail
        val dimAlpha = (25 + intensity * 50).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 90, 95, 110)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)
        
        val activeCount = (hailParticles.size * (0.25f + intensity * 0.75f)).toInt()
        val windDrift = windStrength * 1.5f
        
        for (i in 0 until activeCount) {
            val p = hailParticles[i]
            hailPaint.alpha = p.alpha
            val ovalWidth = p.size * 0.8f
            val ovalHeight = p.size * 1.1f
            canvas.drawOval(p.x - ovalWidth, p.y - ovalHeight, p.x + ovalWidth, p.y + ovalHeight, hailPaint)
            val speedMult = 0.8f + intensity * 0.4f
            p.y += p.speed * speedMult
            p.x += kotlin.math.sin((p.y * 0.05f).toDouble()).toFloat() * 0.5f + windDrift * 0.3f
            if (p.y > screenHeight) resetHailParticle(p)
        }
    }
    
    private fun renderLightningLegacy(canvas: Canvas, timeSeconds: Float) {
        drawStormClouds(canvas, timeSeconds)
        lightningTimer++
        if (lightningTimer > 90 + Random.nextInt(150)) {
            lightningTimer = 0
            lightningFlash = true
            lightningX = screenWidth * 0.15f + Random.nextFloat() * screenWidth * 0.7f
            generateLightningBolt()
        }
        if (lightningFlash) {
            flashPaint.color = Color.argb(100, 255, 255, 255)
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), flashPaint)
            flashPaint.color = Color.argb(60, 255, 255, 255)
            lightningPaint.strokeWidth = 8f
            lightningPaint.color = Color.argb(80, 180, 200, 255)
            drawLightningBolt(canvas)
            lightningPaint.strokeWidth = 3f
            lightningPaint.color = Color.WHITE
            drawLightningBolt(canvas)
            if (lightningTimer > 4) lightningFlash = false
        }
    }
    
    private fun renderFogLegacy(canvas: Canvas) {
        // Base fog opacity depends on intensity (30-70%)
        val baseAlpha = (60 + intensity * 120).toInt().coerceIn(50, 170)
        
        // Use day colors for legacy rendering (no state available)
        val fogR = RenderConstants.FOG_R
        val fogG = RenderConstants.FOG_G
        val fogB = RenderConstants.FOG_B
        
        // Create a smooth vertical gradient from bottom (dense) to top (clear)
        val gradientShader = android.graphics.LinearGradient(
            0f, screenHeight * 0.20f,  // Start: 20% from top (clear)
            0f, screenHeight * 1.2f,   // End: 120% height (below screen edge)
            Color.argb(0, fogR, fogG, fogB),  // Transparent at top
            Color.argb(baseAlpha, fogR, fogG, fogB),  // Dense below screen
            android.graphics.Shader.TileMode.CLAMP
        )
        
        fogPaint.shader = gradientShader
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
    }

    fun render(canvas: Canvas, state: SimulationState) {
        // Update effect based on state
        val effect = weatherToEffect(state.weather)
        setEffect(effect)
        
        if (currentEffect == EffectType.NONE) return

        if (!initialized || screenWidth != canvas.width || screenHeight != canvas.height) {
            screenWidth = canvas.width
            screenHeight = canvas.height
            initParticles()
            initialized = true
        }

        when (currentEffect) {
            EffectType.RAIN -> renderRain(canvas, state)
            EffectType.SNOW -> renderSnow(canvas, state)
            EffectType.HAIL -> renderHail(canvas, state)
            EffectType.LIGHTNING -> renderLightning(canvas, state)
            EffectType.FOG -> renderFog(canvas, state)
            EffectType.NONE -> {}
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
        
        // Initialize hail particles
        for (p in hailParticles) {
            resetHailParticle(p)
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
    
    private fun resetHailParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -15f
        p.speed = 18f + Random.nextFloat() * 12f  // Fast falling
        p.size = 5f + Random.nextFloat() * 8f     // Larger than rain/snow
        p.alpha = 200 + Random.nextInt(55)
    }

    private fun renderRain(canvas: Canvas, state: SimulationState) {
        // Dim the world during rain (atmospheric effect)
        // Intensity affects dimming: light rain = subtle, heavy = more dim
        val dimAlpha = (20 + intensity * 40).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 100, 105, 115)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)
        
        // Active particle count based on intensity (30% to 100% of pool)
        val activeCount = (rainParticles.size * (0.3f + intensity * 0.7f)).toInt()
        
        // Wind affects horizontal drift
        val windDrift = state.windStrength * 3f
        
        for (i in 0 until activeCount) {
            val p = rainParticles[i]
            rainPaint.alpha = p.alpha
            canvas.drawLine(p.x, p.y, p.x - 2f - windDrift, p.y + p.size, rainPaint)

            // Speed increases with intensity
            val speedMult = 0.7f + intensity * 0.6f
            p.y += p.speed * speedMult
            p.x -= (1f + windDrift) * speedMult // Wind-affected drift

            if (p.y > screenHeight) {
                resetRainParticle(p)
            }
        }
    }

    private fun renderSnow(canvas: Canvas, state: SimulationState) {
        // Active particle count based on intensity (20% to 100% of pool)
        val activeCount = (snowParticles.size * (0.2f + intensity * 0.8f)).toInt()
        
        // Wind affects horizontal drift
        val windDrift = state.windStrength * 2f
        
        for (i in 0 until activeCount) {
            val p = snowParticles[i]
            snowPaint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.size, snowPaint)

            // Speed increases with intensity
            val speedMult = 0.6f + intensity * 0.8f
            p.y += p.speed * speedMult
            p.x += kotlin.math.sin((p.y * 0.02f).toDouble()).toFloat() * 0.8f + windDrift * 0.5f

            if (p.y > screenHeight) {
                resetSnowParticle(p)
            }
        }
    }
    
    /**
     * Render hail as icy spheres falling faster than rain/snow.
     * Hail has a slight bounce/irregular motion and is larger.
     */
    private fun renderHail(canvas: Canvas, state: SimulationState) {
        // Dim the world during hail (similar to rain but slightly darker)
        val dimAlpha = (25 + intensity * 50).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 90, 95, 110)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)
        
        // Active particle count based on intensity (25% to 100% of pool)
        val activeCount = (hailParticles.size * (0.25f + intensity * 0.75f)).toInt()
        
        // Wind affects horizontal drift (less than rain because hail is heavier)
        val windDrift = state.windStrength * 1.5f
        
        for (i in 0 until activeCount) {
            val p = hailParticles[i]
            hailPaint.alpha = p.alpha
            
            // Draw hail as irregular oval (ice pellet)
            val ovalWidth = p.size * 0.8f
            val ovalHeight = p.size * 1.1f
            canvas.drawOval(
                p.x - ovalWidth, p.y - ovalHeight,
                p.x + ovalWidth, p.y + ovalHeight,
                hailPaint
            )
            
            // Draw highlight for 3D effect
            hailPaint.alpha = (p.alpha * 0.5f).toInt()
            canvas.drawCircle(p.x - p.size * 0.3f, p.y - p.size * 0.4f, p.size * 0.3f, hailPaint)
            hailPaint.alpha = p.alpha

            // Fast falling with slight wobble
            val speedMult = 0.8f + intensity * 0.4f
            p.y += p.speed * speedMult
            // Slight horizontal wobble (less than snow)
            p.x += kotlin.math.sin((p.y * 0.05f).toDouble()).toFloat() * 0.5f + windDrift * 0.3f

            if (p.y > screenHeight) {
                resetHailParticle(p)
            }
        }
    }

    private fun renderLightning(canvas: Canvas, state: SimulationState) {
        // Draw dark storm clouds first
        drawStormClouds(canvas, state.timeSeconds)
        
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
    
    private fun drawStormClouds(canvas: Canvas, timeSeconds: Float) {
        // Dark, ominous storm clouds covering top of screen
        // Position: -10% (above screen) to 20% from top
        val stormCloudPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        
        // Multiple layers of dark clouds using centralized constants
        val cloudLayers = listOf(
            Triple(RenderConstants.STORM_CLOUD_Y1, RenderConstants.STORM_CLOUD_COLOR_BACK, RenderConstants.STORM_CLOUD_SIZE_BACK),
            Triple(RenderConstants.STORM_CLOUD_Y2, RenderConstants.STORM_CLOUD_COLOR_MID, RenderConstants.STORM_CLOUD_SIZE_MID),
            Triple(RenderConstants.STORM_CLOUD_Y3, RenderConstants.STORM_CLOUD_COLOR_FRONT, RenderConstants.STORM_CLOUD_SIZE_FRONT)
        )
        
        for ((baseY, color, sizeMultiplier) in cloudLayers) {
            stormCloudPaint.color = color
            
            // Draw multiple cloud puffs
            val numPuffs = 8
            for (i in 0 until numPuffs) {
                val xPos = (i.toFloat() / numPuffs) * screenWidth * 1.3f - screenWidth * 0.15f
                val yPos = screenHeight * baseY
                val cloudSize = screenWidth * RenderConstants.STORM_CLOUD_BASE_SIZE * sizeMultiplier
                
                // Slight animated movement using consistent timeSeconds
                val drift = kotlin.math.sin((timeSeconds * 0.3 + i * 0.5).toDouble()).toFloat() * 10f
                
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
     * Render fog as a smooth gradient overlay with subtle animation.
     * Dense at bottom, fading to transparent at top. Intensity affects opacity.
     * Color adjusts based on light level - darker/bluer at night.
     */
    private fun renderFog(canvas: Canvas, state: SimulationState) {
        // Base fog opacity depends on intensity (30-70%)
        val baseAlpha = (60 + intensity * 120).toInt().coerceIn(50, 170)
        
        // Adjust fog color based on light level
        // Day: bright gray (210, 215, 225)
        // Night: dark blue-gray (40, 45, 60)
        val lightLevel = state.getLightLevel()
        val nightR = 40
        val nightG = 45
        val nightB = 60
        val dayR = RenderConstants.FOG_R
        val dayG = RenderConstants.FOG_G
        val dayB = RenderConstants.FOG_B
        
        val fogR = (nightR + (dayR - nightR) * lightLevel).toInt()
        val fogG = (nightG + (dayG - nightG) * lightLevel).toInt()
        val fogB = (nightB + (dayB - nightB) * lightLevel).toInt()
        
        // Create a smooth vertical gradient from bottom (dense) to top (clear)
        // Extend gradient BELOW screen to avoid visible sharp edge at bottom
        val gradientShader = android.graphics.LinearGradient(
            0f, screenHeight * 0.20f,  // Start: 20% from top (clear)
            0f, screenHeight * 1.2f,   // End: 120% height (below screen edge)
            Color.argb(0, fogR, fogG, fogB),  // Transparent at top
            Color.argb(baseAlpha, fogR, fogG, fogB),  // Dense below screen
            android.graphics.Shader.TileMode.CLAMP
        )
        
        fogPaint.shader = gradientShader
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
        
        // Add a second layer for depth - mid-height concentration
        // Also extend below screen
        val midAlpha = (baseAlpha * 0.5f).toInt()
        val midGradient = android.graphics.LinearGradient(
            0f, screenHeight * 0.3f,
            0f, screenHeight * 1.1f,  // End below screen
            Color.argb(0, fogR, fogG, fogB),
            Color.argb(midAlpha, fogR + 5, fogG + 5, fogB + 5),
            android.graphics.Shader.TileMode.CLAMP
        )
        fogPaint.shader = midGradient
        canvas.drawRect(0f, screenHeight * 0.3f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
    }

    override fun resize(w: Float, h: Float) {
        screenWidth = w.toInt()
        screenHeight = h.toInt()
        initialized = false
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Animation handled in render for now
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        render(canvas, state)
    }

    override fun reloadSettings() {
        // No-op
    }

    override fun destroy() {
        // No-op
    }
}
