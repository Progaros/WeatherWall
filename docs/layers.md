# Layer System Reference

WeatherWall uses a layer-based architecture for rendering. Each layer handles a specific part of the scene.

## Layer Hierarchy

```
┌────────────────────────────────────────────────┐
│                   Weather Layer                │  ← Front (overlays)
├────────────────────────────────────────────────┤
│               Ground Event Layer               │
├────────────────────────────────────────────────┤
│                 Terrain Layer                  │
├────────────────────────────────────────────────┤
│                Front Cloud Layer               │
├────────────────────────────────────────────────┤
│               Mid-Sky Event Layer              │
├────────────────────────────────────────────────┤
│                Mountain Layer                  │
├────────────────────────────────────────────────┤
│                Back Cloud Layer                │
├────────────────────────────────────────────────┤
│              Far-Sky Event Layer               │
├────────────────────────────────────────────────┤
│                   Sky Layer                    │  ← Back (background)
└────────────────────────────────────────────────┘
```

## Base Class: SceneLayer

All layers extend the `SceneLayer` abstract class:

```kotlin
abstract class SceneLayer {
    protected var width: Int = 0
    protected var height: Int = 0
    protected var isDirty: Boolean = true
    
    fun setSize(width: Int, height: Int) { ... }
    
    abstract fun draw(
        canvas: Canvas, 
        state: SimulationState, 
        parallaxCalculator: ParallaxCalculator
    )
    
    open fun markDirty() { isDirty = true }
    
    protected open fun onSizeChanged() { isDirty = true }
}
```

### Key Methods

| Method | Purpose |
|--------|---------|
| `setSize()` | Called when canvas dimensions change |
| `draw()` | Render the layer to canvas |
| `markDirty()` | Flag for regeneration |
| `onSizeChanged()` | Hook for dimension-based updates |

## Layer Reference

### SkyLayer

**File:** [rendering/SkyLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/SkyLayer.kt)

**Responsibilities:**
- Sky gradient background
- Star field
- Sun and moon
- Day/night color transitions

**Key Properties:**
- Uses SimulationClock for time
- Stars regenerated on size change
- Celestial body positions calculated per frame

### CloudLayer

**File:** [rendering/CloudLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/CloudLayer.kt)

**Responsibilities:**
- Back clouds (behind mountains)
- Front clouds (in front of mountains)
- Cloud movement and wind response
- Cloud density based on weather

**Key Properties:**
- Separate draw methods for front/back
- Clouds have individual speeds
- Regenerates when settings change

### MountainLayer

**File:** [rendering/MountainLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/MountainLayer.kt)

**Responsibilities:**
- Multiple mountain ranges
- Perlin noise silhouettes
- Depth-based coloring

**Key Properties:**
- Generated once, reused
- Lighter colors = farther away
- Static (no animation)

### TerrainLayer

**File:** [rendering/TerrainLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/TerrainLayer.kt)

**Responsibilities:**
- Rolling hills (3 layers)
- Trees with wind animation
- Grass blades
- Gradient shading

**Key Properties:**
- Most complex layer
- Trees spread along hillsides
- Sparsity and spread settings
- Sway speed affects animation

**Settings:**
- `pref_tree_spread` - How far down hills trees grow
- `pref_tree_sparsity` - Gaps between trees (0-100)
- `pref_tree_sway_speed` - Animation speed (0.0-2.0)

### RandomEventLayer

**File:** [rendering/RandomEventLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/RandomEventLayer.kt)

**Responsibilities:**
- Managing active events
- Event spawning logic
- Drawing events at correct depth

**Key Properties:**
- Events sorted into FAR_SKY, MID_SKY, GROUND
- Separate draw methods for each layer
- Spawn timing based on settings

**Split Drawing:**
```kotlin
fun drawFarSkyEvents(...)   // Shooting stars
fun drawMidSkyEvents(...)   // Birds, planes, balloons
fun drawGroundEvents(...)   // Foxes, rabbits, etc.
```

### WeatherLayer

**File:** [rendering/WeatherLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/WeatherLayer.kt)

**Responsibilities:**
- Rain particles
- Snow particles
- Fog overlay
- Weather transitions

**Key Properties:**
- Drawn last (overlays everything)
- Particle systems for rain/snow
- Fog is gradient from ground

## Adding a New Layer

1. Create class extending `SceneLayer`:

```kotlin
class MyCustomLayer : SceneLayer() {
    
    override fun draw(
        canvas: Canvas,
        state: SimulationState,
        parallaxCalculator: ParallaxCalculator
    ) {
        // Your drawing code here
    }
}
```

2. Add to ParallaxScene:

```kotlin
// In ParallaxScene.kt
private val myCustomLayer = MyCustomLayer()

// In setSize()
myCustomLayer.setSize(width, height)

// In draw() - at appropriate position
myCustomLayer.draw(canvas, state, parallaxCalculator)
```

## Layer Communication

Layers don't communicate directly. All shared state flows through:

1. **SimulationState** - Weather, time, wind
2. **ParallaxCalculator** - Device tilt offsets
3. **Canvas** - Drawing surface

This keeps layers decoupled and testable.

## Parallax Factors

Each layer uses different parallax intensity:

| Layer | Factor | Effect |
|-------|--------|--------|
| Sky | 0.0 | Fixed background |
| Mountains | 0.15 | Subtle movement |
| Back Clouds | 0.2 | Slight drift |
| Front Clouds | 0.3 | Moderate movement |
| Terrain | 0.35 | More noticeable |
| Events | 0.4-0.6 | Based on depth |

## Dirty Flag Pattern

Layers use dirty flags to avoid regeneration:

```kotlin
override fun draw(...) {
    if (isDirty) {
        regenerateContent()
        isDirty = false
    }
    // Draw cached content
}
```

Call `markDirty()` when:
- Settings change
- Screen size changes
- Weather changes significantly
