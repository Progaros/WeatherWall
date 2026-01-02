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
    /** Ground level - drawn interleaved with terrain based on depthFactor */
    GROUND
}

/**
 * Function type for getting hill Y position at a given X ratio and depth factor.
 * @param xRatio X position as ratio of extended width (0.0 to 1.0)
 * @param depthFactor Depth layer to sample (0.0=background to 1.0=foreground)
 * @return Y position as ratio of screen height (0.0=top, 1.0=bottom)
 */
typealias HillHeightProvider = (xRatio: Float, depthFactor: Float) -> Float

/**
 * Info about a hill position, including both Y and the actual hill's depthFactor.
 * Used to ensure ground events are drawn at the correct depth layer.
 */
data class HillInfo(
    /** Y position as ratio of screen height (0.0=top, 1.0=bottom) */
    val yRatio: Float,
    /** The actual hill's depthFactor (for correct draw ordering) */
    val actualDepthFactor: Float
)

/**
 * Function type for getting full hill info (Y position and actual depth factor).
 * @param xRatio X position as ratio of extended width (0.0 to 1.0)
 * @param requestedDepthFactor Approximate depth to find closest hill for
 * @return HillInfo with Y position and actual hill depthFactor
 */
typealias HillInfoProvider = (xRatio: Float, requestedDepthFactor: Float) -> HillInfo

/**
 * Base interface for random events.
 * 
 * Events are synchronized across preview and wallpaper by using absolute time
 * rather than accumulated delta time. The spawnTimeMs is set when the event
 * is created, and progress is derived from (currentTime - spawnTime).
 */
interface RandomEvent {
    /**
     * Which layer this event should be drawn in.
     * Default: GROUND for backward compatibility.
     */
    val layer: EventLayer
        get() = EventLayer.GROUND
    
    /**
     * Depth factor for ground events, determines draw order relative to trees.
     * Range: 0.0 (far background) to 1.0 (near foreground)
     * For hills/ground: typically 0.50 (back hills) to 0.80 (front hills)
     * Default: 0.65f (middle ground)
     * Only used for GROUND layer events.
     */
    val depthFactor: Float
        get() = 0.65f
    
    /**
     * The absolute time (epoch ms) when this event was spawned.
     * Used to compute deterministic progress across multiple scene instances.
     */
    val spawnTimeMs: Long
    
    /**
     * Duration of this event in milliseconds.
     * Used to determine when the event should be removed.
     */
    val durationMs: Long
    
    /**
     * Check if the event is still active based on absolute time.
     * Default implementation uses spawnTimeMs and durationMs.
     */
    fun isActive(absoluteTimeMs: Long): Boolean {
        return absoluteTimeMs - spawnTimeMs < durationMs
    }
    
    /**
     * Get the progress (0.0 to 1.0+) of this event based on absolute time.
     */
    fun getProgress(absoluteTimeMs: Long): Float {
        return (absoluteTimeMs - spawnTimeMs) / durationMs.toFloat()
    }
    
    /**
     * Get the current Y position ratio of this event for Y-based draw ordering.
     * Only applicable for GROUND layer events.
     * @param absoluteTimeMs Current time for position calculation
     * @param hillHeightProvider Function to get hill Y at given X and depth
     * @return Y position as ratio of screen height (0.0=top, 1.0=bottom)
     */
    fun getCurrentYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        return 0.85f  // Default fallback - subclasses should override
    }

    /**
     * Update is now optional - events can be stateless.
     * Default implementation just checks if still active.
     */
    fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        return isActive(state.absoluteTimeMs)
    }

    /**
     * Draw the event.
     * @param width Extended canvas width (for parallax content spanning beyond screen)
     * @param height Screen height
     * @param extendedOffset Offset to center content (subtract from X to convert extended coords to screen coords)
     * @param absoluteTimeMs Current absolute time in milliseconds (use for elapsed time calculation)
     * @param hillHeightProvider Optional function to get hill Y at given X and depth (for ground events)
     */
    fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint, 
             parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
             timeSeconds: Float, ambientColor: Int, extendedOffset: Float = 0f,
             absoluteTimeMs: Long = System.currentTimeMillis(),
             hillHeightProvider: HillHeightProvider? = null)
}
