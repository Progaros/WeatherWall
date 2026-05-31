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
 * Snowflakes gently falling - special winter-only ambient event.
 * Different from weather snow - these are sparse, decorative snowflakes.
 */
class SnowflakeEvent(random: Random, override val spawnTimeMs: Long = System.currentTimeMillis()) : RandomEvent {
    override val layer = EventLayer.MID_SKY
    
    // Snowflakes at various depths
    private val distance = 0.2f + random.nextFloat() * 0.6f
    private val sizeScale = 1.0f - distance * 0.5f
    
    private val startX = random.nextFloat()
    private val startY = -0.05f  // Start above screen
    override val durationMs = 12000L + random.nextLong(8000)  // Slow fall
    
    // Fall speed - gentle drift
    private val fallSpeed = 0.00003f + random.nextFloat() * 0.00002f
    private val driftSpeed = 0.00001f + random.nextFloat() * 0.00001f
    private val driftFreq = 0.5f + random.nextFloat() * 0.5f
    private val driftPhase = random.nextFloat() * 6.28f
    
    // Rotation
    private val rotationSpeed = (random.nextFloat() - 0.5f) * 60f  // Degrees per second
    
    // Parallax
    private val parallaxDepth = 0.3f + distance * 0.3f
    
    // Snowflake type (simple 6-pointed crystal)
    private val armCount = 6
    private val branchRatio = 0.4f + random.nextFloat() * 0.2f
    
    private val path = Path()

    override fun getPreviewAnchorXRatio(absoluteTimeMs: Long): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        return startX + sin(t * driftFreq + driftPhase) * 0.05f + t * driftSpeed
    }

    override fun getPreviewAnchorYRatio(absoluteTimeMs: Long, hillHeightProvider: HillHeightProvider?): Float {
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        return startY + fallSpeed * elapsedMs
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int, extendedOffset: Float, absoluteTimeMs: Long,
                      hillHeightProvider: HillHeightProvider?, extendedOffsetY: Float) {
        val unit = height * 0.004f * sizeScale
        
        val elapsedMs = absoluteTimeMs - spawnTimeMs
        val t = elapsedMs / 1000f
        
        // Gentle falling with horizontal drift
        val x = startX + sin(t * driftFreq + driftPhase) * 0.05f + t * driftSpeed
        val y = startY + fallSpeed * elapsedMs
        
        // Stop at bottom
        if (y > 1.05f) return
        
        val px = parallaxCalculator.getOffsetXForDepth(parallaxDepth)
        val py = parallaxCalculator.getOffsetYForDepth(parallaxDepth)
        canvas.save()
        canvas.translate(px, py)
        
        val screenX = x * width - extendedOffset
        val screenY = EventTraversal.mapFieldY(y, height, extendedOffsetY)
        
        // Snowflake color - white with slight blue tint, adapts to ambient
        val baseColor = Color.argb(230, 240, 248, 255)
        strokePaint.color = RenderConstants.blendWithAmbient(baseColor, ambientColor, 0.3f)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = (unit * 0.15f).coerceAtLeast(1f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        
        canvas.save()
        canvas.translate(screenX, screenY)
        canvas.rotate(t * rotationSpeed)
        
        // Draw 6-pointed snowflake
        val armLength = unit * 3f
        for (i in 0 until armCount) {
            val angle = (i * 360f / armCount)
            canvas.save()
            canvas.rotate(angle)
            
            // Main arm
            canvas.drawLine(0f, 0f, 0f, -armLength, strokePaint)
            
            // Branch on each arm
            val branchY = -armLength * branchRatio
            val branchLen = armLength * 0.4f
            canvas.drawLine(0f, branchY, -branchLen * 0.5f, branchY - branchLen * 0.5f, strokePaint)
            canvas.drawLine(0f, branchY, branchLen * 0.5f, branchY - branchLen * 0.5f, strokePaint)
            
            // Smaller branch near tip
            val branch2Y = -armLength * 0.75f
            val branch2Len = armLength * 0.25f
            canvas.drawLine(0f, branch2Y, -branch2Len * 0.5f, branch2Y - branch2Len * 0.5f, strokePaint)
            canvas.drawLine(0f, branch2Y, branch2Len * 0.5f, branch2Y - branch2Len * 0.5f, strokePaint)
            
            canvas.restore()
        }
        
        canvas.restore()
        canvas.restore()
    }
}
