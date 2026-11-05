package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.ColorMath
import kotlin.random.Random

/**
 * Renders mountain silhouettes in the background.
 * 
 * Uses SimulationState for:
 * - isNight (affects mountain color tint)
 * - No time-based animation (mountains are static)
 * 
 * Uses RenderConstants for:
 * - Reference aspect ratio
 * - Parallax depth range
 * - Mountain color values
 * 
 * Design notes:
 * - Mountains should have gradient shading (atmospheric perspective)
 * - Lighter/bluer at base simulates fog/mist
 * - Add atmospheric haze overlay at bottom of mountains
 */
class MountainLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.MOUNTAINS
    private val paint = Paint().apply { 
        style = Paint.Style.FILL 
        isAntiAlias = true 
    }
    
    private val hazePaint = Paint().apply {
        isAntiAlias = true
    }
    
    private val snowCapPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.WHITE
    }
    
    private val pathLeft = Path()
    private val pathRight = Path()
    private val snowCapPath = Path()
    private val snowShadePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private var cachedSnowShadeGradients = HashMap<Int, LinearGradient>()
    
    private var width = 0f
    private var height = 0f
    private var lastGeneratedWidth = 0f
    private var lastGeneratedHeight = 0f
    /** Cached world-space half-width used for generation; avoids regeneration when viewport still fits. */
    private var generatedWorldHalf = 0f
    
    // Cached haze gradient to avoid per-frame allocation
    private var cachedHazeAmbient = 0
    private var cachedHazeHeight = 0f
    
    // Cached mountain gradient key to avoid per-frame gradient allocation
    // Key: quantized ambient color + quantized light angle → only rebuild when visibly different
    private var cachedMtnAmbient = 0
    private var cachedMtnLightAngleBucket = -1
    private var cachedMtnShadowEnabled = false
    private var cachedLeftGradients = HashMap<Int, LinearGradient>()
    private var cachedRightGradients = HashMap<Int, LinearGradient>()
    private var cachedSnowGradients = HashMap<Int, LinearGradient>()
    
    /**
     * Pre-computed mountain shape: ridge + slope points cached at generation time.
     * Avoids per-frame Random + ArrayList + Pair allocations.
     *
     * Slope arrays are split into **progress** (monotonic 0→1 from peak to base) and
     * **noise** (small random displacement). At draw time, progress is scaled by
     * `mountainH` and noise by `noiseScale`.
     */
    private class MountainShape(
        val ridgeXs: FloatArray,
        val ridgeYs: FloatArray,
        val leftSlopeXs: FloatArray,
        val leftSlopeYs: FloatArray,       // progress only (0→1)
        val leftSlopeNoiseYs: FloatArray,  // noise only (small random displacement)
        val rightSlopeXs: FloatArray,
        val rightSlopeYs: FloatArray,      // progress only
        val rightSlopeNoiseYs: FloatArray, // noise only
        val peakAngleOffset: Float
    )

    private data class MountainData(
        val worldX: Float,     // World-space x coordinate (0 = center)
        val height: Float,     // 0.0 to 1.0 (relative to screen height)
        val width: Float,      // Width multiplier
        val dayColor: Int,     // Color during day
        val depthFactor: Float, // Parallax depth
        val shapeSeed: Long,
        val shape: MountainShape  // Pre-computed shape geometry
    )
    
    // Thread-safe mountain list using volatile reference + immutable list
    // Settings thread creates new list, render thread reads current snapshot
    @Volatile private var mountains: List<MountainData> = emptyList()

    // Cached transform: rebuilt on resize, reused every frame
    private var cachedTransform = WorldViewportTransform(1f, 1f)
    
    // Settings
    private var mountainDensity = 50       // 0-100 percentage of mountain coverage
    private var mountainHeightScale = 1.0f
    private var mountainWidthScale = 1.0f
    private var mountainRoughness = 0.5f
    private var ridgeRoughness = 0.5f
    private var mountainBottomOffset = 0f  // Shift mountains up (0-1 of screen height)
    private var randomSeed = PreferenceDefaults.RANDOM_SEED

    private data class GenerationKey(
        val seed: Int,
        val density: Int,
        val heightScale: Float,
        val widthScale: Float,
        val roughness: Float,
        val ridgeRoughness: Float
    )
    /** Settings used for the last generateMountains() from reloadSettings; null forces the first run. */
    private var lastGenerationKey: GenerationKey? = null
    
    // Seasonal settings (read from terrain settings)
    private var winterSnowEnabled = true
    private var winterSnowCoverage = PreferenceDefaults.WINTER_SNOW_COVERAGE   // 0-100 how much of mountain tops are covered
    private var useWeatherSnow = PreferenceDefaults.WEATHER_SNOW_ENABLED

    // Frame-constant values resolved once per draw() instead of per mountain
    private var frameSnowDepth = 0f  // Fraction of the slope under snow, 0 = no cap
    private var frameDullness = 0f
    private var frameNight = 0f
    
    init {
        reloadSettings(SimulationSettings.fromSnapshot(emptyMap<String, Any?>()))
    }

    /**
     * Peaks keep a seasonal cap in winter; real snow on the ground means the mountains
     * are snowed in further down.
     */
    private fun computeSnowDepth(state: SimulationState): Float {
        val winter = VegetationUtil.getSeasonWeight(
            "winter", state.currentSeason, state.previousSeason, state.seasonTransitionProgress
        )
        val seasonal = if (winterSnowEnabled && winter > 0.01f) {
            (winterSnowCoverage / 100f).coerceIn(0.15f, 0.5f) * winter
        } else {
            0f
        }
        val weather = if (useWeatherSnow && state.snowLevel > 0.005f) 0.2f + 0.45f * state.snowLevel.coerceAtMost(1f) else 0f
        return maxOf(seasonal, weather)
    }

    private fun ambientColorBucket(color: Int): Int = ColorMath.ambientColorBucket(color)

    private fun quantizeAmbientColor(color: Int): Int = ColorMath.quantizeColor(color)
    
    companion object {
        /**
         * World-space cell size for mountain placement.
         * Small enough that the full density slider range (0-100) remains useful.
         */
        const val MOUNTAIN_CELL_SIZE = 110f

        /** Golden ratio for low-discrepancy depth scatter. */
        const val PHI = 0.618033988749895f

        /** Cool shade of snow in the lee of the light. */
        private const val SNOW_SHADE = 0xFF5A6A8C.toInt()
    }

    /**
     * Pre-compute normalised shape geometry for a mountain.
     *
     * All coordinates are in **normalised** form:
     * - X values are fractions of half-width (−1..+1)
     * - Y values are fractions of mountain height (0 = peak, 1 = base)
     *
     * The actual screen positions are derived at draw time using the
     * mountain's peakCenterX, peakY, baseY and halfWidthPx.
     */
    private fun precomputeShape(
        shapeSeed: Long,
        roughness: Float,
        ridgeRough: Float
    ): MountainShape {
        val random = Random(shapeSeed)
        val segments = (10 + roughness * 20).toInt()
        val ridgeSegments = (5 + ridgeRough * 10).toInt().coerceAtLeast(2)

        // Peak angle offset as fraction of halfWidth
        val peakAngleOffset = if (ridgeRough > 0)
            (random.nextFloat() - 0.5f) * 0.3f * ridgeRough
        else 0f

        // Ridge points (normalised)
        val ridgeXs = FloatArray(ridgeSegments + 1) // +1 for base point
        val ridgeYs = FloatArray(ridgeSegments + 1)
        ridgeXs[0] = peakAngleOffset
        ridgeYs[0] = 0f // peak
        for (i in 1 until ridgeSegments) {
            val progress = i.toFloat() / ridgeSegments
            val ridgeNoise = (random.nextFloat() - 0.5f) * ridgeRough * 0.02f * (1f - progress * 0.7f)
            ridgeXs[i] = peakAngleOffset * (1f - progress) + ridgeNoise
            ridgeYs[i] = progress
        }
        ridgeXs[ridgeSegments] = 0f
        ridgeYs[ridgeSegments] = 1f // base

        // Left slope points (normalised: x as fraction of -halfWidth)
        // Progress and noise are stored SEPARATELY so that at draw time
        // progress scales by mountainH and noise scales by noiseScale.
        val leftSlopeXs = FloatArray(segments)
        val leftSlopeYs = FloatArray(segments)
        val leftSlopeNoiseYs = FloatArray(segments)
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            leftSlopeXs[i] = -(progress) // fraction of halfWidth, left side
            leftSlopeYs[i] = progress     // monotonic slope from peak to base
            leftSlopeNoiseYs[i] = (random.nextFloat() - 0.5f) * roughness // noise
        }

        // Right slope points
        val rightSlopeXs = FloatArray(segments)
        val rightSlopeYs = FloatArray(segments)
        val rightSlopeNoiseYs = FloatArray(segments)
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            rightSlopeXs[i] = progress
            rightSlopeYs[i] = progress
            rightSlopeNoiseYs[i] = (random.nextFloat() - 0.5f) * roughness
        }

        return MountainShape(
            ridgeXs, ridgeYs,
            leftSlopeXs, leftSlopeYs, leftSlopeNoiseYs,
            rightSlopeXs, rightSlopeYs, rightSlopeNoiseYs,
            peakAngleOffset
        )
    }

    private fun generateMountains() {
        // Use cached generatedWorldHalf which includes preload margin
        val visibleHalf = generatedWorldHalf.takeIf { it > 0f }
            ?: RenderConstants.getVisibleWorldHalf(width, height)

        // Rebuild cached transform for current dimensions
        cachedTransform = WorldViewportTransform(width, height)

        // Determine index range: mountains are placed at stable world cells
        val indexRange = WorldViewportTransform.indexRangeForWorldRange(
            -visibleHalf, visibleHalf, MOUNTAIN_CELL_SIZE
        )

        // Density scaling: skip indices to respect mountainDensity setting
        // At 100% density, use all indices. At lower density, skip proportionally.
        val densityFraction = (mountainDensity / 100f).coerceIn(0f, 1f)

        // Build new list (thread-safe: atomic swap at end)
        val newMountains = ArrayList<MountainData>()

        for (index in indexRange) {
            val itemRandom = DeterministicLayout.random(randomSeed, section = 100, index = index)

            // Density gating: deterministic skip based on density setting
            if (itemRandom.nextFloat() > densityFraction) continue

            // World-space position: cell center + jitter
            val worldX = index * MOUNTAIN_CELL_SIZE +
                (itemRandom.nextFloat() - 0.5f) * MOUNTAIN_CELL_SIZE * 0.6f

            // Depth: golden-ratio scatter ensures even distribution across z-layers
            // regardless of which cells survive density gating. Avoids the problem
            // where low density randomly clusters all mountains at the same depth.
            val baseDepth = ((index.toFloat() * PHI) % 1f).let { if (it < 0f) it + 1f else it }
            val depthJitter = (itemRandom.nextFloat() - 0.5f) * 0.15f
            val depth = (baseDepth + depthJitter).coerceIn(0f, 1f)

            // Parallax factor based on depth using centralized constants
            val depthFactor = RenderConstants.PARALLAX_MOUNTAIN_MIN +
                depth * (RenderConstants.PARALLAX_MOUNTAIN_MAX - RenderConstants.PARALLAX_MOUNTAIN_MIN)

            // Height: varies by scale setting
            val h = (RenderConstants.MOUNTAIN_HEIGHT_MIN + itemRandom.nextFloat() * RenderConstants.MOUNTAIN_HEIGHT_RANGE) * mountainHeightScale

            // Width: Random variation
            val w = (RenderConstants.MOUNTAIN_WIDTH_MIN + itemRandom.nextFloat() * RenderConstants.MOUNTAIN_WIDTH_RANGE) * mountainWidthScale

            // Color: lighter for far mountains (depth 0 = far, 1 = near)
            val baseGray = RenderConstants.MOUNTAIN_GRAY_FAR +
                (depth * (RenderConstants.MOUNTAIN_GRAY_NEAR - RenderConstants.MOUNTAIN_GRAY_FAR)).toInt()
            val dayColor = Color.rgb(baseGray, baseGray + 5, baseGray + 15)

            val shapeSeed = DeterministicLayout.mix(randomSeed, section = 101, index = index)
            val shape = precomputeShape(shapeSeed, mountainRoughness, ridgeRoughness)
            newMountains.add(MountainData(worldX, h, w, dayColor, depthFactor, shapeSeed, shape))
        }

        // Sort: Far mountains first (drawn behind)
        newMountains.sortBy { it.depthFactor }

        // Atomic reference swap (volatile ensures visibility to render thread)
        mountains = newMountains
    }

    override fun resize(w: Float, h: Float) {
        val oldW = width
        val oldH = height
        val sizeChanged = oldW != w || oldH != h
        width = w
        height = h

        if (!sizeChanged) return

        // Rebuild cached transform for current dimensions
        cachedTransform = WorldViewportTransform(w, h)

        // Invalidate cached gradients that embed old height coordinates
        cachedLeftGradients.clear()
        cachedRightGradients.clear()
        cachedSnowGradients.clear()
        cachedSnowShadeGradients.clear()
        cachedMtnAmbient = 0
        cachedMtnLightAngleBucket = -1
        cachedHazeAmbient = 0
        cachedHazeHeight = 0f

        val firstGenerate = lastGeneratedWidth <= 0f || lastGeneratedHeight <= 0f
        // Regenerate only when generated coverage can no longer contain the visible
        // world range.  Preload margin prevents per-frame thrash from tiny changes.
        val requiredWorldHalf = RenderConstants.getVisibleWorldHalf(w, h)
        val needsWiderCoverage = requiredWorldHalf > generatedWorldHalf
        if (firstGenerate || needsWiderCoverage) {
            generatedWorldHalf = requiredWorldHalf * 1.25f
            generateMountains()
            lastGeneratedWidth = w
            lastGeneratedHeight = h
        }
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Mountains don't animate
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Take local snapshot for thread-safe iteration.
        // This is NOT a filtered list — it's a direct reference to the volatile
        // mountains field, so withIndex() gives stable original indices suitable
        // for gradient cache keys.
        val currentMountains = mountains

        frameSnowDepth = computeSnowDepth(state)
        frameDullness = state.weatherDullness()
        frameNight = RenderConstants.nightFactor(state.getLightLevel())
        
        // Get ambient color for atmospheric blending
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        val ambientBucket = ambientColorBucket(ambientColor) or
            ((frameDullness * 20f).toInt() shl 16) or ((frameSnowDepth * 40f).toInt() shl 21) or
            ((frameNight * 10f).toInt() shl 27)
        val bucketedAmbientColor = quantizeAmbientColor(ambientColor)
        
        // Invalidate cached gradients when lighting changes significantly
        // Quantize light angle to ~5° buckets to avoid rebuilding every frame
        val lightAngleBucket = (state.getLightSourceAngle() * 10f).toInt()
        val shadowEnabled = state.mountainShadowsEnabled
        if (ambientBucket != cachedMtnAmbient || lightAngleBucket != cachedMtnLightAngleBucket || shadowEnabled != cachedMtnShadowEnabled) {
            cachedMtnAmbient = ambientBucket
            cachedMtnLightAngleBucket = lightAngleBucket
            cachedMtnShadowEnabled = shadowEnabled
            cachedLeftGradients.clear()
            cachedRightGradients.clear()
            cachedSnowGradients.clear()
            cachedSnowShadeGradients.clear()
        }
        
        // Build transform for world-to-screen projection with height-based zoom
        val transform = cachedTransform

        for (mountainIdx in currentMountains.indices) {
            val mountain = currentMountains[mountainIdx]
            val peakCenterX = transform.worldToScreenX(mountain.worldX)
            val halfWidthPx = height * mountain.width * 0.25f
            if (!ViewportCulling.isVisibleHorizontally(
                    x = peakCenterX,
                    objectHalfWidth = halfWidthPx,
                    screenWidth = width,
                    height = height,
                    extraMargin = width * 0.05f
                )) {
                continue
            }

            val px = parallaxCalculator.getOffsetXForDepth(mountain.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(mountain.depthFactor)
            
            canvas.withTranslation(px, py) {
                drawMountain(canvas, mountainIdx, mountain, state, bucketedAmbientColor, transform)
            }
        }
        
        // Draw atmospheric haze at the base of mountains (fog overlay)
        // This blends the mountains into the terrain and adds depth
        drawAtmosphericHaze(canvas, bucketedAmbientColor)
    }
    
    /**
     * Draw a soft atmospheric haze at the bottom portion of the screen.
     * This simulates fog/mist at the base of mountains.
     */
    private fun drawAtmosphericHaze(canvas: Canvas, ambientColor: Int) {
        val hazeStartY = height * RenderConstants.HAZE_START_Y
        val hazeEndY = height * RenderConstants.HAZE_END_Y
        val hazeBucket = ambientColorBucket(ambientColor)
        
        // Only recreate gradient if ambient color or height changed (avoids per-frame allocation)
        if (hazeBucket != cachedHazeAmbient || height != cachedHazeHeight) {
            cachedHazeAmbient = hazeBucket
            cachedHazeHeight = height
            
            // Haze color blends with ambient for consistent atmosphere
            val hazeColor = Color.argb(
                RenderConstants.HAZE_ALPHA,
                Color.red(ambientColor),
                Color.green(ambientColor),
                Color.blue(ambientColor)
            )
            
            hazePaint.shader = LinearGradient(
                0f, hazeStartY,
                0f, hazeEndY,
                Color.TRANSPARENT,
                hazeColor,
                Shader.TileMode.CLAMP
            )
        }
        
        canvas.drawRect(0f, hazeStartY, width, hazeEndY, hazePaint)
    }
    
    private fun drawMountain(canvas: Canvas, mountainIdx: Int, mountain: MountainData, state: SimulationState, ambientColor: Int, transform: WorldViewportTransform) {
        pathLeft.reset()
        pathRight.reset()
        
        // Convert world-space coordinate to screen pixels via canonical transform
        val peakCenterX = transform.worldToScreenX(mountain.worldX)
        val offsetPx = mountainBottomOffset * height
        val peakY = height * (1f - mountain.height) - offsetPx
        val baseY = height - offsetPx
        val halfWidthPx = height * mountain.width * 0.25f
        val mountainH = baseY - peakY  // total mountain height in pixels
        val noiseScale = height * 0.015f  // scale normalised noise to pixels
        
        // Use pre-computed normalised shape
        val shape = mountain.shape
        val segments = shape.leftSlopeXs.size

        // Denormalise ridge points to screen coordinates
        val ridgeCount = shape.ridgeXs.size
        val actualPeakX = peakCenterX + shape.peakAngleOffset * halfWidthPx
        
        // Build left path from pre-computed shape
        val overlapPx = 1f
        pathLeft.moveTo(actualPeakX + overlapPx, peakY + shape.ridgeYs[0] * mountainH)
        
        // Left slope: progress * mountainH for base shape, noise * noiseScale for roughness
        for (i in 0 until segments) {
            val sx = actualPeakX + shape.leftSlopeXs[i] * halfWidthPx
            val sy = peakY + shape.leftSlopeYs[i] * mountainH + shape.leftSlopeNoiseYs[i] * noiseScale
            pathLeft.lineTo(sx, sy)
        }
        pathLeft.lineTo(actualPeakX - halfWidthPx, baseY)
        pathLeft.lineTo(actualPeakX - halfWidthPx, height)
        pathLeft.lineTo(peakCenterX + overlapPx, height)
        pathLeft.lineTo(peakCenterX + overlapPx, baseY)
        
        // Close back along ridge (in reverse)
        for (i in ridgeCount - 1 downTo 0) {
            val rx = peakCenterX + shape.ridgeXs[i] * halfWidthPx + overlapPx
            val ry = peakY + shape.ridgeYs[i] * mountainH
            pathLeft.lineTo(rx, ry)
        }
        pathLeft.close()
        
        // Build right path
        pathRight.moveTo(actualPeakX, peakY + shape.ridgeYs[0] * mountainH)
        for (i in 0 until segments) {
            val sx = actualPeakX + shape.rightSlopeXs[i] * halfWidthPx
            val sy = peakY + shape.rightSlopeYs[i] * mountainH + shape.rightSlopeNoiseYs[i] * noiseScale
            pathRight.lineTo(sx, sy)
        }
        pathRight.lineTo(actualPeakX + halfWidthPx, baseY)
        pathRight.lineTo(actualPeakX + halfWidthPx, height)
        pathRight.lineTo(peakCenterX, height)
        pathRight.lineTo(peakCenterX, baseY)
        for (i in ridgeCount - 1 downTo 0) {
            val rx = peakCenterX + shape.ridgeXs[i] * halfWidthPx
            val ry = peakY + shape.ridgeYs[i] * mountainH
            pathRight.lineTo(rx, ry)
        }
        pathRight.close()
        
        // Apply ambient tinting (global tint system)
        // Mountains blend with ambient color based on their depth
        val distance = 1f - (mountain.depthFactor - RenderConstants.PARALLAX_MOUNTAIN_MIN) /
            (RenderConstants.PARALLAX_MOUNTAIN_MAX - RenderConstants.PARALLAX_MOUNTAIN_MIN)
        val blendAmount = RenderConstants.depthHaze(distance.coerceIn(0f, 1f))
        val baseColor = RenderConstants.sceneLight(
            mountain.dayColor, ambientColor, blendAmount, frameDullness, frameNight
        )
        
        // Create atmospheric gradient: lighter/bluer at base (fog effect)
        // Peaks are darker/more distinct, base fades into atmospheric haze
        // Bottom 25% fades to transparent so parallax doesn't reveal hard edges
        val peakColor = baseColor
        // Base blends even more with ambient (atmospheric perspective)
        val baseAtmoColor = RenderConstants.blendWithAmbient(baseColor, ambientColor, blendAmount + 0.2f)
        
        // === REALISTIC CONE-STYLE SHADOW SYSTEM ===
        // Sun shines from BEHIND the mountains. When sun is low (morning/evening),
        // both viewer-facing slopes are partially shadowed. At noon, minimal shadow.
        //
        // Light angle: 0 = sunrise (right), π/2 = noon (overhead), π = sunset (left)
        val shadowStrength = if (state.mountainShadowsEnabled) state.getEffectiveShadowStrength() else 0f

        // Sun elevation (0 = horizon, 1 = overhead) from the shared scene lighting model.
        val sunElevation = state.lightElevation01
        
        // Base shadow on viewer-facing slopes (stronger when sun is low behind mountains)
        // At noon (elevation=1): minimal shadow. At sunrise/sunset (elevation=0): max shadow
        val frontShadowBase = shadowStrength * (1f - sunElevation) * 0.35f
        
        // Left/right asymmetry based on sun position
        // Light from left (sunrise) means left side catches more light, right is darker
        val lightBias = -state.lightDirX  // +1 at sunrise (light left), -1 at sunset

        // Cast-shadow term: the ridge blocks the sun, so the whole flank turned away from it
        // sits in shade. Scales with how far off-centre the light is, independent of the
        // elevation-driven base shading, so mountains keep a lit and a shaded face all day.
        val ridgeShade = shadowStrength * 0.45f
        val leftRidgeShade = (-lightBias).coerceAtLeast(0f) * ridgeShade
        val rightRidgeShade = lightBias.coerceAtLeast(0f) * ridgeShade

        // Shadow intensity for each side (base shadow + directional component)
        // Both sides always have some shadow when sun is low, but one side is darker
        val leftShadow = (frontShadowBase + leftRidgeShade).coerceIn(0f, 0.55f)
        val rightShadow = (frontShadowBase + rightRidgeShade).coerceIn(0f, 0.55f)
        
        // Cone-style: shadow is STRONGER at base (where mountain blocks more light)
        // and WEAKER at peak (which catches more direct sunlight)
        // This creates a natural "lit peak, shadowed base" effect
        
        // Left side colors - shadow is stronger at base
        val leftPeakColor = Color.rgb(
            (Color.red(peakColor) * (1f - leftShadow * 0.3f)).toInt().coerceIn(0, 255),
            (Color.green(peakColor) * (1f - leftShadow * 0.35f)).toInt().coerceIn(0, 255),
            (Color.blue(peakColor) * (1f - leftShadow * 0.1f)).toInt().coerceIn(0, 255)
        )
        val leftMidColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - leftShadow * 0.6f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - leftShadow * 0.65f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - leftShadow * 0.3f)).toInt().coerceIn(0, 255)
        )
        val leftBaseColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - leftShadow * 0.8f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - leftShadow * 0.85f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - leftShadow * 0.5f)).toInt().coerceIn(0, 255)
        )
        
        // Four-point vertical gradient for left side: peak (light) -> mid -> base (darker)
        val gradient = cachedLeftGradients.getOrPut(mountainIdx) {
            LinearGradient(
                actualPeakX, peakY,
                actualPeakX, Math.max(baseY, height),
                intArrayOf(leftPeakColor, leftMidColor, leftBaseColor, leftBaseColor),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        
        // Draw left side with gradient
        paint.shader = gradient
        canvas.drawPath(pathLeft, paint)
        
        // Right side colors - shadow is stronger at base  
        val rightPeakColor = Color.rgb(
            (Color.red(peakColor) * (1f - rightShadow * 0.3f)).toInt().coerceIn(0, 255),
            (Color.green(peakColor) * (1f - rightShadow * 0.35f)).toInt().coerceIn(0, 255),
            (Color.blue(peakColor) * (1f - rightShadow * 0.1f)).toInt().coerceIn(0, 255)
        )
        val rightMidColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - rightShadow * 0.6f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - rightShadow * 0.65f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - rightShadow * 0.3f)).toInt().coerceIn(0, 255)
        )
        val rightBaseColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - rightShadow * 0.8f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - rightShadow * 0.85f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - rightShadow * 0.5f)).toInt().coerceIn(0, 255)
        )
        
        val rightGradient = cachedRightGradients.getOrPut(mountainIdx) {
            LinearGradient(
                actualPeakX, peakY,
                actualPeakX, Math.max(baseY, height),
                intArrayOf(rightPeakColor, rightMidColor, rightBaseColor, rightBaseColor),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = rightGradient
        canvas.drawPath(pathRight, paint)
        
        // Reset shader
        paint.shader = null
        
        // Draw snow cap (seasonal and/or weather-driven)
        if (frameSnowDepth > 0.01f) {
            val snowDepth = frameSnowDepth
            val snowSegmentCount = (segments * snowDepth).toInt().coerceIn(1, segments)
            
            snowCapPath.reset()
            val peakInset = 2f
            snowCapPath.moveTo(
                peakCenterX + shape.ridgeXs[0] * halfWidthPx,
                peakY + shape.ridgeYs[0] * mountainH + peakInset
            )
            
            // Left side of snow
            for (i in 0 until snowSegmentCount) {
                val sx = actualPeakX + shape.leftSlopeXs[i] * halfWidthPx
                val sy = peakY + shape.leftSlopeYs[i] * mountainH + shape.leftSlopeNoiseYs[i] * noiseScale
                snowCapPath.lineTo(sx, sy)
            }
            
            // Bottom edge - jagged snow line
            val snowRandom = Random(mountain.shapeSeed + 2000)
            val lastLeftX = actualPeakX + shape.leftSlopeXs[snowSegmentCount - 1] * halfWidthPx
            val lastLeftY = peakY + shape.leftSlopeYs[snowSegmentCount - 1] * mountainH + shape.leftSlopeNoiseYs[snowSegmentCount - 1] * noiseScale
            val lastRightX = actualPeakX + shape.rightSlopeXs[snowSegmentCount - 1] * halfWidthPx
            val lastRightY = peakY + shape.rightSlopeYs[snowSegmentCount - 1] * mountainH + shape.rightSlopeNoiseYs[snowSegmentCount - 1] * noiseScale
            
            val jaggedSteps = 5
            for (j in 0..jaggedSteps) {
                val t = j.toFloat() / jaggedSteps
                val x = lastLeftX + (lastRightX - lastLeftX) * t
                val snowBaseY = lastLeftY + (lastRightY - lastLeftY) * t
                val jag = snowRandom.nextFloat() * height * 0.015f
                snowCapPath.lineTo(x, snowBaseY + jag)
            }
            
            // Right side of snow in reverse
            for (i in snowSegmentCount - 1 downTo 0) {
                val sx = actualPeakX + shape.rightSlopeXs[i] * halfWidthPx
                val sy = peakY + shape.rightSlopeYs[i] * mountainH + shape.rightSlopeNoiseYs[i] * noiseScale
                snowCapPath.lineTo(sx, sy)
            }
            
            snowCapPath.close()
            
            // Gradient: pure white at peak, fading to semi-transparent white at bottom
            // This blends naturally with the mountain color underneath
            val snowTopY = peakY + shape.ridgeYs[0] * mountainH
            val snowBottomY = lastLeftY + height * 0.01f
            val snowGradient = cachedSnowGradients.getOrPut(mountainIdx) {
                // Same distance haze and weather light as the rock, so snow isn't glowing at night
                val snow = RenderConstants.sceneLight(
                    Color.WHITE, ambientColor, blendAmount, frameDullness, frameNight
                ) and 0x00FFFFFF
                LinearGradient(
                    actualPeakX, snowTopY,
                    actualPeakX, snowBottomY,
                    intArrayOf(
                        snow or (255 shl 24),
                        snow or (255 shl 24),
                        snow or (180 shl 24),
                        snow or (40 shl 24)
                    ),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            snowCapPaint.shader = snowGradient
            canvas.drawPath(snowCapPath, snowCapPaint)
            snowCapPaint.shader = null

            // The flank turned away from the light stays shaded under the snow, so peaks keep their form
            val leftSnowShade = leftShadow * 0.6f + 0.18f * (-lightBias * 3f).coerceIn(0f, 1f)
            val rightSnowShade = rightShadow * 0.6f + 0.18f * (lightBias * 3f).coerceIn(0f, 1f)
            if (leftSnowShade > 0.01f || rightSnowShade > 0.01f) {
                snowShadePaint.shader = cachedSnowShadeGradients.getOrPut(mountainIdx) {
                    LinearGradient(
                        actualPeakX, snowTopY, actualPeakX, snowBottomY,
                        intArrayOf(SNOW_SHADE, SNOW_SHADE, SNOW_SHADE and 0xB4FFFFFF.toInt(), SNOW_SHADE and 0x28FFFFFF),
                        floatArrayOf(0f, 0.4f, 0.75f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.withClip(snowCapPath) {
                    snowShadePaint.alpha = (255 * leftSnowShade).toInt().coerceIn(0, 255)
                    canvas.drawPath(pathLeft, snowShadePaint)
                    snowShadePaint.alpha = (255 * rightSnowShade).toInt().coerceIn(0, 255)
                    canvas.drawPath(pathRight, snowShadePaint)
                }
                snowShadePaint.shader = null
            }
        }
    }

    override fun reloadSettings(settings: SimulationSettings) {
        
        // Random seed for consistent placement
        randomSeed = settings.getInt("pref_random_seed", PreferenceDefaults.RANDOM_SEED)
        
        // Mountain density: 0-100 percentage (scales with extended width)
        mountainDensity = settings.getInt("pref_mountain_density", PreferenceDefaults.MOUNTAIN_DENSITY)
        
        // Height: 25-200 -> 0.25 - 2.0
        val heightVal = settings.getInt("pref_mountain_height", PreferenceDefaults.MOUNTAIN_HEIGHT)
        mountainHeightScale = heightVal / 100f
        
        // Width: 50-200 -> 0.5 - 2.0
        val widthVal = settings.getInt("pref_mountain_width", PreferenceDefaults.MOUNTAIN_WIDTH)
        mountainWidthScale = widthVal / 100f
        
        // Roughness: 0-200 -> 0.0 - 2.0
        val roughVal = settings.getInt("pref_mountain_roughness", PreferenceDefaults.MOUNTAIN_ROUGHNESS)
        mountainRoughness = roughVal / 100f
        
        // Ridge roughness: 0-200 -> 0.0 - 2.0
        val ridgeVal = settings.getInt("pref_mountain_ridge_roughness", PreferenceDefaults.MOUNTAIN_RIDGE_ROUGHNESS)
        ridgeRoughness = ridgeVal / 100f
        
        // Bottom offset: 0-50 -> 0.0 - 0.5 (shift mountains up by % of screen height)
        val bottomOffsetVal = settings.getInt("pref_mountain_bottom_offset", PreferenceDefaults.MOUNTAIN_BOTTOM_OFFSET)
        mountainBottomOffset = bottomOffsetVal / 100f
        
        // Seasonal settings (same preferences as TerrainLayer for consistency)
        winterSnowEnabled = settings.getBoolean("pref_winter_snow_hills", PreferenceDefaults.WINTER_SNOW_HILLS_ENABLED)
        winterSnowCoverage = settings.getInt("pref_winter_snow_coverage", PreferenceDefaults.WINTER_SNOW_COVERAGE)
        useWeatherSnow = settings.getBoolean("pref_weather_snow_enabled", PreferenceDefaults.WEATHER_SNOW_ENABLED)
        
        // Bottom offset, season and snow are draw-time only; regenerate just for geometry inputs.
        val key = GenerationKey(
            randomSeed, mountainDensity, mountainHeightScale, mountainWidthScale,
            mountainRoughness, ridgeRoughness
        )
        if (key != lastGenerationKey) {
            lastGenerationKey = key
            generateMountains()
        }
    }

    override fun destroy() {
        // Cleanup
    }
}
