package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Paint
import info.olli.weatherlwp.parallax.ParallaxCalculator
import kotlin.random.Random

/**
 * Perseid meteor shower burst: a rapid series of fast meteors whose trails all point back
 * to the radiant in Perseus. The annual shower itself spawns single Perseids at a rate that
 * follows the solar longitude (see [MeteorShowers]); this burst is the manually triggered form.
 */
class MeteorShowerEvent(random: Random, override val spawnTimeMs: Long) : RandomEvent {
    override val layer = EventLayer.FAR_SKY

    private val meteors: List<ShootingStarEvent>

    init {
        val list = ArrayList<ShootingStarEvent>()
        var startMs = spawnTimeMs + 300L
        // Overlapping starts keep at least one meteor in flight for the whole burst.
        while (startMs < spawnTimeMs + BURST_MS) {
            list += ShootingStarEvent.perseid(random, startMs)
            startMs += 350L + random.nextInt(450)
        }
        meteors = list
    }

    override val durationMs: Long = meteors.maxOf { it.spawnTimeMs + it.durationMs } - spawnTimeMs

    /** Meteor followed by the settings preview: the newest one in flight, else the next to start. */
    private fun previewMeteor(absoluteTimeMs: Long): ShootingStarEvent =
        meteors.lastOrNull { absoluteTimeMs >= it.spawnTimeMs && it.isActive(absoluteTimeMs) }
            ?: meteors.firstOrNull { it.spawnTimeMs > absoluteTimeMs }
            ?: meteors.last()

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float =
        previewMeteor(absoluteTimeMs).getPreviewAnchorXRatio(absoluteTimeMs)

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float =
        previewMeteor(absoluteTimeMs).getPreviewAnchorYRatio(absoluteTimeMs, hillHeightProvider)

    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        for (meteor in meteors) {
            // No isActive() cut-off: traversal is height-scaled, each meteor stops drawing once burnt out.
            if (absoluteTimeMs < meteor.spawnTimeMs) continue
            meteor.draw(canvas, paint, strokePaint, parallaxCalculator, width, height, timeSeconds,
                ambientColor, extendedOffset, absoluteTimeMs, hillHeightProvider, extendedOffsetY)
        }
    }

    private companion object {
        const val BURST_MS = 9_000L
    }
}
