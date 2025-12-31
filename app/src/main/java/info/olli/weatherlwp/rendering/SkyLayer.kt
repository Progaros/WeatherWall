package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the sky background with gradient colors, stars, sun, moon, and vignette.
 * 
 * Uses SimulationState for:
 * - Time of day (colors, star visibility, sun/moon position)
 * - absoluteTimeMs (star twinkle animation)
 * 
 * Uses RenderConstants for:
 * - Sky colors for different times
 * - Parallax factors
 * - Time thresholds
 * 
 * Design notes:
 * - Night scenes should have darker corners (vignette effect)
 * - Sun follows arc across sky during day
 * - Moon visible at night
 */
class SkyLayer(private val context: Context? = null) : SceneLayer {
    private val skyPaint = Paint()
    private val starPaint = Paint().apply { 
        color = Color.WHITE 
        isAntiAlias = true 
        style = Paint.Style.FILL 
    }
    private val vignettePaint = Paint().apply {
        isAntiAlias = true
    }
    private val sunPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val moonPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private var width = 0f
    private var height = 0f
    
    // Stars - generated once with fixed seed for consistency
    private val stars = ArrayList<FloatArray>()
    
    // Current 3-stop gradient colors (Top, Middle, Horizon)
    private var skyTop = Color.BLUE
    private var skyMid = Color.CYAN
    private var skyHorizon = Color.WHITE
    
    // Settings
    private var sunEnabled = true
    private var moonEnabled = true
    private var celestialSize = 1.0f

    init {
        generateStars()
        reloadSettings()
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
        // Always use a 3-stop LinearGradient for the sky
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
            // This creates the "horizon glow" effect
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
        
        // Sun and Moon with subtle parallax
        val celestialParallaxX = px * RenderConstants.PARALLAX_SKY * 0.5f
        val celestialParallaxY = py * RenderConstants.PARALLAX_SKY * 0.5f
        
        if (sunEnabled) {
            drawSun(canvas, state, celestialParallaxX, celestialParallaxY)
        }
        
        if (moonEnabled) {
            drawMoon(canvas, state, celestialParallaxX, celestialParallaxY)
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

    /**
     * Draw the sun as a bright circle following an arc across the sky.
     * Position is based on time of day - rises in east (left), sets in west (right).
     * Arc goes from bottom left at sunrise (6am), peaks at noon (12pm), to bottom right at sunset (6pm).
     */
    private fun drawSun(canvas: Canvas, state: SimulationState, parallaxX: Float, parallaxY: Float) {
        val hour = state.timeOfDayHours
        
        // Sun visible from 6am to 18pm (6pm)
        if (hour < 6f || hour > 18f) return
        
        // Calculate position along arc (0 = sunrise at 6am, 1 = sunset at 6pm)
        val dayProgress = (hour - 6f) / 12f  // 0 to 1 over daytime
        
        // X position: left to right across screen
        val sunX = width * (0.1f + dayProgress * 0.8f)
        
        // Y position: parabolic arc, highest at noon
        // At progress 0.5 (noon), should be at top. At 0 and 1, near horizon.
        val arcHeight = height * 0.7f  // How high the arc goes
        val baseY = height * 0.85f  // Horizon level
        val arcProgress = 1f - 4f * (dayProgress - 0.5f) * (dayProgress - 0.5f)  // Parabola: 0 at edges, 1 at center
        val sunY = baseY - arcHeight * arcProgress
        
        // Sun size
        val baseRadius = kotlin.math.min(width, height) * 0.04f
        val radius = baseRadius * celestialSize
        
        // Sun glow gradient (warm yellow/orange center, fading out)
        val glowRadius = radius * 3f
        val sunGradient = RadialGradient(
            sunX + parallaxX, sunY + parallaxY, glowRadius,
            intArrayOf(
                Color.argb(255, 255, 250, 200),  // Bright warm center
                Color.argb(200, 255, 230, 150),
                Color.argb(100, 255, 200, 100),
                Color.argb(0, 255, 180, 50)
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        
        sunPaint.shader = sunGradient
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, glowRadius, sunPaint)
        
        // Solid sun core
        sunPaint.shader = null
        sunPaint.color = Color.argb(255, 255, 255, 220)
        canvas.drawCircle(sunX + parallaxX, sunY + parallaxY, radius, sunPaint)
    }

    /**
     * Draw the moon as a pale circle with crescent effect.
     * Visible at night, follows arc opposite to sun.
     * Crescent phase varies with position in the night cycle.
     */
    private fun drawMoon(canvas: Canvas, state: SimulationState, parallaxX: Float, parallaxY: Float) {
        val hour = state.timeOfDayHours
        
        // Moon visible from 18pm (6pm) to 6am
        if (hour >= 6f && hour <= 18f) return
        
        // Calculate position along arc (normalize night hours)
        // Night runs from 18 to 24 (6pm to midnight) and 0 to 6 (midnight to 6am)
        val nightProgress = if (hour >= 18f) {
            (hour - 18f) / 12f  // 18-24 maps to 0-0.5
        } else {
            (hour + 6f) / 12f  // 0-6 maps to 0.5-1
        }
        
        // X position: left to right across screen
        val moonX = width * (0.1f + nightProgress * 0.8f)
        
        // Y position: parabolic arc, highest at midnight
        val arcHeight = height * 0.6f  // Moon arc is a bit lower than sun
        val baseY = height * 0.85f
        val arcProgress = 1f - 4f * (nightProgress - 0.5f) * (nightProgress - 0.5f)
        val moonY = baseY - arcHeight * arcProgress
        
        // Moon size
        val baseRadius = kotlin.math.min(width, height) * 0.03f
        val radius = baseRadius * celestialSize
        
        // Moon glow gradient (soft silver/blue)
        val glowRadius = radius * 2.5f
        val moonGradient = RadialGradient(
            moonX + parallaxX, moonY + parallaxY, glowRadius,
            intArrayOf(
                Color.argb(150, 220, 230, 255),  // Pale silver center
                Color.argb(80, 180, 200, 230),
                Color.argb(20, 150, 170, 200),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        
        moonPaint.shader = moonGradient
        canvas.drawCircle(moonX + parallaxX, moonY + parallaxY, glowRadius, moonPaint)
        
        // Solid moon disc (lit portion)
        moonPaint.shader = null
        moonPaint.color = Color.argb(255, 245, 248, 255)  // Bright moon white
        canvas.drawCircle(moonX + parallaxX, moonY + parallaxY, radius, moonPaint)
        
        // Crescent shadow - simulates shadow side of moon
        // Shadow circle is same size but offset to the upper-right to create crescent
        // Get night sky color to blend the shadow naturally
        val skyR = Color.red(skyTop)
        val skyG = Color.green(skyTop)
        val skyB = Color.blue(skyTop)
        moonPaint.color = Color.argb(255, skyR, skyG, skyB)
        
        // Offset the shadow circle to create crescent - position to the right and slightly up
        val shadowOffset = radius * 0.65f
        canvas.drawCircle(
            moonX + parallaxX + shadowOffset, 
            moonY + parallaxY - shadowOffset * 0.3f, 
            radius * 0.92f,  // Slightly smaller to show thin crescent edge
            moonPaint
        )
    }

    override fun reloadSettings() {
        context?.let { ctx ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            sunEnabled = prefs.getBoolean("pref_sun_enabled", true)
            moonEnabled = prefs.getBoolean("pref_moon_enabled", true)
            celestialSize = prefs.getInt("pref_celestial_size", 100) / 100f
        }
    }

    override fun destroy() {
        // Nothing to destroy
    }
}
