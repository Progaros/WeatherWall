package info.olli.weatherlwp.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.graphics.withSave
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.rendering.events.EventLayer
import info.olli.weatherlwp.rendering.events.EventSpec
import info.olli.weatherlwp.rendering.events.EventTerrainProvider
import info.olli.weatherlwp.rendering.events.RandomEvent
import kotlin.math.sin
import kotlin.random.Random

/**
 * Event preview rendered with runtime RandomEvent classes.
 *
 * Uses a virtual full-size scene and a camera transform so tiny settings previews
 * stay centered and legible even when events use off-screen/random spawn coordinates.
 */
class EventPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Settings-list order; the ordinal seeds each preview, so entries must not be reordered. */
    enum class EventType(val spec: EventSpec) {
        AIRPLANE(EventSpec.AIRPLANE), BIRDS(EventSpec.BIRDS), EAGLE(EventSpec.EAGLE), OWL(EventSpec.OWL),
        BALLOON(EventSpec.BALLOON), SHOOTING_STAR(EventSpec.SHOOTING_STAR), UFO(EventSpec.ALIEN),
        BUTTERFLY(EventSpec.BUTTERFLY), DRAGONFLY(EventSpec.DRAGONFLY), RABBIT(EventSpec.RABBIT),
        FOX(EventSpec.FOX), SQUIRREL(EventSpec.SQUIRREL), FIREFLIES(EventSpec.FIREFLIES),
        SNOWFLAKE(EventSpec.SNOWFLAKE), BAT(EventSpec.BAT), DEER(EventSpec.DEER),
        FISH(EventSpec.FISH), DUCK(EventSpec.DUCK), FROG(EventSpec.FROG);

        companion object {
            fun fromSpec(spec: EventSpec): EventType = entries.first { it.spec == spec }
        }
    }

    enum class PreviewMode {
        SMALL,
        LARGE
    }

    private data class PreviewFraming(
        val scale: Float,
        val yOffsetFactor: Float
    )

    var eventType: EventType = EventType.BAT
        set(value) {
            if (field == value) return
            field = value
            recreatePreviewEvent()
        }

    var previewMode: PreviewMode = PreviewMode.SMALL
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eventStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }

    private val previewParallax = ParallaxCalculator().apply {
        setCanvasSize(PREVIEW_WORLD_WIDTH, PREVIEW_WORLD_HEIGHT)
        reset()
    }

    private val previewGroundColor = Color.argb(70, 85, 105, 80)

    private val previewHillProvider: EventTerrainProvider = { eventX, depthFactor ->
        val x = eventX.coerceIn(-0.2f, 1.2f)
        val centered = ((x + 0.2f) / 1.4f) * 2f - 1f
        val arch = 1f - centered * centered
        val depthShift = (depthFactor - 0.65f) * 0.06f
        (0.80f - arch * 0.10f + depthShift).coerceIn(0.62f, 0.92f)
    }

    private var animationTimeSeconds = 0f
    private var previewEvent: RandomEvent? = null
    private var previewSpawnTimeMs = 0L
    private var previewStartOffsetMs = 0L
    private var previewClockOriginSeconds = 0f

    private val animator = object : Runnable {
        override fun run() {
            animationTimeSeconds += 0.016f
            if (animationTimeSeconds > 120f) {
                animationTimeSeconds = 0f
                recreatePreviewEvent()
            }
            invalidate()
            postDelayed(this, FRAME_DELAY_MS)
        }
    }

    private val backgroundColor: Int
        get() {
            if (previewMode == PreviewMode.SMALL) {
                // Inline circles: fixed daylight sky for consistent appearance
                return Color.argb(140, 100, 180, 235)
            }
            // Large popup: cycle between day sky and night sky (~5s period)
            val phase = sin(animationTimeSeconds * 1.257f)  // 2π/5 ≈ 1.257
            val t = ((phase + 1f) / 2f).coerceIn(0f, 1f)
            val r = (15 + (100 - 15) * t).toInt()
            val g = (15 + (180 - 15) * t).toInt()
            val b = (35 + (235 - 35) * t).toInt()
            return Color.argb(140, r, g, b)
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (previewEvent == null) {
            recreatePreviewEvent()
        }
        removeCallbacks(animator)
        post(animator)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animator)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            removeCallbacks(animator)
            post(animator)
        } else {
            removeCallbacks(animator)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (previewEvent == null) {
            recreatePreviewEvent()
        }

        var event = previewEvent ?: return
        var absoluteTimeMs = previewTimeMs()
        if (!event.isActive(absoluteTimeMs)) {
            recreatePreviewEvent()
            event = previewEvent ?: return
            absoluteTimeMs = previewTimeMs()
        }

        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val size = minOf(w, h)
        val radius = size * 0.48f

        backgroundPaint.style = Paint.Style.FILL
        backgroundPaint.color = backgroundColor
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        if (event.layer == EventLayer.GROUND) {
            backgroundPaint.color = previewGroundColor
            canvas.drawOval(
                centerX - radius * 0.8f,
                centerY + radius * 0.15f,
                centerX + radius * 0.8f,
                centerY + radius * 0.55f,
                backgroundPaint
            )
        }

        eventPaint.style = Paint.Style.FILL
        eventStrokePaint.style = Paint.Style.STROKE
        eventStrokePaint.strokeCap = Paint.Cap.ROUND
        eventStrokePaint.strokeJoin = Paint.Join.ROUND

        val anchorXRatio = event.getPreviewAnchorXRatio(absoluteTimeMs).coerceIn(-0.5f, 1.5f)
        val anchorYRatio = event.getPreviewAnchorYRatio(absoluteTimeMs, previewHillProvider).coerceIn(-0.3f, 1.3f)
        val anchorX = anchorXRatio * PREVIEW_WORLD_WIDTH - PREVIEW_EXTENDED_OFFSET
        val anchorY = anchorYRatio * PREVIEW_WORLD_HEIGHT
        val framing = getFraming(eventType)
        val worldScale = framing.scale * FRAMING_REFERENCE_HEIGHT / PREVIEW_WORLD_HEIGHT

        canvas.withSave {
            translate(centerX, centerY + framing.yOffsetFactor * size)
            scale(worldScale, worldScale)
            translate(-anchorX, -anchorY)
            try {
                event.draw(
                    canvas = this,
                    paint = eventPaint,
                    strokePaint = eventStrokePaint,
                    parallaxCalculator = previewParallax,
                    width = PREVIEW_WORLD_WIDTH,
                    height = PREVIEW_WORLD_HEIGHT,
                    timeSeconds = animationTimeSeconds,
                    ambientColor = previewAmbientColor(),
                    extendedOffset = PREVIEW_EXTENDED_OFFSET,
                    absoluteTimeMs = absoluteTimeMs,
                    hillHeightProvider = previewHillProvider
                )
            } catch (e: Exception) {
                // Keep settings responsive if one event renderer throws.
                Log.w("EventPreviewView", "Preview event renderer threw", e)
            }
        }
    }

    private fun recreatePreviewEvent() {
        val seed = 10_000L + eventType.ordinal.toLong() * 9_973L
        val now = System.currentTimeMillis()

        val sample = createPreviewEvent(eventType, seed, now)
        val offsetMs = getPreviewOffsetMs(sample)

        previewSpawnTimeMs = now - offsetMs
        previewStartOffsetMs = offsetMs
        previewClockOriginSeconds = animationTimeSeconds
        previewEvent = createPreviewEvent(eventType, seed, previewSpawnTimeMs)
        previewParallax.reset()
        invalidate()
    }

    /** Event time: starts at the chosen offset into the event and advances with the animation clock. */
    private fun previewTimeMs(): Long =
        previewSpawnTimeMs + previewStartOffsetMs +
            ((animationTimeSeconds - previewClockOriginSeconds) * 1000f).toLong()

    private fun getPreviewOffsetMs(event: RandomEvent): Long {
        val targetOffset = (event.durationMs * eventType.spec.preview.startFraction).toLong()

        val minOffset = 300L
        val maxOffset = (event.durationMs - 300L).coerceAtLeast(minOffset)
        return targetOffset.coerceIn(minOffset, maxOffset)
    }

    private fun getFraming(type: EventType): PreviewFraming {
        val smallScale = type.spec.preview.smallScale
        val baseYOffset = type.spec.preview.yOffsetFactor

        return if (previewMode == PreviewMode.LARGE) {
            PreviewFraming(
                scale = (smallScale * 1.5f).coerceAtMost(1.8f),
                yOffsetFactor = baseYOffset * 0.5f
            )
        } else {
            PreviewFraming(
                scale = smallScale,
                yOffsetFactor = baseYOffset
            )
        }
    }

    private fun createPreviewEvent(type: EventType, seed: Long, spawnTimeMs: Long): RandomEvent =
        type.spec.create(Random(seed), spawnTimeMs)

    private fun previewAmbientColor(): Int {
        val color = backgroundColor
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
        private const val FRAME_DELAY_MS = 16L
        // At the reference height traversal scaling is 1, so preview anchors match draw() positions.
        private const val PREVIEW_WORLD_HEIGHT = RenderConstants.REFERENCE_HEIGHT
        private const val PREVIEW_WORLD_WIDTH = PREVIEW_WORLD_HEIGHT * 16f / 9f
        private const val PREVIEW_EXTENDED_OFFSET = 0f
        // World height the per-event framing scales were tuned for.
        private const val FRAMING_REFERENCE_HEIGHT = 1080f
    }
}
