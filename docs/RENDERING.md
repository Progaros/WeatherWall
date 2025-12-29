# WeatherWall Rendering Pipeline

This document describes how the scene layers render the landscape.

## Layer Overview

The scene is composed of stacked layers, each responsible for a specific visual element:

| Layer | Purpose | Parallax Depth | Animation |
|-------|---------|----------------|-----------|
| SkyLayer | Sky gradient + stars | -0.15 (inverse) | Star twinkle |
| CloudLayer | Wispy clouds | 0.08 | Wind drift |
| MountainLayer | Mountain silhouettes | 0.15-0.35 | Static |
| TerrainLayer | Hills + Trees | 0.75-0.95 | Tree wind sway |
| WeatherLayer | Rain/snow/fog | N/A | Particle animation |
| SeasonalEvents | Christmas star, fireworks | N/A | Seasonal effects |

## Color System

### Time-of-Day Palettes

The sky uses a 3-stop gradient (top, middle, horizon):

| Time | Top | Middle | Horizon |
|------|-----|--------|---------|
| Night | #0B0B1A (deep space) | #141428 (dark blue) | #252540 (lighter) |
| Dawn | #4A3060 (purple) | #FF8855 (orange) | #FFCC88 (bright) |
| Day | #1565C0 (deep blue) | #42A5F5 (bright blue) | #B3E5FC (light) |
| Dusk | #4A2060 (purple) | #FF6B35 (orange) | #FFAB76 (bright) |

### Ambient Tinting

All scene elements are tinted by the current ambient light color:

| Time | Ambient Color | Effect |
|------|---------------|--------|
| Night | #2A2040 | Purple-blue, cool |
| Dawn | #FF9966 | Warm orange |
| Day | #E8F0F8 | Nearly white |
| Dusk | #FF7744 | Orange-red |

**Atmospheric Perspective**: Distant objects blend more with ambient:
- Foreground (depth 0): 5% ambient blend
- Background (depth 1): 45% ambient blend

This creates depth by making distant elements hazier and more affected by sky color.

### Eased Color Transitions

Color transitions use cubic easing for smooth blends:

```kotlin
fun easeInOutCubic(t: Float): Float {
    return if (t < 0.5f) {
        4f * t * t * t
    } else {
        1f - (-2f * t + 2f).pow(3) / 2f
    }
}
```

This prevents jarring color jumps when time changes rapidly (e.g., slider adjustments).

## Layer Details

### SkyLayer

**Purpose**: Renders sky background and stars.

**Components**:
1. **Sky Gradient**: 3-stop LinearGradient
   - Position 0%: Top color (darkest)
   - Position 40%: Middle color
   - Position 100%: Horizon color (brightest)

2. **Stars**: Fixed positions with twinkle animation
   - 100 stars in upper 60% of screen
   - Twinkle via alpha modulation based on time
   - Only visible at night/dusk

3. **Vignette**: Radial gradient at corners (night only)
   - Creates atmospheric focus
   - Darker corners, lighter center

**Parallax**: `-0.15f` (moves opposite direction for infinite depth illusion)

### CloudLayer

**Purpose**: Renders soft, wispy clouds with wind movement.

**Rendering**:
1. **BlurMaskFilter**: 40px blur for soft appearance
2. **Elliptical puffs**: Multiple overlapping ellipses per cloud
3. **Horizontal elongation**: `cloudStretch` setting (1.0-5.0)
4. **Low alpha**: 30-80 for see-through effect

**Movement**:
```
cloudX = initialX + timeSeconds * speed * windSpeed
cloudX = wrap(cloudX, WORLD_MIN_X, WORLD_MAX_X)  // Infinite scroll
```

**Color Tinting**:
- Night: Bluish-gray (150, 155, 180)
- Dawn: Pink to orange transition
- Day: White with slight warmth (250, 250, 255)
- Dusk: Orange to pink transition

**Parallax**: `0.08f` (subtle, far away)

### MountainLayer

**Purpose**: Renders procedural mountain silhouettes with atmospheric shading.

**Generation**:
1. Multiple mountains sorted by depth
2. Each mountain has:
   - Peak position (peakX, peakY)
   - Width and height multipliers
   - Shape seed for reproducible roughness
   - Depth factor for parallax

**Rendering**:
1. **Two-sided shape**: Left (lit) and right (shadow) paths
2. **Ridge roughness**: Jagged center line with random displacement
3. **Vertical gradient**: Dark peak → Light atmospheric base
4. **Shadow side**: 80% brightness of lit side

**Atmospheric Haze**:
- Overlay at bottom of mountains (55-85% of screen)
- Blends mountains into terrain
- Uses ambient color with 25 alpha

**Parallax**: `0.15f-0.35f` (mid-distance range)

### TerrainLayer

**Purpose**: Renders rolling hills and trees with wind animation.

**Hills**:
1. Smooth curves using control points (80-120 points per hill)
2. Sorted by depth (far hills behind near hills)
3. Brightness increases with distance (atmospheric perspective)
4. Position range: `hillStartY` (60%) to `hillEndY` (80%)

**Trees**:
1. Triangular shapes with optional segments
2. Placed on hill surfaces
3. Wind sway animation with multiple harmonics:
   - Primary sway: Base oscillation
   - Gust sway: Slower, larger movements
   - Flutter: Fast, small movements
4. Base stays planted, tips sway more
5. Amplitude scales with windStrength²

**Wind Animation Formula**:
```kotlin
val primarySway = sin(time * baseFreq + phase)
val gustSway = sin(time * baseFreq * 0.3 + phase * 0.5) * 0.4
val flutter = sin(time * baseFreq * 2.5 + phase * 2) * 0.15
val sway = (primarySway + gustSway + flutter) * amplitude
```

**Parallax**: `0.75f-0.95f` (foreground range)

## Coordinate System

### World Coordinates
- X: `-4.0` to `5.0` (width = 9.0)
- Y: `0.0` to `1.0`

### Screen Conversion
```kotlin
val scaleX = height * REFERENCE_ASPECT_RATIO  // 9:16
val screenX = (worldX - 0.5f) * scaleX + width / 2f
val screenY = worldY * height
```

This maintains consistent proportions across different screen sizes.

## Performance Optimizations

### Gradient Caching
MountainLayer caches the atmospheric haze gradient:
```kotlin
if (ambientColor != cachedHazeAmbient || height != cachedHazeHeight) {
    hazePaint.shader = LinearGradient(...)
    cachedHazeAmbient = ambientColor
    cachedHazeHeight = height
}
```

### Fixed Random Seeds
All procedural generation uses fixed seeds based on user preference:
```kotlin
val random = Random(randomSeed.toLong() + offset)
```
This ensures:
- Consistent appearance across app restarts
- No "jumping" when settings change
- Reproducible scenes for testing

### Minimal Allocations
- Paths are reused: `path.reset()` instead of `Path()`
- Paint objects created once in init
- ArrayLists pre-allocated where possible

## Draw Order

The render loop in `ParallaxScene.render()`:

```kotlin
1. canvas.drawColor(Color.BLACK)  // Clear

2. for (layer in layers) {        // Layers in order
       layer.draw(canvas, state, parallaxCalculator)
   }
   // Order: Sky → Clouds → Mountains → Terrain

3. if (weatherEnabled) {          // Weather overlay
       weatherLayer.draw(...)
   }

4. if (seasonalEnabled) {         // Seasonal overlay
       seasonalEvents.render(...)
   }
```

Each layer applies parallax offset before drawing:
```kotlin
val (px, py) = parallaxCalculator.getOffsetForDepth(depthFactor)
canvas.save()
canvas.translate(px, py)
// ... draw content ...
canvas.restore()
```

## Settings Integration

Each layer reads settings in `reloadSettings()`:

| Setting | Layer | Effect |
|---------|-------|--------|
| `pref_random_seed` | All | Procedural generation seed |
| `pref_mountain_count` | Mountain | Number of mountains |
| `pref_mountain_width` | Mountain | Width scale (50-1000%) |
| `pref_mountain_height` | Mountain | Height scale (10-1000%) |
| `pref_mountain_roughness` | Mountain | Edge roughness |
| `pref_mountain_ridge_roughness` | Mountain | Ridge jaggedness |
| `pref_hill_count` | Terrain | Number of hills |
| `pref_tree_count` | Terrain | Number of trees |
| `pref_tree_size` | Terrain | Tree size |
| `pref_tree_color_variation` | Terrain | Color randomness |
| `pref_cloud_density` | Cloud | Cloud count |
| `pref_cloud_stretch` | Cloud | Horizontal elongation |
| `pref_cloud_opacity` | Cloud | Transparency |
