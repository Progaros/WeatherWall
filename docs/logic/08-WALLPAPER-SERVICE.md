# Wallpaper Service

## Overview

`WeatherWallpaper` extends Android's `WallpaperService` and manages the rendering lifecycle.

## Class Structure

```kotlin
class WeatherWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = WeatherEngine()
    
    inner class WeatherEngine : Engine() {
        private var renderThread: Thread? = null
        private var visible: Boolean = false
        
        private lateinit var scene: ParallaxScene
        private lateinit var seasonal: SeasonalEvents
        private val weather = WeatherLayer()
    }
}
```

## Lifecycle

### Surface Created

```kotlin
override fun onSurfaceCreated(holder: SurfaceHolder) {
    visible = true
    loadPrefs()
    scene.start()       // Start sensors
    startRender()       // Start render thread
    drawFrame()         // Immediate first frame
}
```

### Surface Destroyed

```kotlin
override fun onSurfaceDestroyed(holder: SurfaceHolder) {
    visible = false
    stopRender()        // Stop render thread
    scene.stop()        // Stop sensors
}
```

### Visibility Changed

```kotlin
override fun onVisibilityChanged(visible: Boolean) {
    this.visible = visible
    if (visible) {
        loadPrefs()
        scene.start()
        startRender()
    } else {
        stopRender()
        scene.stop()
    }
}
```

## Render Thread

### Thread Creation

```kotlin
private fun startRender() {
    if (renderThread != null) return
    
    renderThread = Thread {
        val frameTimeMs = (1000L / targetFps).coerceAtLeast(16L)
        
        while (!Thread.currentThread().isInterrupted) {
            if (!visible) {
                SystemClock.sleep(250)  // Low power when hidden
                continue
            }
            
            val start = SystemClock.uptimeMillis()
            drawFrame()
            val dt = SystemClock.uptimeMillis() - start
            val sleep = frameTimeMs - dt
            if (sleep > 0) SystemClock.sleep(sleep)
        }
    }.also { 
        it.isDaemon = true
        it.start() 
    }
}
```

### Thread Termination

```kotlin
private fun stopRender() {
    renderThread?.interrupt()
    renderThread = null
}
```

## Frame Drawing

```kotlin
private fun drawFrame() {
    val holder = surfaceHolder ?: return
    var canvas: Canvas? = null
    
    try {
        canvas = holder.lockCanvas() ?: return
        
        // Validate dimensions
        val w = canvas.width
        val h = canvas.height
        if (w <= 0 || h <= 0) return
        
        // Clear to black
        canvas.drawColor(Color.BLACK)
        
        // Render layers in order
        scene.render(canvas)                    // Sky, clouds, terrain
        weather.render(canvas, weatherEnabled)  // Rain, snow, etc.
        seasonal.render(canvas, seasonalEnabled) // Christmas, New Year
        
    } catch (e: Exception) {
        Log.e(TAG, "Error drawing frame", e)
    } finally {
        canvas?.let { holder.unlockCanvasAndPost(it) }
    }
}
```

## FPS Control

### Target FPS

```kotlin
targetFps = prefs.getString("pref_max_fps", "30")?.toIntOrNull() ?: 30
targetFps = targetFps.coerceIn(10, 60)
```

### Frame Timing

```kotlin
val frameTimeMs = (1000L / targetFps).coerceAtLeast(16L)
// 60 FPS → 16ms
// 30 FPS → 33ms
// 20 FPS → 50ms
```

## Weather Logic

### Weather Source Priority

```kotlin
val effectiveWeather = when {
    // 1. Debug mode override
    debugEnabled && currentWeather != "auto" -> {
        parseWeatherSetting(currentWeather)
    }
    // 2. Auto mode with Breezy Weather
    currentWeather == "auto" && useBreezyWeather -> {
        convertBreezyWeather(WeatherRepository.getWeatherType(context))
    }
    // 3. Manual selection
    else -> {
        parseWeatherSetting(currentWeather)
    }
}
```

### Weather Type Mapping

```kotlin
when (currentWeather) {
    "rain" -> WeatherLayer.WeatherType.RAIN
    "snow" -> WeatherLayer.WeatherType.SNOW
    "lightning" -> WeatherLayer.WeatherType.LIGHTNING
    "fog" -> WeatherLayer.WeatherType.FOG
    else -> WeatherLayer.WeatherType.NONE
}
```

### Precipitation Intensity

```kotlin
val windIntensity = if (useBreezyWeather) {
    WeatherRepository.getNormalizedWindStrength(context)
} else {
    0.5f  // Default medium
}

// Base 30% + up to 70% from wind
weather.setIntensity(0.3f + windIntensity * 0.7f)
```

## Error Handling

Every lifecycle method is wrapped in try-catch:

```kotlin
override fun onVisibilityChanged(visible: Boolean) {
    this.visible = visible
    try {
        // ... lifecycle logic
    } catch (e: Exception) {
        Log.e(TAG, "Error in onVisibilityChanged", e)
    }
}
```

## Battery Optimization

1. **Visibility-based pausing**: Render thread sleeps 250ms when not visible
2. **Sensor pausing**: Accelerometer stopped when wallpaper hidden
3. **Adaptive FPS**: User-configurable 10-60 FPS
4. **Minimum frame time**: Always at least 16ms between frames

## Live Weather Updates

Preference listener for Breezy Weather broadcasts:

```kotlin
private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == "weather_current_type" || key == "weather_wind_speed") {
        loadPrefs()
    }
}
```

Registered in `onCreate()`, unregistered in `onDestroy()`.

## Known Issues

1. **No wallpaper offset support**: Doesn't respond to home screen scrolling
2. **Preview handling**: Some launchers don't call `onVisibilityChanged(true)` properly
3. **Thread safety**: `visible` flag accessed from multiple threads without synchronization

