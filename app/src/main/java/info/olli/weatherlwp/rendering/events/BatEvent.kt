package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bat colony flying at night - chaotic, jerky movement unlike the smooth soaring of birds.
 * Night-time equivalent of BirdFlockEvent.
 * Seasonal: Year-round but only at night
 */
class BatEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Distance for depth: 0.3-0.7 (bats fly at medium altitude)
    private val distance = 0.3f + random.nextFloat() * 0.4f
    private val sizeScale = 1.0f - distance * 0.5f
    
    // Starting position and direction
    private val startX = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (startX < 0f) 1f else -1f
    private val baseY = 0.25f + random.nextFloat() * 0.25f  // Upper-mid sky
    
    // Speed - bats are fast
    private val speed = 0.00005f + random.nextFloat() * 0.00003f
    override val durationMs: Long = (1.3f / speed).toLong()
    
    // Colony size (3-8 bats)
    private val batCount = 3 + random.nextInt(6)
    private val bats = List(batCount) { Bat(random, sizeScale) }
    
    private val path = Path()
    
    // Parallax depth
    private val parallaxDepth = 0.30f + distance * 0.25f

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        return startX + direction * speed * elapsedMs
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return baseY
    }
    
    private class Bat(random: Random, sizeScale: Float) {
        val offsetX = (random.nextFloat() - 0.5f) * 0.08f * sizeScale
        val offsetY = (random.nextFloat() - 0.5f) * 0.04f * sizeScale
        val wingPhase = random.nextFloat() * 6.28f
        val sizeVariation = 0.7f + random.nextFloat() * 0.6f
        // Each bat has slightly different jerk frequency for chaotic movement
        val jerkFreqX = 3f + random.nextFloat() * 4f
        val jerkFreqY = 4f + random.nextFloat() * 5f
        val jerkAmpX = 0.002f + random.nextFloat() * 0.003f
        val jerkAmpY = 0.001f + random.nextFloat() * 0.002f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val elapsedSec = elapsedMs / 1000f
        
        // Base position from elapsed time (height-scaled traversal)
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val baseX = startX + direction * speed * traversalElapsed
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        // Scale: bat silhouettes - slightly larger for visibility
        val unit = height * 0.003f * sizeScale
        
        // Distant bats are more faded
        val alpha = (1f - distance * 0.4f)
        
        // Dark silhouette - slightly lighter than pure black for visibility against night sky
        paint.color = RenderConstants.blendWithAmbient(
            Color.argb((220 * alpha).toInt(), 45, 40, 50), ambientColor, 0.2f)
        paint.style = Paint.Style.FILL
        
        for (bat in bats) {
            // Jerky, erratic movement characteristic of bats
            val jerkX = sin(elapsedSec * bat.jerkFreqX + bat.wingPhase) * bat.jerkAmpX +
                        sin(elapsedSec * bat.jerkFreqX * 1.7f) * bat.jerkAmpX * 0.5f
            val jerkY = sin(elapsedSec * bat.jerkFreqY + bat.wingPhase * 0.7f) * bat.jerkAmpY +
                        sin(elapsedSec * bat.jerkFreqY * 2.3f) * bat.jerkAmpY * 0.4f
            
            val batX = (baseX + bat.offsetX + jerkX) * width - extendedOffset
            val batY = EventTraversal.mapFieldY(baseY + bat.offsetY + jerkY, height, extendedOffsetY)
            
            val size = bat.sizeVariation * unit * 4f
            
            // Rapid wing beats - bats flap very fast. Two detuned sines keep the beat
            // from looking like a mechanical loop.
            // Elapsed seconds stay precise; the wrapped 24h clock jitters a 25 Hz beat.
            val wingBeat = sin(elapsedSec * 25f + bat.wingPhase) * 0.85f +
                sin(elapsedSec * 11f + bat.wingPhase * 1.7f) * 0.15f
            val wingAngle = wingBeat * 0.6f  // Wing rotation in radians
            
            // Draw bat silhouette - membrane wings
            canvas.save()
            canvas.translate(batX, batY)
            
            // Far wing first, smaller and raised: two mirror-image wings read as a
            // symmetric ornament rather than an animal seen at an angle.
            canvas.save()
            canvas.rotate(-wingAngle * 34f)
            buildBatWingPath(path, size, -1f, 0.86f, -size * 0.07f)
            canvas.drawPath(path, paint)
            canvas.restore()

            // Body (small oval)
            canvas.drawOval(-size * 0.3f, -size * 0.15f, size * 0.3f, size * 0.15f, paint)

            // Near wing
            canvas.save()
            canvas.rotate(wingAngle * 40f)
            buildBatWingPath(path, size, 1f, 1f, 0f)
            canvas.drawPath(path, paint)
            canvas.restore()
            
            // Tiny ears (characteristic of bats)
            path.reset()
            path.moveTo(-size * 0.15f, -size * 0.1f)
            path.lineTo(-size * 0.2f, -size * 0.35f)
            path.lineTo(-size * 0.05f, -size * 0.15f)
            canvas.drawPath(path, paint)
            
            path.reset()
            path.moveTo(size * 0.15f, -size * 0.1f)
            path.lineTo(size * 0.2f, -size * 0.35f)
            path.lineTo(size * 0.05f, -size * 0.15f)
            canvas.drawPath(path, paint)
            
            canvas.restore()
        }
        
        canvas.restore()
    }

    /** Membrane wing: shoulder -> elbow -> tip, with the trailing edge scalloped by the finger bones. */
    private fun buildBatWingPath(path: Path, size: Float, dir: Float, scale: Float, yOffset: Float) {
        val w = size * scale
        path.reset()
        path.moveTo(0f, yOffset - size * 0.04f)
        path.quadTo(dir * w * 0.55f, yOffset - w * 0.72f, dir * w * 1.02f, yOffset - w * 0.55f)
        path.quadTo(dir * w * 1.42f, yOffset - w * 0.44f, dir * w * 1.55f, yOffset - w * 0.16f)
        path.lineTo(dir * w * 1.18f, yOffset + w * 0.10f)
        path.lineTo(dir * w * 1.06f, yOffset - w * 0.06f)
        path.lineTo(dir * w * 0.74f, yOffset + w * 0.20f)
        path.lineTo(dir * w * 0.60f, yOffset + w * 0.01f)
        path.lineTo(dir * w * 0.30f, yOffset + w * 0.19f)
        path.close()
    }
}
