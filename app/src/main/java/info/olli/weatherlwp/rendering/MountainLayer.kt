package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
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
    
    // Extended canvas dimensions for parallax content
    private var extendedWidth = 0f
    private var extendedOffset = 0f
    
    // Cached haze gradient to avoid per-frame allocation
    private var cachedHazeAmbient = 0
    private var cachedHazeHeight = 0f
    
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
    
    /**
     * Determines current terrain season based on date or override setting.
     */
    private fun getCurrentTerrainSeason(): String {
        if (terrainSeasonOverride != "auto") {
            return terrainSeasonOverride
        }
        val cal = java.util.Calendar.getInstance()
        return when (cal.get(java.util.Calendar.MONTH)) {
            2, 3, 4 -> "spring"
            5, 6, 7 -> "summer"
            8, 9, 10 -> "autumn"
            else -> "winter"
        }
    }
    
    private fun generateMountains() {
        // Calculate mountain count from density and extended width
        // More width = more mountains to maintain visual density
        val widthRatio = if (extendedWidth > 0 && height > 0) {
            extendedWidth / (height * RenderConstants.REFERENCE_ASPECT_RATIO)
        } else {
            4f  // Default for 4x extended width
        }
        val baseMountains = 8  // Base number at 100% density
        val mountainCount = ((mountainDensity / 100f) * baseMountains * widthRatio).toInt().coerceIn(0, 50)
        
        // Build new list (thread-safe: atomic swap at end)
        val newMountains = ArrayList<MountainData>(mountainCount)
        val random = Random(randomSeed.toLong())
        
        for (i in 0 until mountainCount) {
            // Depth: 0 is far, 1 is near
            val depth = i.toFloat() / mountainCount.coerceAtLeast(1)
            
            // Parallax factor based on depth using centralized constants
            val depthFactor = RenderConstants.PARALLAX_MOUNTAIN_MIN + 
                depth * (RenderConstants.PARALLAX_MOUNTAIN_MAX - RenderConstants.PARALLAX_MOUNTAIN_MIN)
            
            // Position: spread across extended width (0-1 range)
            val peakX = random.nextFloat()
            
            // Height: varies by scale setting
            val h = (RenderConstants.MOUNTAIN_HEIGHT_MIN + random.nextFloat() * RenderConstants.MOUNTAIN_HEIGHT_RANGE) * mountainHeightScale
            
            // Width: Random variation
            val w = (RenderConstants.MOUNTAIN_WIDTH_MIN + random.nextFloat() * RenderConstants.MOUNTAIN_WIDTH_RANGE) * mountainWidthScale
            
            // Color: Darker/bluer for far mountains (atmospheric perspective)
            val baseGray = RenderConstants.MOUNTAIN_GRAY_MIN + 
                (depth * (RenderConstants.MOUNTAIN_GRAY_MAX - RenderConstants.MOUNTAIN_GRAY_MIN)).toInt()
            val dayColor = Color.rgb(baseGray, baseGray + 5, baseGray + 15)
            
            newMountains.add(MountainData(peakX, h, w, dayColor, depthFactor, random.nextLong()))
        }
        
        // Sort: Far mountains first (drawn behind)
        newMountains.sortBy { it.depthFactor }
        
        // Atomic reference swap (volatile ensures visibility to render thread)
        mountains = newMountains
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        // Calculate extended width for parallax content
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)
        // Regenerate mountains for new dimensions
        generateMountains()
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
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk
        )
        
        for (mountain in currentMountains) {
            val px = parallaxCalculator.getOffsetXForDepth(mountain.depthFactor)
            val py = parallaxCalculator.getOffsetYForDepth(mountain.depthFactor)
            
            canvas.save()
            canvas.translate(px, py)
            
            drawMountain(canvas, mountain, state, ambientColor)
            
            canvas.restore()
        }
        
        // Draw atmospheric haze at the base of mountains (fog overlay)
        // This blends the mountains into the terrain and adds depth
        drawAtmosphericHaze(canvas, state.isNight, ambientColor)
    }
    
    /**
     * Draw a soft atmospheric haze at the bottom portion of the screen.
     * This simulates fog/mist at the base of mountains.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawAtmosphericHaze(canvas: Canvas, isNight: Boolean, ambientColor: Int) {
        val hazeStartY = height * RenderConstants.HAZE_START_Y
        val hazeEndY = height * RenderConstants.HAZE_END_Y
        
        // Only recreate gradient if ambient color or height changed (avoids per-frame allocation)
        if (ambientColor != cachedHazeAmbient || height != cachedHazeHeight) {
            cachedHazeAmbient = ambientColor
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
        pathLeft.moveTo(ridgePoints[0].first + overlapPx, ridgePoints[0].second)
        
        // Draw left slope segments
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = peakCenterX - halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * noiseBase
            pathLeft.lineTo(targetX, targetY + noise)
        }
        pathLeft.lineTo(peakCenterX - halfWidthPx, baseY)
        
        // Close back along ridge (in reverse) - with overlap into right side
        for (i in ridgePoints.size - 1 downTo 0) {
            pathLeft.lineTo(ridgePoints[i].first + overlapPx, ridgePoints[i].second)
        }
        pathLeft.close()
        
        // Right side (shadow side) - connect from ridge to right edge
        pathRight.moveTo(ridgePoints[0].first, ridgePoints[0].second)
        
        // Draw right slope segments
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = peakCenterX + halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * noiseBase
            pathRight.lineTo(targetX, targetY + noise)
        }
        pathRight.lineTo(peakCenterX + halfWidthPx, baseY)
        
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
        
        // Determine shadow side based on light source angle
        // getLightSourceAngle() returns: π = light from left, 0 = light from right
        val lightAngle = state.getLightSourceAngle()
        val shadowStrength = if (state.mountainShadowsEnabled) state.getEffectiveShadowStrength() else 0f
        
        // Light from left (angle > π/2) means right side is shadowed
        // Light from right (angle < π/2) means left side is shadowed
        val rightIsShadowed = lightAngle > kotlin.math.PI.toFloat() / 2f
        
        // Shadow intensity based on how far from directly overhead the light is
        // At noon (angle = π/2), minimal shadow difference
        val angleDiff = kotlin.math.abs(lightAngle - kotlin.math.PI.toFloat() / 2f)
        val shadowFactor = (angleDiff / (kotlin.math.PI.toFloat() / 2f)).coerceIn(0.1f, 1f)
        val effectiveShadow = shadowStrength * shadowFactor * 0.25f  // 0-0.25 darkening
        
        // Left side shadow (when light from right)
        val leftShadowDarkening = if (!rightIsShadowed) effectiveShadow else 0f
        val leftPeakColor = Color.rgb(
            (Color.red(peakColor) * (1f - leftShadowDarkening)).toInt().coerceIn(0, 255),
            (Color.green(peakColor) * (1f - leftShadowDarkening)).toInt().coerceIn(0, 255),
            (Color.blue(peakColor) * (1f - leftShadowDarkening * 0.5f)).toInt().coerceIn(0, 255)
        )
        val leftBaseColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - leftShadowDarkening * 0.7f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - leftShadowDarkening * 0.7f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - leftShadowDarkening * 0.5f)).toInt().coerceIn(0, 255)
        )
        
        // Three-point vertical gradient for left side
        val gradient = LinearGradient(
            peakCenterX, peakY,
            peakCenterX, baseY,
            intArrayOf(leftPeakColor, leftBaseColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        
        // Draw left side with gradient
        paint.shader = gradient
        canvas.drawPath(pathLeft, paint)
        
        // Right side shadow (when light from left)
        val rightShadowDarkening = if (rightIsShadowed) effectiveShadow else 0f
        val rightPeakColor = Color.rgb(
            (Color.red(peakColor) * (1f - rightShadowDarkening)).toInt().coerceIn(0, 255),
            (Color.green(peakColor) * (1f - rightShadowDarkening)).toInt().coerceIn(0, 255),
            (Color.blue(peakColor) * (1f - rightShadowDarkening * 0.5f)).toInt().coerceIn(0, 255)
        )
        val rightBaseColor = Color.rgb(
            (Color.red(baseAtmoColor) * (1f - rightShadowDarkening * 0.7f)).toInt().coerceIn(0, 255),
            (Color.green(baseAtmoColor) * (1f - rightShadowDarkening * 0.7f)).toInt().coerceIn(0, 255),
            (Color.blue(baseAtmoColor) * (1f - rightShadowDarkening * 0.5f)).toInt().coerceIn(0, 255)
        )
        val rightGradient = LinearGradient(
            peakCenterX, peakY,
            peakCenterX, baseY,
            intArrayOf(rightPeakColor, rightBaseColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = rightGradient
        canvas.drawPath(pathRight, paint)
        
        // Reset shader
        paint.shader = null
        
        // Draw snow cap if winter and snow enabled
        val season = getCurrentTerrainSeason()
        if (winterSnowEnabled && season == "winter") {
            // Snow coverage determines how far down the snow extends
            val snowDepth = (winterSnowCoverage / 100f).coerceIn(0.15f, 0.5f)
            val snowBottomY = peakY + (baseY - peakY) * snowDepth
            
            // Build snow cap shape following the ridge and mountain slopes
            snowCapPath.reset()
            
            // Start at peak
            snowCapPath.moveTo(ridgePoints[0].first, ridgePoints[0].second)
            
            // Left side of snow cap (follow left slope down to snow line)
            val random = Random(mountain.shapeSeed + 1000)
            val snowSegments = (segments * snowDepth).toInt().coerceAtLeast(3)
            for (i in 0 until snowSegments) {
                val progress = (i + 1).toFloat() / segments
                if (progress > snowDepth) break
                val targetX = peakCenterX - halfWidthPx * progress
                val targetY = peakY + (baseY - peakY) * progress
                val noise = (random.nextFloat() - 0.5f) * height * 0.008f
                snowCapPath.lineTo(targetX, targetY + noise)
            }
            
            // Bottom edge of snow (jagged snow line)
            val snowLineX = peakCenterX - halfWidthPx * snowDepth
            snowCapPath.lineTo(snowLineX, snowBottomY)
            
            // Cross to right side at snow line
            val snowLineXRight = peakCenterX + halfWidthPx * snowDepth
            snowCapPath.lineTo(snowLineXRight, snowBottomY)
            
            // Right side of snow cap (follow right slope back to peak)
            for (i in snowSegments - 1 downTo 0) {
                val progress = (i + 1).toFloat() / segments
                if (progress > snowDepth) continue
                val targetX = peakCenterX + halfWidthPx * progress
                val targetY = peakY + (baseY - peakY) * progress
                val noise = (random.nextFloat() - 0.5f) * height * 0.008f
                snowCapPath.lineTo(targetX, targetY + noise)
            }
            
            snowCapPath.close()
            canvas.drawPath(snowCapPath, snowCapPaint)
        }
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Random seed for consistent placement
        randomSeed = prefs.getInt("pref_random_seed", 42)
        
        // Mountain density: 0-100 percentage (scales with extended width)
        mountainDensity = prefs.getInt("pref_mountain_density", 50)
        
        // Height: 10-1000 -> 0.1 - 10.0
        val heightVal = prefs.getInt("pref_mountain_height", 140)
        mountainHeightScale = heightVal / 100f
        
        // Width: 50-1000 -> 0.5 - 10.0 (default 175 = 1.75x for good visual size)
        val widthVal = prefs.getInt("pref_mountain_width", 175)
        mountainWidthScale = widthVal / 100f
        
        // Roughness: 0-200 -> 0.0 - 2.0
        val roughVal = prefs.getInt("pref_mountain_roughness", 80)
        mountainRoughness = roughVal / 100f
        
        // Ridge roughness: 0-200 -> 0.0 - 2.0
        val ridgeVal = prefs.getInt("pref_mountain_ridge_roughness", 50)
        ridgeRoughness = ridgeVal / 100f
        
        // Bottom offset: 0-50 -> 0.0 - 0.5 (shift mountains up by % of screen height)
        val bottomOffsetVal = prefs.getInt("pref_mountain_bottom_offset", 0)
        mountainBottomOffset = bottomOffsetVal / 100f
        
        // Seasonal settings (same preferences as TerrainLayer for consistency)
        terrainSeasonOverride = prefs.getString("pref_terrain_season", "auto") ?: "auto"
        winterSnowEnabled = prefs.getBoolean("pref_winter_snow_hills", true)
        winterSnowCoverage = prefs.getInt("pref_winter_snow_coverage", 50)
        
        generateMountains()
    }

    override fun destroy() {
        // Cleanup
    }
}
