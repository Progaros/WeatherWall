package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Eagle with majestic soaring silhouette.
 * Distance affects size, speed, and parallax for depth perception.
 */
class EagleEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.2-0.7 (eagles can be fairly close)
    private val distance = 0.2f + random.nextFloat() * 0.5f
    
    // Size scales inversely with distance
    private val sizeScale = 1.0f - distance * 0.5f  // 0.65-1.0 based on distance
    
    // Far eagles soar higher
    private val baseY = 0.15f + random.nextFloat() * 0.12f + distance * 0.08f
    
    // Speed scales with distance (far = slower)
    private val baseSpeed = 0.00004f - distance * 0.00002f
    private val speed = baseSpeed + random.nextFloat() * 0.00002f
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val path = Path()
    private val soarPhase = random.nextFloat() * 6.28f
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.2f + distance * 0.2f

    /**
     * Shared by [draw] and the preview so both use the identical traversal: -0.1 (just off
     * the left edge) to 1.3 at the end of [durationMs]. The old form applied the -0.1 twice
     * and ran to 1.72, so the eagle left the screen at ~63% of its duration.
     */
    private fun anchorXRatio(absoluteTimeMs: Long, screenHeight: Float): Float {
        val traversalElapsed = EventTraversal.scaledElapsed(absoluteTimeMs - spawnTimeMs, screenHeight)
        return -0.1f + speed * traversalElapsed
    }

    // Two detuned waves; a single sine reads as a mechanical loop.
    private fun soarOffset(seconds: Float): Float =
        sin(seconds * 0.42f + soarPhase) * 0.012f * sizeScale +
            sin(seconds * 0.91f + soarPhase * 0.6f) * 0.004f * sizeScale

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float =
        anchorXRatio(absoluteTimeMs, RenderConstants.REFERENCE_HEIGHT)

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float =
        baseY + soarOffset((absoluteTimeMs - spawnTimeMs) / 1000f)
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        // Scale: ~1-1.5% of height based on distance
        val unit = height * 0.005f * sizeScale
        val size = unit * 5f
        
        val elapsedSec = (absoluteTimeMs - spawnTimeMs) / 1000f

        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)

        val currentX = anchorXRatio(absoluteTimeMs, height) * width - extendedOffset
        val currentY = EventTraversal.mapFieldY(baseY + soarOffset(elapsedSec), height, extendedOffsetY)
        
        // Alpha based on distance
        val alpha = (1f - distance * 0.25f)
        val birdColor = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 35, 30, 25), ambientColor, 0.35f)
        // Bald-eagle contrast: with a single flat colour the head vanishes into the body.
        val eagleWhite = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 240, 238, 230), ambientColor, 0.35f)
        // Torso sits a few shades lighter than the wings, otherwise the middle of the
        // bird disappears into the wing mass and the silhouette reads as a plank.
        val eagleBody = RenderConstants.blendWithAmbient(
            Color.argb((255 * alpha).toInt(), 70, 52, 36), ambientColor, 0.35f)

        // Seen from below, heading "up" and tilted into the direction of travel. Wings spanning
        // the flight axis with the head between them read as a torn strip, not a bird.
        val bank = sin(elapsedSec * 0.55f + soarPhase) * 6f
        canvas.rotate(28f + bank, currentX, currentY)

        // Dihedral flex: the fingers lift and settle as the bird rides the air.
        val flex = sin(elapsedSec * 0.75f + soarPhase) * size * 0.05f +
            sin(elapsedSec * 1.93f + soarPhase * 1.7f) * size * 0.02f

        paint.color = birdColor
        buildWingPath(path, currentX, currentY, -1f, size, flex, 0.92f)
        canvas.drawPath(path, paint)
        buildWingPath(path, currentX, currentY, 1f, size, flex, 1f)
        canvas.drawPath(path, paint)

        // Tail: short white fan behind the body
        paint.color = eagleWhite
        path.reset()
        path.moveTo(currentX - size * 0.09f, currentY + size * 0.28f)
        path.lineTo(currentX - size * 0.19f, currentY + size * 0.60f)
        path.quadTo(currentX, currentY + size * 0.70f, currentX + size * 0.19f, currentY + size * 0.60f)
        path.lineTo(currentX + size * 0.09f, currentY + size * 0.28f)
        path.close()
        canvas.drawPath(path, paint)

        // Body: tapered torso between the wing roots
        paint.color = eagleBody
        path.reset()
        path.moveTo(currentX, currentY - size * 0.34f)
        path.cubicTo(
            currentX + size * 0.17f, currentY - size * 0.22f,
            currentX + size * 0.15f, currentY + size * 0.22f,
            currentX, currentY + size * 0.36f
        )
        path.cubicTo(
            currentX - size * 0.15f, currentY + size * 0.22f,
            currentX - size * 0.17f, currentY - size * 0.22f,
            currentX, currentY - size * 0.34f
        )
        path.close()
        canvas.drawPath(path, paint)

        // Head: white, pushed well ahead of the wings
        paint.color = eagleWhite
        canvas.drawOval(
            currentX - size * 0.12f, currentY - size * 0.58f,
            currentX + size * 0.12f, currentY - size * 0.28f, paint
        )

        // Beak (hooked, characteristic of raptors)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((230 * alpha).toInt(), 225, 180, 40), ambientColor, 0.3f)
        path.reset()
        path.moveTo(currentX - size * 0.05f, currentY - size * 0.55f)
        path.quadTo(currentX + size * 0.02f, currentY - size * 0.72f, currentX + size * 0.06f, currentY - size * 0.62f)
        path.lineTo(currentX + size * 0.05f, currentY - size * 0.55f)
        path.close()
        canvas.drawPath(path, paint)

        canvas.restore()
    }

    /**
     * Broad soaring wing seen from below: straight leading edge, bulging secondaries and five
     * spread primaries ("fingers") at the tip. [dir] is -1 for the left wing, 1 for the right.
     */
    private fun buildWingPath(
        path: Path, centerX: Float, centerY: Float, dir: Float,
        size: Float, flex: Float, scale: Float
    ) {
        val s = size * scale
        fun x(f: Float) = centerX + dir * s * f
        path.reset()
        path.moveTo(x(0.10f), centerY - size * 0.18f)
        // Leading edge: nearly straight out to the wrist, then a gentle sweep back
        path.cubicTo(
            x(0.40f), centerY - size * 0.26f,
            x(0.80f), centerY - size * 0.27f - flex * 0.5f,
            x(1.05f), centerY - size * 0.20f - flex
        )
        // Fingers: tips fan out along the wing tip, notches between them
        val fingerTipsY = floatArrayOf(-0.17f, -0.07f, 0.03f, 0.13f, 0.22f)
        val fingerReach = floatArrayOf(1.30f, 1.36f, 1.36f, 1.31f, 1.22f)
        for (i in fingerTipsY.indices) {
            val tipY = centerY + size * fingerTipsY[i] - flex
            path.lineTo(x(fingerReach[i]), tipY)
            if (i < fingerTipsY.lastIndex) {
                path.lineTo(x(1.10f), centerY + size * (fingerTipsY[i] + fingerTipsY[i + 1]) * 0.5f - flex * 0.8f)
            }
        }
        // Trailing edge: broad secondaries bulge back towards the tail
        path.lineTo(x(1.02f), centerY + size * 0.26f - flex * 0.6f)
        path.cubicTo(
            x(0.75f), centerY + size * 0.34f,
            x(0.35f), centerY + size * 0.36f,
            x(0.10f), centerY + size * 0.24f
        )
        path.close()
    }
}
