package info.olli.weatherlwp.settings

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Custom view that renders an animated preview of an event type.
 * Used in event preference items to show what the event looks like.
 */
class EventPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class EventType {
        // Sky events
        AIRPLANE, BIRDS, EAGLE, OWL, BALLOON, SHOOTING_STAR, UFO,
        // Ground events
        BUTTERFLY, DRAGONFLY, RABBIT, FOX, SQUIRREL, FIREFLIES, SNOWFLAKE, BAT, DEER
    }

    var eventType: EventType = EventType.BAT
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var animationTime = 0f
    private val frameDelay = 16L // ~60fps

    private val animator = object : Runnable {
        override fun run() {
            animationTime += 0.016f
            if (animationTime > 100f) animationTime = 0f  // Reset to prevent overflow
            invalidate()
            postDelayed(this, frameDelay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(animator)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animator)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val size = minOf(w, h)
        val unit = size * 0.15f  // Larger unit for bigger previews

        // Draw background circle using nearly full height
        paint.color = Color.argb(40, 128, 128, 128)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, size * 0.48f, paint)

        when (eventType) {
            // Sky events
            EventType.AIRPLANE -> drawAirplane(canvas, centerX, centerY, unit)
            EventType.BIRDS -> drawBirds(canvas, centerX, centerY, unit)
            EventType.EAGLE -> drawEagle(canvas, centerX, centerY, unit)
            EventType.OWL -> drawOwl(canvas, centerX, centerY, unit)
            EventType.BALLOON -> drawBalloon(canvas, centerX, centerY, unit)
            EventType.SHOOTING_STAR -> drawShootingStar(canvas, centerX, centerY, unit)
            EventType.UFO -> drawUFO(canvas, centerX, centerY, unit)
            // Ground events
            EventType.BUTTERFLY -> drawButterfly(canvas, centerX, centerY, unit)
            EventType.DRAGONFLY -> drawDragonfly(canvas, centerX, centerY, unit)
            EventType.RABBIT -> drawRabbit(canvas, centerX, centerY, unit)
            EventType.FOX -> drawFox(canvas, centerX, centerY, unit)
            EventType.SQUIRREL -> drawSquirrel(canvas, centerX, centerY, unit)
            EventType.FIREFLIES -> drawFireflies(canvas, centerX, centerY, unit)
            EventType.SNOWFLAKE -> drawSnowflake(canvas, centerX, centerY, unit)
            EventType.BAT -> drawBat(canvas, centerX, centerY, unit)
            EventType.DEER -> drawDeer(canvas, centerX, centerY, unit)
        }
    }

    // ===== SKY EVENTS =====
    
    private fun drawAirplane(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wobble = sin(animationTime * 2f) * unit * 0.1f
        
        paint.color = Color.argb(220, 200, 200, 200)
        paint.style = Paint.Style.FILL
        
        canvas.save()
        canvas.translate(cx, cy + wobble)
        
        // Fuselage
        canvas.drawOval(-unit * 2f, -unit * 0.25f, unit * 2f, unit * 0.25f, paint)
        
        // Wings
        path.reset()
        path.moveTo(-unit * 0.5f, 0f)
        path.lineTo(-unit * 0.8f, -unit * 1.5f)
        path.lineTo(unit * 0.3f, -unit * 1.5f)
        path.lineTo(unit * 0.5f, 0f)
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(-unit * 0.5f, 0f)
        path.lineTo(-unit * 0.8f, unit * 1.5f)
        path.lineTo(unit * 0.3f, unit * 1.5f)
        path.lineTo(unit * 0.5f, 0f)
        canvas.drawPath(path, paint)
        
        // Tail
        path.reset()
        path.moveTo(-unit * 1.8f, 0f)
        path.lineTo(-unit * 2.2f, -unit * 0.8f)
        path.lineTo(-unit * 1.5f, -unit * 0.3f)
        canvas.drawPath(path, paint)
        
        // Contrail
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawOval(-unit * 4f, -unit * 0.15f, -unit * 2f, unit * 0.15f, paint)
        canvas.drawOval(-unit * 5.5f, -unit * 0.1f, -unit * 3.5f, unit * 0.1f, paint)
        
        canvas.restore()
    }
    
    private fun drawBirds(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingBeat = sin(animationTime * 12f) * 0.3f
        
        paint.color = Color.argb(200, 40, 40, 40)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * 0.15f
        paint.strokeCap = Paint.Cap.ROUND
        
        // Draw 5 birds in V-formation
        val birds = arrayOf(
            Pair(0f, 0f),
            Pair(-unit * 1.5f, -unit),
            Pair(-unit * 3f, -unit * 2f),
            Pair(-unit * 1.5f, unit),
            Pair(-unit * 3f, unit * 2f)
        )
        
        for ((dx, dy) in birds) {
            canvas.save()
            canvas.translate(cx + dx, cy + dy)
            
            // Simple bird shape - two lines forming V wings
            val wingUp = wingBeat * unit
            canvas.drawLine(-unit * 0.6f, wingUp, 0f, 0f, paint)
            canvas.drawLine(0f, 0f, unit * 0.6f, wingUp, paint)
            
            canvas.restore()
        }
    }
    
    private fun drawEagle(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingGlide = sin(animationTime * 1.5f) * 5f
        val soar = sin(animationTime * 0.8f) * unit * 0.3f
        
        paint.color = Color.argb(220, 60, 40, 20)
        paint.style = Paint.Style.FILL
        
        canvas.save()
        canvas.translate(cx, cy + soar)
        
        // Body
        canvas.drawOval(-unit * 0.8f, -unit * 0.25f, unit * 0.8f, unit * 0.25f, paint)
        
        // Head
        canvas.drawCircle(unit * 0.9f, -unit * 0.1f, unit * 0.35f, paint)
        
        // Beak
        paint.color = Color.argb(255, 220, 180, 50)
        path.reset()
        path.moveTo(unit * 1.2f, -unit * 0.1f)
        path.lineTo(unit * 1.7f, 0f)
        path.lineTo(unit * 1.2f, unit * 0.1f)
        canvas.drawPath(path, paint)
        
        // Wings (soaring, slight movement)
        paint.color = Color.argb(220, 60, 40, 20)
        canvas.save()
        canvas.rotate(wingGlide)
        path.reset()
        path.moveTo(0f, 0f)
        path.quadTo(-unit * 1.5f, -unit * 0.5f, -unit * 3f, -unit * 0.3f)
        path.lineTo(-unit * 2.5f, unit * 0.2f)
        path.quadTo(-unit, unit * 0.3f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()
        
        canvas.save()
        canvas.rotate(-wingGlide)
        path.reset()
        path.moveTo(0f, 0f)
        path.quadTo(-unit * 1.5f, unit * 0.5f, -unit * 3f, unit * 0.3f)
        path.lineTo(-unit * 2.5f, -unit * 0.2f)
        path.quadTo(-unit, -unit * 0.3f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()
        
        // Tail feathers
        path.reset()
        path.moveTo(-unit * 0.8f, 0f)
        path.lineTo(-unit * 1.5f, -unit * 0.3f)
        path.lineTo(-unit * 1.5f, unit * 0.3f)
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
    
    private fun drawOwl(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingBeat = sin(animationTime * 6f) * 10f
        val eyeBlink = if ((animationTime * 2f).toInt() % 6 == 0) 0.2f else 1f
        
        paint.color = Color.argb(220, 100, 80, 60)
        paint.style = Paint.Style.FILL
        
        canvas.save()
        canvas.translate(cx, cy)
        
        // Body
        canvas.drawOval(-unit * 0.8f, -unit * 0.5f, unit * 0.8f, unit * 0.8f, paint)
        
        // Head
        canvas.drawCircle(0f, -unit * 0.8f, unit * 0.9f, paint)
        
        // Ear tufts
        path.reset()
        path.moveTo(-unit * 0.5f, -unit * 1.2f)
        path.lineTo(-unit * 0.7f, -unit * 1.8f)
        path.lineTo(-unit * 0.2f, -unit * 1.3f)
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(unit * 0.5f, -unit * 1.2f)
        path.lineTo(unit * 0.7f, -unit * 1.8f)
        path.lineTo(unit * 0.2f, -unit * 1.3f)
        canvas.drawPath(path, paint)
        
        // Eyes (big owl eyes)
        paint.color = Color.argb(255, 255, 200, 0)
        canvas.drawOval(-unit * 0.55f, -unit - unit * 0.35f * eyeBlink, -unit * 0.15f, -unit * 0.5f + unit * 0.35f * eyeBlink, paint)
        canvas.drawOval(unit * 0.15f, -unit - unit * 0.35f * eyeBlink, unit * 0.55f, -unit * 0.5f + unit * 0.35f * eyeBlink, paint)
        
        // Pupils
        paint.color = Color.BLACK
        canvas.drawCircle(-unit * 0.35f, -unit * 0.75f, unit * 0.12f * eyeBlink, paint)
        canvas.drawCircle(unit * 0.35f, -unit * 0.75f, unit * 0.12f * eyeBlink, paint)
        
        // Beak
        paint.color = Color.argb(255, 80, 60, 40)
        path.reset()
        path.moveTo(-unit * 0.15f, -unit * 0.5f)
        path.lineTo(0f, -unit * 0.2f)
        path.lineTo(unit * 0.15f, -unit * 0.5f)
        canvas.drawPath(path, paint)
        
        // Wings
        paint.color = Color.argb(220, 100, 80, 60)
        canvas.save()
        canvas.rotate(wingBeat)
        canvas.drawOval(-unit * 2f, -unit * 0.3f, -unit * 0.6f, unit * 0.6f, paint)
        canvas.restore()
        
        canvas.save()
        canvas.rotate(-wingBeat)
        canvas.drawOval(unit * 0.6f, -unit * 0.3f, unit * 2f, unit * 0.6f, paint)
        canvas.restore()
        
        canvas.restore()
    }
    
    private fun drawBalloon(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val sway = sin(animationTime * 1.2f) * unit * 0.3f
        val bob = sin(animationTime * 0.8f) * unit * 0.2f
        
        canvas.save()
        canvas.translate(cx + sway, cy + bob)
        
        // Balloon envelope
        paint.color = Color.argb(220, 220, 60, 60)
        paint.style = Paint.Style.FILL
        canvas.drawOval(-unit * 1.5f, -unit * 2.5f, unit * 1.5f, unit * 0.5f, paint)
        
        // Stripes
        paint.color = Color.argb(220, 255, 200, 50)
        canvas.drawOval(-unit * 0.5f, -unit * 2.4f, unit * 0.5f, unit * 0.4f, paint)
        
        // Basket
        paint.color = Color.argb(220, 139, 90, 43)
        canvas.drawRect(-unit * 0.4f, unit * 0.8f, unit * 0.4f, unit * 1.4f, paint)
        
        // Ropes
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * 0.05f
        paint.color = Color.argb(180, 80, 60, 40)
        canvas.drawLine(-unit * 0.4f, unit * 0.8f, -unit, unit * 0.5f, paint)
        canvas.drawLine(unit * 0.4f, unit * 0.8f, unit, unit * 0.5f, paint)
        
        canvas.restore()
    }
    
    private fun drawShootingStar(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        // Shooting star oscillates within bounds
        val offset = sin(animationTime * 3f) * unit * 0.4f
        val twinkle = 0.7f + sin(animationTime * 15f) * 0.3f
        
        canvas.save()
        canvas.translate(cx + offset, cy - offset)
        
        // Core star
        paint.color = Color.argb((255 * twinkle).toInt(), 255, 255, 200)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, 0f, unit * 0.5f, paint)
        
        // Glow
        paint.color = Color.argb((100 * twinkle).toInt(), 255, 255, 150)
        canvas.drawCircle(0f, 0f, unit * 1.2f, paint)
        
        // Trail (short, stays in bounds)
        val grad = LinearGradient(
            0f, 0f,
            -unit * 2.5f, unit * 2.5f,
            intArrayOf(Color.argb((200 * twinkle).toInt(), 255, 255, 200), Color.argb(60, 255, 200, 100), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = grad
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(-unit * 2f, unit * 2f)
        path.lineTo(-unit * 1.8f, unit * 2.2f)
        path.close()
        canvas.drawPath(path, paint)
        paint.shader = null
        
        canvas.restore()
    }
    
    private fun drawUFO(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val hover = sin(animationTime * 4f) * unit * 0.2f
        val tilt = sin(animationTime * 2f) * 5f
        val lightPhase = (animationTime * 8f).toInt() % 4
        
        canvas.save()
        canvas.translate(cx, cy + hover)
        canvas.rotate(tilt)
        
        // Main saucer body
        paint.color = Color.argb(230, 150, 160, 170)
        paint.style = Paint.Style.FILL
        canvas.drawOval(-unit * 2f, -unit * 0.3f, unit * 2f, unit * 0.5f, paint)
        
        // Dome
        paint.color = Color.argb(150, 180, 220, 255)
        canvas.drawArc(-unit * 0.8f, -unit * 1f, unit * 0.8f, unit * 0.1f, 180f, 180f, true, paint)
        
        // Lights around edge
        val lightColors = arrayOf(
            Color.argb(255, 255, 100, 100),
            Color.argb(255, 100, 255, 100),
            Color.argb(255, 100, 100, 255),
            Color.argb(255, 255, 255, 100)
        )
        for (i in 0 until 4) {
            val angle = (i * 90f + animationTime * 60f) * Math.PI.toFloat() / 180f
            val lx = kotlin.math.cos(angle) * unit * 1.5f
            val ly = kotlin.math.sin(angle) * unit * 0.25f
            paint.color = if (i == lightPhase) lightColors[i] else Color.argb(100, 150, 150, 150)
            canvas.drawCircle(lx, ly, unit * 0.2f, paint)
        }
        
        canvas.restore()
    }
    
    // ===== GROUND EVENTS =====
    
    private fun drawBat(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingBeat = sin(animationTime * 20f)
        val wingAngle = wingBeat * 35f

        paint.color = Color.argb(200, 30, 30, 35)
        paint.style = Paint.Style.FILL

        canvas.save()
        canvas.translate(cx, cy)

        // Body
        canvas.drawOval(-unit * 0.6f, -unit * 0.3f, unit * 0.6f, unit * 0.3f, paint)

        // Left wing
        canvas.save()
        canvas.rotate(-wingAngle)
        path.reset()
        path.moveTo(0f, 0f)
        path.quadTo(-unit, -unit * 1.5f, -unit * 2.5f, -unit * 0.5f)
        path.lineTo(-unit * 2f, unit * 0.2f)
        path.quadTo(-unit * 0.8f, unit * 0.3f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()

        // Right wing
        canvas.save()
        canvas.rotate(wingAngle)
        path.reset()
        path.moveTo(0f, 0f)
        path.quadTo(unit, -unit * 1.5f, unit * 2.5f, -unit * 0.5f)
        path.lineTo(unit * 2f, unit * 0.2f)
        path.quadTo(unit * 0.8f, unit * 0.3f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()

        // Ears
        path.reset()
        path.moveTo(-unit * 0.3f, -unit * 0.2f)
        path.lineTo(-unit * 0.4f, -unit * 0.7f)
        path.lineTo(-unit * 0.1f, -unit * 0.3f)
        path.moveTo(unit * 0.3f, -unit * 0.2f)
        path.lineTo(unit * 0.4f, -unit * 0.7f)
        path.lineTo(unit * 0.1f, -unit * 0.3f)
        canvas.drawPath(path, paint)

        canvas.restore()
    }

    private fun drawDeer(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val legPhase = sin(animationTime * 4f) * 0.15f

        paint.color = Color.argb(220, 139, 90, 43)  // Brown
        paint.style = Paint.Style.FILL
        
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 80, 50, 25)
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.15f
        }

        canvas.save()
        canvas.translate(cx - unit, cy + unit * 0.5f)

        // Body (horizontal oval)
        canvas.drawOval(-unit * 1.5f, -unit * 0.8f, unit * 1.5f, unit * 0.8f, paint)

        // Head
        canvas.drawOval(unit * 1.2f, -unit * 1.5f, unit * 2.2f, -unit * 0.5f, paint)

        // Legs with walking animation
        val frontLegOffset = legPhase * unit * 2f
        val backLegOffset = -legPhase * unit * 2f
        
        // Front legs
        canvas.drawLine(unit * 0.8f, unit * 0.5f, unit * 0.8f + frontLegOffset, unit * 2.2f, strokePaint)
        canvas.drawLine(unit * 0.4f, unit * 0.5f, unit * 0.4f - frontLegOffset, unit * 2.2f, strokePaint)
        
        // Back legs
        canvas.drawLine(-unit * 0.8f, unit * 0.5f, -unit * 0.8f + backLegOffset, unit * 2.2f, strokePaint)
        canvas.drawLine(-unit * 1.2f, unit * 0.5f, -unit * 1.2f - backLegOffset, unit * 2.2f, strokePaint)

        // Antlers
        val antlerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 100, 70, 40)
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.1f
        }
        canvas.drawLine(unit * 1.5f, -unit * 1.4f, unit * 1.2f, -unit * 2.5f, antlerPaint)
        canvas.drawLine(unit * 1.3f, -unit * 2f, unit * 1f, -unit * 2.2f, antlerPaint)
        canvas.drawLine(unit * 1.8f, -unit * 1.4f, unit * 2.1f, -unit * 2.5f, antlerPaint)
        canvas.drawLine(unit * 2f, -unit * 2f, unit * 2.3f, -unit * 2.2f, antlerPaint)

        canvas.restore()
    }

    private fun drawDragonfly(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingBeat = sin(animationTime * 30f)
        val bodyWobble = sin(animationTime * 5f) * unit * 0.2f

        paint.style = Paint.Style.FILL

        canvas.save()
        canvas.translate(cx + bodyWobble, cy)

        // Body (long and thin)
        paint.color = Color.argb(230, 50, 150, 200)
        canvas.drawOval(-unit * 0.2f, -unit * 2f, unit * 0.2f, unit * 2f, paint)

        // Head
        canvas.drawCircle(0f, -unit * 2.3f, unit * 0.4f, paint)

        // Wings (4 wings)
        paint.color = Color.argb(100, 200, 220, 255)
        val wingLength = unit * 2.5f
        val wingWidth = unit * 0.8f
        val wingOffset = wingBeat * 15f

        // Upper left
        canvas.save()
        canvas.rotate(-30f + wingOffset)
        canvas.drawOval(-wingLength, -wingWidth * 0.3f, 0f, wingWidth * 0.3f, paint)
        canvas.restore()

        // Upper right
        canvas.save()
        canvas.rotate(30f - wingOffset)
        canvas.drawOval(0f, -wingWidth * 0.3f, wingLength, wingWidth * 0.3f, paint)
        canvas.restore()

        // Lower left
        canvas.save()
        canvas.translate(0f, unit * 0.5f)
        canvas.rotate(-35f - wingOffset)
        canvas.drawOval(-wingLength * 0.9f, -wingWidth * 0.25f, 0f, wingWidth * 0.25f, paint)
        canvas.restore()

        // Lower right
        canvas.save()
        canvas.translate(0f, unit * 0.5f)
        canvas.rotate(35f + wingOffset)
        canvas.drawOval(0f, -wingWidth * 0.25f, wingLength * 0.9f, wingWidth * 0.25f, paint)
        canvas.restore()

        canvas.restore()
    }

    private fun drawSquirrel(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val tailWag = sin(animationTime * 6f) * 10f

        paint.color = Color.argb(230, 139, 90, 43)  // Brown
        paint.style = Paint.Style.FILL

        canvas.save()
        canvas.translate(cx, cy + unit)

        // Body
        canvas.drawOval(-unit * 1.2f, -unit * 0.7f, unit * 0.8f, unit * 0.7f, paint)

        // Head
        canvas.drawCircle(unit * 1f, -unit * 0.2f, unit * 0.7f, paint)

        // Ears
        canvas.drawCircle(unit * 0.7f, -unit * 0.9f, unit * 0.25f, paint)
        canvas.drawCircle(unit * 1.2f, -unit * 0.9f, unit * 0.25f, paint)

        // Eye
        paint.color = Color.BLACK
        canvas.drawCircle(unit * 1.2f, -unit * 0.3f, unit * 0.1f, paint)

        // Tail (bushy, animated)
        paint.color = Color.argb(230, 139, 90, 43)
        canvas.save()
        canvas.translate(-unit * 1f, -unit * 0.3f)
        canvas.rotate(tailWag - 45f)
        canvas.drawOval(-unit * 0.5f, -unit * 2f, unit * 0.5f, unit * 0.3f, paint)
        canvas.restore()

        canvas.restore()
    }
    
    private fun drawRabbit(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val hop = kotlin.math.abs(sin(animationTime * 6f)) * unit * 0.5f
        val earWiggle = sin(animationTime * 8f) * 5f
        
        paint.color = Color.argb(220, 180, 160, 140)
        paint.style = Paint.Style.FILL
        
        canvas.save()
        canvas.translate(cx, cy + unit - hop)
        
        // Body
        canvas.drawOval(-unit * 0.9f, -unit * 0.5f, unit * 0.7f, unit * 0.6f, paint)
        
        // Head
        canvas.drawCircle(unit * 0.8f, -unit * 0.3f, unit * 0.6f, paint)
        
        // Ears (long bunny ears)
        canvas.save()
        canvas.translate(unit * 0.5f, -unit * 0.7f)
        canvas.rotate(earWiggle - 10f)
        canvas.drawOval(-unit * 0.15f, -unit * 1.5f, unit * 0.15f, 0f, paint)
        canvas.restore()
        
        canvas.save()
        canvas.translate(unit * 0.9f, -unit * 0.7f)
        canvas.rotate(-earWiggle + 10f)
        canvas.drawOval(-unit * 0.15f, -unit * 1.5f, unit * 0.15f, 0f, paint)
        canvas.restore()
        
        // Eye
        paint.color = Color.BLACK
        canvas.drawCircle(unit * 1f, -unit * 0.35f, unit * 0.08f, paint)
        
        // Nose
        paint.color = Color.argb(255, 255, 180, 180)
        canvas.drawCircle(unit * 1.3f, -unit * 0.2f, unit * 0.1f, paint)
        
        // Fluffy tail
        paint.color = Color.WHITE
        canvas.drawCircle(-unit * 0.9f, unit * 0.2f, unit * 0.35f, paint)
        
        // Legs (when hopping)
        if (hop > unit * 0.2f) {
            paint.color = Color.argb(220, 180, 160, 140)
            // Back leg extended
            canvas.drawOval(-unit * 0.6f, unit * 0.3f, -unit * 0.2f, unit * 0.8f, paint)
            // Front leg tucked
            canvas.drawOval(unit * 0.2f, unit * 0.2f, unit * 0.5f, unit * 0.5f, paint)
        }
        
        canvas.restore()
    }
    
    private fun drawFox(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val walkPhase = sin(animationTime * 4f) * 0.12f
        val tailSway = sin(animationTime * 3f) * 15f
        
        paint.color = Color.argb(230, 220, 100, 30)  // Orange
        paint.style = Paint.Style.FILL
        
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 80, 40, 15)
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.12f
        }
        
        canvas.save()
        canvas.translate(cx - unit * 0.5f, cy + unit * 0.5f)
        
        // Body
        canvas.drawOval(-unit * 1.3f, -unit * 0.6f, unit * 1.3f, unit * 0.6f, paint)
        
        // Head (pointed fox face)
        path.reset()
        path.moveTo(unit * 1.3f, 0f)
        path.lineTo(unit * 2.5f, -unit * 0.3f)
        path.lineTo(unit * 2.5f, unit * 0.3f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawCircle(unit * 1.3f, -unit * 0.2f, unit * 0.6f, paint)
        
        // Ears (triangular)
        path.reset()
        path.moveTo(unit * 1f, -unit * 0.6f)
        path.lineTo(unit * 0.8f, -unit * 1.4f)
        path.lineTo(unit * 1.4f, -unit * 0.7f)
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(unit * 1.5f, -unit * 0.6f)
        path.lineTo(unit * 1.6f, -unit * 1.3f)
        path.lineTo(unit * 1.9f, -unit * 0.6f)
        canvas.drawPath(path, paint)
        
        // White chest/muzzle
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(unit * 1.5f, -unit * 0.1f)
        path.lineTo(unit * 2.4f, -unit * 0.15f)
        path.lineTo(unit * 2.4f, unit * 0.15f)
        path.lineTo(unit * 1.5f, unit * 0.3f)
        path.close()
        canvas.drawPath(path, paint)
        
        // Eye
        paint.color = Color.BLACK
        canvas.drawCircle(unit * 1.6f, -unit * 0.35f, unit * 0.08f, paint)
        
        // Nose
        canvas.drawCircle(unit * 2.4f, 0f, unit * 0.1f, paint)
        
        // Legs
        val frontLegOffset = walkPhase * unit * 2f
        val backLegOffset = -walkPhase * unit * 2f
        canvas.drawLine(unit * 0.7f, unit * 0.4f, unit * 0.7f + frontLegOffset, unit * 1.8f, strokePaint)
        canvas.drawLine(unit * 0.3f, unit * 0.4f, unit * 0.3f - frontLegOffset, unit * 1.8f, strokePaint)
        canvas.drawLine(-unit * 0.7f, unit * 0.4f, -unit * 0.7f + backLegOffset, unit * 1.8f, strokePaint)
        canvas.drawLine(-unit * 1f, unit * 0.4f, -unit * 1f - backLegOffset, unit * 1.8f, strokePaint)
        
        // Bushy tail
        paint.color = Color.argb(230, 220, 100, 30)
        canvas.save()
        canvas.translate(-unit * 1.2f, -unit * 0.2f)
        canvas.rotate(tailSway - 30f)
        canvas.drawOval(-unit * 1.8f, -unit * 0.4f, 0f, unit * 0.4f, paint)
        // White tail tip
        paint.color = Color.WHITE
        canvas.drawOval(-unit * 1.8f, -unit * 0.25f, -unit * 1.2f, unit * 0.25f, paint)
        canvas.restore()
        
        canvas.restore()
    }
    
    private fun drawFireflies(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        // Multiple fireflies with independent glow phases
        val fireflies = arrayOf(
            Triple(0.3f, 0.2f, 0f),
            Triple(-0.5f, -0.3f, 1.5f),
            Triple(0.6f, -0.5f, 3f),
            Triple(-0.2f, 0.5f, 4.5f),
            Triple(-0.7f, 0.1f, 2f)
        )
        
        paint.style = Paint.Style.FILL
        
        for ((px, py, phase) in fireflies) {
            val glowPhase = animationTime * 3f + phase
            val glow = (sin(glowPhase) * 0.5f + 0.5f)
            val drift = sin(animationTime * 2f + phase) * unit * 0.15f
            
            val fx = cx + px * unit * 2.5f + drift
            val fy = cy + py * unit * 2f + drift * 0.5f
            
            if (glow > 0.3f) {
                // Outer glow
                paint.color = Color.argb((80 * glow).toInt(), 200, 255, 100)
                canvas.drawCircle(fx, fy, unit * 0.6f * glow, paint)
                
                // Inner glow
                paint.color = Color.argb((200 * glow).toInt(), 255, 255, 150)
                canvas.drawCircle(fx, fy, unit * 0.25f * glow, paint)
            }
            
            // Firefly body (tiny dark dot)
            paint.color = Color.argb(150, 40, 40, 30)
            canvas.drawCircle(fx, fy, unit * 0.12f, paint)
        }
    }

    private fun drawSnowflake(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val rotation = animationTime * 30f
        val pulse = 1f + sin(animationTime * 3f) * 0.1f

        paint.color = Color.argb(220, 200, 220, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * 0.15f
        paint.strokeCap = Paint.Cap.ROUND

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        canvas.scale(pulse, pulse)

        // 6 main branches
        for (i in 0 until 6) {
            canvas.save()
            canvas.rotate(i * 60f)
            
            // Main branch
            canvas.drawLine(0f, 0f, 0f, -unit * 2f, paint)
            
            // Side branches
            canvas.drawLine(0f, -unit, -unit * 0.5f, -unit * 1.5f, paint)
            canvas.drawLine(0f, -unit, unit * 0.5f, -unit * 1.5f, paint)
            canvas.drawLine(0f, -unit * 1.5f, -unit * 0.3f, -unit * 1.8f, paint)
            canvas.drawLine(0f, -unit * 1.5f, unit * 0.3f, -unit * 1.8f, paint)
            
            canvas.restore()
        }

        canvas.restore()
    }

    private fun drawButterfly(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val wingBeat = sin(animationTime * 8f)
        val wingScale = 0.6f + wingBeat * 0.4f

        paint.style = Paint.Style.FILL

        canvas.save()
        canvas.translate(cx, cy)

        // Body
        paint.color = Color.argb(230, 60, 40, 30)
        canvas.drawOval(-unit * 0.15f, -unit * 1.2f, unit * 0.15f, unit * 1.2f, paint)

        // Head
        canvas.drawCircle(0f, -unit * 1.5f, unit * 0.25f, paint)

        // Antennae
        paint.strokeWidth = unit * 0.08f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(-unit * 0.1f, -unit * 1.6f, -unit * 0.5f, -unit * 2.2f, paint)
        canvas.drawLine(unit * 0.1f, -unit * 1.6f, unit * 0.5f, -unit * 2.2f, paint)

        // Wings (orange monarch style)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 255, 140, 0)

        // Left upper wing
        canvas.save()
        canvas.scale(-wingScale, 1f)
        path.reset()
        path.moveTo(0f, -unit * 0.5f)
        path.quadTo(-unit * 2f, -unit * 2f, -unit * 2f, 0f)
        path.quadTo(-unit * 1f, unit * 0.5f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()

        // Right upper wing
        canvas.save()
        canvas.scale(wingScale, 1f)
        path.reset()
        path.moveTo(0f, -unit * 0.5f)
        path.quadTo(unit * 2f, -unit * 2f, unit * 2f, 0f)
        path.quadTo(unit * 1f, unit * 0.5f, 0f, 0f)
        canvas.drawPath(path, paint)
        canvas.restore()

        // Lower wings
        paint.color = Color.argb(200, 255, 120, 0)

        // Left lower
        canvas.save()
        canvas.scale(-wingScale, 1f)
        path.reset()
        path.moveTo(0f, unit * 0.2f)
        path.quadTo(-unit * 1.5f, unit * 0.5f, -unit * 1.5f, unit * 1.5f)
        path.quadTo(-unit * 0.5f, unit * 1.2f, 0f, unit * 0.8f)
        canvas.drawPath(path, paint)
        canvas.restore()

        // Right lower
        canvas.save()
        canvas.scale(wingScale, 1f)
        path.reset()
        path.moveTo(0f, unit * 0.2f)
        path.quadTo(unit * 1.5f, unit * 0.5f, unit * 1.5f, unit * 1.5f)
        path.quadTo(unit * 0.5f, unit * 1.2f, 0f, unit * 0.8f)
        canvas.drawPath(path, paint)
        canvas.restore()

        canvas.restore()
    }
}
