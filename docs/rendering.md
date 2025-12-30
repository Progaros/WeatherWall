# Rendering Pipeline

This document explains how WeatherWall renders each frame of the live wallpaper.

## Overview

WeatherWall uses Android's Canvas API for all rendering. No OpenGL or hardware-accelerated graphics libraries are used, making it lightweight and battery-efficient.

```
┌─────────────────────────────────────────────────────────────────┐
│                        ParallaxScene                            │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ drawBackground() - Clear canvas with sky gradient        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ skyLayer.draw() - Stars, celestial bodies                │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ eventLayer.drawFarSkyEvents() - Shooting stars           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ cloudLayer.drawBackClouds() - Distant clouds             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ mountainLayer.draw() - Distant mountains                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ eventLayer.drawMidSkyEvents() - Birds, planes, balloons  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ cloudLayer.drawFrontClouds() - Close clouds              │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ terrainLayer.draw() - Hills, trees, grass                │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ eventLayer.drawGroundEvents() - Foxes, rabbits, etc.     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            ↓                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ weatherLayer.draw() - Rain, snow, fog                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Frame Rate Control

The wallpaper runs at a target of 30 FPS:

```kotlin
// From RenderConstants.kt
const val FRAME_TIME_MS = 33L  // ~30 FPS
```

Lower frame rates are acceptable - the main loop handles variable timing.

## Parallax System

### Overview

The parallax effect creates depth by moving layers at different speeds based on device tilt. Layers farther away move less, closer layers move more.

### ParallaxCalculator

Calculates offset for each layer based on:
- Current sensor tilt (x, y)
- Layer depth factor (0.0 = background, 1.0 = foreground)
- Screen dimensions

```kotlin
// Depth factors used (approximate)
mountains: 0.15f  // Moves very little
clouds:    0.25f  // Slight movement
terrain:   0.35f  // Moderate movement
events:    0.5f   // Significant movement
```

### ParallaxSensorManager

Handles accelerometer data:
- Smooths sensor input to prevent jitter
- Normalizes values across device orientations
- Provides x/y offset percentages (-1.0 to 1.0)

## Layer Details

### SkyLayer

Renders the background gradient and celestial objects.

**Gradient Calculation:**
- Uses SimulationClock time (0.0-24.0)
- Interpolates between 6 key times (dawn, day, dusk, night, etc.)
- Applies weather-based color shifts

**Stars:**
- Only visible when darkness > threshold
- Fade in during evening, out during morning
- Random positions, sizes, and twinkle patterns

**Sun/Moon:**
- Arc path across sky based on time
- Size varies (larger near horizon)
- Moon phases cycle over simulated months

### CloudLayer

Manages cloud rendering with depth.

**Cloud Properties:**
- Position, size, speed, opacity
- Shape variations (puff count, stretch)
- Front/back sorting for depth

**Back Clouds:** Drawn before mountains, lower opacity
**Front Clouds:** Drawn after mountains, higher opacity

### MountainLayer

Distant mountain silhouettes.

**Generation:**
- Multiple ranges at different depths
- Perlin noise for natural ridge lines
- Color based on depth (lighter = farther)

### TerrainLayer

Rolling hills with trees and grass.

**Hills:**
- 3 layers at different depths
- Perlin noise for smooth curves
- Gradient shading for 3D effect

**Trees:**
- Distributed along hillsides
- Sparsity setting controls gaps
- Wind sway animation

**Grass:**
- Procedural grass blades on front hills
- Color variation for natural look

### WeatherLayer

Weather effects overlay.

**Rain:**
- Angled raindrops based on wind
- Splash particles on ground
- Intensity based on weather data

**Snow:**
- Gentle falling snowflakes
- Drift with wind
- Accumulation not implemented

**Fog:**
- Gradient overlay from ground
- Opacity based on fog intensity

## Event Layer System

Events are sorted into three layers:

| Layer | Events | When Drawn |
|-------|--------|------------|
| FAR_SKY | Shooting stars | Before mountains |
| MID_SKY | Birds, planes, balloons | Between mountains and terrain |
| GROUND | Foxes, rabbits, butterflies | After terrain |

This ensures proper depth ordering without z-buffer sorting.

## Canvas Operations

Common patterns used:

```kotlin
// Save/restore for isolated transforms
canvas.save()
canvas.translate(x, y)
canvas.rotate(angle)
// ... drawing code ...
canvas.restore()

// Clipping for masked effects
canvas.clipPath(path)

// Alpha blending
paint.alpha = (opacity * 255).toInt()
```

## Performance Tips

1. **Reuse Paint objects** - Don't allocate in draw loops
2. **Cache paths** - Regenerate only when needed
3. **Minimize overdraw** - Use opacity wisely
4. **Profile on device** - Emulator performance differs

## Memory Management

- Layers regenerate content only on size/setting changes
- Bitmap caching not used (increases memory)
- All drawing is immediate mode (no retained graphics)
