# Terrain Generation

## Overview

Terrain is procedurally generated on load and when settings change. Consists of three main elements: Mountains, Hills, and Trees.

## Mountains

### Data Structure

```kotlin
private data class MountainShape(
    val stepsLeft: Int,           // Left silhouette vertex count
    val stepsRight: Int,          // Right silhouette vertex count
    val leftJitter: FloatArray,   // Per-vertex roughness offsets
    val rightJitter: FloatArray,
    val ridgeSteps: Int,          // Ridge line vertex count
    val ridgeJitterX: FloatArray,
    val ridgeJitterY: FloatArray,
    val baseCenterBias: Float     // Ridge asymmetry (-0.125 to 0.125)
)

private data class MountainData(
    val peakX: Float,       // X position in world coords
    val peakHeight: Float,  // Y ratio (0=top, 1=bottom)
    val slopeWidth: Float,  // Width multiplier
    val depthFactor: Float, // Parallax depth (0.1-0.6)
    val shape: MountainShape
)
```

### Generation Algorithm

```kotlin
private fun generateMountains() {
    // 1. Use time-based seed for variety per session
    val random = Random(System.currentTimeMillis() / 60000)
    
    // 2. Position distribution
    // - 70% in visible area (-0.5 to 1.5)
    // - 30% in extended parallax area
    
    // 3. Depth-based sizing
    // - Far (depth < 0.3): Tall, wide (32-46% height, 0.40-0.58 width)
    // - Mid (depth < 0.6): Medium (38-50% height)
    // - Near: Low foothills (45-55% height)
    
    // 4. Sort by depth (far drawn first)
    mountains.sortBy { it.depthFactor }
}
```

### Shape Creation

```kotlin
private fun createMountainShape(random: Random, slopeWidth: Float): MountainShape {
    // Wider mountains get more facets
    val stepsBase = (14 + (slopeWidth * 20f).toInt()).coerceIn(14, 26)
    
    // Roughness affects jitter magnitude
    val roughnessMult = 0.3f + mountainRoughness * 1.4f
    
    // Jitter function with bias towards smaller values
    fun nextJitter(): Float {
        val signed = random.nextFloat() * 2f - 1f
        return signed * random.nextFloat() * roughnessMult
    }
}
```

### Drawing (Split Ridge)

Mountains have two sides with different brightness (light/shadow):

```kotlin
private fun drawMountain(...) {
    // Left (lit) side: peak → left base
    mountainPathLeft.moveTo(leftBaseX, baseY)
    for (step in silhouette) {
        mountainPathLeft.lineTo(x + jitter, y)
    }
    mountainPathLeft.lineTo(peakCenterX, peakY)
    // ... ridge down to center
    
    // Right (shadow) side: darker color
    val darkColor = multiplyColor(lightColor, 0.82f)
}
```

## Hills

### Data Structure

```kotlin
private data class HillData(
    val controlPoints: List<Float>, // Height values at evenly-spaced X positions
    val depthFactor: Float,         // Parallax (0.3-0.5)
    val heightOffset: Float,        // Y position on screen
    val hillIndex: Int              // For consistent tree placement
)
```

### Generation

```kotlin
private fun generateHills() {
    // Fixed seed for consistent terrain trees can reference
    val random = Random(12345)
    
    for (i in 0 until hillCount) {
        // 60-80 control points covering WORLD_MIN_X to WORLD_MAX_X
        val numPoints = 60 + random.nextInt(20)
        
        // Height values: 30-50% of available space
        val points = List(numPoints) { 0.3f + random.nextFloat() * 0.2f }
        
        // Depth progression from far to near
        val depth = i.toFloat() / hillCount
        val heightOffset = hillLowerStart + depth * (hillUpperEnd - hillLowerStart)
    }
}
```

### Tree Height Sampling

Trees query hill height at their X position:

```kotlin
private fun getHillY(xRatio: Float, hill: HillData): Float {
    // Linear interpolation between control points
    val normalizedX = (xRatio - WORLD_MIN_X) / (WORLD_MAX_X - WORLD_MIN_X)
    val indexFloat = normalizedX * (points.size - 1)
    
    return hill.heightOffset + 0.12f * (1f - interpolatedControl)
}
```

## Trees

### Data Structure

```kotlin
private data class TreeData(
    val xRatio: Float,     // X position in world coords
    val yRatio: Float,     // Y position (sits on hill)
    val scale: Float,      // Size (0.3-1.0 based on depth)
    val depthFactor: Float // Matches parent hill's depth
)
```

### Generation

```kotlin
private fun generateTrees() {
    for (i in 0 until treeCount) {
        // Random X across world
        val xRatio = WORLD_MIN_X + random.nextFloat() * (WORLD_MAX_X - WORLD_MIN_X)
        
        // Pick random hill to sit on
        val hillIndex = random.nextInt(hills.size)
        val hill = hills[hillIndex]
        
        // Query exact Y on hill surface
        val groundY = getHillY(xRatio, hill)
        
        // Sit slightly into ground
        val yRatio = groundY + TREE_FOOT_OFFSET
        
        // Use hill's depth for parallax sync
        val depthFactor = hill.depthFactor
    }
}
```

### Drawing with Segments

Trees are drawn as stacked triangular tiers:

```kotlin
private fun drawTree(canvas, xRatio, baseY, scale, segments, ..., depthFactor) {
    for (seg in 0 until segments) {
        // Width decreases towards top
        val widthRatio = 1f - segProgress * 0.6f
        
        // Wind sway calculation
        val swayAmount = windIntensity * heightRatio * 25f * scale
        val sway = sin(timeSeconds * 2.5f + phaseOffset) * swayAmount
        
        // Draw triangular tier
        treePath.moveTo(x + sway, tierTopY)
        treePath.lineTo(x - tierWidth, tierBottomY)
        treePath.lineTo(x + tierWidth, tierBottomY)
    }
}
```

## Regeneration Triggers

Terrain regenerates when settings change:

```kotlin
// Mountains regenerate on:
if (mountainCount != lastMountainCount || 
    mountainWidthScale != lastMountainWidthScale ||
    mountainHeightScale != lastMountainHeightScale ||
    mountainRoughness != lastMountainRoughness) {
    generateMountains()
}

// Hills + Trees regenerate together:
if (hills.size != hillCount || 
    hillLowerStart != lastHillLowerStart ||
    hillUpperEnd != lastHillUpperEnd) {
    generateHills()
    generateTrees()  // Trees depend on hills
}
```

