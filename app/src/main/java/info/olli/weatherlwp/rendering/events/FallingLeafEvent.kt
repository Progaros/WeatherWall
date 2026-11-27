package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.util.ColorMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Autumn leaves detaching from a tree crown and fluttering down to the hill surface.
 *
 * Spawns at [spawnPosition] when provided (typically resolved from a visible deciduous tree
 * crown via [EventSpawnSource.TREE_CROWN]), or falls back to a centered upper-mid position
 * for standalone previews and treeless scenes.
 *
 * Rendered in [EventLayer.GROUND] at the source tree's [depthFactor] so the falling leaves
 * share the tree's parallax and interleave naturally with surrounding hills.
 */
class FallingLeafEvent(
    random: Random,
    override val spawnTimeMs: Long = System.currentTimeMillis(),
    spawnPosition: EventSpawnPosition? = null
) : RandomEvent {

    override val layer = EventLayer.GROUND
    override val depthFactor: Float = (spawnPosition?.depthFactor ?: (0.58f + random.nextFloat() * 0.22f))
        .coerceIn(RenderConstants.PARALLAX_HILLS_MIN, RenderConstants.PARALLAX_HILLS_MAX)

    private val originX = spawnPosition?.x ?: 0.5f
    private val originY = spawnPosition?.y ?: 0.44f
    private val hasExplicitSpawn = spawnPosition != null

    private data class LeafParticle(
        val delayMs: Long,
        val offsetX: Float,
        val offsetY: Float,
        val fallSpeed: Float,       // screen-height ratio per second
        val swayFreq: Float,        // rad/s
        val swayAmp: Float,         // extended-field ratio
        val swayPhase: Float,
        val driftSpeed: Float,      // extended-field ratio per second
        val tumbleFreq: Float,      // rad/s for 3D flip illusion
        val tumblePhase: Float,
        val spinSpeedDeg: Float,
        val baseAngleDeg: Float,
        val sizeScale: Float,
        val color: Int
    )

    private val leaves: List<LeafParticle>
    override val durationMs: Long

    private val leafPath = Path()

    init {
        val palette = intArrayOf(
            Color.rgb(212, 82, 24),   // Burnt orange-red
            Color.rgb(228, 146, 34),  // Golden amber
            Color.rgb(178, 48, 24),   // Deep crimson
            Color.rgb(232, 182, 46),  // Warm yellow-gold
            Color.rgb(196, 102, 28)   // Autumn russet
        )
        // Shared gentle breeze direction for the cluster
        val breezeDir = if (random.nextBoolean()) 1f else -1f
        val count = 2 + random.nextInt(3)  // 2..4 leaves detaching together
        val list = ArrayList<LeafParticle>(count)
        var maxDelay = 0L

        for (i in 0 until count) {
            val delay = if (i == 0) 0L else (i * 320L + random.nextLong(260L))
            if (delay > maxDelay) maxDelay = delay
            list.add(
                LeafParticle(
                    delayMs = delay,
                    offsetX = if (i == 0) 0f else (random.nextFloat() - 0.5f) * 0.022f,
                    offsetY = if (i == 0) 0f else (random.nextFloat() - 0.5f) * 0.022f,
                    fallSpeed = 0.036f + random.nextFloat() * 0.022f,
                    swayFreq = 1.6f + random.nextFloat() * 1.1f,
                    swayAmp = 0.012f + random.nextFloat() * 0.014f,
                    swayPhase = random.nextFloat() * 6.2831855f,
                    driftSpeed = breezeDir * (0.004f + random.nextFloat() * 0.008f),
                    tumbleFreq = 2.4f + random.nextFloat() * 1.8f,
                    tumblePhase = random.nextFloat() * 6.2831855f,
                    spinSpeedDeg = (random.nextFloat() - 0.5f) * 55f,
                    baseAngleDeg = random.nextFloat() * 360f,
                    sizeScale = 0.85f + random.nextFloat() * 0.4f,
                    color = palette[random.nextInt(palette.size)]
                )
            )
        }
        leaves = list
        durationMs = maxDelay + 7500L + random.nextLong(2500L)
    }

    private fun primaryPos(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?
    ): Pair<Float, Float> {
        val lead = leaves[0]
        val elapsedSec = ((absoluteTimeMs - spawnTimeMs).coerceAtLeast(0L)) / 1000f
        val rawX = originX + lead.offsetX +
            sin(elapsedSec * lead.swayFreq + lead.swayPhase) * lead.swayAmp +
            lead.driftSpeed * elapsedSec
        val groundY = resolveGroundY(rawX, hillHeightProvider)
        val rawY = (originY + lead.offsetY + lead.fallSpeed * elapsedSec).coerceAtMost(groundY)
        return rawX to rawY
    }

    private fun resolveGroundY(eventX: Float, hillHeightProvider: EventTerrainProvider?): Float {
        val hillY = hillHeightProvider?.invoke(eventX, depthFactor)
        return if (hillY != null) {
            // Settle slightly below the ridge line so the leaf rests on the hill slope
            (hillY + 0.012f).coerceIn(originY + 0.04f, 0.98f)
        } else {
            (originY + 0.30f).coerceAtMost(0.95f)
        }
    }

    override fun getCurrentYRatio(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?,
        screenHeight: Float
    ): Float = primaryPos(absoluteTimeMs, hillHeightProvider).second

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float =
        primaryPos(absoluteTimeMs, null).first

    override fun getPreviewAnchorYRatio(
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?
    ): Float = primaryPos(absoluteTimeMs, hillHeightProvider).second

    override fun draw(
        canvas: Canvas,
        paint: Paint,
        strokePaint: Paint,
        parallaxCalculator: ParallaxCalculator,
        width: Float,
        height: Float,
        timeSeconds: Float,
        ambientColor: Int,
        extendedOffset: Float,
        absoluteTimeMs: Long,
        hillHeightProvider: EventTerrainProvider?,
        extendedOffsetY: Float
    ) {
        val totalElapsedMs = absoluteTimeMs - spawnTimeMs
        if (totalElapsedMs < 0L || totalElapsedMs > durationMs) return

        val nearness = ((depthFactor - RenderConstants.PARALLAX_HILLS_MIN) /
            (RenderConstants.PARALLAX_HILLS_MAX - RenderConstants.PARALLAX_HILLS_MIN).coerceAtLeast(0.01f))
            .coerceIn(0f, 1f)
        val depthScale = 0.55f + 0.45f * nearness
        // Size scaled to screen height; slightly larger in preview where no explicit tree is present
        val baseUnit = height * (if (hasExplicitSpawn) 0.0075f else 0.012f) * depthScale

        val px = parallaxCalculator.getOffsetXForDepth(depthFactor)
        val py = parallaxCalculator.getOffsetYForDepth(depthFactor)
        canvas.save()
        canvas.translate(px, py)

        // Overall fade-out over the final 1.4 seconds
        val remainingMs = durationMs - totalElapsedMs
        val globalFade = (remainingMs / 1400f).coerceIn(0f, 1f)

        for (leaf in leaves) {
            val leafElapsedMs = totalElapsedMs - leaf.delayMs
            if (leafElapsedMs < 0L) continue

            val t = leafElapsedMs / 1000f
            // Soft fade-in as the leaf detaches from the canopy
            val fadeIn = (leafElapsedMs / 250f).coerceIn(0f, 1f)

            val rawX = originX + leaf.offsetX +
                sin(t * leaf.swayFreq + leaf.swayPhase) * leaf.swayAmp +
                leaf.driftSpeed * t
            val groundY = resolveGroundY(rawX, hillHeightProvider)
            val freeFallY = originY + leaf.offsetY + leaf.fallSpeed * t

            val landed = freeFallY >= groundY
            val yRatio: Float
            val xRatio: Float
            val settledFade: Float
            val tiltDeg: Float
            val flipScale: Float

            if (landed) {
                val landTimeSec = ((groundY - (originY + leaf.offsetY)) / leaf.fallSpeed).coerceAtLeast(0f)
                val timeOnGround = (t - landTimeSec).coerceAtLeast(0f)
                xRatio = originX + leaf.offsetX +
                    sin(landTimeSec * leaf.swayFreq + leaf.swayPhase) * leaf.swayAmp +
                    leaf.driftSpeed * landTimeSec
                yRatio = groundY
                // Rest briefly on the hill then gently fade into the ground litter
                settledFade = (1f - (timeOnGround - 0.6f) / 1.4f).coerceIn(0f, 1f)
                tiltDeg = leaf.baseAngleDeg + landTimeSec * leaf.spinSpeedDeg
                flipScale = 0.6f
            } else {
                xRatio = rawX
                yRatio = freeFallY
                settledFade = 1f
                // Pendulum banking follows the derivative of horizontal sway
                val swaySlope = cos(t * leaf.swayFreq + leaf.swayPhase) * 32f
                tiltDeg = leaf.baseAngleDeg + swaySlope + t * leaf.spinSpeedDeg * 0.35f
                flipScale = 0.32f + 0.68f * abs(cos(t * leaf.tumbleFreq + leaf.tumblePhase))
            }

            val alpha = (235f * fadeIn * settledFade * globalFade).toInt().coerceIn(0, 255)
            if (alpha <= 2) continue

            val screenX = xRatio * width - extendedOffset
            val screenY = yRatio * height
            val leafLen = baseUnit * leaf.sizeScale
            val leafHalfW = leafLen * 0.52f

            val blendedColor = RenderConstants.blendWithAmbient(leaf.color, ambientColor, 0.35f)
            paint.style = Paint.Style.FILL
            paint.color = blendedColor
            paint.alpha = alpha

            // Pointed autumn leaf silhouette
            leafPath.reset()
            leafPath.moveTo(-leafLen, 0f)
            leafPath.quadTo(-leafLen * 0.15f, -leafHalfW * 1.25f, leafLen, 0f)
            leafPath.quadTo(-leafLen * 0.15f, leafHalfW * 1.25f, -leafLen, 0f)
            leafPath.close()

            canvas.save()
            canvas.translate(screenX, screenY)
            canvas.rotate(tiltDeg)
            canvas.scale(1f, flipScale)

            canvas.drawPath(leafPath, paint)

            // Subtle central vein and tiny stem
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = (leafLen * 0.11f).coerceAtLeast(0.9f)
            strokePaint.color = ColorMath.darkenColor(blendedColor, 0.65f)
            strokePaint.alpha = (alpha * 0.7f).toInt()
            canvas.drawLine(-leafLen * 1.25f, 0f, leafLen * 0.72f, 0f, strokePaint)

            canvas.restore()
        }

        canvas.restore()
    }
}
