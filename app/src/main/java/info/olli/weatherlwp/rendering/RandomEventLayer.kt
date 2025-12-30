package info.olli.weatherlwp.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Layer that manages random events like birds, planes, and animals.
 * Events spawn randomly and update/draw until they leave the screen.
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
 * Airplane flying across the sky with contrail
 */
class AirplaneEvent(random: Random) : RandomEvent {
    private var progress = -0.1f
    private val y = 0.08f + random.nextFloat() * 0.15f  // High in sky
    private val speed = 0.00015f + random.nextFloat() * 0.0001f
    private val path = Path()
    private val size = 8f + random.nextFloat() * 6f
    
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
        
        // Contrail
        strokePaint.color = Color.argb(60, 255, 255, 255)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(0f.coerceAtLeast(-width * 0.1f), currentY, currentX - size * 2, currentY, strokePaint)
        
        // Plane silhouette
        paint.color = RenderConstants.blendWithAmbient(Color.DKGRAY, ambientColor, 0.3f)
        path.reset()
        path.moveTo(currentX, currentY)  // Nose
        path.lineTo(currentX - size * 2, currentY - size * 0.5f)  // Wing top
        path.lineTo(currentX - size * 1.5f, currentY)  // Body
        path.lineTo(currentX - size * 2, currentY + size * 0.5f)  // Wing bottom
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}

/**
 * Flock of birds flying across
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
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(30, 30, 30), ambientColor, 0.4f)
        
        for (bird in birds) {
            val birdX = (x + bird.offsetX) * width
            val birdY = (y + bird.offsetY) * height
            
            // Flapping animation
            val wingY = sin(timeSeconds * 8f + bird.wingPhase) * bird.size * 0.6f
            
            path.reset()
            path.moveTo(birdX, birdY)  // Body center
            path.lineTo(birdX - bird.size, birdY - wingY)  // Left wing
            path.lineTo(birdX, birdY + 1.5f)  // Tail
            path.lineTo(birdX + bird.size, birdY - wingY)  // Right wing
            path.close()
            canvas.drawPath(path, paint)
        }
        
        canvas.restore()
    }
}

/**
 * Soaring eagle - larger, slower, circular movement
 */
class EagleEvent(random: Random) : RandomEvent {
    private var progress = -0.1f
    private val baseY = 0.2f + random.nextFloat() * 0.15f
    private val speed = 0.0001f + random.nextFloat() * 0.00008f
    private val path = Path()
    private val size = 15f + random.nextFloat() * 8f
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
        // Soaring up and down motion
        val soarOffset = sin(timeSeconds * 0.5f + soarPhase) * 0.03f
        val currentY = (baseY + soarOffset) * height
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(40, 35, 30), ambientColor, 0.35f)
        
        // Eagle shape - wider wings
        path.reset()
        path.moveTo(currentX + size * 0.3f, currentY)  // Head
        path.lineTo(currentX - size * 1.5f, currentY - size * 0.3f)  // Left wing tip
        path.lineTo(currentX - size * 0.5f, currentY)  // Wing base
        path.lineTo(currentX - size * 0.7f, currentY + size * 0.4f)  // Tail
        path.lineTo(currentX - size * 0.5f, currentY)  // Other side
        path.lineTo(currentX - size * 1.5f, currentY + size * 0.3f)  // Right wing tip
        path.close()
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}

/**
 * Butterfly - small, fluttery movement near flowers/ground
 */
class ButterflyEvent(random: Random) : RandomEvent {
    private var x = random.nextFloat()  // Random start position
    private val baseY = 0.55f + random.nextFloat() * 0.15f
    private var lifetime = 0L
    private val maxLifetime = 8000L + random.nextLong(5000)
    private val path = Path()
    private val size = 3f + random.nextFloat() * 2f
    private val flutterPhase = random.nextFloat() * 6.28f
    private val color = when (random.nextInt(4)) {
        0 -> Color.rgb(255, 180, 50)   // Orange
        1 -> Color.rgb(100, 150, 255)  // Blue
        2 -> Color.rgb(255, 255, 100)  // Yellow
        else -> Color.rgb(255, 200, 200)  // Pink
    }
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        lifetime += deltaTimeMs
        // Random wandering
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
        val flutter = sin(timeSeconds * 15f + flutterPhase)
        val yOffset = sin(timeSeconds * 3f + flutterPhase * 2) * 0.02f
        val currentY = (baseY + yOffset) * height
        
        paint.color = RenderConstants.blendWithAmbient(color, ambientColor, 0.25f)
        
        // Butterfly wings
        val wingAngle = flutter * 35f  // Wing flap angle in degrees
        
        // Left wing
        canvas.save()
        canvas.rotate(-wingAngle, currentX, currentY)
        canvas.drawOval(currentX - size * 2, currentY - size, currentX, currentY + size, paint)
        canvas.restore()
        
        // Right wing
        canvas.save()
        canvas.rotate(wingAngle, currentX, currentY)
        canvas.drawOval(currentX, currentY - size, currentX + size * 2, currentY + size, paint)
        canvas.restore()
        
        canvas.restore()
    }
}

/**
 * Deer on a hill - grazes and walks slowly
 */
class DeerEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f  // Start from side
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.78f + random.nextFloat() * 0.08f
    private var grazingTimer = 0L
    private var isGrazing = false
    private var grazingDuration = 2000L + random.nextLong(3000)
    private val path = Path()
    private val size = 0.8f + random.nextFloat() * 0.4f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        if (isGrazing) {
            grazingTimer += deltaTimeMs
            if (grazingTimer > grazingDuration) {
                isGrazing = false
                grazingTimer = 0
            }
        } else {
            x += direction * 0.00005f * deltaTimeMs
            // Random chance to start grazing
            if (kotlin.random.Random.nextFloat() < 0.0003f * deltaTimeMs) {
                isGrazing = true
                grazingDuration = 2000L + kotlin.random.Random.nextLong(4000)
            }
        }
        return x > -0.2f && x < 1.2f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.3f)  // Foreground
        val py = parallaxCalculator.getOffsetYForDepth(-0.3f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val cy = groundY * height
        val s = size * height * 0.03f  // Scale factor
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(120, 85, 55), ambientColor, 0.35f)
        
        // Body
        path.reset()
        val facingRight = direction > 0
        val flip = if (facingRight) 1f else -1f
        
        canvas.drawOval(cx - s * 2, cy - s * 3, cx + s * 2, cy - s, paint)
        
        // Legs
        canvas.drawRect(cx - s * 1.5f, cy - s, cx - s * 0.8f, cy, paint)
        canvas.drawRect(cx + s * 0.8f, cy - s, cx + s * 1.5f, cy, paint)
        
        // Neck and head
        val headY = if (isGrazing) cy - s * 1.5f else cy - s * 4.5f
        val neckX = cx + s * 1.5f * flip
        
        // Neck
        strokePaint.color = paint.color
        strokePaint.strokeWidth = s * 0.5f
        canvas.drawLine(cx + s * flip, cy - s * 2.5f, neckX, headY, strokePaint)
        
        // Head
        canvas.drawCircle(neckX + s * 0.5f * flip, headY, s * 0.8f, paint)
        
        // Antlers (simple)
        strokePaint.strokeWidth = s * 0.2f
        val antlerBase = headY - s * 0.5f
        canvas.drawLine(neckX + s * 0.3f * flip, antlerBase, neckX, antlerBase - s * 1.5f, strokePaint)
        canvas.drawLine(neckX + s * 0.7f * flip, antlerBase, neckX + s * 0.4f * flip, antlerBase - s * 1.3f, strokePaint)
        
        canvas.restore()
    }
}

/**
 * Rabbit - small, hops around on ground
 */
class RabbitEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.05f else 1.05f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.85f + random.nextFloat() * 0.05f
    private var hopPhase = 0f
    private var hopTimer = 0L
    private val path = Path()
    private val size = 0.4f + random.nextFloat() * 0.2f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        hopTimer += deltaTimeMs
        hopPhase = (hopTimer * 0.01f) % (2f * 3.14159f)
        
        // Hop movement
        val hopping = sin(hopPhase) > 0.5f
        if (hopping) {
            x += direction * 0.0002f * deltaTimeMs
        }
        
        return x > -0.1f && x < 1.1f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.2f)
        val py = parallaxCalculator.getOffsetYForDepth(-0.2f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val hopHeight = (sin(hopPhase).coerceAtLeast(0f)) * height * 0.02f * size
        val cy = groundY * height - hopHeight
        val s = size * height * 0.015f
        
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(140, 120, 100), ambientColor, 0.35f)
        
        // Body
        canvas.drawOval(cx - s * 1.5f, cy - s, cx + s * 1.5f, cy + s * 0.8f, paint)
        
        // Head
        val headX = cx + direction * s * 1.2f
        canvas.drawCircle(headX, cy - s * 0.3f, s * 0.7f, paint)
        
        // Ears
        canvas.drawOval(headX - s * 0.3f, cy - s * 1.8f, headX + s * 0.1f, cy - s * 0.5f, paint)
        canvas.drawOval(headX + s * 0.1f, cy - s * 1.6f, headX + s * 0.5f, cy - s * 0.4f, paint)
        
        // Tail
        paint.color = Color.rgb(200, 190, 180)
        canvas.drawCircle(cx - direction * s * 1.3f, cy, s * 0.4f, paint)
        
        canvas.restore()
    }
}

/**
 * Fox - walks across terrain
 */
class FoxEvent(random: Random) : RandomEvent {
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.82f + random.nextFloat() * 0.06f
    private var walkPhase = 0f
    private val size = 0.6f + random.nextFloat() * 0.3f
    
    override fun update(deltaTimeMs: Long, state: SimulationState, width: Float, height: Float): Boolean {
        x += direction * 0.00008f * deltaTimeMs
        walkPhase += deltaTimeMs * 0.015f
        return x > -0.15f && x < 1.15f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        val px = parallaxCalculator.getOffsetXForDepth(-0.25f)
        val py = parallaxCalculator.getOffsetYForDepth(-0.25f)
        canvas.save()
        canvas.translate(px, py)
        
        val cx = x * width
        val cy = groundY * height
        val s = size * height * 0.02f
        val flip = if (direction > 0) 1f else -1f
        
        // Orange body
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 100, 40), ambientColor, 0.3f)
        
        // Body
        canvas.drawOval(cx - s * 2f, cy - s * 1.2f, cx + s * 1.5f, cy, paint)
        
        // Legs with walking animation
        val legOffset = sin(walkPhase) * s * 0.3f
        canvas.drawRect(cx - s * 1.3f, cy - s * 0.3f, cx - s * 0.8f, cy + legOffset * 0.5f, paint)
        canvas.drawRect(cx + s * 0.5f, cy - s * 0.3f, cx + s * 1f, cy - legOffset * 0.5f, paint)
        
        // Head
        val headX = cx + s * 2f * flip
        canvas.drawOval(headX - s * 0.8f, cy - s * 1.6f, headX + s * 0.8f, cy - s * 0.3f, paint)
        
        // Ears
        canvas.drawOval(headX - s * 0.6f, cy - s * 2.2f, headX - s * 0.2f, cy - s * 1.4f, paint)
        canvas.drawOval(headX + s * 0.2f, cy - s * 2.1f, headX + s * 0.6f, cy - s * 1.3f, paint)
        
        // Snout (lighter)
        paint.color = Color.rgb(240, 200, 180)
        canvas.drawOval(headX + s * 0.3f * flip, cy - s * 1.1f, headX + s * 1f * flip, cy - s * 0.5f, paint)
        
        // Tail
        paint.color = RenderConstants.blendWithAmbient(Color.rgb(200, 100, 40), ambientColor, 0.3f)
        val tailX = cx - s * 2.5f * flip
        canvas.drawOval(tailX - s * 0.8f, cy - s * 1.5f, tailX + s * 0.8f, cy - s * 0.3f, paint)
        // White tail tip
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(tailX - s * 0.5f * flip, cy - s * 0.9f, s * 0.35f, paint)
        
        canvas.restore()
    }
}
