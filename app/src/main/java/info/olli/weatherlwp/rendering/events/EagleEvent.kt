package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bald eagle gliding left to right, seen in side profile.
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
     * the left edge) to 1.3 at the end of [durationMs].
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
        canvas.withTranslation(px, py) {
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

            // Far wing sits in shadow behind the body.
            val farWingColor = RenderConstants.blendWithAmbient(
                Color.argb((255 * alpha).toInt(), 22, 18, 15), ambientColor, 0.35f)

            // Side view: only a gentle pitch bob, the bird always faces +x.
            val pitch = sin(elapsedSec * 0.55f + soarPhase) * 3f
            canvas.rotate(pitch, currentX, currentY)

            // Soaring flex: tips lift and settle as the bird rides the air.
            val flexWave = sin(elapsedSec * 0.75f + soarPhase) * 0.7f +
                sin(elapsedSec * 1.93f + soarPhase * 1.7f) * 0.3f
            val bend = flexWave * size * 0.07f
            val sweep = flexWave * 0.07f

            val cx = currentX
            val cy = currentY
            val s = size

            paint.color = farWingColor
            buildWingPath(path, cx + s * 0.20f, cy - s * 0.10f, s, s * 1.0f, 0.62f - sweep * 0.8f, bend * 0.8f, 0.85f)
            canvas.drawPath(path, paint)

            // Tail: white wedge fanning out behind the body
            paint.color = eagleWhite
            path.reset()
            path.moveTo(cx - s * 0.34f, cy - s * 0.06f)
            path.lineTo(cx - s * 0.74f, cy - s * 0.10f)
            path.quadTo(cx - s * 0.86f, cy + s * 0.02f, cx - s * 0.74f, cy + s * 0.14f)
            path.lineTo(cx - s * 0.34f, cy + s * 0.07f)
            path.close()
            canvas.drawPath(path, paint)

            // Body: horizontal torso, deeper at the chest
            paint.color = eagleBody
            path.reset()
            path.moveTo(cx + s * 0.36f, cy - s * 0.08f)
            path.cubicTo(cx + s * 0.16f, cy - s * 0.17f, cx - s * 0.20f, cy - s * 0.13f, cx - s * 0.42f, cy - s * 0.05f)
            path.lineTo(cx - s * 0.42f, cy + s * 0.07f)
            path.cubicTo(cx - s * 0.20f, cy + s * 0.17f, cx + s * 0.18f, cy + s * 0.19f, cx + s * 0.38f, cy + s * 0.05f)
            path.close()
            canvas.drawPath(path, paint)

            paint.color = birdColor
            buildWingPath(path, cx + s * 0.10f, cy - s * 0.10f, s, s * 1.2f, 0.85f + sweep, bend, 1f)
            canvas.drawPath(path, paint)

            // Head: white, flowing out of the neck towards the beak
            paint.color = eagleWhite
            path.reset()
            path.moveTo(cx + s * 0.24f, cy - s * 0.11f)
            path.cubicTo(cx + s * 0.36f, cy - s * 0.19f, cx + s * 0.54f, cy - s * 0.18f, cx + s * 0.62f, cy - s * 0.08f)
            path.cubicTo(cx + s * 0.64f, cy - s * 0.02f, cx + s * 0.58f, cy + s * 0.04f, cx + s * 0.48f, cy + s * 0.05f)
            path.cubicTo(cx + s * 0.40f, cy + s * 0.07f, cx + s * 0.32f, cy + s * 0.09f, cx + s * 0.26f, cy + s * 0.10f)
            path.close()
            canvas.drawPath(path, paint)

            paint.color = birdColor
            canvas.drawCircle(cx + s * 0.50f, cy - s * 0.09f, s * 0.022f, paint)

            // Beak: hooked, pointing forward
            paint.color = RenderConstants.blendWithAmbient(
                Color.argb((230 * alpha).toInt(), 225, 180, 40), ambientColor, 0.3f)
            path.reset()
            path.moveTo(cx + s * 0.58f, cy - s * 0.09f)
            path.quadTo(cx + s * 0.70f, cy - s * 0.10f, cx + s * 0.745f, cy - s * 0.04f)
            path.quadTo(cx + s * 0.75f, cy, cx + s * 0.725f, cy + s * 0.01f)
            path.quadTo(cx + s * 0.715f, cy - s * 0.02f, cx + s * 0.66f, cy - s * 0.015f)
            path.lineTo(cx + s * 0.60f, cy)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    // Wing frame for wx/wy; plain fields keep the path build allocation-free.
    private var wRootX = 0f
    private var wRootY = 0f
    private var wSpanX = 0f
    private var wSpanY = 0f
    private var wChord = 0f
    private var wBend = 0f

    // a = fraction along the span (root→tip), b = chord offset (trailing positive, size units).
    private fun wx(a: Float, b: Float) = wRootX + wSpanX * a - wChord * b * 0.8f + wBend * a * a * 0.4f
    private fun wy(a: Float, b: Float) = wRootY + wSpanY * a + wChord * b * 0.6f - wBend * a * a

    /**
     * Broad gliding wing seen from the side, raised up-and-back from the shoulder at [angle]
     * (radians back from vertical), with five spread primaries ("fingers") at the tip.
     */
    private fun buildWingPath(
        path: Path, rootX: Float, rootY: Float, size: Float,
        length: Float, angle: Float, bend: Float, chord: Float
    ) {
        wRootX = rootX
        wRootY = rootY
        wSpanX = -sin(angle) * length
        wSpanY = -cos(angle) * length
        wChord = size * chord
        wBend = bend

        path.reset()
        path.moveTo(wx(0f, 0f), wy(0f, 0f))
        path.cubicTo(
            wx(0.35f, -0.06f), wy(0.35f, -0.06f),
            wx(0.65f, -0.08f), wy(0.65f, -0.08f),
            wx(0.86f, -0.03f), wy(0.86f, -0.03f)
        )
        for (i in FINGER_REACH.indices) {
            val a = FINGER_REACH[i]
            val b = FINGER_CHORD[i]
            path.lineTo(wx(a, b), wy(a, b))
            if (i < FINGER_REACH.lastIndex) {
                val notchB = (b + FINGER_CHORD[i + 1]) * 0.5f
                path.lineTo(wx(0.84f, notchB), wy(0.84f, notchB))
            }
        }
        // Trailing edge: broad secondaries bulging back to the body
        path.lineTo(wx(0.80f, 0.40f), wy(0.80f, 0.40f))
        path.cubicTo(
            wx(0.60f, 0.48f), wy(0.60f, 0.48f),
            wx(0.30f, 0.46f), wy(0.30f, 0.46f),
            wx(0f, 0.30f), wy(0f, 0.30f)
        )
        path.close()
    }

    private companion object {
        val FINGER_REACH = floatArrayOf(1.00f, 1.05f, 1.04f, 0.99f, 0.90f)
        val FINGER_CHORD = floatArrayOf(0.00f, 0.08f, 0.16f, 0.24f, 0.31f)
    }
}
