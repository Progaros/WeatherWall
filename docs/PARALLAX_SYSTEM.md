# WeatherWall Parallax System

This document describes the velocity-based parallax system used in WeatherWall.

## Overview

The parallax system creates a 3D depth effect by moving scene layers at different speeds in response to device tilt. Unlike position-based systems that map tilt angle directly to offset, WeatherWall uses a **velocity-based** approach where tilt changes translate to movement velocity.

## Sensors Used

| Sensor | Priority | Purpose |
|--------|----------|---------|
| `TYPE_GRAVITY` | Primary | Isolates tilt from linear motion (walking, hand jitter) |
| `TYPE_ACCELEROMETER` | Fallback | Used if gravity sensor unavailable |

**Sensor Output**:
- Values in **m/s²** (Android standard)
- Coordinate system: X = right, Y = up, Z = toward user (device screen up)
- TYPE_GRAVITY provides ~9.8 m/s² pointing "down" (toward Earth)
- TYPE_ACCELEROMETER includes all acceleration (gravity + linear motion)

**Why TYPE_GRAVITY is preferred**: The gravity sensor uses sensor fusion (gyro + accel + magnetometer) to provide a rock-steady "down" vector, immune to the user walking or moving their hand. This eliminates drift artifacts.

## Thread Ownership

| Thread | Responsibility | Data Access |
|--------|---------------|-------------|
| **Sensor Thread** | Receives sensor callbacks | Writes to ParallaxSensorManager |
| **Main/UI Thread** | Settings changes | Reads prefs, calls reloadSettings() |
| **Render Thread** | Draws frames | Reads layer data (via local snapshots) |

**Thread Safety Pattern**: Layer data uses `@Volatile` references with immutable list swapping. The render thread takes a local snapshot reference before iteration, ensuring no concurrent modification.

## Why Velocity-Based?

### Position-Based (Traditional)
```
Tilt 10° left → Scene offset 50px left (direct mapping)
```
**Problems**:
- Scene snaps to position
- Feels mechanical
- No sense of inertia

### Velocity-Based (WeatherWall)
```
Tilt 10° left → Scene MOVES left at speed proportional to tilt
                Scene continues moving while tilted
                Scene drifts back to center when level
```
**Benefits**:
- Smooth, organic movement
- Natural momentum feel
- Intuitive response to tilt changes

## Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    GRAVITY SENSOR (preferred)                    │
│                   or ACCELEROMETER (fallback)                    │
│                   (Raw: m/s² in X, Y, Z)                        │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 ParallaxSensorManager                            │
│  1. Compute delta from last reading                             │
│  2. Apply low-pass filter (α = 0.8)                             │
│  3. Normalize by typical tilt range (÷ 5.0 heuristic)           │
│  4. Apply dead zone (< 0.01 → 0)                                │
│  5. Scale velocity (× 8.0)                                      │
│  6. Clamp to [-1, 1]                                            │
│  Output: velocity (-1 to 1) per axis                            │
└───────────────────────────┬─────────────────────────────────────┘
                            │ onOffsetChanged(velocityX, velocityY)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 ParallaxCalculator                               │
│  1. Accumulate velocity input                                   │
│  2. Apply smoothing filter (lerp toward input)                  │
│  3. Integrate velocity into position                            │
│  4. Clamp position to ±maxOffsetPx                              │
│  5. Clear momentum at boundaries                                │
│  6. Apply center drift when idle                                │
│  Output: position (pixels) for base layer                       │
└───────────────────────────┬─────────────────────────────────────┘
                            │ getOffsetForDepth(depthFactor)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    LAYER RENDERING                               │
│  offset = basePosition × effectiveDepth                         │
│  effectiveDepth = depthFactor × depthSeparation                 │
│  Canvas.translate(offsetX, offsetY)                             │
│  Draw layer content                                             │
└─────────────────────────────────────────────────────────────────┘
```

## ParallaxSensorManager

### Purpose
Converts raw accelerometer data to normalized velocity values.

### Key Constants
| Constant | Value | Purpose |
|----------|-------|---------|
| `SENSOR_ALPHA` | 0.8 | Low-pass filter strength (higher = more filtering) |
| `VELOCITY_SCALE` | 8.0 | Amplification of velocity (was 15.0, reduced to prevent over-sensitivity) |
| `MIN_VELOCITY` | 0.01 | Dead zone threshold |

### Algorithm
```kotlin
// On each sensor event:
val deltaX = currentX - lastX
val deltaY = currentY - lastY

// Low-pass filter for smoothing
filteredX = filteredX * SENSOR_ALPHA + deltaX * (1 - SENSOR_ALPHA)
filteredY = filteredY * SENSOR_ALPHA + deltaY * (1 - SENSOR_ALPHA)

// Normalize to velocity (-1 to 1)
velocityX = (filteredX / 5f * VELOCITY_SCALE).coerceIn(-1f, 1f)
velocityY = (filteredY / 5f * VELOCITY_SCALE).coerceIn(-1f, 1f)

// Apply dead zone
if (abs(velocityX) < MIN_VELOCITY) velocityX = 0f
if (abs(velocityY) < MIN_VELOCITY) velocityY = 0f
```

**Note on ÷5.0 heuristic**: This is an application-specific tuning value, not a physics constant. While gravity magnitude is ~9.8 m/s², dividing by 5.0 maps typical user tilt ranges (±30°) to comfortable velocity values. This was determined empirically through user testing.

## ParallaxCalculator

### Purpose
Physics engine that converts velocity input to screen-space position.

### User-Configurable Parameters
| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `strength` | 1.0 | 0-5 | Sensitivity multiplier |
| `maxOffsetPx` | 80 | 5-200 | Maximum offset in pixels |
| `centerDriftRate` | 0.015 | 0.005-0.05 | Speed of return to center |
| `smoothing` | 0.4 | 0.1-0.9 | Velocity filter strength |
| `depthSeparation` | 0.25 | 0-1.2 | Layer depth difference |

### Internal Constants
| Constant | Value | Purpose |
|----------|-------|---------|
| `VELOCITY_SCALE` | 1.2 | Position integration rate (was 3.0) |
| `CENTER_DRIFT_RATE` | 0.015 | Default drift speed |
| `IDLE_FRAMES_BEFORE_DRIFT` | 30 | Frames before centering starts (~0.5s) |

### Core Algorithm

```kotlin
fun update(deltaTimeMs: Long = 16L) {
    val deltaScale = deltaTimeMs / 16f  // Normalize to ~60fps
    
    // 1. SMOOTHING: Filter velocity input
    // Higher smoothing = more filtering = slower but smoother
    val filterStrength = smoothing.coerceIn(0f, 0.95f)
    smoothedVelocity = smoothedVelocity * filterStrength + 
                       pendingVelocity * (1f - filterStrength)
    
    // 2. INTEGRATION: Velocity → Position
    val dx = smoothedVelocityX * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale
    val dy = smoothedVelocityY * VELOCITY_SCALE * strength * maxOffsetPx * deltaScale
    positionX += dx
    positionY += dy
    
    // 3. CLAMPING: Keep within bounds
    positionX = positionX.coerceIn(-maxOffsetPx, maxOffsetPx)
    positionY = positionY.coerceIn(-maxOffsetPx, maxOffsetPx)
    
    // 4. BOUNDARY MOMENTUM CLEARING
    // When hitting edge while moving in that direction,
    // clear velocity to allow instant reversal
    if (positionX == maxOffsetPx && smoothedVelocityX > 0f) {
        smoothedVelocityX = 0f
    }
    // ... same for other boundaries
    
    // 5. CENTER DRIFT: Return to center when idle
    if (idleFrameCount > IDLE_FRAMES_BEFORE_DRIFT) {
        positionX *= (1f - centerDriftRate * deltaScale)
        positionY *= (1f - centerDriftRate * deltaScale)
    }
}
```

### Depth Calculation

```kotlin
fun getOffsetForDepth(depthFactor: Float): Pair<Float, Float> {
    // Formula: offset = position × depthFactor × depthSeparation
    // depthFactor: 0.0 (far/background) to 1.0 (near/foreground)
    // depthSeparation: User setting that controls layer spread
    
    val effectiveDepth = depthFactor * depthSeparation
    return Pair(positionX * effectiveDepth, positionY * effectiveDepth)
}
```

**Key insight**: `effectiveDepth = depthFactor × depthSeparation`
- Sky (depth -0.15): Moves opposite direction (infinite distance illusion)
- Far mountains (depth 0.15): Barely moves
- Near hills (depth 0.95): Moves almost as much as foreground

## Depth Factors by Layer

| Layer | Depth Factor | Movement | Rationale |
|-------|--------------|----------|-----------|
| Sky | -0.15 | Opposite | Infinite distance illusion |
| Clouds | 0.08 | Minimal | Far, high altitude |
| Mountains (far) | 0.15 | Little | Background |
| Mountains (near) | 0.35 | Moderate | Mid-distance |
| Hills (far) | 0.75 | Significant | Foreground start |
| Hills (near) | 0.95 | Maximum | Closest to viewer |

## Tuning Guide

### Parallax Feels Too Sensitive
- Reduce `strength` (slider: pref_parallax_strength)
- Reduce `maxOffsetPx` (slider: pref_parallax_max_offset)
- Reduce VELOCITY_SCALE in ParallaxSensorManager

### Parallax Feels Sluggish
- Reduce `smoothing` (lower = faster response)
- Increase `strength`
- Reduce SENSOR_ALPHA in ParallaxSensorManager

### Layers Don't Separate Enough
- Increase `depthSeparation` (slider: pref_parallax_depth)
- Ensure layers have different depth factors

### Scene Sticks at Edges
- Check boundary momentum clearing code
- Ensure smoothedVelocity is cleared when at boundary

### Scene Returns to Center Too Fast/Slow
- Adjust `centerDriftRate` (slider: pref_parallax_return_speed)
- Adjust `IDLE_FRAMES_BEFORE_DRIFT` (increase for longer hold)

## Historical Changes

### Initial Implementation
- Position-based: tilt angle → offset
- Too aggressive, felt mechanical

### Velocity-Based Refactor
- Tilt velocity → position integration
- Added smoothing filter
- Added center drift

### Scaling Fixes (This Session)
- VELOCITY_SCALE: 15 → 8 (sensor manager)
- VELOCITY_SCALE: 3 → 1.2 (calculator)
- Combined was 45x, now ~9.6x
- Much more controllable feel

### Depth Formula Fix (This Session)
- Old: `offset = position * (depthFactor + depthSeparation)`
- New: `offset = position * (depthFactor * depthSeparation)`
- Correct multiplication instead of addition

### Smoothing Formula Fix (This Session)
- Old: Higher smoothing = less filtering (inverted)
- New: Higher smoothing = more filtering (intuitive)
- Formula: `smoothed = smoothed * filter + pending * (1 - filter)`

### Boundary Momentum (This Session)
- Added: Clear velocity when hitting edge while moving that direction
- Allows instant reversal when tilting back from edge
- Prevents "sticky" feel at boundaries

## Thread Safety

`ParallaxCalculator` is accessed from:
1. **Sensor thread**: Calls `updateFromSensor()`
2. **Render thread**: Calls `update()` and `getOffsetForDepth()`

Thread safety is ensured via `@Volatile` annotations on shared mutable state:
```kotlin
@Volatile private var positionX = 0f
@Volatile private var positionY = 0f
@Volatile private var smoothedVelocityX = 0f
@Volatile private var smoothedVelocityY = 0f
@Volatile private var pendingVelocityX = 0f
@Volatile private var pendingVelocityY = 0f
@Volatile private var idleFrameCount = 0
```

This ensures visibility of writes across threads, though some read-modify-write races are acceptable for smooth animation.
