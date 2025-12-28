package com.example.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import com.example.weatherlwp.parallax.ParallaxCalculator
import com.example.weatherlwp.simulation.SimulationState
import kotlin.random.Random

/**
 * Renders mountain silhouettes in the background.
 * 
 * Uses SimulationState for:
 * - isNight (affects mountain color tint)
 * - No time-based animation (mountains are static)
 */
class MountainLayer(private val context: Context) : SceneLayer {
    private val paint = Paint().apply { 
        style = Paint.Style.FILL 
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
        val depthFactor: Float, // 0.0 (far) to 1.0 (near)
        val shapeSeed: Long
    )
    
    private val mountains = ArrayList<MountainData>()
    
    // Settings
    private var mountainCount = 3
    private var mountainHeightScale = 1.0f
    private var mountainWidthScale = 1.0f
    private var mountainRoughness = 0.5f
    
    companion object {
        private const val REFERENCE_ASPECT_RATIO = 0.5625f // 9:16
    }
    
    init {
        reloadSettings()
    }
    
    private fun generateMountains() {
        mountains.clear()
        val random = Random(123) // Fixed seed for consistency
        
        for (i in 0 until mountainCount) {
            // Depth: 0 is far, 1 is near
            val depth = i.toFloat() / mountainCount.coerceAtLeast(1)
            
            // Parallax factor: 0.25 (far) to 0.45 (near)
            val depthFactor = 0.25f + depth * 0.20f
            
            // Position: spread across width
            val peakX = -0.2f + random.nextFloat() * 1.4f
            
            // Height: 0.3 to 0.6 of screen height
            val h = (0.3f + random.nextFloat() * 0.3f) * mountainHeightScale
            
            // Width: Random variation
            val w = (0.8f + random.nextFloat() * 0.6f) * mountainWidthScale
            
            // Color: Darker/bluer for far mountains (atmospheric perspective)
            val baseGray = 40 + (depth * 60).toInt() // 40..100
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
        
        val scaleX = height * REFERENCE_ASPECT_RATIO
        
        for (mountain in mountains) {
            val (px, py) = parallaxCalculator.getOffsetForDepth(mountain.depthFactor)
            
            canvas.save()
            canvas.translate(px, py)
            
            drawMountain(canvas, mountain, scaleX, state.isNight)
            
            canvas.restore()
        }
    }
    
    private fun drawMountain(canvas: Canvas, mountain: MountainData, scaleX: Float, isNight: Boolean) {
        pathLeft.reset()
        pathRight.reset()
        
        val peakCenterX = (mountain.peakX - 0.5f) * scaleX + width / 2f
        val peakY = height * (1f - mountain.height)
        val baseY = height
        val halfWidthPx = scaleX * mountain.width * 0.5f
        
        // Generate jagged shape
        val random = Random(mountain.shapeSeed)
        val segments = (10 + mountainRoughness * 20).toInt()
        
        // Left side
        pathLeft.moveTo(peakCenterX, peakY)
        
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = peakCenterX - halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * 30f * mountainRoughness
            pathLeft.lineTo(targetX, targetY + noise)
        }
        pathLeft.lineTo(peakCenterX - halfWidthPx, baseY)
        pathLeft.lineTo(peakCenterX, baseY)
        pathLeft.close()
        
        // Right side (shadow side)
        pathRight.moveTo(peakCenterX, peakY)
        
        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val targetX = peakCenterX + halfWidthPx * progress
            val targetY = peakY + (baseY - peakY) * progress
            val noise = (random.nextFloat() - 0.5f) * 30f * mountainRoughness
            pathRight.lineTo(targetX, targetY + noise)
        }
        pathRight.lineTo(peakCenterX + halfWidthPx, baseY)
        pathRight.lineTo(peakCenterX, baseY)
        pathRight.close()
        
        // Apply night tint if needed
        val color = if (isNight) {
            // Add blue tint at night
            Color.rgb(
                (Color.red(mountain.dayColor) * 0.7f).toInt(),
                (Color.green(mountain.dayColor) * 0.7f).toInt(),
                (Color.blue(mountain.dayColor) * 0.9f + 30).toInt().coerceAtMost(255)
            )
        } else {
            mountain.dayColor
        }
        
        // Draw left side
        paint.color = color
        canvas.drawPath(pathLeft, paint)
        
        // Shadow side slightly darker
        paint.color = Color.rgb(
            (Color.red(color) * 0.8f).toInt(),
            (Color.green(color) * 0.8f).toInt(),
            (Color.blue(color) * 0.9f).toInt()
        )
        canvas.drawPath(pathRight, paint)
    }

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
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
        
        generateMountains()
    }

    override fun destroy() {
        // Cleanup
    }
}
