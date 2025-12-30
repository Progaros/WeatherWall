package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.random.Random

/**
 * Airplane with improved fuselage and fading contrail
 */
class AirplaneEvent(random: Random) : RandomEvent {
    override val layer = EventLayer.MID_SKY  // In front of mountains, between clouds and terrain
    
    private var progress = -0.1f
    private val y = 0.08f + random.nextFloat() * 0.15f
    private val speed = 0.00015f + random.nextFloat() * 0.0001f
    private val path = Path()
    private val size = 10f + random.nextFloat() * 4f
    private val contrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        progress += speed * deltaTimeMs
        return progress < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.1f)
        val py = parallaxCalculator.getOffsetYForDepth(0.1f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.3f * width * progress
        val currentY = y * height
        
        // Contrail with gradient fade
        val contrailStartX = (currentX - width * 0.4f).coerceAtLeast(0f)
        val contrailEndX = currentX - size * 3
        if (contrailEndX > contrailStartX) {
            contrailPaint.shader = android.graphics.LinearGradient(
                contrailStartX, currentY,
                contrailEndX, currentY,
                Color.argb(0, 255, 255, 255),
                Color.argb(90, 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawLine(contrailStartX, currentY, contrailEndX, currentY, contrailPaint)
            contrailPaint.shader = null
        }
        
        // Plane fuselage (rounded)
        paint.color = RenderConstants.blendWithAmbient(Color.DKGRAY, ambientColor, 0.3f)
        val fusW = size * 3.0f
        val fusH = size * 0.6f
        canvas.drawRoundRect(
            currentX - fusW, currentY - fusH * 0.5f,
            currentX, currentY + fusH * 0.5f,
            fusH, fusH, paint
        )
        
        // Wings (diamond shape)
        path.reset()
        path.moveTo(currentX - size * 1.6f, currentY)
        path.lineTo(currentX - size * 0.9f, currentY - size * 0.9f)
        path.lineTo(currentX - size * 0.4f, currentY)
        path.lineTo(currentX - size * 0.9f, currentY + size * 0.9f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Tail fin
        path.reset()
        path.moveTo(currentX - fusW, currentY)
        path.lineTo(currentX - fusW - size * 0.6f, currentY - size * 0.5f)
        path.lineTo(currentX - fusW - size * 0.6f, currentY + size * 0.5f)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}
