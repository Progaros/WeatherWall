package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Layer that manages random events like birds, planes, and animals.
 * Events spawn randomly and update/draw until they leave the screen.
 * 
 * Improved version: Path-based rendering, local coordinate systems, better proportions.
 */
class RandomEventLayer(private val context: Context) : SceneLayer {
    
    // Thread-safe event list using volatile + immutable list pattern
    @Volatile private var events: List<RandomEvent> = emptyList()
    
    private val random = Random(System.currentTimeMillis())
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private var width = 0f
    private var height = 0f
    private var eventsEnabled = true
    private var spawnChance = 0.001f  // ~once every 16s at 60fps
    private var lastSpawnCheck = 0L
    
    // Individual event toggles
    private var airplaneEnabled = true
    private var birdsEnabled = true
    private var eagleEnabled = true
    private var butterflyEnabled = true
    private var deerEnabled = true
    private var rabbitEnabled = true
    private var foxEnabled = true
    
    init {
        reloadSettings()
    }
    
    override fun resize(w: Float, h: Float) {
        width = w
        height = h
    }
    
    override fun update(state: SimulationState, deltaTimeMs: Long) {
        if (!eventsEnabled || width <= 0) return
        
        // Update existing events and remove finished ones
        val currentEvents = events.toMutableList()
        val iterator = currentEvents.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (!event.update(deltaTimeMs, state, width, height)) {
                iterator.remove()
            }
        }
        
        // Spawn new events (throttled to avoid too many)
        lastSpawnCheck += deltaTimeMs
        if (lastSpawnCheck > 100) {  // Check every 100ms
            lastSpawnCheck = 0
            if (random.nextFloat() < spawnChance * 6 && currentEvents.size < 5) {
                spawnRandomEvent(state, currentEvents)
            }
        }
        
        events = currentEvents.toList()
    }
    
    private fun spawnRandomEvent(state: SimulationState, currentEvents: MutableList<RandomEvent>) {
        // Build list of available event types based on settings
        val availableEvents = mutableListOf<() -> RandomEvent?>()
        
        if (airplaneEnabled && !state.isNight) availableEvents.add { AirplaneEvent(random) }
        if (birdsEnabled && !state.isNight) availableEvents.add { BirdFlockEvent(random) }
        if (eagleEnabled && !state.isNight) availableEvents.add { EagleEvent(random) }
        if (butterflyEnabled && !state.isNight) availableEvents.add { ButterflyEvent(random) }
        if (deerEnabled) availableEvents.add { DeerEvent(random) }
        if (rabbitEnabled) availableEvents.add { RabbitEvent(random) }
        if (foxEnabled) availableEvents.add { FoxEvent(random) }
        
        if (availableEvents.isEmpty()) return
        
        // Pick random event from available types
        val event = availableEvents[random.nextInt(availableEvents.size)]()
        event?.let { currentEvents.add(it) }
    }
    
    override fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator) {
        if (width <= 0 || height <= 0 || !eventsEnabled) return
        
        val currentEvents = events
        if (currentEvents.isEmpty()) return
        
        // Apply ambient tinting
        val ambientColor = RenderConstants.getAmbientColor(
            state.timeOfDayHours, state.isNight, state.isDawn, state.isDusk
        )
        
        for (event in currentEvents) {
            event.draw(canvas, paint, strokePaint, parallaxCalculator, width, height, 
                state.timeSeconds, ambientColor)
        }
    }
    
    /**
     * Debug method to spawn a random event immediately.
     * Called from settings or debug menu.
     */
    fun debugSpawnEvent() {
        val currentEvents = events.toMutableList()
        
        // Pick a random event type
        val eventTypes = listOf<() -> RandomEvent>(
            { AirplaneEvent(random) },
            { BirdFlockEvent(random) },
            { EagleEvent(random) },
            { ButterflyEvent(random) },
            { DeerEvent(random) },
            { RabbitEvent(random) },
            { FoxEvent(random) }
        )
        
        val event = eventTypes[random.nextInt(eventTypes.size)]()
        currentEvents.add(event)
        events = currentEvents.toList()
        
        android.util.Log.d("RandomEventLayer", "Debug spawned: ${event.javaClass.simpleName}")
    }
    
    override fun reloadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        eventsEnabled = prefs.getBoolean("pref_events_enabled", true)
        val chance = prefs.getInt("pref_event_frequency", 50)
        spawnChance = 0.0002f + (chance / 100f) * 0.002f  // 0.0002 to 0.0022
        
        // Individual event toggles
        airplaneEnabled = prefs.getBoolean("pref_event_airplane", true)
        birdsEnabled = prefs.getBoolean("pref_event_birds", true)
        eagleEnabled = prefs.getBoolean("pref_event_eagle", true)
        butterflyEnabled = prefs.getBoolean("pref_event_butterfly", true)
        deerEnabled = prefs.getBoolean("pref_event_deer", true)
        rabbitEnabled = prefs.getBoolean("pref_event_rabbit", true)
        foxEnabled = prefs.getBoolean("pref_event_fox", true)
    }
    
    override fun destroy() {
        events = emptyList()
    }
}

/**
 * Base interface for random events
 */
interface RandomEvent {
    fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean
    fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint, 
             parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
             timeSeconds: Float, ambientColor: Int)
}

/**
 * Airplane with improved fuselage and fading contrail
 */
class AirplaneEvent(random: Random) : RandomEvent {
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

/**
 * Bird Flock with V-shape strokes
 */
class BirdFlockEvent(random: Random) : RandomEvent {
    private val birds = List(3 + random.nextInt(5)) { Bird(random) }
    private var x = -0.15f
    private val y = 0.15f + random.nextFloat() * 0.25f
    private val speed = 0.0003f + random.nextFloat() * 0.0002f
    private val path = Path()
    
    private class Bird(random: Random) {
        val offsetX = random.nextFloat() * 0.08f
        val offsetY = (random.nextFloat() - 0.5f) * 0.04f
        val wingPhase = random.nextFloat() * 6.28f
        val size = 4f + random.nextFloat() * 3f
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += speed * deltaTimeMs
        return x < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.4f)
        val py = parallaxCalculator.getOffsetYForDepth(0.4f)
        canvas.save()
        canvas.translate(px, py)
        
        strokePaint.color = RenderConstants.blendWithAmbient(Color.rgb(30, 30, 30), ambientColor, 0.4f)
        strokePaint.style = Paint.Style.STROKE
        
        for (bird in birds) {
            val birdX = (x + bird.offsetX) * width
            val birdY = (y + bird.offsetY) * height
            val wingY = sin(timeSeconds * 8f + bird.wingPhase) * bird.size * 0.8f
            
            strokePaint.strokeWidth = (bird.size * 0.35f).coerceAtLeast(1.2f)
            
            // Draw stylized V / gull shape
            path.reset()
            path.moveTo(birdX - bird.size, birdY)
            path.quadTo(birdX - bird.size * 0.2f, birdY - wingY, birdX, birdY)
            path.quadTo(birdX + bird.size * 0.2f, birdY - wingY, birdX + bird.size, birdY)
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }
}

/**
 * Eagle with majestic soaring silhouette
 */
class EagleEvent(random: Random) : RandomEvent {
    private var progress = -0.1f
    private val baseY = 0.2f + random.nextFloat() * 0.15f
    private val speed = 0.0001f + random.nextFloat() * 0.00008f
    private val path = Path()
    private val size = 18f + random.nextFloat() * 8f
    private val soarPhase = random.nextFloat() * 6.28f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        progress += speed * deltaTimeMs
        return progress < 1.3f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.3f)
        val py = parallaxCalculator.getOffsetYForDepth(0.3f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = -0.1f * width + 1.4f * width * progress
        val soarOffset = sin(timeSeconds * 0.8f + soarPhase) * 0.02f
        val currentY = (baseY + soarOffset) * height
        
        strokePaint.color = RenderConstants.blendWithAmbient(Color.rgb(40, 35, 30), ambientColor, 0.35f)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = (size * 0.18f).coerceAtLeast(2f)
        
        val span = size * 2.2f
        val camber = size * 0.45f
        
        // Draw broad M-shaped wings
        path.reset()
        path.moveTo(currentX - span, currentY)
        path.quadTo(currentX - size, currentY - camber, currentX, currentY)
        path.quadTo(currentX + size, currentY - camber, currentX + span, currentY)
        canvas.drawPath(path, strokePaint)
        
        // Head as small circle
        paint.color = strokePaint.color
        canvas.drawCircle(currentX + size * 0.25f, currentY, size * 0.18f, paint)
        
        canvas.restore()
    }
}

/**
 * Butterfly with distinct wing shapes and flutter animation
 */
class ButterflyEvent(random: Random) : RandomEvent {
    private var x = random.nextFloat()
    private val baseY = 0.55f + random.nextFloat() * 0.15f
    private var lifetime = 0L
    private val maxLifetime = 8000L + random.nextLong(5000)
    private val path = Path()
    private val size = 4f + random.nextFloat() * 2f
    private val flutterPhase = random.nextFloat() * 6.28f
    private val color = when (random.nextInt(4)) {
        0 -> Color.rgb(255, 160, 40)   // Monarch Orange
        1 -> Color.rgb(100, 180, 255)  // Blue
        2 -> Color.rgb(255, 240, 80)   // Yellow
        else -> Color.rgb(255, 180, 200) // Pink
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        lifetime += deltaTimeMs
        x += (sin(lifetime * 0.002f + flutterPhase) * 0.0001f) * deltaTimeMs
        return lifetime < maxLifetime
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.7f)
        val py = parallaxCalculator.getOffsetYForDepth(0.7f)
        canvas.save()
        canvas.translate(px, py)
        
        val currentX = x * width
        val flutter = sin(timeSeconds * 18f + flutterPhase)
        val yOffset = sin(timeSeconds * 4f + flutterPhase * 2) * 0.02f
        val currentY = (baseY + yOffset) * height
        
        paint.color = RenderConstants.blendWithAmbient(color, ambientColor, 0.25f)
        
        // Wing shape generator
        fun drawWing(dir: Float) {
            path.reset()
            path.moveTo(currentX, currentY)
            // Top wing
            path.cubicTo(
                currentX + (size * 2f * dir), currentY - size * 2.5f,
                currentX + (size * 3.5f * dir), currentY - size * 0.5f,
                currentX, currentY
            )
            // Bottom wing
            path.cubicTo(
                currentX + (size * 2.5f * dir), currentY + size * 1.5f,
                currentX + (size * 0.5f * dir), currentY + size * 2.5f,
                currentX, currentY
            )
            canvas.drawPath(path, paint)
        }

        val wingScale = flutter
        
        // Draw Left Wing
        canvas.save()
        canvas.scale(if (wingScale > 0) 1f else 0.2f, 1f, currentX, currentY)
        drawWing(-1f)
        canvas.restore()
        
        // Draw Right Wing
        canvas.save()
        canvas.scale(if (wingScale < 0) 1f else 0.2f, 1f, currentX, currentY)
        drawWing(1f)
        canvas.restore()
        
        canvas.restore()
    }
}

/**
 * Deer with organic silhouette using local coordinate system
 */
class DeerEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.78f + random.nextFloat() * 0.08f
    private var isGrazing = false
    private var grazingTimer = 0L
    private var grazingDuration = 2000L + random.nextLong(3000)
    private val path = Path()
    private val size = 0.9f + random.nextFloat() * 0.3f
    private val eventRandom = random
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        if (isGrazing) {
            grazingTimer += deltaTimeMs
            if (grazingTimer > grazingDuration) {
                isGrazing = false
                grazingTimer = 0
            }
        } else {
            x += direction * 0.00005f * deltaTimeMs
            if (eventRandom.nextFloat() < 0.0003f * deltaTimeMs) {
                isGrazing = true
                grazingDuration = 2000L + eventRandom.nextLong(4000)
            }
        }
        return x > -0.2f && x < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.02f)
        val py = parallaxCalculator.getOffsetYForDepth(0.02f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val cy = groundY * height
        val unit = minOf(width, height)
        val s = size * unit * 0.030f
        val flip = if (direction > 0) 1f else -1f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(120, 85, 55), ambientColor, 0.35f)
        strokePaint.color = paint.color
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        // Body
        canvas.drawOval(-2.2f*s, -3.0f*s, 2.0f*s, -1.2f*s, paint)
        // Chest
        canvas.drawOval(0.8f*s, -2.8f*s, 2.6f*s, -1.3f*s, paint)
        // Legs
        canvas.drawRoundRect(-1.6f*s, -1.2f*s, -1.1f*s, 0.6f*s, 0.3f*s, 0.3f*s, paint)
        canvas.drawRoundRect(-0.3f*s, -1.2f*s, 0.2f*s, 0.6f*s, 0.3f*s, 0.3f*s, paint)
        canvas.drawRoundRect(0.9f*s, -1.2f*s, 1.4f*s, 0.6f*s, 0.3f*s, 0.3f*s, paint)
        canvas.drawRoundRect(1.7f*s, -1.2f*s, 2.2f*s, 0.6f*s, 0.3f*s, 0.3f*s, paint)
        
        // Neck + head (grazing position)
        val headY = if (isGrazing) -2.0f*s else -4.2f*s
        strokePaint.strokeWidth = 0.45f*s
        strokePaint.style = Paint.Style.STROKE
        canvas.drawLine(1.6f*s, -2.4f*s, 2.6f*s, headY, strokePaint)
        canvas.drawOval(2.1f*s, headY - 0.6f*s, 3.5f*s, headY + 0.2f*s, paint)
        
        // Antlers (only when head up)
        if (!isGrazing) {
            strokePaint.strokeWidth = 0.20f*s
            val ax = 2.9f*s
            val ay = headY - 0.4f*s
            canvas.drawLine(ax, ay, ax, ay - 1.6f*s, strokePaint)
            canvas.drawLine(ax, ay - 0.7f*s, ax + 0.6f*s, ay - 1.2f*s, strokePaint)
            canvas.drawLine(ax, ay - 0.6f*s, ax - 0.6f*s, ay - 1.1f*s, strokePaint)
        }
        
        canvas.restore()
        canvas.restore()
    }
}

/**
 * Rabbit with bunny silhouette using local coordinate system
 */
class RabbitEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.05f else 1.05f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.85f + random.nextFloat() * 0.05f
    private var hopPhase = 0f
    private var hopTimer = 0L
    private val path = Path()
    private val size = 0.5f + random.nextFloat() * 0.2f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        hopTimer += deltaTimeMs
        hopPhase = (hopTimer * 0.01f) % (2f * 3.14159f)
        if (sin(hopPhase) > 0.5f) {
            x += direction * 0.0002f * deltaTimeMs
        }
        return x > -0.1f && x < 1.1f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.03f)
        val py = parallaxCalculator.getOffsetYForDepth(0.03f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val unit = minOf(width, height)
        val hopHeight = (sin(hopPhase).coerceAtLeast(0f)) * unit * 0.02f * size
        val cy = groundY * height - hopHeight
        val s = size * unit * 0.015f
        val flip = if (direction > 0) 1f else -1f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        // Body + haunch
        canvas.drawOval(-1.8f*s, -0.9f*s, 1.6f*s, 0.8f*s, paint)
        canvas.drawOval(-2.3f*s, -0.6f*s, -1.0f*s, 0.6f*s, paint)
        
        // Head
        canvas.drawOval(1.0f*s, -1.2f*s, 2.4f*s, -0.1f*s, paint)
        
        // Ears
        canvas.drawRoundRect(1.4f*s, -3.0f*s, 1.8f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        canvas.drawRoundRect(1.9f*s, -2.8f*s, 2.3f*s, -1.1f*s, 0.4f*s, 0.4f*s, paint)
        
        // Tail (fluffy white)
        paint.color = Color.rgb(200, 190, 180)
        canvas.drawCircle(-2.2f*s, 0.0f, 0.45f*s, paint)
        
        canvas.restore()
        canvas.restore()
    }
}

/**
 * Fox with proper tail and ears using local coordinate system
 */
class FoxEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.82f + random.nextFloat() * 0.06f
    private var walkPhase = 0f
    private val size = 0.7f + random.nextFloat() * 0.3f
    private val path = Path()
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += direction * 0.00008f * deltaTimeMs
        walkPhase += deltaTimeMs * 0.015f
        return x > -0.15f && x < 1.15f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(0.025f)
        val py = parallaxCalculator.getOffsetYForDepth(0.025f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val cy = groundY * height
        val unit = minOf(width, height)
        val s = size * unit * 0.020f
        val flip = if (direction > 0) 1f else -1f
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(flip, 1f)
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        
        // Tail (big bushy)
        path.reset()
        path.moveTo(-1.5f*s, -0.8f*s)
        path.cubicTo(
            -3.5f*s, -0.5f*s,
            -3.5f*s, -2.0f*s,
            -1.5f*s, -1.5f*s
        )
        path.close()
        canvas.drawPath(path, paint)
        
        // Body
        canvas.drawOval(-2.4f*s, -1.2f*s, 2.0f*s, 0.2f*s, paint)
        
        // Head + snout
        canvas.drawOval(1.7f*s, -1.8f*s, 3.2f*s, -0.6f*s, paint)
        paint.color = Color.rgb(240, 200, 180)
        canvas.drawOval(2.7f*s, -1.4f*s, 3.7f*s, -0.9f*s, paint)
        
        // Legs
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        val step = sin(walkPhase) * 0.25f*s
        canvas.drawRoundRect(-1.6f*s, 0.0f, -1.2f*s, 0.9f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(-0.4f*s, 0.0f, 0.0f*s, 0.9f*s - step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(0.8f*s, 0.0f, 1.2f*s, 0.9f*s + step, 0.2f*s, 0.2f*s, paint)
        canvas.drawRoundRect(1.6f*s, 0.0f, 2.0f*s, 0.9f*s - step, 0.2f*s, 0.2f*s, paint)
        
        // Tail tip (white)
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(-3.6f*s, -1.0f*s, 0.35f*s, paint)
        
        // Ears
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 90, 30), ambientColor, 0.3f)
        path.reset()
        path.moveTo(2.2f*s, -1.6f*s)
        path.lineTo(2.0f*s, -2.4f*s)
        path.lineTo(2.6f*s, -1.6f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(2.8f*s, -1.6f*s)
        path.lineTo(2.9f*s, -2.3f*s)
        path.lineTo(3.2f*s, -1.6f*s)
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
        canvas.restore()
    }
}
