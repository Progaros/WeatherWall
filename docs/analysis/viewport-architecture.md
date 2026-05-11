# World Viewport Architecture

## Coordinate System

### World Coordinates
- **worldX**: Horizontal position in world-space units. `worldX = 0` is scene center.
- **worldY / yRatio**: Vertical position as ratio of screen height (0 = top, 1 = bottom).
- Objects (trees, bushes, mountains, flowers, mushrooms, leaves) are placed in world-space.
- Placement is deterministic: `seed + layerId + cellIndex → position, variant, size`.
- The camera center is implicitly at `worldX = 0` in `WorldViewportTransform`.
  For explicit camera positioning (e.g. infinite scrolling), use `Viewport` with `centerWorldX`.

### Screen Coordinates
- Pixels from (0, 0) at top-left.
- Produced by `WorldViewportTransform.worldToScreenX()` or `WorldToScreenMapper.x()`.

## Viewport Model

### WorldViewportTransform (legacy-compatible)
Used by all core rendering layers.
```
screenX = screenWidth / 2 + worldX * heightScale
worldX  = (screenX - screenWidth / 2) / heightScale    // inverse (screenToWorldX)
heightScale = screenHeight / REFERENCE_HEIGHT  (clamped to MIN_HEIGHT_SCALE)
```

`screenToWorldX()` is the exact inverse of `worldToScreenX()`:
`screenToWorldX(worldToScreenX(wx)) == wx` for all values.
Used by RiverLayer and LakeLayer to convert screen sampling positions to worldX
when calling `terrainYWorldProvider`.

Parallax depth is **not** applied inside `worldToScreenX()` — it is handled
exclusively by `canvas.translate(px, py)` at the call site (camera-center model).
This preserves intra-layer spacing: two objects in the same layer always have
`screenDistance = (worldX2 - worldX1) * heightScale`, regardless of depth.

### Viewport + WorldToScreenMapper (full model)
```
worldWidth  = worldHeight * aspect * horizontalZoomFactor
worldRect   = centered at (centerWorldX, centerWorldY) with worldWidth × worldHeight
screenX     = (worldX - rect.left) / rect.width * screenWidthPx
pixelsPerUnit = screenHeightPx / worldHeight
```

Both are equivalent when `worldHeight = REFERENCE_HEIGHT`:
- `heightScale = pixelsPerUnit = screenHeight / 1920`
- `worldToScreenX(wx) = screenWidth/2 + wx * heightScale`

## Camera / Zoom

- `heightScale < 1` (short screen or landscape): world is zoomed out, wider visible range.
- `heightScale > 1` (tall screen): world is zoomed in, narrower visible range.
- Landscape naturally shows wider world because `heightScale = screenHeight / 1920` is smaller.
- `getVisibleWorldHalf()` returns the padded world half-width for generation/culling.

## Parallax

- Sensor-based parallax (`ParallaxCalculator`) produces pixel offsets per depth layer.
- Applied via `canvas.translate(px, py)` before drawing each object.
- This is the **camera-center model**: the canvas translate shifts the effective
  camera center per layer, preserving world-space distances between objects.
- `worldToScreenX()` does NOT take a parallax/depthFactor parameter.
- Object spacing within a layer is invariant under parallax:
  `screenDistance = (worldX2 - worldX1) * heightScale`, always.

### Incorrect (legacy / banned)
```kotlin
// Object-coordinate scaling — DO NOT USE:
screenX = centerX + worldX * heightScale * depthFactor
```
This compresses intra-layer spacing and is not equivalent to camera parallax.

### Canvas.translate and terrain sampling
`canvas.translate(px, py)` is a screen-space layer offset. It shifts the **drawn output**,
not the sampling coordinates. When layers like RiverLayer or TerrainLayer sample terrain
at screen positions (`screenX = left + i * step`), those positions are in the **untranslated**
canvas coordinate system. Therefore `screenToWorldX()` is called with raw screen positions
and no parallax offset compensation is needed.

Do **not** replace `canvas.translate()` with worldX scaling — that would break intra-layer spacing.

## Procedural Generation

### Cell-Based (Mountains, Trees, Bushes)
- World divided into cells: `cellSize` world units per cell.
- `DeterministicLayout.random(seed, section, cellIndex)` → per-cell Random.
- `worldX = cellIndex * cellSize + jitter` within cell bounds.
- Only cells overlapping `getVisibleWorldHalf()` are generated.
- Overlapping ranges always produce identical objects in overlap.

### Noise-Based (Terrain/Hills)
- `VegetationUtil.getHillYFromNoise(worldX, ...)` evaluates Perlin-like noise.
- Hills are sampled at screen pixel positions, inverted to worldX: `worldX = (screenX - centerX) / heightScale`.
- This "pixel-walking" pattern is correct for continuous terrain.

### Finite Per-Hill (Decorations)
- Flowers, fallen leaves, mushrooms: deterministic per-hill lists.
- Positions stored as `worldX` in world-space (migrated from `xRatio`).
- Snow patches: stored as `startWorldX / endWorldX` (dead code — integrated into hill gradient).

## Layer Classification

### World-Space Layers (use WorldViewportTransform)
| Layer | Generation | Drawing |
|-------|-----------|---------|
| MountainLayer | Cell-based, `getVisibleWorldHalf` | `transform.worldToScreenX()` |
| TerrainLayer | Noise-based, pixel-walking | `transform.centerX / heightScale` inverse |
| TreeRenderer | Cell-based, `getVisibleWorldHalf` | `cachedTransform.worldToScreenX()` |
| BushRenderer | Cell-based, `getVisibleWorldHalf` | `cachedTransform.worldToScreenX()` |
| CloudLayer | `getVisibleWorldHalf`, world-space wrap | `transform.worldToScreenX()` |
| Seasonal decorations | Per-hill, `worldX` coordinates | `transform.worldToScreenX()` |

### Screen-Space Layers (intentionally NOT in world coordinates)
| Layer | Reason |
|-------|--------|
| SkyLayer (sun, moon, stars) | Celestial bodies at infinite distance |
| WeatherLayer (rain, snow, hail, fog) | Full-screen overlay effects |
| SeasonalEvents (fireworks, dragon, rainbow) | Screen-space decorative overlays |
| Storm clouds | Weather overlay, not landscape elements |

### Legacy Subsystem (works via adapter)
| Component | Pattern | Status |
|-----------|---------|--------|
| RiverLayer | Prefers `terrainYWorldProvider`; falls back to deprecated `terrainYProvider` | Primary: world-space |
| LakeLayer | Same pattern | Primary: world-space |
| RandomEventLayer | `hillInfoWorldProvider` / `hillHeightWorldProvider` for depth snapping; individual events still use xRatio internally via `HillHeightProvider` | Partial: layer=world, events=legacy |
| HillHeightProvider / HillInfoProvider | `xRatio`-based type aliases — **deprecated**. Use `HillHeightWorldProvider` / `HillInfoWorldProvider`. | Deprecated |
| Individual events (Duck, Frog, Deer, etc.) | Use xRatio positions and `HillHeightProvider` for terrain following | Legacy — future migration |

## Performance Notes

- TreeRenderer/BushRenderer cache `WorldViewportTransform` — rebuilt only on dimension change.
- MountainLayer/CloudLayer create transform once per frame.
- TerrainLayer creates per-hill (3-5 per frame, negligible).
- No Paint/Path/Random allocation in per-object draw loops.
- Generation bounded by `getVisibleWorldHalf()`.

## Do / Don't

### Do
```kotlin
val screenX = transform.worldToScreenX(worldX)
val screenSize = worldSize * transform.heightScale
val range = transform.visibleWorldRangeX(paddingWorld = 100f)
// Parallax via canvas.translate:
canvas.translate(parallaxCalculator.getOffsetXForDepth(depth), ...)
```

### Don't
```kotlin
val screenX = screenWidth * xRatio                     // screen-space placement
val screenX = width / 2f + worldX * heightScale        // manual formula
val screenX = fixedX * positionScale                   // banned API
val screenX = centerX + worldX * heightScale * depth   // object-coordinate parallax
```

### Key rules
- `heightScale` = pixels per world unit = `screenHeight / REFERENCE_HEIGHT`.
- `worldX` is the canonical horizontal coordinate. `xRatio` is legacy.
- `getPositionScale()` is deprecated — must not appear in production renderers.
- Screen-space overlays (sky, weather, effects) are intentionally NOT in world coordinates.
- New landscape code must use `WorldViewportTransform.worldToScreenX()`.
- Parallax must be modeled as camera-center movement, not object-coordinate scaling.
