# WeatherWall Architecture

## Overview

WeatherWall is an Android Live Wallpaper with a layered rendering architecture. The app creates a parallax scene with mountains, hills, trees, weather effects, and seasonal events.

## Package Structure

```
com.example.weatherlwp/
├── MainActivity.kt              # Settings launcher
├── parallax/
│   └── ParallaxSensorManager.kt # Accelerometer input handling
├── prefs/
│   └── SettingsFragment.kt      # Preference UI
├── rendering/
│   ├── ParallaxScene.kt         # Main scene renderer (1349 lines)
│   ├── CloudLayer.kt            # Standalone cloud system (unused)
│   ├── DayNightCycle.kt         # Standalone day/night (unused)
│   ├── SeasonalEvents.kt        # Christmas/New Year effects
│   └── WeatherLayer.kt          # Rain, snow, lightning, fog
├── wallpaper/
│   └── WeatherWallpaper.kt      # WallpaperService implementation
└── weather/
    ├── BreezyWeatherReceiver.kt # Broadcast receiver for Breezy Weather
    ├── WeatherRepository.kt     # Weather data storage
    └── WeatherType.kt           # Weather enum
```

## Layer Rendering Order

The scene is rendered back-to-front in this order:

| Layer | Component | Parallax Factor | Description |
|-------|-----------|-----------------|-------------|
| 0 | Sky gradient | -0.15 (opposite) | Creates depth illusion |
| 0.5 | Stars | 0.30 | Night sky, moves with mountains |
| 1 | Clouds | 0.08 | Behind mountains, slow drift |
| 2 | Mountains | 0.25-0.45 | Far to near, depth-based color |
| 3 | Hills | 0.05-0.12 | Rolling terrain |
| 4 | Trees | 0.05-0.12 | Foreground, matches hills |
| 5 | Weather | N/A | Rain, snow, lightning, fog |
| 6 | Seasonal | N/A | Christmas stars, fireworks |

## Design Patterns

### 1. Pre-allocation Pattern
All paints, paths, and particle arrays are pre-allocated to avoid GC pressure:
```kotlin
private val mountainPathLeft = Path()
private val mountainPathRight = Path()
private val rainParticles = Array(300) { Particle() }
```

### 2. Synchronized Terrain Lock
Terrain data can be modified from UI thread (preference changes) while render thread iterates:
```kotlin
private val terrainLock = Any()
synchronized(terrainLock) {
    mountains.clear()
    // ... modify terrain
}
```

### 3. Sensor Listener Pattern
`ParallaxSensorManager` uses callback interface:
```kotlin
interface Listener {
    fun onOffsetChanged(x: Float, y: Float)
}
```

### 4. SharedPreferences Caching
Settings are loaded into member variables rather than read every frame:
```kotlin
fun loadSettings() {
    parallaxStrength = prefs.getInt("pref_parallax_strength", 50) / 50f
    // ... cache all settings
}
```

## Key Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `TERRAIN_BASELINE_RATIO` | 0.70f | Ground line at 70% down screen |
| `TREE_FOOT_OFFSET` | 0.02f | Trees sit slightly into ground |
| `WORLD_MIN_X` | -4.0f | Extended world for wide aspect ratios |
| `WORLD_MAX_X` | 5.0f | Extended world for parallax |

## Thread Model

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Main Thread   │     │  Render Thread  │     │  Sensor Thread  │
│   (UI/Prefs)    │     │  (drawFrame)    │     │  (Accelerometer)│
├─────────────────┤     ├─────────────────┤     ├─────────────────┤
│ loadSettings()  │────>│ render()        │<────│ onSensorChanged │
│ terrainLock     │     │ terrainLock     │     │ → onOffsetChanged│
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

## Issues Identified

1. **Unused Classes**: `CloudLayer.kt` and `DayNightCycle.kt` are not used - ParallaxScene handles these internally
2. **God Class**: `ParallaxScene.kt` is 1349 lines and handles too many responsibilities
3. **Magic Numbers**: Many hardcoded values scattered throughout (e.g., `0.70f`, `0.02f`)

