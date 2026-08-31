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
            Color.argb((255 * alpha).toInt(), 95, 74, 54), ambientColor, 0.35f)

        val wingspan = size * 2.2f
        val bodyLen = size * 0.8f

        // Bank the whole bird: a perfectly level, symmetric silhouette reads as a logo.
        val bank = sin(elapsedSec * 0.55f + soarPhase) * 0.08f
        canvas.rotate(bank * 10f, currentX, currentY)

        val tipFlex = sin(elapsedSec * 0.75f + soarPhase) * size * 0.09f +
            sin(elapsedSec * 1.93f + soarPhase * 1.7f) * size * 0.035f
        val tipY = currentY - size * 0.72f + tipFlex

        // Three-quarter view: the far wing is shorter and carried higher. Two identical
        // blades either side of the body is what made the bird look like a moustache.
        val farWingScale = 0.88f + bank * 0.08f
        paint.color = birdColor
        buildWingPath(path, currentX, currentY, -1f, wingspan, size, tipY - size * 0.10f, farWingScale)
        canvas.drawPath(path, paint)
        buildWingPath(path, currentX, currentY, 1f, wingspan, size, tipY, 1f)
        canvas.drawPath(path, paint)

        // Tail - long and fanned, over the wing roots but under the body
        paint.color = eagleWhite
        path.reset()
        path.moveTo(currentX - bodyLen * 0.35f, currentY - size * 0.08f)
        path.lineTo(currentX - bodyLen * 1.30f, currentY - size * 0.22f)
        path.lineTo(currentX - bodyLen * 1.14f, currentY)
        path.lineTo(currentX - bodyLen * 1.28f, currentY + size * 0.22f)
        path.lineTo(currentX - bodyLen * 0.35f, currentY + size * 0.08f)
        path.close()
        canvas.drawPath(path, paint)

        // Body - deep chest tapering to the tail; an ellipse read as a capsule laid on top
        paint.color = eagleBody
        path.reset()
        path.moveTo(currentX - bodyLen * 0.58f, currentY)
        path.cubicTo(
            currentX - bodyLen * 0.25f, currentY - size * 0.24f,
            currentX + bodyLen * 0.28f, currentY - size * 0.20f,
            currentX + bodyLen * 0.58f, currentY - size * 0.06f
        )
        path.cubicTo(
            currentX + bodyLen * 0.35f, currentY + size * 0.22f,
            currentX - bodyLen * 0.25f, currentY + size * 0.20f,
            currentX - bodyLen * 0.58f, currentY
        )
        path.close()
        canvas.drawPath(path, paint)

        // Head - flows out of the neck instead of sitting on the body as a pale dot
        paint.color = eagleWhite
        path.reset()
        path.moveTo(currentX + bodyLen * 0.20f, currentY - size * 0.14f)
        path.cubicTo(
            currentX + bodyLen * 0.42f, currentY - size * 0.18f,
            currentX + bodyLen * 0.68f, currentY - size * 0.12f,
            currentX + bodyLen * 0.72f, currentY - size * 0.02f
        )
        path.cubicTo(
            currentX + bodyLen * 0.62f, currentY + size * 0.10f,
            currentX + bodyLen * 0.35f, currentY + size * 0.10f,
            currentX + bodyLen * 0.18f, currentY + size * 0.04f
        )
        path.close()
        canvas.drawPath(path, paint)
        
        // Beak (hooked, characteristic of raptors)
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((220 * alpha).toInt(), 200, 180, 60), ambientColor, 0.3f)
        path.reset()
        path.moveTo(currentX + bodyLen * 0.66f, currentY - size * 0.06f)
        path.quadTo(
            currentX + bodyLen * 0.91f, currentY - size * 0.01f,
            currentX + bodyLen * 0.85f, currentY + size * 0.08f
        )
        path.lineTo(currentX + bodyLen * 0.68f, currentY + size * 0.04f)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }

    /**
     * Raptor wing: broad inner section to the elbow, narrower hand, and slotted primaries.
     * [dir] is 1 for the near wing and -1 for the mirrored far wing.
     */
    private fun buildWingPath(
        path: Path, centerX: Float, centerY: Float, dir: Float,
        wingspan: Float, size: Float, tipY: Float, scale: Float
    ) {
        val span = wingspan * scale
        val shoulderX = centerX + dir * size * 0.12f
        path.reset()
        path.moveTo(shoulderX, centerY - size * 0.08f)
        path.cubicTo(
            centerX + dir * span * 0.20f, centerY - size * 0.38f,
            centerX + dir * span * 0.38f, centerY - size * 0.63f,
            centerX + dir * span * 0.48f, centerY - size * 0.55f
        )
        path.cubicTo(
            centerX + dir * span * 0.70f, centerY - size * 0.48f,
            centerX + dir * span * 0.90f, tipY - size * 0.08f,
            centerX + dir * span, tipY
        )
        // Slotted primary tips - small notches are enough at this scale
        path.lineTo(centerX + dir * span * 0.91f, tipY + size * 0.16f)
        path.lineTo(centerX + dir * span * 0.83f, tipY + size * 0.07f)
        path.lineTo(centerX + dir * span * 0.76f, tipY + size * 0.21f)
        path.cubicTo(
            centerX + dir * span * 0.55f, centerY + size * 0.13f,
            centerX + dir * span * 0.30f, centerY + size * 0.20f,
            shoulderX, centerY + size * 0.14f
        )
        path.close()
    }
}
