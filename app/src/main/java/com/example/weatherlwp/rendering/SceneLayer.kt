package com.example.weatherlwp.rendering

import android.graphics.Canvas
import com.example.weatherlwp.parallax.ParallaxCalculator

/**
 * Interface for a distinct layer in the parallax scene.
 * Each layer is responsible for its own rendering and parallax handling.
 */
interface SceneLayer {
    /**
     * Called when the surface dimensions change.
     */
    fun resize(w: Float, h: Float)

    /**
     * Called every frame to update state (animations, physics).
     * @param deltaTimeMs Time since last frame in milliseconds
     */
    fun update(deltaTimeMs: Long)

    /**
     * Draw the layer.
     * @param canvas The canvas to draw on
     * @param parallaxCalculator The calculator to retrieve depth-based offsets
     */
    fun draw(canvas: Canvas, parallaxCalculator: ParallaxCalculator)

    /**
     * Update theme/weather settings.
     */
    fun updateTheme(theme: String, isNight: Boolean)
    
    /**
     * Reload preferences/settings.
     */
    fun reloadSettings()
    
    /**
     * Clean up resources.
     */
    fun destroy()
}
