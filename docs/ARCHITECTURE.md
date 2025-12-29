# WeatherWall Architecture

This document describes the architecture and design of the WeatherWall Android Live Wallpaper.

## Overview

WeatherWall is a dynamic live wallpaper that renders a layered landscape scene with:
- Sky with stars and time-of-day color transitions
- Clouds with wind-based movement
- Mountains with atmospheric perspective
- Terrain (hills and trees) with wind animation
- Weather effects (rain, snow, fog, lightning)
- Seasonal events (Christmas star, fireworks)
- 3D parallax effect responding to device tilt

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         WeatherWallpaper                             │
│                      (WallpaperService.Engine)                       │
│  - Lifecycle management (create, destroy, visibility changes)       │
│  - Render thread management                                         │
│  - Surface holder for drawing                                       │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │
┌───────────────────────────────────────▼─────────────────────────────┐
│                          ParallaxScene                               │
│                       (Main Scene Renderer)                          │
│  - Coordinates all subsystems                                       │
│  - Manages layer lifecycle                                          │
│  - Handles preference changes                                       │
│  ┌─────────────────────┐  ┌──────────────────┐  ┌─────────────────┐ │
│  │SimulationController │  │ParallaxCalculator│  │ParallaxSensor   │ │
│  │  (State Machine)    │  │  (Physics)       │  │   Manager       │ │
│  └─────────────────────┘  └──────────────────┘  └─────────────────┘ │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │
┌───────────────────────────────────────▼─────────────────────────────┐
│                       Scene Layers (Draw Order)                      │
│  ┌──────────┐  ┌───────────┐  ┌─────────────┐  ┌──────────────────┐ │
│  │ SkyLayer │→│CloudLayer │→│MountainLayer│→│  TerrainLayer    │  │
│  │(Background)│(Mid-back)  │  │ (Mid-front) │  │(Hills + Trees)   │ │
│  └──────────┘  └───────────┘  └─────────────┘  └──────────────────┘ │
└───────────────────────────────────────┬─────────────────────────────┘
                                        │
┌───────────────────────────────────────▼─────────────────────────────┐
│                            Overlays                                  │
│  ┌────────────────────────┐  ┌────────────────────────────────────┐ │
│  │     WeatherLayer       │  │        SeasonalEvents              │ │
│  │ (Rain/Snow/Fog/etc.)   │  │  (Christmas Star, Fireworks)       │ │
│  └────────────────────────┘  └────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

### WeatherWallpaper (wallpaper/WeatherWallpaper.kt)
- Extends `WallpaperService` with inner `Engine` class
- Manages render thread lifecycle (start/stop on visibility changes)
- Creates and holds `ParallaxScene`
- Handles surface size changes
- Dispatches frame rendering at target FPS

### ParallaxScene (rendering/ParallaxScene.kt)
- Main orchestrator for the entire scene
- Implements `ParallaxSensorManager.Listener` to receive tilt input
- Manages layer list (add, resize, update, draw, destroy)
- Coordinates settings loading across all components
- Provides single entry point for rendering: `render(canvas)`

### SimulationController (simulation/SimulationController.kt)
- **Single source of truth** for simulation state
- Combines data from:
  - `SimulationClock`: Time of day (real or manual override)
  - `SimulationSettings`: User preferences
  - `WeatherRepository`: Live weather data from Breezy Weather
- Produces immutable `SimulationState` snapshots each frame
- Handles time smoothing for smooth color transitions

### SimulationState (simulation/SimulationState.kt)
- Immutable data class containing all rendering state:
  - Time: `timeOfDayHours`, `timeSeconds`, `absoluteTimeMs`
  - Time flags: `isNight`, `isDawn`, `isDusk`
  - Weather: `weather` type, `cloudDensity`, `windStrength`
  - Screen dimensions: `screenWidth`, `screenHeight`
  - Debug mode flag
- Passed to all layers for consistent state across a frame

### ParallaxCalculator (parallax/ParallaxCalculator.kt)
- Velocity-based parallax physics engine
- Converts sensor velocity input to screen-space offset
- Features:
  - Configurable strength, max offset, smoothing
  - Depth separation for layered 3D effect
  - Boundary momentum clearing (prevents "sticky edges")
  - Center drift when idle
- See [PARALLAX_SYSTEM.md](PARALLAX_SYSTEM.md) for details

### ParallaxSensorManager (parallax/ParallaxSensorManager.kt)
- Listens to device accelerometer
- Converts raw sensor data to normalized velocity values
- Applies low-pass filter and dead zone
- Passes velocity to registered listener (ParallaxScene)

## Layer System

All scene layers implement the `SceneLayer` interface:

```kotlin
interface SceneLayer {
    fun resize(w: Float, h: Float)
    fun update(state: SimulationState, deltaTimeMs: Long)
    fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator)
    fun reloadSettings()
    fun destroy()
}
```

### Layer Draw Order (back to front)
1. **SkyLayer** - Gradient background, stars with twinkle
2. **CloudLayer** - Wispy clouds with wind movement
3. **MountainLayer** - Silhouettes with atmospheric perspective
4. **TerrainLayer** - Hills and trees in foreground
5. **WeatherLayer** - Particle effects (overlay)
6. **SeasonalEvents** - Special occasion effects (overlay)

### Parallax Depth Factors
Each layer uses a depth factor for parallax:
- **Sky**: `-0.15` (moves opposite direction for infinite depth illusion)
- **Clouds**: `0.08` (subtle, far away)
- **Mountains**: `0.15 - 0.35` (mid-distance range)
- **Hills/Trees**: `0.75 - 0.95` (foreground range)

Depth factor `0.0` = no movement (infinitely far)
Depth factor `1.0` = maximum movement (attached to screen)

## Data Flow

### Render Loop
```
1. WeatherWallpaper.drawFrame()
   ├── Get canvas from SurfaceHolder
   ├── scene.render(canvas)
   │   ├── simulationController.getState()      → SimulationState
   │   ├── parallaxCalculator.update()          → Physics tick
   │   ├── For each layer:
   │   │   ├── layer.update(state, deltaTime)   → Animation state
   │   │   └── layer.draw(canvas, state, calc)  → Render
   │   └── Draw overlays (weather, seasonal)
   └── Unlock canvas and post
```

### Sensor Data Flow
```
1. ParallaxSensorManager.onSensorChanged(event)
   ├── Compute velocity from acceleration delta
   ├── Apply low-pass filter (SENSOR_ALPHA = 0.8)
   ├── Apply dead zone (MIN_VELOCITY = 0.01)
   ├── Scale velocity (VELOCITY_SCALE = 8.0)
   └── listener.onOffsetChanged(velocityX, velocityY)
       └── parallaxCalculator.updateFromSensor(vx, vy)
```

### Settings Flow
```
1. User changes preference in SettingsFragment
2. SharedPreferences broadcasts change
3. Listeners in WeatherWallpaper/MainActivity receive callback
4. scene.loadPrefs() called
   ├── Read all preference values
   ├── Apply to ParallaxCalculator
   ├── simulationController.reloadSettings()
   └── layer.reloadSettings() for each layer
```

## Threading Model

### Main Thread
- UI and preference handling
- Settings fragment
- MainActivity preview surface callbacks

### Render Thread
- Background thread managed by WeatherWallpaper/MainActivity
- Continuous loop: sleep → draw → repeat
- Targets 30 FPS (wallpaper) or 20 FPS (preview)

### Sensor Thread
- Android sensor callbacks on system thread
- Updates ParallaxCalculator (thread-safe with @Volatile)

**Thread Safety**: `ParallaxCalculator` uses `@Volatile` on mutable state that's accessed from both sensor and render threads.

## Configuration System

### Preference Categories
1. **Scene**: Random seed, mountains, hills, trees
2. **3D Parallax**: Enable, strength, max offset, return speed, smoothing, depth
3. **Visual Effects**: Clouds (density, stretch, opacity), day/night cycle, FPS
4. **Weather**: Breezy Weather integration, weather effects
5. **Seasonal**: Christmas star, New Year fireworks
6. **Simulation/Debug**: Time override, wind, weather type, cloud density

### Key Constants (RenderConstants.kt)
- Reference aspect ratio: `0.5625` (9:16)
- World coordinate bounds: `-0.5 to 1.5` (X), `0 to 1` (Y)
- Time thresholds: Night ends 5:00, Dawn 5:00-7:30, Day 7:30-17:00, Dusk 17:00-20:00
- Color palettes for sky, mountains, hills, trees

## File Structure

```
app/src/main/java/info/olli/weatherlwp/
├── MainActivity.kt           # Settings activity with preview
├── parallax/
│   ├── ParallaxCalculator.kt # Velocity-based parallax physics
│   └── ParallaxSensorManager.kt  # Accelerometer input
├── prefs/
│   └── SettingsFragment.kt   # Preference UI
├── rendering/
│   ├── CloudLayer.kt         # Cloud rendering
│   ├── MountainLayer.kt      # Mountain rendering
│   ├── ParallaxScene.kt      # Main scene coordinator
│   ├── RenderConstants.kt    # Shared constants and utilities
│   ├── SceneLayer.kt         # Layer interface
│   ├── SeasonalEvents.kt     # Christmas/New Year effects
│   ├── SkyLayer.kt           # Sky gradient and stars
│   ├── TerrainLayer.kt       # Hills and trees
│   └── WeatherLayer.kt       # Rain, snow, fog effects
├── simulation/
│   ├── SimulationClock.kt    # Time management
│   ├── SimulationController.kt  # State coordinator
│   ├── SimulationSettings.kt    # Preference wrapper
│   └── SimulationState.kt    # Immutable state snapshot
├── wallpaper/
│   └── WeatherWallpaper.kt   # WallpaperService implementation
└── weather/
    ├── BreezyWeatherProvider.kt  # Breezy integration
    ├── BreezyWeatherReceiver.kt  # Broadcast receiver
    ├── WeatherRepository.kt      # Weather data singleton
    ├── WeatherProvider.kt        # Provider interface
    └── WeatherType.kt            # Weather enum
```

## Design Principles

1. **Immutable State**: `SimulationState` is immutable, ensuring consistent rendering within a frame
2. **Single Source of Truth**: `SimulationController` is the only place that combines all state
3. **Separation of Concerns**: Each layer handles its own rendering logic
4. **Centralized Constants**: `RenderConstants` holds all magic numbers and shared utilities
5. **Thread Safety**: `@Volatile` and careful data flow for cross-thread access
6. **Performance**: Cached gradients, minimal allocations in hot paths
