package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import kotlin.random.Random

/**
 * Airplane with contrail - distant jet crossing the sky.
 * Uses distance-based scaling for realistic appearance.
 * Distance 0.0=near, 1.0=far affects size and speed.
 */
class AirplaneEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Random distance: 0.6-1.0 (always fairly far, planes are high up)
    private val distance = 0.6f + random.nextFloat() * 0.4f
    
    // Size scales inversely with distance (far planes are smaller)
    // Base size scaled by 0.3-0.7 depending on distance
    private val sizeScale = 0.7f - distance * 0.4f
    
    // Speed scales inversely with distance (far planes appear slower)
    private val baseSpeed = 0.00004f - distance * 0.00002f  // Slower at distance
    private val speed = baseSpeed + random.nextFloat() * 0.00001f
    
    override val durationMs: Long = (1.4f / speed).toLong()
    
    private val y = 0.06f + random.nextFloat() * 0.12f + distance * 0.05f  // Higher when farther
    private val path = Path()
    private val contrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    // Parallax depth based on distance
    private val parallaxDepth = 0.05f + distance * 0.15f
    
    // Airline accent colour used for the cheatline and tail fin
    private val liveryVariant = random.nextInt(3)
    
    // Unit-length gradient, stretched onto the contrail each frame via the local matrix
    private val contrailShader by lazy {
        LinearGradient(
            0f, 0f, 1f, 0f,
            Color.argb(0, 255, 255, 255),
            Color.argb((60 + (1f - distance) * 40).toInt(), 255, 255, 255),
            Shader.TileMode.CLAMP
        )
    }
    private val contrailMatrix = Matrix()

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val progress = (elapsedMs * speed).coerceIn(-0.1f, 1.4f)
        return -0.1f + 1.4f * progress
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: EventTerrainProvider?): Float {
        return y
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: EventTerrainProvider?, extendedOffsetY: Float) {
        // Scale: 0.4-0.8% of height based on distance
        val unit = height * 0.004f * sizeScale
        val size = unit * 8f  // Base airplane size
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val traversalElapsed = EventTraversal.scaledElapsed(elapsedMs, height)
        val progress = (traversalElapsed * speed).coerceIn(-0.1f, 1.4f)
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.withTranslation(px, py) {
            val currentX = -0.1f * width + 1.4f * width * progress - extendedOffset
            val currentY = EventTraversal.mapFieldY(y, height, extendedOffsetY)

            // Contrail width based on distance
            contrailPaint.strokeWidth = (4f - distance * 2f).coerceAtLeast(1.5f)

            // Contrail with gradient fade
            val contrailLength = width * (0.25f + (1f - distance) * 0.15f)
            val contrailStartX = (currentX - contrailLength).coerceAtLeast(-extendedOffset)
            val contrailEndX = currentX - size * 2.5f
            if (contrailEndX > contrailStartX) {
                contrailMatrix.setScale(contrailEndX - contrailStartX, 1f)
                contrailMatrix.postTranslate(contrailStartX, currentY)
                contrailShader.setLocalMatrix(contrailMatrix)
                contrailPaint.shader = contrailShader
                canvas.drawLine(contrailStartX, currentY, contrailEndX, currentY, contrailPaint)
                contrailPaint.shader = null
            }

            // Side-view airliner, nose at currentX pointing right
            val bodyColor = RenderConstants.blendWithAmbient(Color.rgb(225, 226, 234), ambientColor, 0.3f)
            val wingColor = RenderConstants.blendWithAmbient(Color.rgb(188, 190, 202), ambientColor, 0.3f)
            val shadowColor = RenderConstants.blendWithAmbient(Color.rgb(100, 100, 110), ambientColor, 0.3f)
            val windowColor = RenderConstants.blendWithAmbient(Color.rgb(70, 80, 100), ambientColor, 0.3f)
            val accentColor = RenderConstants.blendWithAmbient(
                when (liveryVariant) {
                    0 -> Color.rgb(30, 70, 150)     // Navy
                    1 -> Color.rgb(190, 40, 45)     // Red
                    else -> Color.rgb(20, 130, 130) // Teal
                },
                ambientColor, 0.3f
            )
            val h = size * 0.17f  // Fuselage half-height

            // Tail fin (vertical stabilizer), swept back, in the airline colour
            paint.color = accentColor
            path.reset()
            path.moveTo(currentX - size * 2.5f, currentY - h)
            path.lineTo(currentX - size * 2.95f, currentY - h - size * 0.48f)
            path.lineTo(currentX - size * 3.2f, currentY - h - size * 0.48f)
            path.lineTo(currentX - size * 3.1f, currentY - h)
            path.close()
            canvas.drawPath(path, paint)

            // Fuselage: rounded nose, straight cabin, tail cone sweeping up from the belly
            paint.color = bodyColor
            path.reset()
            path.moveTo(currentX - size * 0.45f, currentY - h)
            path.quadTo(currentX - size * 0.05f, currentY - h, currentX, currentY + h * 0.25f)
            path.quadTo(currentX - size * 0.05f, currentY + h, currentX - size * 0.45f, currentY + h)
            path.lineTo(currentX - size * 2.4f, currentY + h)
            path.lineTo(currentX - size * 3.05f, currentY - h * 0.5f)
            path.lineTo(currentX - size * 3.1f, currentY - h)
            path.close()
            canvas.drawPath(path, paint)

            // Cheatline along the cabin, rising with the tail cone into the fin
            paint.color = accentColor
            path.reset()
            path.moveTo(currentX - size * 0.3f, currentY + h * 0.05f)
            path.lineTo(currentX - size * 2.25f, currentY + h * 0.05f)
            path.lineTo(currentX - size * 2.8f, currentY - h * 0.6f)
            path.lineTo(currentX - size * 2.9f, currentY - h * 0.3f)
            path.lineTo(currentX - size * 2.4f, currentY + h * 0.32f)
            path.lineTo(currentX - size * 0.3f, currentY + h * 0.32f)
            path.close()
            canvas.drawPath(path, paint)

            // Cabin windows: evenly spaced dots above the cheatline
            paint.color = windowColor
            val windowRadius = (size * 0.028f).coerceAtLeast(0.5f)
            val windowY = currentY - h * 0.35f
            for (i in 0 until 14) {
                canvas.drawCircle(currentX - size * (0.62f + i * 0.125f), windowY, windowRadius, paint)
            }

            // Cockpit window at the nose
            path.reset()
            path.moveTo(currentX - size * 0.1f, currentY - h * 0.25f)
            path.lineTo(currentX - size * 0.2f, currentY - h * 0.62f)
            path.lineTo(currentX - size * 0.32f, currentY - h * 0.66f)
            path.lineTo(currentX - size * 0.3f, currentY - h * 0.25f)
            path.close()
            canvas.drawPath(path, paint)

            // Horizontal stabilizer at the tail cone
            paint.color = wingColor
            path.reset()
            path.moveTo(currentX - size * 2.7f, currentY - h * 0.1f)
            path.lineTo(currentX - size * 3.1f, currentY - h * 0.75f)
            path.lineTo(currentX - size * 3.22f, currentY - h * 0.7f)
            path.lineTo(currentX - size * 3.0f, currentY + h * 0.05f)
            path.close()
            canvas.drawPath(path, paint)

            // Near wing: swept back and angled slightly down toward the viewer
            path.reset()
            path.moveTo(currentX - size * 1.05f, currentY + h * 0.4f)
            path.lineTo(currentX - size * 1.75f, currentY + size * 0.42f)
            path.lineTo(currentX - size * 1.95f, currentY + size * 0.42f)
            path.lineTo(currentX - size * 1.75f, currentY + h * 0.4f)
            path.close()
            canvas.drawPath(path, paint)

            // Engine slung under the wing, nacelle poking ahead of the leading edge
            paint.color = shadowColor
            val engineHalfHeight = size * 0.065f
            canvas.drawOval(
                currentX - size * 1.55f, currentY + size * 0.24f - engineHalfHeight,
                currentX - size * 1.17f, currentY + size * 0.24f + engineHalfHeight,
                paint
            )
        }
    }
}
