package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Draws dark vignette/gradient overlays on screen edges.
 * 
 * Useful for darkening areas where app icons are placed (typically bottom edge)
 * to improve icon visibility against bright backgrounds.
 * 
 * Each edge (top, bottom, left, right) can be independently configured:
 * - Strength: 0-100 (0 = no vignette, 100 = fully black)
 * - Size: 0-50% of screen dimension
 */
class VignetteLayer(private val context: Context) : SceneLayer {

    private val paint = Paint().apply {
        isAntiAlias = false  // Not needed for gradient fills
    }

    private var width = 0f
    private var height = 0f

    // Edge settings: strength (0-100) and size (0-50% of dimension)
    private var topStrength = 0
    private var topSize = 15
    private var bottomStrength = 30  // Default for app dock area
    private var bottomSize = 20
    private var leftStrength = 0
    private var leftSize = 10
    private var rightStrength = 0
    private var rightSize = 10

    // Cached gradients (recreated on resize or settings change)
    private var topGradient: LinearGradient? = null
    private var bottomGradient: LinearGradient? = null
    private var leftGradient: LinearGradient? = null
    private var rightGradient: LinearGradient? = null
    private var gradientsDirty = true

    init {
        reloadSettings()
    }

    override fun update(state: SimulationState, deltaTimeMs: Long) {
        // No animation needed
    }

    override fun draw(canvas: Canvas, state: SimulationState, parallax: ParallaxCalculator) {
        if (width <= 0 || height <= 0) return
        
        // Rebuild gradients if needed
        if (gradientsDirty) {
            buildGradients()
            gradientsDirty = false
        }

        // Draw top vignette
        if (topStrength > 0 && topGradient != null) {
            val topHeight = height * (topSize / 100f)
            paint.shader = topGradient
            canvas.drawRect(0f, 0f, width, topHeight, paint)
        }

        // Draw bottom vignette
        if (bottomStrength > 0 && bottomGradient != null) {
            val bottomHeight = height * (bottomSize / 100f)
            paint.shader = bottomGradient
            canvas.drawRect(0f, height - bottomHeight, width, height, paint)
        }

        // Draw left vignette
        if (leftStrength > 0 && leftGradient != null) {
            val leftWidth = width * (leftSize / 100f)
            paint.shader = leftGradient
            canvas.drawRect(0f, 0f, leftWidth, height, paint)
        }

        // Draw right vignette
        if (rightStrength > 0 && rightGradient != null) {
            val rightWidth = width * (rightSize / 100f)
            paint.shader = rightGradient
            canvas.drawRect(width - rightWidth, 0f, width, height, paint)
        }

        paint.shader = null
    }

    private fun buildGradients() {
        // Top gradient: black at top fading to transparent
        if (topStrength > 0) {
            val topHeight = height * (topSize / 100f)
            val alpha = (topStrength * 2.55f).toInt().coerceIn(0, 255)
            topGradient = LinearGradient(
                0f, 0f,
                0f, topHeight,
                Color.argb(alpha, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        } else {
            topGradient = null
        }

        // Bottom gradient: transparent at top fading to black at bottom
        if (bottomStrength > 0) {
            val bottomHeight = height * (bottomSize / 100f)
            val alpha = (bottomStrength * 2.55f).toInt().coerceIn(0, 255)
            bottomGradient = LinearGradient(
                0f, height - bottomHeight,
                0f, height,
                Color.TRANSPARENT,
                Color.argb(alpha, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        } else {
            bottomGradient = null
        }

        // Left gradient: black at left fading to transparent
        if (leftStrength > 0) {
            val leftWidth = width * (leftSize / 100f)
            val alpha = (leftStrength * 2.55f).toInt().coerceIn(0, 255)
            leftGradient = LinearGradient(
                0f, 0f,
                leftWidth, 0f,
                Color.argb(alpha, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        } else {
            leftGradient = null
        }

        // Right gradient: transparent fading to black at right
        if (rightStrength > 0) {
            val rightWidth = width * (rightSize / 100f)
            val alpha = (rightStrength * 2.55f).toInt().coerceIn(0, 255)
            rightGradient = LinearGradient(
                width - rightWidth, 0f,
                width, 0f,
                Color.TRANSPARENT,
                Color.argb(alpha, 0, 0, 0),
                Shader.TileMode.CLAMP
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

    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Edge vignette settings (strength 0-100, size 0-50)
        topStrength = prefs.getInt("pref_vignette_top_strength", 0)
        topSize = prefs.getInt("pref_vignette_top_size", 15).coerceIn(5, 50)
        
        bottomStrength = prefs.getInt("pref_vignette_bottom_strength", 30)
        bottomSize = prefs.getInt("pref_vignette_bottom_size", 20).coerceIn(5, 50)
        
        leftStrength = prefs.getInt("pref_vignette_left_strength", 0)
        leftSize = prefs.getInt("pref_vignette_left_size", 10).coerceIn(5, 50)
        
        rightStrength = prefs.getInt("pref_vignette_right_strength", 0)
        rightSize = prefs.getInt("pref_vignette_right_size", 10).coerceIn(5, 50)
        
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
