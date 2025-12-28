# Seasonal Events

## Overview

Seasonal events provide special visual effects during holidays: Christmas (Dec 15-26) and New Year (Dec 27 - Jan 7).

## Season Detection

```kotlin
enum class Season {
    NONE,
    CHRISTMAS,   // Dec 15-26
    NEW_YEAR     // Dec 27 - Jan 7
}

fun getCurrentSeason(): Season {
    // Debug override check
    if (prefs?.getBoolean("pref_debug_enabled", false) == true) {
        when (prefs.getString("pref_debug_season", "auto")) {
            "christmas" -> return Season.CHRISTMAS
            "newyear" -> return Season.NEW_YEAR
            "none" -> return Season.NONE
        }
    }
    
    val month = Calendar.getInstance().get(Calendar.MONTH)  // 0-indexed
    val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    
    return when {
        month == 11 && day in 15..26 -> Season.CHRISTMAS
        month == 11 && day >= 27 -> Season.NEW_YEAR
        month == 0 && day <= 7 -> Season.NEW_YEAR
        else -> Season.NONE
    }
}
```

## Christmas Stars

### Components

1. **Background Stars**: 30 twinkling white dots in upper 40%
2. **Bethlehem Star**: Large golden star with rays
3. **Trail/Schweif**: Diagonal trail emanating from main star

### Data Structures

```kotlin
private data class ChristmasStar(
    val x: Float,           // Upper right area position
    val y: Float,
    val size: Float,        // Base size (25-40)
    var twinkle: Float,     // Animation phase
    var twinkleSpeed: Float // Animation speed (3.0)
)

private data class TrailParticle(
    var x: Float,
    var y: Float,
    var alpha: Int,   // Fades along trail
    var size: Float   // Larger near star
)

private data class Star(
    var x: Float,
    var y: Float,
    var size: Float,        // 2-6 px
    var twinkle: Float,     // Phase
    var twinkleSpeed: Float // 2-6
)
```

### Bethlehem Star Position

```kotlin
christmasStar = ChristmasStar(
    x = screenWidth * 0.75f + random.nextFloat() * screenWidth * 0.15f,
    y = screenHeight * 0.08f + random.nextFloat() * screenHeight * 0.08f,
    size = 25f + random.nextFloat() * 15f
)
```

### Trail Rendering

Trail goes down-left at 225° angle:

```kotlin
val trailAngle = Math.toRadians(225.0)  // Down-left
val trailLength = effectiveSize * 6f * trailLengthMultiplier

for (i in trailParticles.indices) {
    val t = i.toFloat() / trailParticles.size
    val dist = t * trailLength
    
    particle.x = star.x + (dist * cos(trailAngle)).toFloat()
    particle.y = star.y + (dist * sin(trailAngle)).toFloat()
    
    // Twinkle along trail
    val alpha = (baseAlpha * (1 - t * 0.7f) * twinkleFactor).toInt()
    canvas.drawCircle(particle.x, particle.y, size, trailPaint)
}
```

### Star Burst Effect

4-point star with overlapping rays:

```kotlin
// Central circle
canvas.drawCircle(star.x, star.y, size * 0.4f, christmasStarPaint)

// 4 rays at 0°, 45°, 90°, 135°
for (angle in listOf(0.0, 90.0, 45.0, 135.0)) {
    val rayLength = size * 1.2f
    val rayWidth = size * (if (angle in 0..90) 0.15f else 0.105f)
    
    christmasStarPaint.strokeWidth = rayWidth
    christmasStarPaint.style = Paint.Style.STROKE
    canvas.drawLine(startX, startY, endX, endY, christmasStarPaint)
}

// Outer glow
christmasStarPaint.alpha = (starAlpha * 0.3f).toInt()
canvas.drawCircle(star.x, star.y, star.size * 0.8f, christmasStarPaint)
```

## New Year Fireworks

### Firework Particle

```kotlin
private data class Firework(
    var x: Float,
    var y: Float,
    var vx: Float,    // Velocity X
    var vy: Float,    // Velocity Y
    var color: Int,
    var life: Int,    // Remaining frames
    var maxLife: Int
)

private val fireworks = Array(100) { Firework() }
```

### Colors

```kotlin
private val fireworkColors = intArrayOf(
    Color.RED,
    Color.YELLOW,
    Color.GREEN,
    Color.CYAN,
    Color.MAGENTA,
    Color.WHITE,
    0xFFFF6600.toInt()  // Orange
)
```

### Launch Timing

```kotlin
private var fireworkTimer = 0

fun renderNewYearFireworks(canvas: Canvas) {
    fireworkTimer++
    
    if (fireworkTimer > 60 + Random.nextInt(60)) {
        fireworkTimer = 0
        launchFirework()
    }
    // ...
}
```

### Burst Pattern

```kotlin
private fun launchFirework() {
    // Random position in center area
    val cx = screenWidth * 0.2f + Random.nextFloat() * screenWidth * 0.6f
    val cy = screenHeight * 0.2f + Random.nextFloat() * screenHeight * 0.3f
    val color = fireworkColors.random()
    
    for (f in fireworks) {
        if (f.life <= 0) {
            // Radial burst
            val angle = Random.nextFloat() * 360f
            val speed = 3f + Random.nextFloat() * 5f
            
            f.x = cx
            f.y = cy
            f.vx = (speed * cos(angle)).toFloat()
            f.vy = (speed * sin(angle)).toFloat()
            f.color = color
            f.maxLife = 40 + Random.nextInt(30)
            f.life = f.maxLife
            
            // 15-25 particles per burst
            if (Random.nextFloat() > 0.75f) break
        }
    }
}
```

### Physics Update

```kotlin
for (f in fireworks) {
    if (f.life > 0) {
        f.x += f.vx
        f.y += f.vy
        f.vy += 0.15f  // Gravity
        f.life--
        
        // Fade based on remaining life
        val alpha = (255 * f.life / f.maxLife).toInt()
        val size = 3f + (f.life / f.maxLife) * 4f
        
        canvas.drawCircle(f.x, f.y, size, fireworkPaint)
    }
}
```

## Settings

| Setting | Key | Range | Purpose |
|---------|-----|-------|---------|
| Star Size | `pref_christmas_star_size` | 50-200% | Main star scale |
| Trail Length | `pref_christmas_star_trail` | 0-200% | Trail/Schweif length |

```kotlin
private fun loadSettings() {
    starSizeMultiplier = (prefs.getInt("pref_christmas_star_size", 100) / 100f).coerceIn(0.5f, 2.0f)
    trailLengthMultiplier = (prefs.getInt("pref_christmas_star_trail", 100) / 100f).coerceIn(0f, 2.0f)
}
```

## Performance Notes

1. Pre-allocated particle arrays (no per-frame allocations)
2. Settings read on init, not every frame
3. Debug override check is fast (single boolean read)

