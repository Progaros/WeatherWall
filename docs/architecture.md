# Architecture Overview

WeatherWall follows a clean layered architecture with clear separation between UI, simulation, and rendering concerns.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    WeatherWallpaper                        │
│                  (WallpaperService)                        │
├─────────────────────────────────────────────────────────────┤
│                     ParallaxScene                          │
│              (Orchestrates all layers)                     │
├────────────┬────────────┬─────────────┬────────────────────┤
│ SkyLayer   │ CloudLayer │ Mountains   │ RandomEventLayer   │
│            │            │ Layer       │                    │
├────────────┴────────────┴─────────────┴────────────────────┤
│                     TerrainLayer                           │
│              (Hills + Trees + Wind)                        │
├─────────────────────────────────────────────────────────────┤
│               SimulationController                         │
│     (State updates, wind gusts, day/night cycle)          │
└─────────────────────────────────────────────────────────────┘
```

## Core Components

### WallpaperService Layer

**WeatherWallpaper.kt** - Entry point extending Android's `WallpaperService`
- Manages render thread lifecycle
- Handles visibility changes (start/stop sensors)
- Coordinates preference changes via `OnSharedPreferenceChangeListener`

### Scene Orchestration

**ParallaxScene.kt** - Main rendering coordinator
- Maintains ordered list of `SceneLayer` implementations
- Handles draw order with special event layer placement
- Applies parallax calculations to each layer
- Manages simulation state updates

### Layer System

Each layer implements `SceneLayer` interface:

```kotlin
interface SceneLayer {
    fun resize(w: Float, h: Float)
    fun update(state: SimulationState, deltaTimeMs: Long)
    fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator)
    fun reloadSettings()
    fun destroy()
}
```

**Draw Order** (back to front):
1. **SkyLayer** - Gradient sky, sun/moon, stars
2. **CloudLayer** - Animated cloud shapes
3. **FAR_SKY events** - Shooting stars (behind mountains)
4. **MountainLayer** - Background mountain silhouettes
5. **MID_SKY events** - Birds, planes, eagles, balloons, UFOs
6. **TerrainLayer** - Hills and trees with wind animation
7. **GROUND events** - Foxes, rabbits, butterflies, fireflies, owls
8. **WeatherLayer** - Rain/snow/lightning particles
9. **SeasonalEvents** - Christmas/New Year effects

### Simulation System

**SimulationController.kt** - State management
- Creates immutable `SimulationState` snapshots each frame
- Manages wind gust simulation with decay
- Coordinates time from `SimulationClock`
- Reads settings from `SimulationSettings`

**SimulationState.kt** - Immutable per-frame state
```kotlin
data class SimulationState(
    val timeSeconds: Float,
    val timeOfDayHours: Float,
    val isNight: Boolean,
    val isDawn: Boolean,
    val isDusk: Boolean,
    val weather: WeatherType,
    val windStrength: Float,
    val windGust: Float,
    val treeSwaySpeed: Float,
    // ... more fields
)
```

### Parallax System

**ParallaxCalculator.kt** - Offset calculations
- Converts sensor velocity to smooth offset values
- Applies per-depth parallax factors
- Handles center drift (return to center when phone is still)
- Configurable sensitivity, smoothing, and depth separation

**ParallaxSensorManager.kt** - Sensor input
- Uses `TYPE_ACCELEROMETER` for device orientation
- Applies low-pass filtering for smooth input
- Normalizes axis values across device orientations

## Data Flow

```
User Settings (preferences.xml)
         │
         ▼
┌─────────────────────┐
│ SimulationSettings  │ ← Reads preferences
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│ SimulationController│ ← Creates state per frame
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│   SimulationState   │ ← Immutable snapshot
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│     SceneLayers     │ ← Render using state
└─────────────────────┘
```

## Threading Model

- **Main/UI Thread**: Settings UI, preference changes
- **Render Thread**: All drawing operations
- **Sensor Thread**: Accelerometer callbacks

Thread safety achieved via:
- `@Volatile` references with immutable lists
- Atomic reference swaps for terrain data
- Settings reload on render thread via message queue

## Key Design Patterns

### Immutable State Pattern
`SimulationState` is a frozen snapshot - layers can read it without synchronization.

### Layer Abstraction
`SceneLayer` interface allows easy addition of new visual elements.

### Event Layer System
`EventLayer` enum enables correct draw ordering:
```kotlin
enum class EventLayer {
    FAR_SKY,   // Behind mountains
    MID_SKY,   // Between mountains and terrain
    GROUND     // In front of terrain
}
```

### Procedural Generation
Hills and terrain use Perlin-like noise based on seed for reproducible randomness.
