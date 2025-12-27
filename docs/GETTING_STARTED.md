# Getting Started with WeatherWall

A guide for developers who know JavaScript/HTML Canvas but are new to Android/Kotlin.

## 🎯 What This Project Is

WeatherWall is an **Android Live Wallpaper** - a dynamic, animated background that runs behind your home screen. Think of it like a full-screen canvas animation that responds to:
- **Sensors** (gyroscope for parallax effect)
- **Real-time data** (weather from Breezy Weather app)
- **Time of day** (auto day/night cycle)

### Canvas Comparison: JS vs Android

| Concept | JavaScript/HTML Canvas | Android/Kotlin |
|---------|----------------------|----------------|
| Drawing surface | `<canvas>` element | `Canvas` object passed to `render()` |
| Get context | `canvas.getContext('2d')` | Canvas is passed directly |
| Draw rectangle | `ctx.fillRect(x, y, w, h)` | `canvas.drawRect(rect, paint)` |
| Set fill color | `ctx.fillStyle = '#FF0000'` | `paint.color = Color.RED` |
| Draw path | `ctx.beginPath(); ctx.moveTo(); ctx.lineTo()` | `path.reset(); path.moveTo(); path.lineTo()` |
| Transform | `ctx.save(); ctx.translate(); ctx.restore()` | `canvas.save(); canvas.translate(); canvas.restore()` |
| Gradient | `ctx.createLinearGradient()` | `LinearGradient` + `Paint.shader` |

## 📁 Project Structure

```
app/src/main/
├── java/com/example/weatherlwp/
│   ├── MainActivity.kt          # Settings screen (entry point)
│   ├── wallpaper/
│   │   └── WeatherWallpaper.kt   # THE MAIN SERVICE - Android entry point
│   ├── rendering/
│   │   ├── ParallaxScene.kt      # ⭐ MAIN RENDERING - mountains, trees, hills
│   │   ├── WeatherLayer.kt       # Rain, snow, lightning particles
│   │   ├── CloudLayer.kt         # Cloud rendering
│   │   ├── DayNightCycle.kt      # Time-based color calculations
│   │   └── SeasonalEvents.kt     # Christmas, New Year effects
│   ├── parallax/
│   │   └── ParallaxSensorManager.kt  # Gyroscope → offset values
│   ├── prefs/
│   │   └── SettingsFragment.kt   # Preference UI handler
│   └── weather/
│       ├── WeatherRepository.kt   # Stores weather data
│       ├── BreezyWeatherReceiver.kt  # Receives weather broadcasts
│       └── WeatherType.kt         # Weather enum
└── res/
    ├── xml/
    │   ├── preferences.xml       # Settings UI definition
    │   └── wallpaper.xml         # Wallpaper metadata
    └── values/
        ├── strings.xml           # Text strings
        ├── colors.xml            # Color definitions
        └── arrays.xml            # List options (FPS, weather types)
```

## 🔑 Key Files to Understand

### 1. WeatherWallpaper.kt - The Service

This is like your "main loop" in a game. Android calls methods on your `Engine`:

```kotlin
class WeatherWallpaper : WallpaperService() {
    inner class WeatherEngine : Engine() {
        // Called when wallpaper becomes visible
        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) startRender() else stopRender()
        }
        
        // The render loop (runs on a background thread)
        private fun startRender() {
            Thread {
                while (!interrupted) {
                    drawFrame()  // ← This is your requestAnimationFrame equivalent
                    sleep(frameTime)
                }
            }.start()
        }
        
        private fun drawFrame() {
            val canvas = surfaceHolder.lockCanvas()  // Get drawing surface
            scene.render(canvas)                     // Draw everything
            surfaceHolder.unlockCanvasAndPost(canvas) // Display it
        }
    }
}
```

### 2. ParallaxScene.kt - The Renderer

This is where **all the visual magic happens**. It's analogous to your canvas draw loop:

```kotlin
fun render(canvas: Canvas) {
    // Similar to: ctx.clearRect(0, 0, w, h)
    canvas.drawColor(Color.BLACK)
    
    // Draw layers back-to-front (painter's algorithm)
    drawSky(canvas)      // Background gradient
    drawClouds(canvas)   // Behind mountains
    drawMountains(canvas) // Low-poly peaks
    drawHills(canvas)    // Rolling terrain
    drawTrees(canvas)    // Pine tree silhouettes
}
```

#### Parallax Effect

The parallax is achieved by translating the canvas before drawing each layer:

```kotlin
// Far elements move less (small multiplier)
canvas.save()
canvas.translate(offsetX * 0.1f, offsetY * 0.05f)  // Sky barely moves
drawSky(canvas)
canvas.restore()

// Near elements move more (larger multiplier)
canvas.save()
canvas.translate(offsetX * 0.5f, offsetY * 0.3f)  // Trees move most
drawTrees(canvas)
canvas.restore()
```

### 3. Key Rendering Concepts

#### Paint Object (like ctx.fillStyle + ctx settings combined)

```kotlin
private val treePaint = Paint().apply {
    style = Paint.Style.FILL      // FILL, STROKE, or FILL_AND_STROKE
    color = Color.GREEN
    isAntiAlias = true            // Smooth edges
}

// Later:
canvas.drawPath(treePath, treePaint)
```

#### Path Object (like ctx.beginPath())

```kotlin
val path = Path()
path.moveTo(x, y)          // Like ctx.moveTo()
path.lineTo(x2, y2)        // Like ctx.lineTo()
path.close()               // Close the shape
canvas.drawPath(path, paint)
```

#### Gradients

```kotlin
// Create a vertical gradient (top to bottom)
val gradient = LinearGradient(
    0f, 0f,         // Start point (x, y)
    0f, height,     // End point (x, y)
    topColor,       // Start color
    bottomColor,    // End color
    Shader.TileMode.CLAMP
)
paint.shader = gradient
canvas.drawRect(0f, 0f, width, height, paint)
```

## 🛠️ Setting Up Development

### Prerequisites

1. **Android Studio** (Ladybug or newer recommended)
2. **JDK 17+** (usually bundled with Android Studio)
3. An Android device or emulator (API 26+ / Android 8.0+)

### Building the Project

```bash
# Clone and open in Android Studio
cd /path/to/WeatherWall

# Build from command line (optional)
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Testing on Device

1. Build and run via Android Studio (green play button)
2. Long-press home screen → Wallpapers → Live Wallpapers → WeatherWall
3. Or: Settings → Apps → WeatherWall → Set as Wallpaper

### Live Development Tips

- **Settings changes are instant**: Most settings trigger `loadSettings()` → `regenerate*()` automatically
- **Hot reload doesn't work**: Live wallpapers need a fresh deploy to see code changes
- **Use the Preview**: The wallpaper preview shows changes without setting as full wallpaper

## 🎨 Common Modifications

### Adding a New Setting

1. **Add preference in `res/xml/preferences.xml`**:
```xml
<SeekBarPreference
    android:key="pref_my_setting"
    android:title="My Setting"
    android:defaultValue="50"
    android:max="100"
    app:min="0"
    app:showSeekBarValue="true"
    app:updatesContinuously="true" />
```

2. **Load it in `ParallaxScene.loadSettings()`**:
```kotlin
val myValue = prefs.getInt("pref_my_setting", 50)
```

3. **Use it in rendering**:
```kotlin
// e.g., adjust tree count, color intensity, speed, etc.
```

### Adding a New Visual Element

1. Create data class to hold positions/properties:
```kotlin
private data class StarData(val x: Float, val y: Float, val size: Float)
private val stars = mutableListOf<StarData>()
```

2. Generate in `generateStars()` (called once or on setting change):
```kotlin
private fun generateStars() {
    stars.clear()
    for (i in 0 until starCount) {
        stars.add(StarData(
            x = Random.nextFloat(),
            y = Random.nextFloat() * 0.5f,
            size = 1f + Random.nextFloat() * 2f
        ))
    }
}
```

3. Draw in `render()`:
```kotlin
// In render(), after sky but before mountains:
for (star in stars) {
    canvas.drawCircle(star.x * w, star.y * h, star.size, starPaint)
}
```

### Changing Colors by Time of Day

The project uses `getTimeOfDay()` (returns 0.0-24.0 hours) and `lerpColor()` for smooth transitions:

```kotlin
private fun getMyColor(): Int {
    val time = getTimeOfDay()
    return when {
        time < 6f -> nightColor
        time < 8f -> lerpColor(nightColor, dayColor, (time - 6f) / 2f)
        time < 18f -> dayColor
        time < 20f -> lerpColor(dayColor, nightColor, (time - 18f) / 2f)
        else -> nightColor
    }
}
```

## 🔄 Data Flow

```
User changes setting in UI
         ↓
SettingsFragment saves to SharedPreferences
         ↓
WeatherWallpaper.loadPrefs() → scene.loadSettings()
         ↓
ParallaxScene regenerates affected elements (mountains, trees, etc.)
         ↓
Next render() call uses new values
```

## 📡 Weather Integration

Breezy Weather sends weather data via Android Broadcast:

```
Breezy Weather app
    ↓ (sends broadcast intent)
BreezyWeatherReceiver.onReceive()
    ↓ (parses JSON)
WeatherRepository.updateWeather()
    ↓ (stores in SharedPreferences)
ParallaxScene.loadSettings()
    ↓ (reads wind speed, weather type)
render() uses the data (tree sway, rain particles, sky colors)
```

## 🐛 Debugging

### View Logs

```bash
# In Android Studio: View → Tool Windows → Logcat
# Filter by tag:
adb logcat -s ParallaxScene WeatherWallpaper

# Or in the app: Settings → Debug → Export Log
```

### Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| Black screen | Exception in render() | Check Logcat for errors |
| Choppy animation | Too much work per frame | Reduce tree/mountain count |
| Settings not applying | Missing loadSettings() call | Add regeneration trigger |
| Preview looks wrong | Aspect ratio handling | Check scaleX calculation |

## 📚 Kotlin Quick Reference for JS Developers

```kotlin
// Variables
val x = 5          // const (immutable)
var y = 10         // let (mutable)

// Null safety (no undefined!)
var name: String? = null   // ? means nullable
name?.length               // ?. is like JS optional chaining
name ?: "default"          // Elvis operator (like JS ??)

// Functions
fun add(a: Int, b: Int): Int = a + b

// Lambdas (like arrow functions)
val double = { x: Int -> x * 2 }
list.map { it * 2 }        // 'it' is implicit parameter

// Classes
class Tree(val x: Float, val y: Float) {
    fun draw(canvas: Canvas) { ... }
}

// Data classes (like JS objects with auto equals/toString)
data class Point(val x: Float, val y: Float)

// When expression (like switch but better)
when (weather) {
    "rain" -> drawRain()
    "snow" -> drawSnow()
    else -> drawClear()
}

// Ranges
for (i in 0 until 10) { }  // 0 to 9
for (i in 0..10) { }       // 0 to 10 (inclusive)
(0 until count).forEach { i -> ... }

// List operations (like JS array methods)
list.filter { it > 5 }
list.map { it * 2 }
list.forEach { println(it) }
list.sortedBy { it.name }
```

## 🎮 Try These Modifications

1. **Change tree color** to purple at night → edit `getTreeColor()`
2. **Make mountains taller** → adjust `mountainHeightScale` 
3. **Add falling leaves** → create new particle system like `WeatherLayer`
4. **Make clouds pulse** → animate alpha in cloud draw loop

Happy coding! 🌲⛰️🌤️
