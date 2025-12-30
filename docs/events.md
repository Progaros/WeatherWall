# Adding New Random Events

This guide explains how to create new random events for WeatherWall.

## Event Architecture

Events are defined in `app/src/main/java/info/olli/weatherlwp/rendering/events/`.

Each event implements the `RandomEvent` interface:

```kotlin
interface RandomEvent {
    val layer: EventLayer
        get() = EventLayer.GROUND  // Default
    
    fun update(deltaTimeMs: Long, state: SimulationState, 
               width: Float, height: Float): Boolean
    
    fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint, 
             parallaxCalculator: ParallaxCalculator, 
             width: Float, height: Float,
             timeSeconds: Float, ambientColor: Int)
}
```

## EventLayer Enum

Controls when the event is drawn relative to terrain:

```kotlin
enum class EventLayer {
    FAR_SKY,   // Behind mountains (shooting stars)
    MID_SKY,   // Between mountains and terrain (birds, planes)
    GROUND     // In front of terrain (foxes, rabbits)
}
```

## Step-by-Step: Creating a New Event

### 1. Create the Event Class

Create a new file in `events/` directory:

```kotlin
// DeerEvent.kt
package info.olli.weatherlwp.rendering.events

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import info.olli.weatherlwp.parallax.ParallaxCalculator
import info.olli.weatherlwp.rendering.RenderConstants
import info.olli.weatherlwp.simulation.SimulationState
import kotlin.random.Random

class DeerEvent(random: Random) : RandomEvent {
    // Deer walks on ground, so use GROUND layer
    override val layer = EventLayer.GROUND
    
    // State variables
    private var x = if (random.nextBoolean()) -0.1f else 1.1f
    private val direction = if (x < 0.5f) 1f else -1f
    private val groundY = 0.78f + random.nextFloat() * 0.08f
    private val size = 0.8f + random.nextFloat() * 0.3f
    private var animPhase = 0f
    private val path = Path()
    
    override fun update(deltaTimeMs: Long, state: SimulationState, 
                        width: Float, height: Float): Boolean {
        // Move deer across screen
        x += direction * 0.00008f * deltaTimeMs
        
        // Animate walk cycle
        animPhase = (animPhase + deltaTimeMs * 0.008f) % (2f * Math.PI.toFloat())
        
        // Return false when offscreen to remove event
        return x > -0.15f && x < 1.15f
    }
    
    override fun draw(canvas: Canvas, paint: Paint, strokePaint: Paint,
                      parallaxCalculator: ParallaxCalculator, 
                      width: Float, height: Float,
                      timeSeconds: Float, ambientColor: Int) {
        // Apply parallax for depth
        val depth = 0.02f  // Near foreground
        val px = parallaxCalculator.getOffsetXForDepth(depth)
        val py = parallaxCalculator.getOffsetYForDepth(depth)
        
        canvas.save()
        canvas.translate(px, py)
        
        val screenX = x * width
        val screenY = groundY * height
        val scale = size * 25f
        
        // Draw deer silhouette
        paint.color = RenderConstants.blendWithAmbient(
            Color.rgb(139, 90, 43), ambientColor, 0.3f
        )
        
        // Body
        path.reset()
        path.addOval(
            screenX - scale, screenY - scale * 0.6f,
            screenX + scale, screenY + scale * 0.4f,
            Path.Direction.CW
        )
        canvas.drawPath(path, paint)
        
        // Head
        canvas.drawCircle(
            screenX + direction * scale * 0.9f,
            screenY - scale * 0.4f,
            scale * 0.3f, paint
        )
        
        // Legs (animated)
        val legOffset = kotlin.math.sin(animPhase) * scale * 0.2f
        strokePaint.color = paint.color
        strokePaint.strokeWidth = scale * 0.1f
        
        // Front legs
        canvas.drawLine(
            screenX + direction * scale * 0.5f, screenY,
            screenX + direction * scale * 0.5f + legOffset, screenY + scale * 0.6f,
            strokePaint
        )
        canvas.drawLine(
            screenX + direction * scale * 0.3f, screenY,
            screenX + direction * scale * 0.3f - legOffset, screenY + scale * 0.6f,
            strokePaint
        )
        
        // Back legs
        canvas.drawLine(
            screenX - direction * scale * 0.4f, screenY,
            screenX - direction * scale * 0.4f - legOffset, screenY + scale * 0.6f,
            strokePaint
        )
        
        canvas.restore()
    }
}
```

### 2. Add to RandomEventLayer

In `RandomEventLayer.kt`, add the event to spawn logic:

```kotlin
// Add field for toggle
private var deerEnabled = true

// In spawnRandomEvent(), add spawn condition
if (deerEnabled && !state.isNight) {
    availableEvents.add { DeerEvent(random) }
}

// In spawnSpecificEvent(), add case
"deer" -> DeerEvent(random)

// In reloadSettings(), add preference read
deerEnabled = prefs.getBoolean("pref_event_deer", true)

// In debugSpawnEvent(), add to eventTypes list
{ DeerEvent(random) },
```

### 3. Add Preference

In `res/xml/preferences.xml`:

```xml
<SwitchPreferenceCompat
    android:key="pref_event_deer"
    android:title="Deer"
    android:summary="Deer walking across terrain"
    android:defaultValue="true"
    android:dependency="pref_events_enabled" />
```

## Tips for Great Events

### Positioning
- Use `x` as 0-1 ratio of screen width
- Use `y` as 0-1 ratio of screen height
- Start offscreen: `x = -0.1f` or `x = 1.1f`

### Animation
- Use `deltaTimeMs` for smooth time-based animation
- Store animation phase as class field
- Use `sin()` for oscillating movements

### Colors
- Use `RenderConstants.blendWithAmbient()` for day/night tinting
- Check `state.isNight` for night-only features
- Keep colors muted for cohesive look

### Performance
- Reuse `Path` and `Paint` objects
- Avoid allocations in `update()` and `draw()`
- Keep drawing operations simple

### Parallax
- Use higher depth values (0.05+) for foreground
- Use lower/negative depth (-0.3) for background
- Match depth to visual position

## Testing Your Event

1. Build and install: `./gradlew installDebug`
2. Enable Debug mode in settings
3. Use "Spawn Random Event" button
4. Or add your event to the forced spawn list for testing
