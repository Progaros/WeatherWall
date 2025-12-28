# Weather Effects

## Overview

Weather effects include Rain, Snow, Lightning, and Fog. The system supports both automatic weather from Breezy Weather and manual selection.

## Weather Types

```kotlin
enum class WeatherType {
    NONE,
    RAIN,
    SNOW,
    LIGHTNING,
    FOG
}
```

## Particle System

### Pre-allocated Pools

```kotlin
// Max pool sizes - actual count depends on intensity
private val rainParticles = Array(300) { Particle() }
private val snowParticles = Array(200) { Particle() }

private data class Particle(
    var x: Float = 0f,
    var y: Float = 0f,
    var speed: Float = 0f,
    var size: Float = 0f,
    var alpha: Int = 255
)
```

### Intensity Control

Intensity (0.0-1.0) controls active particle count:

```kotlin
fun setIntensity(value: Float) {
    intensity = value.coerceIn(0f, 1f)
}

// Rain: 30-100% of pool active
val activeCount = (rainParticles.size * (0.3f + intensity * 0.7f)).toInt()

// Snow: 20-100% of pool active  
val activeCount = (snowParticles.size * (0.2f + intensity * 0.8f)).toInt()
```

## Rain

### Particle Properties

| Property | Range | Purpose |
|----------|-------|---------|
| Speed | 15-25 | Fall speed (px/frame) |
| Size | 15-40 | Rain drop length |
| Alpha | 120-200 | Transparency |

### Rendering

```kotlin
private fun renderRain(canvas: Canvas) {
    for (i in 0 until activeCount) {
        val p = rainParticles[i]
        
        // Draw angled line (slight wind effect)
        canvas.drawLine(p.x, p.y, p.x - 2f, p.y + p.size, rainPaint)
        
        // Update position
        val speedMult = 0.7f + intensity * 0.6f
        p.y += p.speed * speedMult
        p.x -= 1f * speedMult  // Wind drift
        
        if (p.y > screenHeight) resetRainParticle(p)
    }
}
```

### Rain Paint

```kotlin
private val rainPaint = Paint().apply {
    color = Color.argb(180, 150, 180, 220)  // Light blue
    strokeWidth = 2f
    isAntiAlias = true
}
```

## Snow

### Particle Properties

| Property | Range | Purpose |
|----------|-------|---------|
| Speed | 1.5-3.5 | Slow fall |
| Size | 3-8 | Snowflake radius |
| Alpha | 180-255 | White, mostly opaque |

### Rendering

```kotlin
private fun renderSnow(canvas: Canvas) {
    for (i in 0 until activeCount) {
        val p = snowParticles[i]
        
        // Draw circle
        canvas.drawCircle(p.x, p.y, p.size, snowPaint)
        
        // Horizontal drift (sine wave wobble)
        p.x += sin(p.y * 0.02f) * 0.8f
        p.y += p.speed * speedMult
    }
}
```

## Lightning

### Mechanism

1. Timer counts up each frame
2. At random interval (120-300 frames), trigger flash
3. Flash lasts 3 frames
4. Generate jagged bolt path

```kotlin
private fun renderLightning(canvas: Canvas) {
    lightningTimer++
    
    if (lightningTimer > 120 + Random.nextInt(180)) {
        lightningTimer = 0
        lightningFlash = true
        lightningX = screenWidth * 0.2f + Random.nextFloat() * screenWidth * 0.6f
        generateLightningBolt()
    }
    
    if (lightningFlash) {
        // Full-screen flash overlay
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, flashPaint)
        
        // Draw bolt segments
        for (i in lightningPoints.indices step 2) {
            canvas.drawLine(prevX, prevY, points[i], points[i+1], lightningPaint)
        }
        
        if (lightningTimer > 3) lightningFlash = false
    }
}
```

### Bolt Generation

```kotlin
private fun generateLightningBolt() {
    var x = lightningX
    var y = 0f
    val segmentHeight = screenHeight / (points.size / 2f)
    
    for (i in points.indices step 2) {
        x += Random.nextFloat() * 60f - 30f  // Zigzag
        y += segmentHeight
        points[i] = x
        points[i + 1] = y
    }
}
```

## Fog

### Gradient Overlay

```kotlin
private fun renderFog(canvas: Canvas) {
    val baseAlpha = (75 + intensity * 180).toInt()  // 75-255 based on intensity
    
    // Main gradient: clear at top, dense at bottom
    val gradient = LinearGradient(
        0f, screenHeight * 0.2f,  // Start 20% down
        0f, screenHeight,
        Color.argb(0, 200, 210, 220),      // Transparent
        Color.argb(baseAlpha, 200, 210, 220),  // Dense
        TileMode.CLAMP
    )
    
    fogPaint.shader = gradient
    canvas.drawRect(0f, 0f, screenWidth, screenHeight, fogPaint)
}
```

### Animated Wisps

6 horizontal fog wisps drift slowly:

```kotlin
for (i in 0 until 6) {
    val phase = i * 1.047f  // 60 degrees apart
    val speed = 0.00008f + i * 0.00002f
    
    // Horizontal drift
    val xOffset = sin(time * speed + phase) * screenWidth * 0.15f
    
    // Draw wisp band with gradient
    canvas.drawRect(0f, yBase - wispHeight/2, screenWidth, yBase + wispHeight/2, fogPaint)
}
```

## Breezy Weather Integration

### BreezyWeatherReceiver

Receives Gadgetbridge broadcast intent:

```kotlin
class BreezyWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER") {
            val json = intent.getStringExtra("WeatherJson")
            parseWeatherData(context, json)
        }
    }
}
```

### Weather Code Mapping

OpenWeatherMap condition codes to internal types:

| Code Range | OWM Condition | WeatherType |
|------------|---------------|-------------|
| 200-232 | Thunderstorm | THUNDERSTORM → LIGHTNING |
| 300-321 | Drizzle | RAIN |
| 500-531 | Rain | RAIN |
| 600-622 | Snow | SNOW |
| 701-781 | Atmosphere | FOG |
| 800 | Clear | CLEAR |
| 802-804 | Clouds | CLOUDY |

### Wind Speed Integration

Wind affects precipitation intensity and tree sway:

```kotlin
// Normalize wind (0-20 m/s → 0-1)
fun getNormalizedWindStrength(context: Context): Float {
    val speed = getWindSpeed(context)
    return (speed / 20f).coerceIn(0f, 1f)
}

// Map to precipitation intensity
val windIntensity = WeatherRepository.getNormalizedWindStrength(context)
weather.setIntensity(0.3f + windIntensity * 0.7f)
```

## Debug Override

```kotlin
val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
if (debugEnabled && currentWeather != "auto") {
    // Use setting directly for testing
}
```

