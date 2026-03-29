package info.olli.weatherlwp.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.events.AirplaneEvent
import info.olli.weatherlwp.rendering.events.AlienEvent
import info.olli.weatherlwp.rendering.events.BatEvent
import info.olli.weatherlwp.rendering.events.BirdFlockEvent
import info.olli.weatherlwp.rendering.events.ButterflyEvent
import info.olli.weatherlwp.rendering.events.DeerEvent
import info.olli.weatherlwp.rendering.events.DragonflyEvent
import info.olli.weatherlwp.rendering.events.DuckEvent
import info.olli.weatherlwp.rendering.events.EagleEvent
import info.olli.weatherlwp.rendering.events.EventLayer
import info.olli.weatherlwp.rendering.events.FireflyEvent
import info.olli.weatherlwp.rendering.events.FishJumpEvent
import info.olli.weatherlwp.rendering.events.FoxEvent
import info.olli.weatherlwp.rendering.events.FrogEvent
import info.olli.weatherlwp.rendering.events.HillHeightProvider
import info.olli.weatherlwp.rendering.events.HotAirBalloonEvent
import info.olli.weatherlwp.rendering.events.OwlEvent
import info.olli.weatherlwp.rendering.events.RabbitEvent
import info.olli.weatherlwp.rendering.events.RandomEvent
import info.olli.weatherlwp.rendering.events.ShootingStarEvent
import info.olli.weatherlwp.rendering.events.SnowflakeEvent
import info.olli.weatherlwp.rendering.events.SquirrelEvent
import kotlin.random.Random

/**
 * Event preview that renders with the same runtime event classes used by the wallpaper.
 * This avoids drift between settings previews and live rendering logic.
 */
class EventPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class EventType {
        // Sky events
        AIRPLANE, BIRDS, EAGLE, OWL, BALLOON, SHOOTING_STAR, UFO,
        // Ground events
        BUTTERFLY, DRAGONFLY, RABBIT, FOX, SQUIRREL, FIREFLIES, SNOWFLAKE, BAT, DEER,
        // Water events
        FISH, DUCK, FROG
    }

    var eventType: EventType = EventType.BAT
        set(value) {
            if (field == value) return
            field = value
            rebuildPreviewEvent()
            invalidate()
        }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eventStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }

    private val previewParallax = ParallaxCalculator().apply {
        reset()
    }

    private val previewAmbientColor = Color.rgb(220, 230, 235)
    private val previewGroundColor = Color.argb(70, 85, 105, 80)

    private val previewHillProvider: HillHeightProvider = { xRatio, depthFactor ->
        val normalizedX = ((xRatio + 0.2f) / 1.4f).coerceIn(0f, 1f)
        val centered = normalizedX * 2f - 1f
        val arch = 1f - centered * centered
        val depthShift = (depthFactor - 0.65f) * 0.06f
        (0.80f - arch * 0.10f + depthShift).coerceIn(0.62f, 0.92f)
    }

    private var previewEvent: RandomEvent = createPreviewEvent(eventType)
    private var previewAbsoluteTimeMs: Long = computePreviewTime(previewEvent)

    init {
        rebuildPreviewEvent()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        previewParallax.setCanvasSize(w.toFloat(), h.toFloat())
        previewParallax.reset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = minOf(w, h) * 0.48f

        backgroundPaint.color = Color.argb(55, 100, 165, 205)
        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        if (previewEvent.layer == EventLayer.GROUND) {
            backgroundPaint.color = previewGroundColor
            canvas.drawOval(cx - radius * 0.8f, cy + radius * 0.15f, cx + radius * 0.8f, cy + radius * 0.55f, backgroundPaint)
        }

        // Normalize paint baseline before event-specific mutations.
        eventPaint.style = Paint.Style.FILL
        eventStrokePaint.style = Paint.Style.STROKE
        eventStrokePaint.strokeCap = Paint.Cap.ROUND
        eventStrokePaint.strokeJoin = Paint.Join.ROUND

        try {
            val timeSeconds = previewAbsoluteTimeMs / 1000f
            previewEvent.draw(
                canvas = canvas,
                paint = eventPaint,
                strokePaint = eventStrokePaint,
                parallaxCalculator = previewParallax,
                width = w,
                height = h,
                timeSeconds = timeSeconds,
                ambientColor = previewAmbientColor,
                extendedOffset = 0f,
                absoluteTimeMs = previewAbsoluteTimeMs,
                hillHeightProvider = previewHillProvider
            )
        } catch (_: Exception) {
            // Preview should never crash settings UI due to a single event draw issue.
        }
    }

    private fun rebuildPreviewEvent() {
        previewEvent = createPreviewEvent(eventType)
        previewAbsoluteTimeMs = computePreviewTime(previewEvent)
        previewParallax.reset()
    }

    private fun computePreviewTime(event: RandomEvent): Long {
        val ratio = when (event) {
            is ShootingStarEvent -> 0.45f
            else -> 0.5f
        }
        return (event.durationMs * ratio).toLong().coerceAtLeast(1L)
    }

    private fun createPreviewEvent(type: EventType): RandomEvent {
        val seed = 10_000L + type.ordinal.toLong() * 9_973L
        val random = Random(seed)
        val spawnTimeMs = 0L

        return when (type) {
            EventType.AIRPLANE -> AirplaneEvent(random, spawnTimeMs)
            EventType.BIRDS -> BirdFlockEvent(random, spawnTimeMs)
            EventType.EAGLE -> EagleEvent(random, spawnTimeMs)
            EventType.OWL -> OwlEvent(random, spawnTimeMs)
            EventType.BALLOON -> HotAirBalloonEvent(random, spawnTimeMs)
            EventType.SHOOTING_STAR -> ShootingStarEvent(random, spawnTimeMs)
            EventType.UFO -> AlienEvent(random, spawnTimeMs)
            EventType.BUTTERFLY -> ButterflyEvent(random, spawnTimeMs)
            EventType.DRAGONFLY -> DragonflyEvent(random, spawnTimeMs)
            EventType.RABBIT -> RabbitEvent(random, spawnTimeMs)
            EventType.FOX -> FoxEvent(random, spawnTimeMs)
            EventType.SQUIRREL -> SquirrelEvent(random, spawnTimeMs)
            EventType.FIREFLIES -> FireflyEvent(random, spawnTimeMs)
            EventType.SNOWFLAKE -> SnowflakeEvent(random, spawnTimeMs)
            EventType.BAT -> BatEvent(random, spawnTimeMs)
            EventType.DEER -> DeerEvent(random, spawnTimeMs)
            EventType.FISH -> FishJumpEvent(random, spawnTimeMs)
            EventType.DUCK -> DuckEvent(random, spawnTimeMs)
            EventType.FROG -> FrogEvent(random, spawnTimeMs)
        }
    }
}
