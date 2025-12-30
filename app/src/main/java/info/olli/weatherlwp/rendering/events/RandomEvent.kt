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
    fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint, 
             parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
             timeSeconds: Float, ambientColor: Int)
}
