package info.olli.weatherlwp.rendering.seasonal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import info.olli.weatherlwp.simulation.SimulationSettings

/** St. Patrick's Day shimmering rainbow arc with edge and vertical fades. */
internal class RainbowRenderer {

    private val rainbowColors = intArrayOf(
        Color.rgb(255, 0, 0),      // Red
        Color.rgb(255, 127, 0),    // Orange
        Color.rgb(255, 255, 0),    // Yellow
        Color.rgb(0, 255, 0),      // Green
        Color.rgb(0, 0, 255),      // Blue
        Color.rgb(75, 0, 130),     // Indigo
        Color.rgb(148, 0, 211)     // Violet
    )

    private var rainbowPhase = 0f

    // Pre-allocated paint for rainbow bands (avoid allocation in render loop)
    private val rainbowBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    // Cached rainbow fade shader — only depends on screen dimensions and position settings
    private var cachedRainbowFadeShader: android.graphics.LinearGradient? = null
    private var cachedRainbowCenterX = 0f
    private var cachedRainbowFadeTop = 0f
    private var cachedRainbowFadeBottom = 0f

    private var rainbowPositionX = 50    // 0-100, horizontal position (50 = center)
    private var rainbowPositionY = 50    // 0-100, vertical position (0 = top, 100 = bottom)
    private var rainbowSize = 100        // 0-200, size percentage

    fun applySettings(settings: SimulationSettings) {
        rainbowPositionX = settings.getInt("pref_rainbow_position_x", 50).coerceIn(0, 100)
        rainbowPositionY = settings.getInt("pref_rainbow_position_y", 40).coerceIn(0, 100)
        rainbowSize = settings.getInt("pref_rainbow_size", 100).coerceIn(20, 200)
    }

    fun draw(
        canvas: Canvas,
        screenWidth: Int,
        screenHeight: Int,
        parallaxOffsetX: Float = 0f,
        parallaxOffsetY: Float = 0f
    ) {
        rainbowPhase = (rainbowPhase + 0.02f) % (2f * kotlin.math.PI.toFloat())

        // Position and size based on settings
        val centerX = screenWidth * (rainbowPositionX / 100f) + parallaxOffsetX
        val baseY = screenHeight * (rainbowPositionY / 100f) + parallaxOffsetY
        val sizeMultiplier = rainbowSize / 100f
        // Keep rainbow size stable across portrait/landscape by anchoring to height.
        // Width cap prevents clipping on very narrow canvases.
        val arcRadius = (screenHeight * 0.36f * sizeMultiplier)
            .coerceAtMost(screenWidth * 0.48f)

        // Full semi-circle: 180° to 360° (or equivalently 180° sweep starting at 180°)
        val startAngle = 180f
        val sweepAngle = 180f

        // Create/cache vertical fade shader (only depends on screen dimensions and position settings)
        val fadeHeight = arcRadius * 0.6f  // How much of bottom portion fades
        val fadeTop = baseY - fadeHeight
        val fadeBottom = baseY + arcRadius * 0.1f

        // Only recreate gradient when parameters actually change (resize or settings change)
        val fadeShader = if (cachedRainbowFadeShader != null &&
            cachedRainbowCenterX == centerX &&
            cachedRainbowFadeTop == fadeTop &&
            cachedRainbowFadeBottom == fadeBottom) {
            cachedRainbowFadeShader!!
        } else {
            android.graphics.LinearGradient(
                centerX, fadeTop,
                centerX, fadeBottom,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            ).also {
                cachedRainbowFadeShader = it
                cachedRainbowCenterX = centerX
                cachedRainbowFadeTop = fadeTop
                cachedRainbowFadeBottom = fadeBottom
            }
        }

        // Draw rainbow arc bands from outer (red) to inner (violet)
        for (i in rainbowColors.indices.reversed()) {
            val bandWidth = arcRadius * 0.055f
            val bandRadius = arcRadius - i * bandWidth

            // Shimmer effect - subtle pulsing
            val shimmer = kotlin.math.sin(rainbowPhase + i * 0.5f) * 0.1f + 0.9f

            // Base alpha for the center of the band
            val centerAlpha = (90 * shimmer).toInt()

            // Create a sweep gradient that fades at the edges of the arc
            // The gradient goes around the arc: transparent -> color -> color -> transparent
            val bandColor = rainbowColors[i]
            val transparentColor = Color.argb(0, Color.red(bandColor), Color.green(bandColor), Color.blue(bandColor))
            val visibleColor = Color.argb(centerAlpha, Color.red(bandColor), Color.green(bandColor), Color.blue(bandColor))

            // SweepGradient with fade at edges (positions 0.0=3 o'clock, going counter-clockwise)
            // For 180° arc starting at 180° (9 o'clock), we need fade at 180° and 360°
            // Map: 0.0=0°, 0.25=90°, 0.5=180° (start), 0.75=270° (top), 1.0=360° (end)
            val sweepGradient = android.graphics.SweepGradient(
                centerX, baseY,
                intArrayOf(
                    transparentColor,   // 0° (right) - not visible
                    transparentColor,   // ~170° approaching start
                    visibleColor,       // ~195° just after start - fade in
                    visibleColor,       // ~270° top - full color
                    visibleColor,       // ~345° approaching end - still full
                    transparentColor,   // ~355° fade out
                    transparentColor    // 360° end
                ),
                floatArrayOf(0f, 0.47f, 0.54f, 0.75f, 0.96f, 0.995f, 1f)
            )

            // Combine sweep gradient with vertical fade using ComposeShader
            val compositeShader = android.graphics.ComposeShader(
                sweepGradient,
                fadeShader,
                android.graphics.PorterDuff.Mode.DST_IN
            )

            rainbowBandPaint.strokeWidth = bandWidth
            rainbowBandPaint.shader = compositeShader

            // Draw full semi-circle arc (180° sweep from 180°)
            canvas.drawArc(
                centerX - bandRadius,
                baseY - bandRadius,
                centerX + bandRadius,
                baseY + bandRadius,
                startAngle, sweepAngle, false, rainbowBandPaint
            )
        }

        // Clear shader to avoid affecting other drawing
        rainbowBandPaint.shader = null
    }
}
