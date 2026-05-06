package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.util.ColorMath
import info.olli.weatherlwp.util.SeasonHelper
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
    
    private var width = 0f
    private var height = 0f
    private var lastGeneratedWidth = 0f
    private var lastGeneratedHeight = 0f
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f
    
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
    
    private data class MountainData(
        val peakX: Float,      // 0.0 to 1.0 (relative to extended width)
        val height: Float,     // 0.0 to 1.0 (relative to screen height)
        val width: Float,      // Width multiplier
        val dayColor: Int,     // Color during day
        val depthFactor: Float, // Parallax depth
        val shapeSeed: Long
    )
    
    // Thread-safe mountain list using volatile reference + immutable list
    // Settings thread creates new list, render thread reads current snapshot
    @Volatile private var mountains: List<MountainData> = emptyList()
    
    // Settings
    private var mountainDensity = 50       // 0-100 percentage of mountain coverage
    private var mountainHeightScale = 1.0f
    private var mountainWidthScale = 1.0f
    private var mountainRoughness = 0.5f
    private var ridgeRoughness = 0.5f
    private var mountainBottomOffset = 0f  // Shift mountains up (0-1 of screen height)
    private var randomSeed = 42
    
    // Seasonal settings (read from terrain settings)
    private var terrainSeasonOverride = "auto"
    private var winterSnowEnabled = true
    private var winterSnowCoverage = 50   // 0-100 how much of mountain tops are covered
    
    init {
        reloadSettings()
    }
    
    private fun getCurrentTerrainSeason(): String =
        SeasonHelper.getEffectiveTerrainSeasonKey(true, terrainSeasonOverride)

    private fun ambientColorBucket(color: Int): Int {
        val r = Color.red(color) shr 3
        val g = Color.green(color) shr 3
        val b = Color.blue(color) shr 3
        return (r shl 10) or (g shl 5) or b
    }

    private fun quantizeAmbientColor(color: Int): Int = ColorMath.quantizeColor(color)
    
    private fun generateMountains() {
        // Calculate mountain count from density and horizontal span.
        // Width-only scaling ensures consistent density between wallpaper and preview
        // regardless of canvas height (preview is short, wallpaper is full-screen).
        val widthRatio = if (extendedWidth > 0f) {
            RenderConstants.getHorizontalDensityScale(extendedWidth, width, height)
        } else {
            1f
        }
        val baseMountains = 14  // Base number at 100% density in portrait
        val mountainCount = ((mountainDensity / 100f) * baseMountains * widthRatio).toInt().coerceIn(0, 60)
        
        // Build new list (thread-safe: atomic swap at end)
        val newMountains = ArrayList<MountainData>(mountainCount)
        
        for (i in 0 until mountainCount) {
            val itemRandom = DeterministicLayout.random(randomSeed, section = 100, index = i)
            // Depth: 0 is far, 1 is near
            val depth = i.toFloat() / mountainCount.coerceAtLeast(1)
            
            // Parallax factor based on depth using centralized constants
            val depthFactor = RenderConstants.PARALLAX_MOUNTAIN_MIN + 
                depth * (RenderConstants.PARALLAX_MOUNTAIN_MAX - RenderConstants.PARALLAX_MOUNTAIN_MIN)
            
            // Position: stratified placement with jitter for even distribution
            // Divide extended width into equal cells, then jitter within each cell
            val cellWidth = 1f / mountainCount.coerceAtLeast(1)
            val cellCenter = (i + 0.5f) * cellWidth
            val jitter = (itemRandom.nextFloat() - 0.5f) * cellWidth * 0.8f
            val peakX = (cellCenter + jitter).coerceIn(0f, 1f)
            
            // Height: varies by scale setting
            val h = (RenderConstants.MOUNTAIN_HEIGHT_MIN + itemRandom.nextFloat() * RenderConstants.MOUNTAIN_HEIGHT_RANGE) * mountainHeightScale
            
            // Width: Random variation
            val w = (RenderConstants.MOUNTAIN_WIDTH_MIN + itemRandom.nextFloat() * RenderConstants.MOUNTAIN_WIDTH_RANGE) * mountainWidthScale
            
            // Color: Darker/bluer for far mountains (atmospheric perspective)
            val baseGray = RenderConstants.MOUNTAIN_GRAY_MIN + 
                (depth * (RenderConstants.MOUNTAIN_GRAY_MAX - RenderConstants.MOUNTAIN_GRAY_MIN)).toInt()
            val dayColor = Color.rgb(baseGray, baseGray + 5, baseGray + 15)
            
            val shapeSeed = DeterministicLayout.mix(randomSeed, section = 101, index = i)
            newMountains.add(MountainData(peakX, h, w, dayColor, depthFactor, shapeSeed))
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
        // Calculate extended width for parallax content
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)

        if (!sizeChanged) return

        // Invalidate cached gradients that embed old height coordinates
        cachedLeftGradients.clear()
        cachedRightGradients.clear()
        cachedSnowGradients.clear()
        cachedMtnAmbient = 0
        cachedMtnLightAngleBucket = -1
        cachedHazeAmbient = 0
        cachedHazeHeight = 0f

        val firstGenerate = lastGeneratedWidth <= 0f || lastGeneratedHeight <= 0f
        val sizeChangedSignificantly =
            kotlin.math.abs(lastGeneratedWidth - w) > 1f ||
            kotlin.math.abs(lastGeneratedHeight - h) > 1f
        if (firstGenerate || sizeChangedSignificantly) {
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
        
        // Take local snapshot for thread-safe iteration
        val currentMountains = mountains
        
        // Get ambient color for atmospheric blending
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk,
            state.nightShadeStrength, state.sunriseHours, state.sunsetHours
        )
        val ambientBucket = ambientColorBucket(ambientColor)
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
        }
        
        for (mountain in currentMountains) {
            val peakCenterX = mountain.peakX * extendedWidth - extendedOffset
            val halfWidthPx = height * mountain.width * 0.25f
            val screenWidth = ViewportCulling.screenWidth(extendedWidth, extendedOffset)
            if (!ViewportCulling.isVisibleHorizontally(
                    x = peakCenterX,
                    objectHalfWidth = halfWidthPx,
                    screenWidth = screenWidth,
                    height = height,
                    extraMargin = width * 0.05f
                )) {
                continue
            }

            val px = parallaxCalculator.getOffsetXForDepth(mountain.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(mountain.depthFactor)
            
            canvas.save()
            canvas.translate(px, py)
            
            drawMountain(canvas, mountain, state, bucketedAmbientColor)
            
            canvas.restore()
        }
        
        // Draw atmospheric haze at the base of mountains (fog overlay)
        // This blends the mountains into the terrain and adds depth
        drawAtmosphericHaze(canvas, state.isNight, bucketedAmbientColor)
    }
    
    /**
     * Draw a soft atmospheric haze at the bottom portion of the screen.
     * This simulates fog/mist at the base of mountains.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawAtmosphericHaze(canvas: Canvas, isNight: Boolean, ambientColor: Int) {
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
    
    @Suppress("UNUSED_PARAMETER")
    private fun drawMountain(canvas: Canvas, mountain: MountainData, state: SimulationState, ambientColor: Int) {
        val mountainIdx = mountains.indexOf(mountain)
        pathLeft.reset()
        pathRight.reset()
        
        // Convert from 0-1 extended space to screen coordinates
        val peakCenterX = mountain.peakX * extendedWidth - extendedOffset
        // Apply bottom offset to shift mountains up
        val offsetPx = mountainBottomOffset * height
        val peakY = height * (1f - mountain.height) - offsetPx
        val baseY = height - offsetPx
        // Scale width based on HEIGHT for consistent proportions across orientations
        // (Mountains should look the same size relative to screen height in portrait and landscape)
        val halfWidthPx = height * mountain.width * 0.25f
        
        // Generate jagged shape
        val random = Random(mountain.shapeSeed)
        val segments = (10 + mountainRoughness * 20).toInt()
        
        // Base noise amplitude scales with screen height for consistent roughness
        // across different canvas sizes (preview vs full wallpaper)
        val noiseBase = height * 0.015f * mountainRoughness
        
        // Ridge noise for center line (horizontal displacement)
        val ridgeNoiseBase = height * 0.02f * ridgeRoughness
        val ridgeSegments = (5 + ridgeRoughness * 10).toInt().coerceAtLeast(2)
        
        // Generate ridge points (jagged center line from peak to base)
        // Also includes random peak angle offset
        val peakAngleOffset = if (ridgeRoughness > 0) {
            (random.nextFloat() - 0.5f) * halfWidthPx * 0.3f * ridgeRoughness
        } else 0f
        
        val ridgePoints = ArrayList<Pair<Float, Float>>()
        ridgePoints.add(Pair(peakCenterX + peakAngleOffset, peakY))
        
        for (i in 1 until ridgeSegments) {
            val progress = i.toFloat() / ridgeSegments
            val ridgeY = peakY + (baseY - peakY) * progress
            // Ridge wanders horizontally with decreasing amplitude toward base
            val ridgeNoise = (random.nextFloat() - 0.5f) * ridgeNoiseBase * (1f - progress * 0.7f)
            // Interpolate X position from peak offset back toward center
            val ridgeX = peakCenterX + peakAngleOffset * (1f - progress) + ridgeNoise
            ridgePoints.add(Pair(ridgeX, ridgeY))
        }
        ridgePoints.add(Pair(peakCenterX, baseY))  // End at center base
        
        // Left side - connect from ridge to left edge
        // Use slight overlap (1px) past ridge to prevent anti-aliasing gaps
        val overlapPx = 1f
        // Get actual peak X position (which may be offset from center)
        val actualPeakX = ridgePoints[0].first
        pathLeft.moveTo(actualPeakX + overlapPx, ridgePoints[0].second)
        
        // Store slope points for snow cap reuse
        val leftSlopePoints = ArrayList<Pair<Float, Float>>()
        val rightSlopePoints = ArrayList<Pair<Float, Float>>()
        
        // Draw left slope segments - slopes radiate from the actual peak position
        // This ensures mountain looks centered even when peak is offset
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = actualPeakX - halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * noiseBase
            val pointY = targetY + noise
            leftSlopePoints.add(Pair(targetX, pointY))
            pathLeft.lineTo(targetX, pointY)
        }
        pathLeft.lineTo(actualPeakX - halfWidthPx, baseY)
        
        // Extend to bottom to prevent floating mountains when offset is applied
        pathLeft.lineTo(actualPeakX - halfWidthPx, height)
        pathLeft.lineTo(peakCenterX + overlapPx, height)
        pathLeft.lineTo(peakCenterX + overlapPx, baseY)
        
        // Close back along ridge (in reverse) - with overlap into right side
        for (i in ridgePoints.size - 1 downTo 0) {
            pathLeft.lineTo(ridgePoints[i].first + overlapPx, ridgePoints[i].second)
        }
        pathLeft.close()
        
        // Right side (shadow side) - connect from ridge to right edge
        pathRight.moveTo(actualPeakX, ridgePoints[0].second)
        
        // Draw right slope segments - also from actual peak position
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = actualPeakX + halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * noiseBase
            val pointY = targetY + noise
            rightSlopePoints.add(Pair(targetX, pointY))
            pathRight.lineTo(targetX, pointY)
        }
        pathRight.lineTo(actualPeakX + halfWidthPx, baseY)
        
        // Extend to bottom to prevent floating mountains when offset is applied
        pathRight.lineTo(actualPeakX + halfWidthPx, height)
        pathRight.lineTo(peakCenterX, height)
        pathRight.lineTo(peakCenterX, baseY)
        
        // Close back along ridge (in reverse)
        for (i in ridgePoints.size - 1 downTo 0) {
            pathRight.lineTo(ridgePoints[i].first, ridgePoints[i].second)
        }
        pathRight.close()
        
        // Apply ambient tinting (global tint system)
        // Mountains blend with ambient color based on their depth
        val blendAmount = RenderConstants.applyAmbientTint(mountain.dayColor, ambientColor, mountain.depthFactor)
        val baseColor = RenderConstants.blendWithAmbient(mountain.dayColor, ambientColor, blendAmount)
        
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
        val lightAngle = state.getLightSourceAngle()
        val shadowStrength = if (state.mountainShadowsEnabled) state.getEffectiveShadowStrength() else 0f
        
        // Calculate sun elevation (0 = horizon, 1 = overhead)
        // Using abs distance from π/2 (noon)
        val noonAngle = kotlin.math.PI.toFloat() / 2f
        val sunElevation = 1f - kotlin.math.abs(lightAngle - noonAngle) / noonAngle
        
        // Base shadow on viewer-facing slopes (stronger when sun is low behind mountains)
        // At noon (elevation=1): minimal shadow. At sunrise/sunset (elevation=0): max shadow
        val frontShadowBase = shadowStrength * (1f - sunElevation) * 0.35f
        
        // Left/right asymmetry based on sun position
        // Light from left (angle > π/2) means left side catches more light, right is darker
        // Light from right (angle < π/2) means right side catches more light, left is darker
        val lightBias = (lightAngle - noonAngle) / noonAngle  // -1 to +1
        
        // Shadow intensity for each side (base shadow + directional component)
        // Both sides always have some shadow when sun is low, but one side is darker
        val leftShadow = (frontShadowBase - lightBias * frontShadowBase * 0.4f).coerceIn(0f, 0.4f)
        val rightShadow = (frontShadowBase + lightBias * frontShadowBase * 0.4f).coerceIn(0f, 0.4f)
        
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
        
        // Draw snow cap if winter and snow enabled
        val season = getCurrentTerrainSeason()
        if (winterSnowEnabled && season == "winter") {
            // Snow coverage determines how far down the snow extends (as fraction of mountain height)
            val snowDepth = (winterSnowCoverage / 100f).coerceIn(0.15f, 0.5f)
            
            // How many slope segments are covered by snow
            val snowSegmentCount = (segments * snowDepth).toInt().coerceIn(1, leftSlopePoints.size)
            
            // Build snow cap shape using exact mountain slope points (prevents edge gaps)
            snowCapPath.reset()
            
            // Start at peak (with slight inset to ensure overlap)
            val peakInset = 2f
            snowCapPath.moveTo(ridgePoints[0].first, ridgePoints[0].second + peakInset)
            
            // Left side of snow - follow the exact mountain slope points
            for (i in 0 until snowSegmentCount) {
                val point = leftSlopePoints[i]
                snowCapPath.lineTo(point.first, point.second)
            }
            
            // Bottom edge - jagged snow line with some randomness
            val snowRandom = Random(mountain.shapeSeed + 2000)
            val lastLeftPoint = leftSlopePoints[snowSegmentCount - 1]
            val lastRightPoint = rightSlopePoints[snowSegmentCount - 1]
            
            // Add a few jagged points along the bottom snow line
            val jaggedSteps = 5
            for (j in 0..jaggedSteps) {
                val t = j.toFloat() / jaggedSteps
                val x = lastLeftPoint.first + (lastRightPoint.first - lastLeftPoint.first) * t
                val snowBaseY = lastLeftPoint.second + (lastRightPoint.second - lastLeftPoint.second) * t
                // Add downward jaggedness (snow drips down)
                val jag = snowRandom.nextFloat() * height * 0.015f
                snowCapPath.lineTo(x, snowBaseY + jag)
            }
            
            // Right side of snow - follow the exact mountain slope points in reverse
            for (i in snowSegmentCount - 1 downTo 0) {
                val point = rightSlopePoints[i]
                snowCapPath.lineTo(point.first, point.second)
            }
            
            snowCapPath.close()
            
            // Gradient: pure white at peak, fading to semi-transparent white at bottom
            // This blends naturally with the mountain color underneath
            val snowTopY = ridgePoints[0].second
            val snowBottomY = lastLeftPoint.second + height * 0.01f
            val snowGradient = cachedSnowGradients.getOrPut(mountainIdx) {
                LinearGradient(
                    actualPeakX, snowTopY,
                    actualPeakX, snowBottomY,
                    intArrayOf(
                        Color.WHITE,
                        Color.WHITE,
                        Color.argb(180, 255, 255, 255),
                        Color.argb(40, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            snowCapPaint.shader = snowGradient
            canvas.drawPath(snowCapPath, snowCapPaint)
            snowCapPaint.shader = null
        }
    }

    override fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        reloadSettings(SimulationSettings.fromSnapshot(snapshot))
    }

    override fun reloadSettings(settings: SimulationSettings) {
        
        // Random seed for consistent placement
        randomSeed = settings.getInt("pref_random_seed", 42)
        
        // Mountain density: 0-100 percentage (scales with extended width)
        mountainDensity = settings.getInt("pref_mountain_density", PreferenceDefaults.MOUNTAIN_DENSITY)
        
        // Height: 25-200 -> 0.25 - 2.0
        val heightVal = settings.getInt("pref_mountain_height", PreferenceDefaults.MOUNTAIN_HEIGHT)
        mountainHeightScale = heightVal / 100f
        
        // Width: 50-200 -> 0.5 - 2.0
        val widthVal = settings.getInt("pref_mountain_width", PreferenceDefaults.MOUNTAIN_WIDTH)
        mountainWidthScale = widthVal / 100f
        
        // Roughness: 0-200 -> 0.0 - 2.0
        val roughVal = settings.getInt("pref_mountain_roughness", 80)
        mountainRoughness = roughVal / 100f
        
        // Ridge roughness: 0-200 -> 0.0 - 2.0
        val ridgeVal = settings.getInt("pref_mountain_ridge_roughness", 50)
        ridgeRoughness = ridgeVal / 100f
        
        // Bottom offset: 0-50 -> 0.0 - 0.5 (shift mountains up by % of screen height)
        val bottomOffsetVal = settings.getInt("pref_mountain_bottom_offset", 0)
        mountainBottomOffset = bottomOffsetVal / 100f
        
        // Seasonal settings (same preferences as TerrainLayer for consistency)
        // Only use terrain season override when debug/simulation mode is enabled
        val debugEnabled = settings.getBoolean("pref_debug_enabled", false)
        terrainSeasonOverride = if (debugEnabled) {
            settings.getString("pref_terrain_season", "auto")
        } else {
            "auto"  // Always use real season when not in debug mode
        }
        winterSnowEnabled = settings.getBoolean("pref_winter_snow_hills", true)
        winterSnowCoverage = settings.getInt("pref_winter_snow_coverage", 50)
        
        generateMountains()
    }

    override fun destroy() {
        // Cleanup
    }
}
