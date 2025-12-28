# Day/Night Cycle

## Overview

The day/night cycle affects sky gradient, mountain colors, tree colors, and hill colors. Uses smooth transitions between time periods.

## Time Calculation

### Real Time Mode

```kotlin
private fun getTimeOfDay(): Float {
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    // Returns: 0.0 to 24.0 (continuous)
}
```

### Manual Time Mode

```kotlin
val minutes = prefs.getInt("pref_manual_time", 720)  // Default noon
return minutes / 60f  // 0-1439 minutes → 0-24 hours
```

## Day/Night Ratio

Continuous value from 0.0 (full day) to 1.0 (full night):

```kotlin
private fun updateDayNightRatio() {
    // Try Breezy Weather sunrise/sunset first
    val sunrise = WeatherRepository.getSunrise(context) * 1000L
    val sunset = WeatherRepository.getSunset(context) * 1000L
    val transitionDuration = 45 * 60 * 1000L  // 45 minutes
    
    dayNightRatio = when {
        now < (sunrise - transitionDuration) -> 1.0f  // Night
        now < (sunrise + transitionDuration) -> {
            // Dawn transition
            1.0f - progress
        }
        now < (sunset - transitionDuration) -> 0.0f   // Day
        now < (sunset + transitionDuration) -> {
            // Dusk transition
            progress
        }
        else -> 1.0f  // Night
    }
}
```

## Time Periods

| Period | Hours | Description |
|--------|-------|-------------|
| Night | 21:00 - 05:00 | Dark sky, blue overlay |
| Dawn | 05:00 - 07:00 | Pink/orange transition |
| Day | 07:00 - 17:00 | Blue sky, bright colors |
| Sunset | 17:00 - 18:30 | Golden hour |
| Dusk | 18:30 - 21:00 | Purple/orange to dark |

## Color Palettes

### Sky Gradients

```kotlin
// Day
SKY_DAY_TOP = #87CEEB (Light blue)
SKY_DAY_BOTTOM = #B0E0E6 (Powder blue)

// Night
SKY_NIGHT_TOP = #050510 (Near black)
SKY_NIGHT_BOTTOM = #101525 (Dark blue)

// Dawn
SKY_DAWN_TOP = #4A2545 (Purple)
SKY_DAWN_BOTTOM = #FFB088 (Peach)

// Sunset (Golden Hour)
SKY_SUNSET_TOP = #FF8C42 (Orange)
SKY_SUNSET_BOTTOM = #FFD93D (Gold)

// Dusk
SKY_DUSK_TOP = #2D1B4E (Deep purple)
SKY_DUSK_BOTTOM = #FF6B6B (Coral)

// Rain (overrides normal)
SKY_RAIN_TOP = #455A64 (Gray-blue)
SKY_RAIN_BOTTOM = #607D8B (Blue-gray)
```

### Mountain Colors

```kotlin
// Night: Deep indigo
MOUNTAIN_NIGHT = #283593
MOUNTAIN_NIGHT_FAR = #3949AB

// Dawn: Pink-purple
MOUNTAIN_DAWN = #8E6593
MOUNTAIN_DAWN_FAR = #B085A5

// Day: Natural gray-blue
MOUNTAIN_DAY = #607D8B
MOUNTAIN_DAY_FAR = #90A4AE

// Dusk: Warm mauve
MOUNTAIN_DUSK = #8D5A6C
MOUNTAIN_DUSK_FAR = #B0849C
```

### Tree Colors

```kotlin
TREE_NIGHT = #1A2F0F  // Very dark green
TREE_DAWN = #3A5F2F   // Dark olive
TREE_DAY = #4A6B28    // Olive green
TREE_DUSK = #3D5B28   // Darker olive
```

## Smooth Color Transitions

All colors use linear interpolation:

```kotlin
private fun lerpColor(startColor: Int, endColor: Int, ratio: Float): Int {
    val r = Color.red(startColor) + ratio * (Color.red(endColor) - Color.red(startColor))
    val g = Color.green(startColor) + ratio * (Color.green(endColor) - Color.green(startColor))
    val b = Color.blue(startColor) + ratio * (Color.blue(endColor) - Color.blue(startColor))
    return Color.rgb(r.toInt(), g.toInt(), b.toInt())
}
```

### Mountain Color with Transition

```kotlin
private fun getMountainColor(isFar: Boolean): Int {
    val time = getTimeOfDay()
    
    when {
        time < 7f -> {
            // Dawn transition (5:00 - 7:00)
            val progress = (time - 5f) / 2f
            lerpColor(MOUNTAIN_NIGHT, MOUNTAIN_DAWN, progress)
        }
        // ... more periods
    }
}
```

## Night-Time Blue Overlay

At night, landscape elements receive a blue tint:

```kotlin
private fun applyNightMultiplier(baseColor: Int, nightRatio: Float): Int {
    // Reduce red, reduce green more, boost blue
    val r = Color.red(baseColor) * (1 - nightRatio * 0.1f)
    val g = Color.green(baseColor) * (1 - nightRatio * 0.15f)
    val b = Color.blue(baseColor) + (255 - Color.blue(baseColor)) * nightRatio * 0.4f
    
    return Color.rgb(r, g, b)
}
```

## Cloud Night Dimming

Clouds also receive night dimming:

```kotlin
val cloudColor = applyNightMultiplier(Color.WHITE, dayNightRatio)
cloudPaint.color = Color.argb(alpha, cloudColor.r, cloudColor.g, cloudColor.b)
```

## Stars

Stars appear only at night/dusk with twinkling effect:

```kotlin
if (theme == "night" || theme == "dusk") {
    val starAlphaBase = if (theme == "night") 150 else 80
    
    for (star in stars) {
        val twinkle = sin(time * 0.001 + star.x * 10)
        val alpha = (starAlphaBase + 50 * twinkle).coerceIn(30, 220)
        
        canvas.drawCircle(star.x * w, star.y * h, star.size, starPaint)
    }
}
```

## Debug Override

Settings can override time for testing:

```kotlin
val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
if (debugEnabled) {
    val debugTime = prefs.getString("pref_debug_time", "auto")
    if (debugTime != "auto") {
        return debugTime  // "night", "dawn", "day", "dusk"
    }
}
```

## Issues Identified

1. **Duplicate DayNightCycle class**: `DayNightCycle.kt` exists but is unused - ParallaxScene handles everything
2. **Theme vs Time confusion**: `getThemeName()` and `getTimeOfDay()` overlap in purpose
3. **Hardcoded time boundaries**: Magic numbers like `5f`, `7f`, `17f`, `21f` scattered throughout

