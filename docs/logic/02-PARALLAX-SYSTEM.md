# Parallax System

## Overview

The parallax effect creates depth perception by moving layers at different speeds based on device tilt. Closer objects move more than distant ones.

## Sensor Input

### ParallaxSensorManager

Located in `parallax/ParallaxSensorManager.kt`:

```kotlin
class ParallaxSensorManager(context: Context) : SensorEventListener {
    // Uses SENSOR_DELAY_UI (60ms) for battery efficiency
    private val alpha = 0.85f  // Low-pass filter smoothing factor
    
    override fun onSensorChanged(event: SensorEvent) {
        // Apply low-pass filter for smooth movement
        ax = alpha * ax + (1 - alpha) * event.values[0]
        ay = alpha * ay + (1 - alpha) * event.values[1]
        
        // Normalize to -1..1 range
        val tiltX = (ax / 9.81f).coerceIn(-1f, 1f)
        val tiltY = (ay / 9.81f).coerceIn(-1f, 1f)
        
        // Convert to pixel offset (150px sensitivity)
        listener?.onOffsetChanged(tiltX * 150f, tiltY * 150f)
    }
}
```

### Key Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Sensor Delay | `SENSOR_DELAY_UI` (60ms) | Balance responsiveness vs battery |
| Smoothing (alpha) | 0.85 | Higher = smoother but slower response |
| Sensitivity | 150px | Max pixel offset at full tilt |

## Parallax Depth Factors

Each layer has a depth factor controlling movement intensity:

| Layer | Depth Factor | Movement |
|-------|--------------|----------|
| Sky | -0.15 | Opposite direction (depth illusion) |
| Stars | 0.30 | Move with distant mountains |
| Clouds | 0.08 | Subtle, behind mountains |
| Mountains (far) | 0.25 | Minimal movement |
| Mountains (near) | 0.45 | More noticeable |
| Hills | 0.05-0.12 | Foreground, barely move |
| Trees | 0.05-0.12 | Match hills, closest to viewer |

## World Coordinate System

The scene uses normalized "world coordinates" that extend beyond the visible screen:

```
WORLD_MIN_X = -4.0f  ───────────────────────────────  WORLD_MAX_X = 5.0f
                      │←── Visible Screen ──→│
                      0.0                   1.0
```

This allows:
1. Smooth parallax without pop-in
2. Support for wide aspect ratios (e.g., 4:1 preview strips)
3. Natural terrain continuation off-screen

### World to Screen Conversion

```kotlin
// scaleX maintains aspect ratio based on height
val scaleX = h * 0.5625f  // 9:16 ratio reference

// Convert world X to screen X
val screenX = (worldX - 0.5f) * scaleX + w / 2f
```

## Center Drift

To prevent parallax getting "stuck" at extreme angles:

```kotlin
// Slowly drift offset toward center
ox *= (1f - centerDriftSpeed)  // centerDriftSpeed = 0.002f
oy *= (1f - centerDriftSpeed)
```

This creates a "floating" effect where the scene slowly returns to neutral position.

## Parallax Strength Setting

User-configurable via `pref_parallax_strength` (0-100):

```kotlin
parallaxStrength = prefs.getInt("pref_parallax_strength", 50) / 50f
// Result: 0 = no parallax, 1.0 = default, 2.0 = maximum
```

## Implementation in ParallaxScene

```kotlin
override fun onOffsetChanged(x: Float, y: Float) {
    targetOx = x * parallaxStrength
    targetOy = y * parallaxStrength
    
    // Reduce immediate effect slightly for smoother feel
    ox = targetOx * 0.85f
    oy = targetOy * 0.85f
}

// During render, apply per-layer
canvas.save()
canvas.translate(ox * mountainParallax, oy * mountainParallax)
drawMountain(canvas, ...)
canvas.restore()
```

## Battery Optimization

1. Sensor only active when wallpaper is visible
2. Uses `SENSOR_DELAY_UI` instead of `SENSOR_DELAY_GAME`
3. Low-pass filter reduces processing overhead
4. Sensors stopped in `onSurfaceDestroyed` and `onVisibilityChanged(false)`

