package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState

/**
 * Rendering layer for events, determines draw order relative to terrain.
 */
enum class EventLayer {
    /** Far background - drawn before mountains (shooting stars, UFOs, balloons) */
    FAR_SKY,
    /** Mid sky - drawn after mountains but before hills/trees (birds, planes, eagles) */
    MID_SKY,
    /** Ground level - drawn after terrain (foxes, rabbits, butterflies, fireflies) */
    GROUND
}

/**
 * Base interface for random events
 */
interface RandomEvent {
    /**
     * Which layer this event should be drawn in.
     * Default: GROUND for backward compatibility.
     */
    val layer: EventLayer
        get() = EventLayer.GROUND
    
    fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean
    
    /**
     * Draw the event.
     * @param width Extended canvas width (for parallax content spanning beyond screen)
     * @param height Screen height
     * @param extendedOffset Offset to center content (subtract from X to convert extended coords to screen coords)
     */
    fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint, 
             parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
             timeSeconds: Float, ambientColor: Int, extendedOffset: Float = 0f)
}
