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
    override val layerType = LayerType.WEATHER

    private companion object {
        private const val RAIN_DEPTH_FAR = 0
        private const val RAIN_DEPTH_MEDIUM = 1
        private const val RAIN_DEPTH_NEAR = 2

        private const val SNOW_DEPTH_SMALL = 0
        private const val SNOW_DEPTH_MEDIUM = 1
        private const val SNOW_DEPTH_LARGE = 2
    }

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
        var alpha: Int = 255,
        var layer: Int = 0  // 0=front (closest), higher=further back
    )
    
    // Weather layer settings - particles spawn across layers for depth effect
    // Layer 0 = front (largest, fastest), higher layers = further back (smaller, slower)
    private var weatherLayers = 3          // Number of depth layers (1-5)
    private var layerSeparation = 0.3f     // Size/speed difference between layers (0.1-0.5)
    private var layerDepthOffset = 0.2f    // Parallax depth offset per layer (0.1-0.4)

    // Pre-allocated particle pools (no runtime allocations)
    // Max pool sizes - actual active count depends on intensity
    private val rainParticles = Array(RenderConstants.RAIN_PARTICLE_POOL_SIZE) { Particle() }
    private val snowParticles = Array(RenderConstants.SNOW_PARTICLE_POOL_SIZE) { Particle() }
    private val hailParticles = Array(150) { Particle() }  // Hail is larger, needs fewer

    // Batched draw buffers - reused every frame (no per-frame allocation)
    private val rainCoordsFar = FloatArray(RenderConstants.RAIN_PARTICLE_POOL_SIZE * 4)
    private val rainCoordsMedium = FloatArray(RenderConstants.RAIN_PARTICLE_POOL_SIZE * 4)
    private val rainCoordsNear = FloatArray(RenderConstants.RAIN_PARTICLE_POOL_SIZE * 4)
    private val snowCoordsSmall = FloatArray(RenderConstants.SNOW_PARTICLE_POOL_SIZE * 2)
    private val snowCoordsMedium = FloatArray(RenderConstants.SNOW_PARTICLE_POOL_SIZE * 2)
    private val snowCoordsLarge = FloatArray(RenderConstants.SNOW_PARTICLE_POOL_SIZE * 2)
    private var rainFarCoordCount = 0
    private var rainMediumCoordCount = 0
    private var rainNearCoordCount = 0
    private var snowSmallCoordCount = 0
    private var snowMediumCoordCount = 0
    private var snowLargeCoordCount = 0
    
    // Intensity (0.0-1.0): controls how many particles are active
    private var intensity = 0.5f  // Default medium intensity

    // Pre-allocated paints
    private val rainPaintFar = Paint().apply {
        color = Color.argb(120, 145, 175, 215)
        strokeWidth = 1.3f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val rainPaintMedium = Paint().apply {
        color = Color.argb(165, 150, 180, 220)
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val rainPaintNear = Paint().apply {
        color = Color.argb(180, 150, 180, 220)
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val snowPaintSmall = Paint().apply {
        color = Color.argb(150, 245, 245, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val snowPaintMedium = Paint().apply {
        color = Color.argb(185, 248, 248, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f
        isAntiAlias = true
    }

    private val snowPaintLarge = Paint().apply {
        color = Color.argb(220, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.0f
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
    
    // Pre-allocated storm cloud paint to avoid allocations in render loop
    private val stormCloudPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    // Pre-allocated storm cloud layers (y, color, size) to avoid allocations in render loop
    private val stormCloudLayers = arrayOf(
        floatArrayOf(RenderConstants.STORM_CLOUD_Y1, RenderConstants.STORM_CLOUD_SIZE_BACK),
        floatArrayOf(RenderConstants.STORM_CLOUD_Y2, RenderConstants.STORM_CLOUD_SIZE_MID),
        floatArrayOf(RenderConstants.STORM_CLOUD_Y3, RenderConstants.STORM_CLOUD_SIZE_FRONT)
    )
    private val stormCloudColors = intArrayOf(
        RenderConstants.STORM_CLOUD_COLOR_BACK,
        RenderConstants.STORM_CLOUD_COLOR_MID,
        RenderConstants.STORM_CLOUD_COLOR_FRONT
    )
    
    private val rainDimPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private var initialized = false
    private var screenWidth = 0
    private var screenHeight = 0

    // Lightning timing (now ms-based instead of frame-based)
    private var lightningTimerMs = 0L
    private var lightningNextFlashMs = 0L  // Time until next flash
    private var lightningFlash = false
    private var lightningFlashRemainingMs = 0L
    private var lightningX = 0f
    private var lightningPoints = FloatArray(20)
    
    // Store state for particle updates
    private var cachedWindStrength = 0f
    
    // Cached fog gradient state to avoid creating LinearGradient every frame
    private var cachedFogLightLevel = -1f
    private var cachedFogGradient: android.graphics.LinearGradient? = null
    private var cachedFogMidGradient: android.graphics.LinearGradient? = null

    private var currentEffect = EffectType.NONE

    fun setEffect(type: EffectType) {
        if (currentEffect != type) {
            currentEffect = type
            initialized = false
        }
    }
    
    /**
     * Check if weather effects are currently active (any particles or effects).
     * Used for idle mode battery optimization.
     */
    fun isActive(): Boolean {
        return currentEffect != EffectType.NONE
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
            EffectType.RAIN -> renderRain(canvas)
            EffectType.SNOW -> renderSnow(canvas, state)
            EffectType.HAIL -> renderHail(canvas, state)
            EffectType.LIGHTNING -> renderLightning(canvas, state)
            EffectType.FOG -> renderFog(canvas, state)
            EffectType.NONE -> {}
        }
    }

    private fun initParticles() {
        configureRainLinePaints()
        configureSnowPointPaints()

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
        // Assign to a random layer (0 = front/closest, weatherLayers-1 = back/furthest)
        p.layer = Random.nextInt(weatherLayers.coerceAtLeast(1))
        val layerScale = getLayerScale(p.layer)
        
        p.x = Random.nextFloat() * screenWidth
        p.y = -20f
        // Base speed scaled by layer (back layers are slower)
        p.speed = (15f + Random.nextFloat() * 10f) * layerScale
        // Base size scaled by layer (back layers are smaller)
        p.size = (15f + Random.nextFloat() * 25f) * layerScale
        // Alpha reduced for back layers (more atmospheric)
        p.alpha = ((120 + Random.nextInt(80)) * layerScale).toInt().coerceIn(60, 255)
    }

    private fun recycleRainParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -20f
    }

    private fun resetSnowParticle(p: Particle) {
        // Assign to a random layer
        p.layer = Random.nextInt(weatherLayers.coerceAtLeast(1))
        val layerScale = getLayerScale(p.layer)
        
        p.x = Random.nextFloat() * screenWidth
        p.y = -10f
        // Base speed scaled by layer
        p.speed = (1.5f + Random.nextFloat() * 2f) * layerScale
        // Size relative to screen height, also scaled by layer
        val heightScale = RenderConstants.getHeightScale(screenHeight.toFloat())
        p.size = (3f + Random.nextFloat() * 5f) * heightScale * layerScale
        // Alpha reduced for back layers
        p.alpha = ((180 + Random.nextInt(75)) * layerScale).toInt().coerceIn(60, 255)
    }

    private fun recycleSnowParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -10f
    }
    
    private fun resetHailParticle(p: Particle) {
        // Assign to a random layer
        p.layer = Random.nextInt(weatherLayers.coerceAtLeast(1))
        val layerScale = getLayerScale(p.layer)
        
        p.x = Random.nextFloat() * screenWidth
        p.y = -15f
        // Base speed scaled by layer
        p.speed = (18f + Random.nextFloat() * 12f) * layerScale
        // Base size scaled by layer
        p.size = (5f + Random.nextFloat() * 8f) * layerScale
        // Alpha reduced for back layers
        p.alpha = ((200 + Random.nextInt(55)) * layerScale).toInt().coerceIn(80, 255)
    }

    private fun recycleHailParticle(p: Particle) {
        p.x = Random.nextFloat() * screenWidth
        p.y = -15f
    }

    private fun configureSnowPointPaints() {
        val heightScale = RenderConstants.getHeightScale(screenHeight.toFloat()).coerceAtLeast(0.35f)
        snowPaintSmall.strokeWidth = (1.2f * heightScale).coerceAtLeast(0.8f)
        snowPaintMedium.strokeWidth = (1.8f * heightScale).coerceAtLeast(1.1f)
        snowPaintLarge.strokeWidth = (2.5f * heightScale).coerceAtLeast(1.5f)
    }

    private fun configureRainLinePaints() {
        val heightScale = RenderConstants.getHeightScale(screenHeight.toFloat()).coerceAtLeast(0.35f)
        rainPaintFar.strokeWidth = (1.1f * heightScale).coerceAtLeast(0.75f)
        rainPaintMedium.strokeWidth = (1.6f * heightScale).coerceAtLeast(1.05f)
        rainPaintNear.strokeWidth = (2.2f * heightScale).coerceAtLeast(1.4f)
    }

    private fun getRainDepthBucket(layer: Int): Int {
        if (weatherLayers <= 1) return RAIN_DEPTH_MEDIUM
        val normalizedLayer = layer.toFloat() / (weatherLayers - 1)
        return when {
            normalizedLayer >= 0.66f -> RAIN_DEPTH_FAR
            normalizedLayer >= 0.33f -> RAIN_DEPTH_MEDIUM
            else -> RAIN_DEPTH_NEAR
        }
    }

    private fun getSnowDepthBucket(layer: Int): Int {
        if (weatherLayers <= 1) return SNOW_DEPTH_MEDIUM
        val normalizedLayer = layer.toFloat() / (weatherLayers - 1)
        return when {
            normalizedLayer >= 0.66f -> SNOW_DEPTH_SMALL
            normalizedLayer >= 0.33f -> SNOW_DEPTH_MEDIUM
            else -> SNOW_DEPTH_LARGE
        }
    }
    
    /**
     * Get scale factor for a layer (0=front=1.0, higher=further back=smaller).
     * Layer scale affects size, speed, and alpha to create depth illusion.
     */
    private fun getLayerScale(layer: Int): Float {
        if (weatherLayers <= 1) return 1f
        // Front layer (0) = 1.0, back layer (max) = 1.0 - (maxLayer * separation)
        // E.g., 3 layers with 0.3 separation: layer0=1.0, layer1=0.7, layer2=0.4
        val maxLayer = weatherLayers - 1
        return 1f - (layer.toFloat() / maxLayer) * layerSeparation * 2f
    }
    
    /**
     * Get parallax depth multiplier for a layer.
     * Front layers move more with parallax (closer to camera).
     */
    fun getLayerDepth(layer: Int): Float {
        if (weatherLayers <= 1) return 0.5f
        // Front layer (0) = higher depth (more movement), back = lower
        val maxLayer = weatherLayers - 1
        val normalizedLayer = layer.toFloat() / maxLayer  // 0 to 1
        // Map to depth: front (0) = 0.3 (foreground), back (1) = 0.8 (background)
        return 0.3f + normalizedLayer * layerDepthOffset * 2f
    }

    private fun renderRain(canvas: Canvas) {
        // Dim the world during rain (atmospheric effect)
        // Intensity affects dimming: light rain = subtle, heavy = more dim
        val dimAlpha = (20 + intensity * 40).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 100, 105, 115)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)

        // Slightly boost visual dynamics by varying alpha per depth batch.
        rainPaintFar.alpha = (105 + intensity * 55f).toInt().coerceIn(70, 200)
        rainPaintMedium.alpha = (145 + intensity * 65f).toInt().coerceIn(100, 225)
        rainPaintNear.alpha = (180 + intensity * 70f).toInt().coerceIn(130, 255)

        if (rainFarCoordCount > 0) {
            canvas.drawLines(rainCoordsFar, 0, rainFarCoordCount, rainPaintFar)
        }
        if (rainMediumCoordCount > 0) {
            canvas.drawLines(rainCoordsMedium, 0, rainMediumCoordCount, rainPaintMedium)
        }
        if (rainNearCoordCount > 0) {
            canvas.drawLines(rainCoordsNear, 0, rainNearCoordCount, rainPaintNear)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun renderSnow(canvas: Canvas, state: SimulationState) {
        // state kept for API consistency with other render methods
        // Draw pre-bucketed snow points with fixed paints (small/mid/large depth)
        if (snowSmallCoordCount > 0) {
            canvas.drawPoints(snowCoordsSmall, 0, snowSmallCoordCount, snowPaintSmall)
        }
        if (snowMediumCoordCount > 0) {
            canvas.drawPoints(snowCoordsMedium, 0, snowMediumCoordCount, snowPaintMedium)
        }
        if (snowLargeCoordCount > 0) {
            canvas.drawPoints(snowCoordsLarge, 0, snowLargeCoordCount, snowPaintLarge)
        }
    }
    
    /**
     * Render hail as icy spheres falling faster than rain/snow.
     * Hail has a slight bounce/irregular motion and is larger.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun renderHail(canvas: Canvas, state: SimulationState) {
        // state kept for API consistency with other render methods
        // Dim the world during hail (similar to rain but slightly darker)
        val dimAlpha = (25 + intensity * 50).toInt()
        rainDimPaint.color = Color.argb(dimAlpha, 90, 95, 110)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), rainDimPaint)
        
        // Active particle count based on intensity (25% to 100% of pool)
        val activeCount = (hailParticles.size * (0.25f + intensity * 0.75f)).toInt()
        
        // Draw only - physics moved to update()
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
        }
    }

    private fun renderLightning(canvas: Canvas, state: SimulationState) {
        // Draw dark storm clouds first
        drawStormClouds(canvas, state.timeSeconds)
        
        // Also render rain during thunderstorm
        renderRain(canvas)
        
        // Lightning flash state is managed in update() - draw only here
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
        // Uses pre-allocated paint and layer arrays to avoid GC during storms
        
        // Multiple layers of dark clouds using pre-allocated arrays
        for (layerIndex in stormCloudLayers.indices) {
            val layer = stormCloudLayers[layerIndex]
            val baseY = layer[0]
            val sizeMultiplier = layer[1]
            stormCloudPaint.color = stormCloudColors[layerIndex]
            
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
     * Uses cached gradients to avoid GC pressure - only recreated when light level changes.
     */
    private fun renderFog(canvas: Canvas, state: SimulationState) {
        // Base fog opacity depends on intensity (30-70%)
        val baseAlpha = (60 + intensity * 120).toInt().coerceIn(50, 170)
        
        // Adjust fog color based on light level
        // Day: bright gray (210, 215, 225)
        // Night: dark blue-gray (40, 45, 60)
        val lightLevel = state.getLightLevel()
        
        // Only recreate gradients when light level changes significantly (>5%)
        // This avoids LinearGradient allocation every frame
        val needsGradientUpdate = cachedFogGradient == null || 
            kotlin.math.abs(lightLevel - cachedFogLightLevel) > 0.05f
        
        if (needsGradientUpdate) {
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
            cachedFogGradient = android.graphics.LinearGradient(
                0f, screenHeight * 0.20f,  // Start: 20% from top (clear)
                0f, screenHeight * 1.2f,   // End: 120% height (below screen edge)
                Color.argb(0, fogR, fogG, fogB),  // Transparent at top
                Color.argb(baseAlpha, fogR, fogG, fogB),  // Dense below screen
                android.graphics.Shader.TileMode.CLAMP
            )
            
            // Add a second layer for depth - mid-height concentration
            val midAlpha = (baseAlpha * 0.5f).toInt()
            cachedFogMidGradient = android.graphics.LinearGradient(
                0f, screenHeight * 0.3f,
                0f, screenHeight * 1.1f,  // End below screen
                Color.argb(0, fogR, fogG, fogB),
                Color.argb(midAlpha, fogR + 5, fogG + 5, fogB + 5),
                android.graphics.Shader.TileMode.CLAMP
            )
            
            cachedFogLightLevel = lightLevel
        }
        
        // Draw main gradient
        fogPaint.shader = cachedFogGradient
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
        
        // Draw mid-depth layer
        fogPaint.shader = cachedFogMidGradient
        canvas.drawRect(0f, screenHeight * 0.3f, screenWidth.toFloat(), screenHeight.toFloat(), fogPaint)
        fogPaint.shader = null
    }

    override fun resize(w: Float, h: Float) {
        screenWidth = w.toInt()
        screenHeight = h.toInt()
        initialized = false
        configureRainLinePaints()
        configureSnowPointPaints()
        // Invalidate cached fog gradients on resize
        cachedFogGradient = null
        cachedFogMidGradient = null
        cachedFogLightLevel = -1f
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Cache state values for particle updates
        cachedWindStrength = state.windStrength
        
        // Update effect based on state
        val effect = weatherToEffect(state.weather)
        setEffect(effect)
        
        if (currentEffect == EffectType.NONE || screenWidth <= 0) return

        if (!initialized) {
            initParticles()
            initialized = true
        }
        
        val deltaSec = deltaTimeMs / 1000f
        
        when (currentEffect) {
            EffectType.RAIN -> updateRainParticles(deltaSec)
            EffectType.SNOW -> updateSnowParticles(deltaSec)
            EffectType.HAIL -> updateHailParticles(deltaSec)
            EffectType.LIGHTNING -> {
                updateRainParticles(deltaSec)  // Lightning includes rain
                updateLightningTimer(deltaTimeMs)
            }
            EffectType.FOG -> {}  // Fog has no particles
            EffectType.NONE -> {}
        }
    }
    
    private fun updateRainParticles(deltaSec: Float) {
        val activeCount = (rainParticles.size * (0.3f + intensity * 0.7f)).toInt()
        val windDrift = cachedWindStrength * 3f
        val speedMult = 0.7f + intensity * 0.6f
        // Scale to ~60fps equivalent (base speed assumed at 60fps)
        val frameScale = deltaSec * 60f
        var farCoordIndex = 0
        var mediumCoordIndex = 0
        var nearCoordIndex = 0
        
        for (i in 0 until activeCount) {
            val p = rainParticles[i]
            p.y += p.speed * speedMult * frameScale
            p.x -= (1f + windDrift) * speedMult * frameScale

            if (p.y > screenHeight) {
                recycleRainParticle(p)
            }

            when (getRainDepthBucket(p.layer)) {
                RAIN_DEPTH_FAR -> {
                    rainCoordsFar[farCoordIndex] = p.x
                    rainCoordsFar[farCoordIndex + 1] = p.y
                    rainCoordsFar[farCoordIndex + 2] = p.x - (1.3f + windDrift * 0.55f)
                    rainCoordsFar[farCoordIndex + 3] = p.y + p.size * 0.72f
                    farCoordIndex += 4
                }

                RAIN_DEPTH_MEDIUM -> {
                    rainCoordsMedium[mediumCoordIndex] = p.x
                    rainCoordsMedium[mediumCoordIndex + 1] = p.y
                    rainCoordsMedium[mediumCoordIndex + 2] = p.x - (1.8f + windDrift * 0.8f)
                    rainCoordsMedium[mediumCoordIndex + 3] = p.y + p.size * 0.95f
                    mediumCoordIndex += 4
                }

                else -> {
                    rainCoordsNear[nearCoordIndex] = p.x
                    rainCoordsNear[nearCoordIndex + 1] = p.y
                    rainCoordsNear[nearCoordIndex + 2] = p.x - (2.4f + windDrift)
                    rainCoordsNear[nearCoordIndex + 3] = p.y + p.size * 1.15f
                    nearCoordIndex += 4
                }
            }
        }

        rainFarCoordCount = farCoordIndex
        rainMediumCoordCount = mediumCoordIndex
        rainNearCoordCount = nearCoordIndex
    }
    
    private fun updateSnowParticles(deltaSec: Float) {
        val activeCount = (snowParticles.size * (0.2f + intensity * 0.8f)).toInt()
        val windDrift = cachedWindStrength * 2f
        val speedMult = 0.6f + intensity * 0.8f
        val frameScale = deltaSec * 60f

        var smallIndex = 0
        var mediumIndex = 0
        var largeIndex = 0
        
        for (i in 0 until activeCount) {
            val p = snowParticles[i]
            val bucket = getSnowDepthBucket(p.layer)
            val fallSpeedMultiplier: Float
            val swayMultiplier: Float
            val windMultiplier: Float
            val swayPhase: Float

            when (bucket) {
                SNOW_DEPTH_SMALL -> {
                    // Background flakes: slow and subtle movement.
                    fallSpeedMultiplier = 0.62f
                    swayMultiplier = 0.55f
                    windMultiplier = 0.35f
                    swayPhase = 0.015f
                }

                SNOW_DEPTH_MEDIUM -> {
                    fallSpeedMultiplier = 1.0f
                    swayMultiplier = 1.0f
                    windMultiplier = 0.55f
                    swayPhase = 0.02f
                }

                else -> {
                    // Foreground flakes: faster fall and wider sway for strong parallax.
                    fallSpeedMultiplier = 1.55f
                    swayMultiplier = 1.85f
                    windMultiplier = 0.85f
                    swayPhase = 0.028f
                }
            }

            p.y += p.speed * speedMult * fallSpeedMultiplier * frameScale
            p.x += kotlin.math.sin((p.y * swayPhase).toDouble()).toFloat() * (0.8f * swayMultiplier) * frameScale +
                windDrift * windMultiplier * frameScale

            if (p.y > screenHeight) {
                recycleSnowParticle(p)
            }

            when (bucket) {
                SNOW_DEPTH_SMALL -> {
                    snowCoordsSmall[smallIndex] = p.x
                    snowCoordsSmall[smallIndex + 1] = p.y
                    smallIndex += 2
                }
                SNOW_DEPTH_MEDIUM -> {
                    snowCoordsMedium[mediumIndex] = p.x
                    snowCoordsMedium[mediumIndex + 1] = p.y
                    mediumIndex += 2
                }
                else -> {
                    snowCoordsLarge[largeIndex] = p.x
                    snowCoordsLarge[largeIndex + 1] = p.y
                    largeIndex += 2
                }
            }
        }

        snowSmallCoordCount = smallIndex
        snowMediumCoordCount = mediumIndex
        snowLargeCoordCount = largeIndex
    }
    
    private fun updateHailParticles(deltaSec: Float) {
        val activeCount = (hailParticles.size * (0.25f + intensity * 0.75f)).toInt()
        val windDrift = cachedWindStrength * 1.5f
        val speedMult = 0.8f + intensity * 0.4f
        val frameScale = deltaSec * 60f
        
        for (i in 0 until activeCount) {
            val p = hailParticles[i]
            p.y += p.speed * speedMult * frameScale
            p.x += kotlin.math.sin((p.y * 0.05f).toDouble()).toFloat() * 0.5f * frameScale + windDrift * 0.3f * frameScale
            if (p.y > screenHeight) recycleHailParticle(p)
        }
    }
    
    private fun updateLightningTimer(deltaTimeMs: Long) {
        // Initialize next flash time if needed
        if (lightningNextFlashMs <= 0L) {
            lightningNextFlashMs = RenderConstants.LIGHTNING_INTERVAL_MIN_MS + 
                Random.nextLong(RenderConstants.LIGHTNING_INTERVAL_RANGE_MS)
        }
        
        lightningTimerMs += deltaTimeMs
        
        // Check if it's time for a flash
        if (!lightningFlash && lightningTimerMs >= lightningNextFlashMs) {
            lightningFlash = true
            lightningFlashRemainingMs = RenderConstants.LIGHTNING_FLASH_DURATION_MS
            lightningTimerMs = 0L
            lightningNextFlashMs = RenderConstants.LIGHTNING_INTERVAL_MIN_MS + 
                Random.nextLong(RenderConstants.LIGHTNING_INTERVAL_RANGE_MS)
            lightningX = screenWidth * 0.15f + Random.nextFloat() * screenWidth * 0.7f
            generateLightningBolt()
        }
        
        // Update flash duration
        if (lightningFlash) {
            lightningFlashRemainingMs -= deltaTimeMs
            if (lightningFlashRemainingMs <= 0L) {
                lightningFlash = false
            }
        }
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        render(canvas, state)
    }

    override fun reloadSettings() {
        // No-op - settings are set via setLayerSettings()
    }
    
    /**
     * Configure weather layer depth settings.
     * @param layers Number of depth layers (1-5)
     * @param separation Size/speed difference between layers (0.1-0.5)
     * @param depth Parallax depth offset per layer (0.1-0.4)
     */
    fun setLayerSettings(layers: Int, separation: Float, depth: Float) {
        weatherLayers = layers.coerceIn(1, 5)
        layerSeparation = separation.coerceIn(0.1f, 0.5f)
        layerDepthOffset = depth.coerceIn(0.1f, 0.4f)
        // Reinitialize particles with new layer assignments
        initialized = false
    }

    override fun destroy() {
        // No-op
    }
}
