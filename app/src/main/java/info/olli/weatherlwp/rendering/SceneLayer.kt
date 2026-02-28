package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Identifies each layer type for draw-order logic without identity checks.
 */
enum class LayerType {
    SKY, CLOUDS, MOUNTAINS, TERRAIN, RIVER,
    RANDOM_EVENTS, SEASONAL_EVENTS, WEATHER, VIGNETTE, DEBUG_OVERLAY
}

/**
 * Interface for a distinct layer in the parallax scene.
 * Each layer is responsible for its own rendering and parallax handling.
 * 
 * Layers receive a SimulationState that contains:
 * - Time (for animations and day/night)
 * - Weather (for effects)
 * - Wind (for tree sway and cloud movement)
 * - Screen dimensions
 */
interface SceneLayer {
    /** Identifies this layer's type for draw-order dispatching. */
    val layerType: LayerType
    /**
     * Called when the surface dimensions change.
     */
    fun resize(w: Float, h: Float)

    /**
     * Called every frame to update state (animations, physics).
     * @param state Current simulation state
     * @param deltaTimeMs Time since last frame in milliseconds
     */
    fun update(state: SimulationState, deltaTimeMs: Long)

    /**
     * Draw the layer.
     * @param canvas The canvas to draw on
     * @param state Current simulation state
     * @param parallaxCalculator The calculator to retrieve depth-based offsets
     */
    fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator)

    /**
     * Reload preferences/settings.
     */
    fun reloadSettings()
    
    /**
     * Clean up resources.
     */
    fun destroy()
}
