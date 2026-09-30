package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationSettings
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Draws dark vignette/gradient overlays on screen edges.
 * 
 * Useful for darkening areas where app icons are placed (typically bottom edge)
 * to improve icon visibility against bright backgrounds.
 * 
 * Each edge (top, bottom, left, right) has three controls:
 * - Distance: How far the vignette extends into screen (0-50% of dimension, 0=off)
 * - Fade: Percentage of the vignette that fades to transparent (5-50%)
 * - Alpha: Opacity of the solid/dark part (0-100%)
 * 
 * Example: distance=30%, fade=20%, alpha=80%
 * - Vignette extends 30% into screen
 * - Bottom 80% of vignette (24% of screen) is at 80% opacity (solid dark)
 * - Top 20% of vignette (6% of screen) fades from 80% to 0% opacity
 */
class VignetteLayer(private val context: Context) : SceneLayer {
    override val layerType = LayerType.VIGNETTE

    private val paint = Paint().apply {
        isAntiAlias = false  // Not needed for gradient fills
        isDither = true      // Full-screen alpha ramps band badly without it
    }

    private var width = 0f
    private var height = 0f

    // Master toggle
    private var vignetteEnabled = true

    // Edge settings: distance (0-50% of dimension), fade (5-50%), alpha (0-100%)
    private var topDistance = 0      // 0 = off
    private var topFade = 20
    private var topAlpha = 60
    
    private var bottomDistance = 25  // Default for app dock area
    private var bottomFade = 15
    private var bottomAlpha = 70
    
    private var leftDistance = 0
    private var leftFade = 20
    private var leftAlpha = 50
    
    private var rightDistance = 0
    private var rightFade = 20
    private var rightAlpha = 50

    // Cached gradients (recreated on resize or settings change)
    private var topGradient: LinearGradient? = null
    private var bottomGradient: LinearGradient? = null
    private var leftGradient: LinearGradient? = null
    private var rightGradient: LinearGradient? = null
    private var gradientsDirty = true

    init {
        reloadSettings(SimulationSettings.fromSnapshot(emptyMap<String, Any?>()))
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // No animation needed
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !vignetteEnabled) return
        
        // Rebuild gradients if needed
        if (gradientsDirty) {
            buildGradients()
            gradientsDirty = false
        }

        // Draw top vignette
        if (topDistance > 0 && topGradient != null) {
            val topHeight = height * (topDistance / 100f)
            paint.shader = topGradient
            canvas.drawRect(0f, 0f, width, topHeight, paint)
        }

        // Draw bottom vignette
        if (bottomDistance > 0 && bottomGradient != null) {
            val bottomHeight = height * (bottomDistance / 100f)
            paint.shader = bottomGradient
            canvas.drawRect(0f, height - bottomHeight, width, height, paint)
        }

        // Draw left vignette
        if (leftDistance > 0 && leftGradient != null) {
            val leftWidth = width * (leftDistance / 100f)
            paint.shader = leftGradient
            canvas.drawRect(0f, 0f, leftWidth, height, paint)
        }

        // Draw right vignette
        if (rightDistance > 0 && rightGradient != null) {
            val rightWidth = width * (rightDistance / 100f)
            paint.shader = rightGradient
            canvas.drawRect(width - rightWidth, 0f, width, height, paint)
        }

        paint.shader = null
    }

    /**
     * Build gradient for an edge.
     * 
     * @param fadePercent Percentage of distance that fades (5-50)
     * @param alpha Opacity of solid part (0-255)
     * @param startX Start X of gradient
     * @param startY Start Y of gradient
     * @param endX End X of gradient
     * @param endY End Y of gradient
     * @param inward If true, gradient goes from transparent to solid (top/left edges)
     *               If false, gradient goes from solid to transparent (bottom/right edges)
     */
    private fun buildEdgeGradient(
        fadePercent: Int,
        alpha: Int,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        inward: Boolean
    ): LinearGradient {
        // fadePercent of the distance fades, rest is solid
        val fadeRatio = (fadePercent / 100f).coerceIn(0.05f, 0.95f)
        val solidRatio = 1f - fadeRatio
        
        val solidColor = Color.argb(alpha, 0, 0, 0)
        val transparent = Color.TRANSPARENT
        
        return if (inward) {
            // For top/left: starts transparent (at screen edge fade zone), becomes solid
            // Gradient: transparent -> solid (at fade boundary) -> solid (at inner edge)
            LinearGradient(
                startX, startY, endX, endY,
                intArrayOf(transparent, solidColor, solidColor),
                floatArrayOf(0f, fadeRatio, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            // For bottom/right: starts solid (at screen edge), fades to transparent
            // Gradient: solid -> solid (at solid boundary) -> transparent
            LinearGradient(
                startX, startY, endX, endY,
                intArrayOf(solidColor, solidColor, transparent),
                floatArrayOf(0f, solidRatio, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun buildGradients() {
        // Top: gradient goes DOWN from screen top, fade is at TOP of vignette region
        if (topDistance > 0) {
            val topHeight = height * (topDistance / 100f)
            val alpha = (topAlpha * 2.55f).toInt().coerceIn(0, 255)
            // Top vignette: dark at screen top, fades to transparent as it goes down
            topGradient = buildEdgeGradient(
                topFade, alpha,
                0f, 0f, 0f, topHeight,
                inward = false  // solid at top (screen edge), transparent toward center
            )
        } else {
            topGradient = null
        }

        // Bottom: gradient goes UP from screen bottom, fade is at TOP of vignette region
        if (bottomDistance > 0) {
            val bottomHeight = height * (bottomDistance / 100f)
            val alpha = (bottomAlpha * 2.55f).toInt().coerceIn(0, 255)
            // Bottom vignette: dark at screen bottom, fades to transparent as it goes up
            bottomGradient = buildEdgeGradient(
                bottomFade, alpha,
                0f, height, 0f, height - bottomHeight,
                inward = false  // solid at bottom (screen edge), transparent toward center
            )
        } else {
            bottomGradient = null
        }

        // Left: gradient goes RIGHT from screen left, fade is at RIGHT of vignette region
        if (leftDistance > 0) {
            val leftWidth = width * (leftDistance / 100f)
            val alpha = (leftAlpha * 2.55f).toInt().coerceIn(0, 255)
            leftGradient = buildEdgeGradient(
                leftFade, alpha,
                0f, 0f, leftWidth, 0f,
                inward = false  // solid at left (screen edge), transparent toward center
            )
        } else {
            leftGradient = null
        }

        // Right: gradient goes LEFT from screen right, fade is at LEFT of vignette region
        if (rightDistance > 0) {
            val rightWidth = width * (rightDistance / 100f)
            val alpha = (rightAlpha * 2.55f).toInt().coerceIn(0, 255)
            rightGradient = buildEdgeGradient(
                rightFade, alpha,
                width, 0f, width - rightWidth, 0f,
                inward = false  // solid at right (screen edge), transparent toward center
            )
        } else {
            rightGradient = null
        }
    }

    override fun resize(w: Float, h: Float) {
        width = w
        height = h
        gradientsDirty = true
    }

    override fun reloadSettings(settings: SimulationSettings) {
        vignetteEnabled = settings.getBoolean("pref_vignette_enabled", PreferenceDefaults.VIGNETTE_ENABLED)
        
        // Top edge vignette
        topDistance = settings.getInt("pref_vignette_top_distance", PreferenceDefaults.VIGNETTE_TOP_DISTANCE).coerceIn(0, 50)
        topFade = settings.getInt("pref_vignette_top_fade", PreferenceDefaults.VIGNETTE_TOP_FADE).coerceIn(5, 50)
        topAlpha = settings.getInt("pref_vignette_top_alpha", PreferenceDefaults.VIGNETTE_TOP_ALPHA).coerceIn(0, 100)
        
        // Bottom edge vignette (default for app dock)
        bottomDistance = settings.getInt("pref_vignette_bottom_distance", PreferenceDefaults.VIGNETTE_BOTTOM_DISTANCE).coerceIn(0, 50)
        bottomFade = settings.getInt("pref_vignette_bottom_fade", PreferenceDefaults.VIGNETTE_BOTTOM_FADE).coerceIn(5, 50)
        bottomAlpha = settings.getInt("pref_vignette_bottom_alpha", PreferenceDefaults.VIGNETTE_BOTTOM_ALPHA).coerceIn(0, 100)
        
        // Left edge vignette
        leftDistance = settings.getInt("pref_vignette_left_distance", PreferenceDefaults.VIGNETTE_LEFT_DISTANCE).coerceIn(0, 50)
        leftFade = settings.getInt("pref_vignette_left_fade", PreferenceDefaults.VIGNETTE_LEFT_FADE).coerceIn(5, 50)
        leftAlpha = settings.getInt("pref_vignette_left_alpha", PreferenceDefaults.VIGNETTE_LEFT_ALPHA).coerceIn(0, 100)
        
        // Right edge vignette
        rightDistance = settings.getInt("pref_vignette_right_distance", PreferenceDefaults.VIGNETTE_RIGHT_DISTANCE).coerceIn(0, 50)
        rightFade = settings.getInt("pref_vignette_right_fade", PreferenceDefaults.VIGNETTE_RIGHT_FADE).coerceIn(5, 50)
        rightAlpha = settings.getInt("pref_vignette_right_alpha", PreferenceDefaults.VIGNETTE_RIGHT_ALPHA).coerceIn(0, 100)
        
        gradientsDirty = true
    }

    override fun destroy() {
        // Cleanup
        topGradient = null
        bottomGradient = null
        leftGradient = null
        rightGradient = null
    }
}
