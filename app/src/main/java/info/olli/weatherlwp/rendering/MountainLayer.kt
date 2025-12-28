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
 * Design notes (from ai.md):
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
    
    private val pathLeft = Path()
    private val pathRight = Path()
    
    private var width = 0f
    private var height = 0f
    
    private data class MountainData(
        val peakX: Float,      // 0.0 to 1.0 (relative to screen width)
        val height: Float,     // 0.0 to 1.0 (relative to screen height)
        val width: Float,      // Width multiplier
        val dayColor: Int,     // Color during day
        val depthFactor: Float, // Parallax depth
        val shapeSeed: Long
    )
    
    private val mountains = ArrayList<MountainData>()
    
    // Settings
    private var mountainCount = 3
    private var mountainHeightScale = 1.0f
    private var mountainWidthScale = 1.0f
    private var mountainRoughness = 0.5f
    private var ridgeRoughness = 0.5f
    private var randomSeed = 42
    
    init {
        reloadSettings()
    }
    
    private fun generateMountains() {
        mountains.clear()
        val random = Random(randomSeed.toLong())
        
        for (i in 0 until mountainCount) {
            // Depth: 0 is far, 1 is near
            val depth = i.toFloat() / mountainCount.coerceAtLeast(1)
            
            // Parallax factor based on depth using centralized constants
            val depthFactor = RenderConstants.PARALLAX_MOUNTAIN_MIN + 
                depth * (RenderConstants.PARALLAX_MOUNTAIN_MAX - RenderConstants.PARALLAX_MOUNTAIN_MIN)
            
            // Position: spread across width
            val peakX = -0.2f + random.nextFloat() * 1.4f
            
            // Height: 0.3 to 0.6 of screen height
            val h = (0.3f + random.nextFloat() * 0.3f) * mountainHeightScale
            
            // Width: Random variation
            val w = (0.8f + random.nextFloat() * 0.6f) * mountainWidthScale
            
            // Color: Darker/bluer for far mountains (atmospheric perspective)
            val baseGray = RenderConstants.MOUNTAIN_GRAY_MIN + 
                (depth * (RenderConstants.MOUNTAIN_GRAY_MAX - RenderConstants.MOUNTAIN_GRAY_MIN)).toInt()
            val dayColor = Color.rgb(baseGray, baseGray + 5, baseGray + 15)
            
            mountains.add(MountainData(peakX, h, w, dayColor, depthFactor, random.nextLong()))
        }
        
        // Sort: Far mountains first (drawn behind)
        mountains.sortBy { it.depthFactor }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // Mountains don't animate
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        val scaleX = height * RenderConstants.REFERENCE_ASPECT_RATIO
        
        // Get ambient color for atmospheric blending (per ai.md)
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk
        )
        
        for (mountain in mountains) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(mountain.depthFactor)
            
            canvas.save()
            canvas.translate(px, py)
            
            drawMountain(canvas, mountain, scaleX, state.isNight, ambientColor)
            
            canvas.restore()
        }
        
        // Draw atmospheric haze at the base of mountains (ai.md: fog overlay)
        // This blends the mountains into the terrain and adds depth
        drawAtmosphericHaze(canvas, state.isNight, ambientColor)
    }
    
    /**
     * Draw a soft atmospheric haze at the bottom portion of the screen.
     * This simulates fog/mist at the base of mountains as described in ai.md.
     */
    private fun drawAtmosphericHaze(canvas: Canvas, isNight: Boolean, ambientColor: Int) {
        val hazeStartY = height * 0.55f  // Start at 55% down
        val hazeEndY = height * 0.85f    // Fade to 85% down
        
        // Haze color blends with ambient for consistent atmosphere
        val hazeColor = Color.argb(
            25,
            Color.red(ambientColor),
            Color.green(ambientColor),
            Color.blue(ambientColor)
        )
        
        val hazeGradient = LinearGradient(
            0f, hazeStartY,
            0f, hazeEndY,
            Color.TRANSPARENT,
            hazeColor,
            Shader.TileMode.CLAMP
        )
        
        hazePaint.shader = hazeGradient
        canvas.drawRect(0f, hazeStartY, width, hazeEndY, hazePaint)
        hazePaint.shader = null
    }
    
    private fun drawMountain(canvas: Canvas, mountain: MountainData, scaleX: Float, isNight: Boolean, ambientColor: Int) {
        pathLeft.reset()
        pathRight.reset()
        
        val peakCenterX = (mountain.peakX - 0.5f) * scaleX + width / 2f
        val peakY = height * (1f - mountain.height)
        val baseY = height
        val halfWidthPx = scaleX * mountain.width * 0.5f
        
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
        pathLeft.moveTo(ridgePoints[0].first, ridgePoints[0].second)
        
        // Draw left slope segments
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = peakCenterX - halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * noiseBase
            pathLeft.lineTo(targetX, targetY + noise)
        }
        pathLeft.lineTo(peakCenterX - halfWidthPx, baseY)
        
        // Close back along ridge (in reverse)
        for (i in ridgePoints.size - 1 downTo 0) {
            pathLeft.lineTo(ridgePoints[i].first, ridgePoints[i].second)
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
        
        // Apply ambient tinting (per ai.md: global tint system)
        // Mountains blend with ambient color based on their depth
        val blendAmount = RenderConstants.applyAmbientTint(mountain.dayColor, ambientColor, mountain.depthFactor)
        val baseColor = RenderConstants.blendWithAmbient(mountain.dayColor, ambientColor, blendAmount)
        
        // Create atmospheric gradient: lighter/bluer at base (fog effect)
        // Peaks are darker/more distinct, base fades into atmospheric haze
        val peakColor = baseColor
        // Base blends even more with ambient (atmospheric perspective)
        val baseAtmoColor = RenderConstants.blendWithAmbient(baseColor, ambientColor, blendAmount + 0.2f)
        
        // Vertical gradient shader (peak color at top, atmospheric at bottom)
        val gradient = LinearGradient(
            peakCenterX, peakY,
            peakCenterX, baseY,
            peakColor, baseAtmoColor,
            Shader.TileMode.CLAMP
        )
        
        // Draw left side with gradient
        paint.shader = gradient
        canvas.drawPath(pathLeft, paint)
        
        // Shadow side: slightly darker gradient
        val shadowPeakColor = Color.rgb(
            (Color.red(peakColor) * 0.8f).toInt(),
            (Color.green(peakColor) * 0.8f).toInt(),
            (Color.blue(peakColor) * 0.9f).toInt()
        )
        val shadowBaseColor = Color.rgb(
            (Color.red(baseAtmoColor) * 0.85f).toInt(),
            (Color.green(baseAtmoColor) * 0.85f).toInt(),
            (Color.blue(baseAtmoColor) * 0.95f).toInt()
        )
        val shadowGradient = LinearGradient(
            peakCenterX, peakY,
            peakCenterX, baseY,
            shadowPeakColor, shadowBaseColor,
            Shader.TileMode.CLAMP
        )
        paint.shader = shadowGradient
        canvas.drawPath(pathRight, paint)
        
        // Reset shader
        paint.shader = null
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Random seed for consistent placement
        randomSeed = prefs.getInt("pref_random_seed", 42)
        
        mountainCount = prefs.getInt("pref_mountain_count", 4)
        
        // Height: 10-1000 -> 0.1 - 10.0
        val heightVal = prefs.getInt("pref_mountain_height", 140)
        mountainHeightScale = heightVal / 100f
        
        // Width: 50-1000 -> 0.5 - 10.0
        val widthVal = prefs.getInt("pref_mountain_width", 500)
        mountainWidthScale = widthVal / 100f
        
        // Roughness: 0-200 -> 0.0 - 2.0
        val roughVal = prefs.getInt("pref_mountain_roughness", 80)
        mountainRoughness = roughVal / 100f
        
        // Ridge roughness: 0-200 -> 0.0 - 2.0
        val ridgeVal = prefs.getInt("pref_mountain_ridge_roughness", 50)
        ridgeRoughness = ridgeVal / 100f
        
        generateMountains()
    }

    override fun destroy() {
        // Cleanup
    }
}
