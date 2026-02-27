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
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin

/**
 * Renders a river in the foreground terrain area.
 * 
 * Water level is driven by weather history:
 * - Rainfall accumulates water
 * - Snowmelt contributes when temperatures rise
 * - Water drains naturally over time
 * - Can dry out completely after extended dry periods
 *
 * Three switchable rendering styles:
 * 1. **Flat** – simple colored band with subtle shimmer
 * 2. **Wavy** – animated sine-wave surface with foam
 * 3. **Reflective** – gradient with sky color reflection and sparkle
 *
 * The river sits in the lowest valley between foreground hills,
 * drawn after terrain but before vignette.
 */
class RiverLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.RIVER

    companion object {
        private const val TAG = "RiverLayer"
    }

    /** River visual style. */
    enum class RiverStyle {
        FLAT, WAVY, REFLECTIVE;

        companion object {
            fun fromString(s: String): RiverStyle = when (s.lowercase()) {
                "flat" -> FLAT
                "wavy" -> WAVY
                "reflective" -> REFLECTIVE
                else -> WAVY
            }
        }
    }

    // Pre-allocated drawing objects
    private val riverPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val foamPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 2f
        color = Color.argb(120, 255, 255, 255)
    }
    private val sparkPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val riverPath = Path()
    private val foamPath = Path()

    // Dimensions
    private var width = 0f
    private var height = 0f
    private var extendedWidth = 0f
    private var extendedOffset = 0f

    // Settings
    private var enabled = PreferenceDefaults.RIVER_ENABLED
    private var style = RiverStyle.WAVY
    private var riverWidthPercent = PreferenceDefaults.RIVER_WIDTH / 100f
    private var opacity = PreferenceDefaults.RIVER_OPACITY / 100f
    private var flowSpeed = PreferenceDefaults.RIVER_SPEED / 100f

    // Animation phase
    private var flowPhase = 0f

    // River base color palette
    private val WATER_DAY = Color.rgb(70, 130, 180)     // Steel blue
    private val WATER_DAWN = Color.rgb(140, 120, 140)    // Muted lavender
    private val WATER_DUSK = Color.rgb(120, 80, 90)      // Dark rose
    private val WATER_NIGHT = Color.rgb(20, 30, 50)      // Deep dark blue
    private val WATER_DEEP = Color.rgb(30, 60, 90)       // Darker shade for depth
    private val ICE_COLOR = Color.rgb(200, 220, 240)      // Icy surface color
    private val FROZEN_THRESHOLD = 0.05f                  // Snow level above which river freezes

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        extendedWidth = RenderConstants.getExtendedWidth(w, h)
        extendedOffset = RenderConstants.getExtendedOffset(w, extendedWidth)
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!enabled || state.riverLevel < 0.01f) return
        // Advance flow animation
        flowPhase += deltaTimeMs / 1000f * flowSpeed * 2f
        if (flowPhase > 1000f) flowPhase -= 1000f  // Prevent float overflow
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (!enabled || width <= 0 || height <= 0) return

        val waterLevel = state.riverLevel
        if (waterLevel < 0.01f) return  // River is dry

        // River position: sits in the lower portion of the screen (foreground terrain area)
        val riverBaseY = height * 0.88f  // Base Y position
        val maxRiverHeight = height * 0.08f * riverWidthPercent  // Max river visual height
        val riverHeight = maxRiverHeight * waterLevel.coerceIn(0f, 1f)

        if (riverHeight < 1f) return  // Too small to draw

        // Parallax offset for river (slightly behind foreground hills)
        val px = parallaxCalculator.getOffsetXForDepth(RenderConstants.PARALLAX_HILLS_MAX - 0.05f)
        val py = parallaxCalculator.getOffsetYForDepth(RenderConstants.PARALLAX_HILLS_MAX - 0.05f)

        canvas.save()
        canvas.translate(px, py)

        // Determine if river should be frozen
        val isFrozen = state.snowLevel > FROZEN_THRESHOLD && state.temperatureCelsius.let {
            !it.isNaN() && it < 0f
        }

        // Get water color based on time of day
        val waterColor = getWaterColor(state, isFrozen)

        val riverTop = riverBaseY - riverHeight
        val riverBottom = riverBaseY

        when (style) {
            RiverStyle.FLAT -> drawFlat(canvas, state, riverTop, riverBottom, waterColor, isFrozen)
            RiverStyle.WAVY -> drawWavy(canvas, state, riverTop, riverBottom, waterColor, waterLevel, isFrozen)
            RiverStyle.REFLECTIVE -> drawReflective(canvas, state, riverTop, riverBottom, waterColor, waterLevel, isFrozen)
        }

        canvas.restore()
    }

    // ========================= Flat Style =========================

    private fun drawFlat(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f

        riverPaint.shader = null
        riverPaint.color = Color.argb(
            (Color.alpha(color) * opacity).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color)
        )

        canvas.drawRect(left, top, right, bottom, riverPaint)

        // Subtle shimmer line
        if (!isFrozen) {
            val shimmerY = top + (bottom - top) * 0.3f
            val shimmerAlpha = (40 + 20 * sin(flowPhase * 3f)).toInt().coerceIn(20, 80)
            foamPaint.color = Color.argb(shimmerAlpha, 255, 255, 255)
            foamPaint.strokeWidth = 1.5f
            canvas.drawLine(left, shimmerY, right, shimmerY, foamPaint)
        }
    }

    // ========================= Wavy Style =========================

    private fun drawWavy(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, waterLevel: Float, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f
        val riverWidth = right - left

        // Build wavy surface path
        riverPath.reset()
        riverPath.moveTo(left, bottom)

        val waveAmplitude = if (isFrozen) 1f else (3f + waterLevel * 5f)  // Bigger waves with more water
        val waveFrequency = 0.02f  // Waves per pixel
        val numPoints = 60

        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth

            // Multiple wave layers for organic surface
            val wave1 = sin((x * waveFrequency + flowPhase * 1.5f).toDouble()).toFloat()
            val wave2 = sin((x * waveFrequency * 2.3f + flowPhase * 0.8f + 1.5f).toDouble()).toFloat() * 0.4f
            val wave3 = sin((x * waveFrequency * 0.5f + flowPhase * 0.3f + 3.0f).toDouble()).toFloat() * 0.6f

            val waveY = top + (wave1 + wave2 + wave3) * waveAmplitude

            if (i == 0) riverPath.moveTo(x, bottom)
            riverPath.lineTo(x, waveY)
        }

        riverPath.lineTo(right, bottom)
        riverPath.close()

        // Gradient from surface to depth
        val alphaMultiplier = (opacity * 255).toInt().coerceIn(0, 255)
        riverPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            Color.argb(alphaMultiplier, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(alphaMultiplier, Color.red(WATER_DEEP), Color.green(WATER_DEEP), Color.blue(WATER_DEEP)),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(riverPath, riverPaint)
        riverPaint.shader = null

        // Draw foam lines on surface (not when frozen)
        if (!isFrozen && waterLevel > 0.2f) {
            drawFoam(canvas, left, right, top, waveAmplitude)
        }
    }

    // ========================= Reflective Style =========================

    private fun drawReflective(
        canvas: Canvas, state: SimulationState,
        top: Float, bottom: Float, color: Int, waterLevel: Float, isFrozen: Boolean
    ) {
        val left = -extendedOffset - width * 0.1f
        val right = width + extendedOffset + width * 0.1f

        // Sky reflection color (lighter/bluer version of water)
        val reflectionColor = if (isFrozen) {
            ICE_COLOR
        } else {
            val skyInfluence = 0.3f
            RenderConstants.lerpColor(color, getSkyReflectionColor(state), skyInfluence)
        }

        val alphaMultiplier = (opacity * 255).toInt().coerceIn(0, 255)

        // Three-stop gradient: reflection at top, water color in middle, dark at bottom
        riverPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(alphaMultiplier,
                    Color.red(reflectionColor), Color.green(reflectionColor), Color.blue(reflectionColor)),
                Color.argb(alphaMultiplier,
                    Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(alphaMultiplier,
                    Color.red(WATER_DEEP), Color.green(WATER_DEEP), Color.blue(WATER_DEEP))
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )

        // Slight wave on surface
        riverPath.reset()
        val riverWidth = right - left
        val numPoints = 40
        val waveAmp = if (isFrozen) 0.5f else 2f + waterLevel * 3f

        riverPath.moveTo(left, bottom)
        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth
            val wave = sin((x * 0.015f + flowPhase).toDouble()).toFloat() * waveAmp
            riverPath.lineTo(x, top + wave)
        }
        riverPath.lineTo(right, bottom)
        riverPath.close()

        canvas.drawPath(riverPath, riverPaint)
        riverPaint.shader = null

        // Sparkle effect (sun/moon reflection on water)
        if (!isFrozen && waterLevel > 0.1f) {
            drawSparkles(canvas, state, left, right, top, bottom)
        }
    }

    // ========================= Shared Effects =========================

    private fun drawFoam(canvas: Canvas, left: Float, right: Float, surfaceY: Float, amplitude: Float) {
        foamPath.reset()
        foamPaint.color = Color.argb(60, 255, 255, 255)
        foamPaint.strokeWidth = 1.5f

        val riverWidth = right - left
        val numPoints = 30

        for (i in 0..numPoints) {
            val ratio = i.toFloat() / numPoints
            val x = left + ratio * riverWidth
            val wave = sin((x * 0.025f + flowPhase * 2f + 0.5f).toDouble()).toFloat() * amplitude * 0.5f
            val y = surfaceY + wave + amplitude * 0.8f

            if (i == 0) foamPath.moveTo(x, y) else foamPath.lineTo(x, y)
        }

        canvas.drawPath(foamPath, foamPaint)
    }

    private fun drawSparkles(
        canvas: Canvas, state: SimulationState,
        left: Float, right: Float, top: Float, bottom: Float
    ) {
        val lightLevel = state.getLightLevel()
        if (lightLevel < 0.2f && state.moonIllumination < 0.3f) return  // Too dark for sparkles

        val sparkleIntensity = if (state.isNight) {
            state.moonIllumination * 0.5f
        } else {
            lightLevel * 0.8f
        }

        val sparkleAlpha = (sparkleIntensity * 180).toInt().coerceIn(0, 200)
        sparkPaint.color = Color.argb(sparkleAlpha, 255, 255, 240)

        // Draw scattered sparkle dots
        val riverWidth = right - left
        val riverHeight = bottom - top
        val seed = (flowPhase * 100).toInt()         
        val numSparkles = (8 * sparkleIntensity).toInt().coerceIn(2, 15)

        for (i in 0 until numSparkles) {
            // Pseudo-random but deterministic positions that shift with flow
            val hash = ((i * 7919 + seed) % 10000) / 10000f
            val hash2 = ((i * 6271 + seed * 3) % 10000) / 10000f

            val x = left + hash * riverWidth
            val y = top + hash2 * riverHeight * 0.5f  // Sparkles near surface

            // Twinkle: vary size/alpha based on flow phase
            val twinkle = sin((flowPhase * 5f + i * 2.3f).toDouble()).toFloat()
            if (twinkle > 0.3f) {
                val radius = 1.5f + twinkle * 2f
                canvas.drawCircle(x, y, radius, sparkPaint)
            }
        }
    }

    // ========================= Color Helpers =========================

    private fun getWaterColor(state: SimulationState, isFrozen: Boolean): Int {
        if (isFrozen) return ICE_COLOR

        return when {
            state.isNight -> WATER_NIGHT
            state.isDawn -> RenderConstants.lerpColor(WATER_NIGHT, WATER_DAWN, 0.5f)
            state.isDusk -> RenderConstants.lerpColor(WATER_DAY, WATER_DUSK, 0.5f)
            else -> WATER_DAY
        }
    }

    private fun getSkyReflectionColor(state: SimulationState): Int {
        return when {
            state.isNight -> Color.rgb(15, 20, 40)
            state.isDawn -> Color.rgb(180, 150, 130)
            state.isDusk -> Color.rgb(160, 100, 80)
            else -> Color.rgb(135, 190, 230)  // Light sky blue
        }
    }

    // ========================= Settings =========================

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        enabled = prefs.getBoolean("pref_river_enabled", PreferenceDefaults.RIVER_ENABLED)
        style = RiverStyle.fromString(
            prefs.getString("pref_river_style", PreferenceDefaults.RIVER_STYLE) ?: PreferenceDefaults.RIVER_STYLE
        )
        riverWidthPercent = prefs.getInt("pref_river_width", PreferenceDefaults.RIVER_WIDTH) / 100f
        opacity = prefs.getInt("pref_river_opacity", PreferenceDefaults.RIVER_OPACITY) / 100f
        flowSpeed = prefs.getInt("pref_river_speed", PreferenceDefaults.RIVER_SPEED) / 100f
    }

    override fun destroy() {
        // No resources to clean up
    }

    /** Check if the river is currently visible. */
    fun isVisible(): Boolean = enabled
}
