package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the sky background with gradient colors, stars, and vignette.
 * 
 * Uses SimulationState for:
 * - Time of day (colors, star visibility)
 * - absoluteTimeMs (star twinkle animation)
 * 
 * Uses RenderConstants for:
 * - Sky colors for different times
 * - Parallax factors
 * - Time thresholds
 * 
 * Design notes (from ai.md):
 * - Night scenes should have darker corners (vignette effect)
 * - Creates focus and atmosphere
 */
class SkyLayer : SceneLayer {
    private val skyPaint = Paint()
    private val starPaint = Paint().apply { 
        color = Color.WHITE 
        isAntiAlias = true 
        style = Paint.Style.FILL 
    }
    private val vignettePaint = Paint().apply {
        isAntiAlias = true
    }
    
    private var width = 0f
    private var height = 0f
    
    // Stars - generated once with fixed seed for consistency
    private val stars = ArrayList<FloatArray>()
    
    // Current 3-stop gradient colors (Top, Middle, Horizon)
    private var skyTop = Color.BLUE
    private var skyMid = Color.CYAN
    private var skyHorizon = Color.WHITE

    init {
        generateStars()
    }

    private fun generateStars() {
        stars.clear()
        val random = Random(42) // Fixed seed for consistency
        for (i in 0 until RenderConstants.STAR_COUNT) {
            stars.add(floatArrayOf(
                random.nextFloat(), // x (0-1)
                random.nextFloat() * 0.6f, // y (0-0.6, top 60%)
                random.nextFloat() * 3f + 1f // radius
            ))
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        updateGradient()
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        updateColorsForTime(state)
    }
    
    private fun updateColorsForTime(state: SimulationState) {
        // Use smoothed time for eased color transitions
        val hour = state.smoothedTimeOfDayHours
        
        // 3-stop gradient: Top, Middle (40% down), Horizon
        // Per ai.md: "Always use a 3-stop LinearGradient for the sky"
        val (newTop, newMid, newHorizon) = when {
            hour < RenderConstants.TIME_NIGHT_END -> {
                Triple(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_NIGHT_BOTTOM)
            }
            hour < RenderConstants.TIME_DAWN_START + 1f -> {
                // Transition from night to dawn - use eased transitions
                val t = hour - RenderConstants.TIME_NIGHT_END
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_DAWN_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_DAWN_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_NIGHT_BOTTOM, RenderConstants.SKY_DAWN_BOTTOM, t)
                )
            }
            hour < RenderConstants.TIME_DAWN_END -> {
                // Dawn to day - use eased transitions
                val t = (hour - 6f) / 1.5f
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_TOP, RenderConstants.SKY_DAY_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_MID, RenderConstants.SKY_DAY_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAWN_BOTTOM, RenderConstants.SKY_DAY_BOTTOM, t)
                )
            }
            hour < RenderConstants.TIME_DAY_END -> {
                Triple(RenderConstants.SKY_DAY_TOP, RenderConstants.SKY_DAY_MID, RenderConstants.SKY_DAY_BOTTOM)
            }
            hour < RenderConstants.TIME_DAY_END + 1.5f -> {
                // Day to dusk - use eased transitions
                val t = (hour - RenderConstants.TIME_DAY_END) / 1.5f
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_TOP, RenderConstants.SKY_DUSK_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_MID, RenderConstants.SKY_DUSK_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DAY_BOTTOM, RenderConstants.SKY_DUSK_BOTTOM, t)
                )
            }
            hour < RenderConstants.TIME_DUSK_END -> {
                // Dusk to night - use eased transitions
                val t = (hour - 18.5f) / 1.5f
                Triple(
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_TOP, RenderConstants.SKY_NIGHT_TOP, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_MID, RenderConstants.SKY_NIGHT_MID, t),
                    RenderConstants.easeSkyColorTransition(RenderConstants.SKY_DUSK_BOTTOM, RenderConstants.SKY_NIGHT_BOTTOM, t)
                )
            }
            else -> Triple(RenderConstants.SKY_NIGHT_TOP, RenderConstants.SKY_NIGHT_MID, RenderConstants.SKY_NIGHT_BOTTOM)
        }
        
        if (newTop != skyTop || newMid != skyMid || newHorizon != skyHorizon) {
            skyTop = newTop
            skyMid = newMid
            skyHorizon = newHorizon
            updateGradient()
        }
    }

    private fun updateGradient() {
        if (height > 0) {
            // 3-stop gradient: Top (0%) -> Middle (40%) -> Horizon (100%)
            // This creates the "horizon glow" effect described in ai.md
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(skyTop, skyMid, skyHorizon),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        val (px, py) = parallaxCalculator.getOffset()
        
        // Sky moves opposite to create depth illusion (infinite distance)
        val overscan = 300f + abs(px) * 0.4f + abs(py) * 0.4f
        
        canvas.save()
        canvas.translate(px * RenderConstants.PARALLAX_SKY, py * RenderConstants.PARALLAX_SKY)
        canvas.drawRect(-overscan, -overscan, width + overscan, height + overscan, skyPaint)
        canvas.restore()

        // Stars visible at night and dusk
        if (state.isNight || state.isDusk) {
            val time = state.absoluteTimeMs
            val starAlphaBase = if (state.isNight) 150 else 80
            
            val starParallaxX = px * RenderConstants.PARALLAX_STARS
            val starParallaxY = py * RenderConstants.PARALLAX_STARS
            
            for (star in stars) {
                val twinkle = sin((time * RenderConstants.STAR_TWINKLE_SPEED + star[0] * 10).toDouble())
                val alpha = (starAlphaBase + 50 * twinkle).toInt().coerceIn(30, 220)
                starPaint.alpha = alpha
                
                val x = star[0] * width + starParallaxX
                val y = star[1] * height + starParallaxY
                
                canvas.drawCircle(x, y, star[2], starPaint)
            }
        }
        
        // Vignette effect for night/dusk - darker corners for atmosphere
        if (state.isNight || state.isDusk) {
            val vignetteAlpha = if (state.isNight) 80 else 40
            drawVignette(canvas, vignetteAlpha)
        }
    }
    
    /**
     * Draw a radial vignette (darker corners) for atmospheric effect.
     */
    private fun drawVignette(canvas: Canvas, alpha: Int) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = kotlin.math.max(width, height) * 0.9f
        
        // Radial gradient: transparent center, dark edges
        val vignetteGradient = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(alpha / 2, 0, 0, 20),
                Color.argb(alpha, 5, 5, 30)
            ),
            floatArrayOf(0f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        
        vignettePaint.shader = vignetteGradient
        canvas.drawRect(0f, 0f, width, height, vignettePaint)
        vignettePaint.shader = null
    }

    override fun reloadSettings() {
        // No user settings for sky yet
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
