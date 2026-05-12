# Viewport-Bounded Rendering Performance Rules

## Core Principles

### 1. Generation is viewport-bounded
- Objects are generated only within `getVisibleWorldHalf(screenWidth)` of center
- Cell-based layers use `indexRangeForWorldRange(left, right, cellSize)` to bound iteration
- Zooming in **reduces** generation cost (fewer visible cells)
- Generation range depends on **screen width only**, never screen height

### 2. What must NEVER be computed per frame
- `WorldViewportTransform` construction — cache as a field, rebuild on `resize()` only
- Mountain shape geometry (ridge/slope points) — pre-compute in `generateMountains()`
- `getVisibleWorldHalf()` — cache on resize
- `getAmbientColor()` — compute once per frame in the layer's `draw()`, pass to sub-methods

### 3. What MAY be computed per frame
- `worldToScreenX()` / `screenToWorldX()` projections (cheap: single multiply + add)
- Noise evaluation for hill contours (screen-proportional sample count)
- Parallax offsets from sensor input
- Wind/animation phase increments

## Allowed vs Forbidden Patterns

### DO
```kotlin
// Cache transform as field
private var cachedTransform = WorldViewportTransform(1f, 1f)

override fun resize(w: Float, h: Float) {
    cachedTransform = WorldViewportTransform(w, h)
}

override fun draw(...) {
    val transform = cachedTransform  // free: field read
    val screenX = transform.worldToScreenX(worldX)  // cheap projection
}
```

### DON'T
```kotlin
override fun draw(...) {
    // BAD: allocates per frame (Viewport + WorldToScreenMapper + WorldRect)
    val transform = WorldViewportTransform(width, height)
    
    // BAD: allocates per mountain per frame
    val random = Random(seed)
    val points = ArrayList<Pair<Float, Float>>()
}
```

### DO: Pre-compute deterministic geometry
```kotlin
private fun generateMountains() {
    // Pre-compute shape from seed ONCE
    val shape = precomputeShape(shapeSeed, roughness, ridgeRoughness)
    MountainData(..., shape = shape)
}

private fun drawMountain(mountain: MountainData) {
    // Use cached normalised shape — zero allocations
    val shape = mountain.shape
    for (i in 0 until shape.leftSlopeXs.size) {
        pathLeft.lineTo(
            actualPeakX + shape.leftSlopeXs[i] * halfWidthPx,
            peakY + shape.leftSlopeYs[i] * mountainH
        )
    }
}
```

### DON'T: Recompute from seed every frame
```kotlin
private fun drawMountain(mountain: MountainData) {
    // BAD: identical computation every frame from same seed
    val random = Random(mountain.shapeSeed)
    val ridgePoints = ArrayList<Pair<Float, Float>>()
    for (i in 0 until segments) {
        ridgePoints.add(Pair(x, y))
    }
}
```

## How to Add a New World-Space Layer Safely

1. **Cache the transform**: Add `private var cachedTransform = WorldViewportTransform(1f, 1f)` and rebuild in `resize()`.

2. **Generate within bounds**: Use `RenderConstants.getVisibleWorldHalf(width)` or `cachedTransform.visibleCellRange(cellSize)` to determine the generation range.

3. **Cull at draw time**: Before drawing each object, check if its screen-projected X is within `[0 - margin, screenWidth + margin]`.

4. **Pre-allocate draw buffers**: Paint, Path, and array objects should be class fields, not local variables in draw loops.

5. **Pre-compute deterministic data**: If an object's geometry depends only on a seed and settings (not animation state), compute it at generation time and store it.

6. **Height-independent generation**: The same set of objects must be generated regardless of screen height. Only screen width affects the generation range.

7. **Add performance tests**: Write tests that verify cell count is bounded by viewport width and that zoom-in reduces cost.
