# WeatherWall Issues and Ideas

This document tracks known issues, analyzed solutions, and improvement ideas.

## Known Issues

### Issue 1: Mountains Too Small (Default Width)

**Status**: ✅ Fixed

**User Report**: "Mountains too small (~150-200% width seems good default)"

**Fix Applied**:
- Changed default from 500 to 175 in [MountainLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/MountainLayer.kt#L317)
- Updated [preferences.xml](../app/src/main/res/xml/preferences.xml#L30) default to match

---

### Issue 2: Hill Layer Positioning  

**Status**: ✅ Verified Correct

**User Report**: "Closer hill layers should be a lot lower on screen, further layers more up"

**Analysis**:
The current code correctly implements this:
- `depth = 0` (far hill): `heightOffset = hillStartY (0.6)` = 60% down (higher on screen)
- `depth = 1` (near hill): `heightOffset = hillEndY (0.8)` = 80% down (lower on screen)
- Hills are sorted by `depthFactor`, so far hills (lower depth) draw first (behind)

**Conclusion**: No change needed. The logic was already correct.

---

### Issue 3: Preview Doesn't Update After Settings Change

**Status**: � Partially Addressed (Thread Safety Fixed)

**User Report**: "Preview doesn't update (have to close app after settings change)"

**Analysis**:
The `MainActivity` registers a preference listener that calls `doPreferenceReload()`.
Thread safety issues were likely causing some updates to not take effect.

**Fix Applied**:
Added thread-safe data patterns to all layers:
- CloudLayer: `@Volatile` + immutable list swap
- TerrainLayer: `@Volatile` + immutable list swap
- MountainLayer: `@Volatile` + immutable list swap

**Remaining Investigation**:
If issue persists, check:
1. Is `previewScene` null when prefs change?
2. Are all settings being propagated via `reloadSettings()`?

---

### Issue 4: Old Clouds Still Visible

**Status**: ✅ Fixed

**User Report**: "Old clouds still visible"

**Root Cause**:
CloudLayer was using `ArrayList<CloudData>` which is not thread-safe. The render thread could read stale data while the main thread was modifying the list.

**Fix Applied** in [CloudLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/CloudLayer.kt):
- Changed to `@Volatile private var clouds: List<CloudData> = emptyList()`
- `generateClouds()` creates new list and atomically swaps
- `draw()` takes local snapshot before iteration

Same pattern applied to TerrainLayer and MountainLayer.
}
```

**Recommended Fix**:
Use synchronized access or copy-on-write:

Option A - Synchronization:
```kotlin
private val cloudsLock = Any()

private fun generateClouds() {
    synchronized(cloudsLock) {
        clouds.clear()
        // ... add clouds
    }
}

override fun draw(...) {
    synchronized(cloudsLock) {
        for (cloud in clouds) { ... }
    }
}
```

Option B - Copy-on-write (better for rendering performance):
```kotlin
@Volatile private var clouds: List<CloudData> = emptyList()

private fun generateClouds() {
    val newClouds = ArrayList<CloudData>()
    // ... populate newClouds
    clouds = newClouds  // Atomic reference swap
}

override fun draw(...) {
    val currentClouds = clouds  // Local snapshot
    for (cloud in currentClouds) { ... }
}
```

---

## Improvement Ideas

### Idea 1: Centralize Magic Numbers

**Priority**: Medium
**Effort**: Low

Many layers still have magic numbers:
- `0.3f + random.nextFloat() * 0.2f` (hill height)
- `0.7f + normalizedDepth * 0.3f` (tree scale)
- `0.15f * 0.15f * tree.scale` (tree height)

**Recommendation**: Move to RenderConstants:
```kotlin
// Terrain generation
const val HILL_BASE_HEIGHT = 0.3f
const val HILL_HEIGHT_RANGE = 0.2f
const val TREE_SCALE_MIN = 0.7f
const val TREE_SCALE_RANGE = 0.3f
const val TREE_HEIGHT_FACTOR = 0.15f
```

---

### Idea 2: Consistent Thread Safety Pattern

**Priority**: High
**Effort**: Medium

Multiple layers access shared state without synchronization:
- `CloudLayer.clouds`
- `TerrainLayer.hills`, `TerrainLayer.trees`
- `MountainLayer.mountains`

**Recommendation**: Use immutable snapshots for render thread:
1. Settings thread generates new data into temporary list
2. Atomically swap reference to new list
3. Render thread uses local reference (no locks needed)

```kotlin
@Volatile private var renderData: LayerData = LayerData.EMPTY

fun reloadSettings() {
    val newData = generateData()  // Create new immutable data
    renderData = newData           // Atomic swap
}

fun draw(canvas: Canvas, ...) {
    val data = renderData  // Local snapshot
    // Use data for rendering (no concurrent modification possible)
}
```

---

### Idea 3: Minutes Slider for Smoother Time Control

**Priority**: Low
**Effort**: Low

**User Suggestion**: "Hours slider should update on drag, add minutes for smoother changes"

**Current**: `pref_manual_time` is hours (0-23)

**Recommendation**: Add minutes or use decimal hours:

Option A - Add minutes slider:
```xml
<SeekBarPreference
    app:key="pref_manual_time_minutes"
    app:title="Manual Time (Minutes)"
    app:min="0"
    app:max="59"
    app:defaultValue="0"
/>
```

Option B - Change to decimal hours:
```kotlin
// In SimulationClock:
val hours = prefs.getInt("pref_manual_time", 12)
val minutes = prefs.getInt("pref_manual_time_minutes", 0)
return hours + minutes / 60f
```

---

### Idea 4: Precompute Layer Data on Settings Change

**Priority**: Medium
**Effort**: Medium

Currently, layers regenerate procedural data (hills, trees, mountains) in `reloadSettings()` which runs on the main thread. This can cause UI jank.

**Recommendation**: Move generation to background thread:
```kotlin
fun reloadSettings() {
    // Read preferences immediately
    val settings = readSettings()
    
    // Generate data in background
    executor.execute {
        val newData = generate(settings)
        mainHandler.post {
            renderData = newData
        }
    }
}
```

---

### Idea 5: Better Debugging Tools

**Priority**: Low
**Effort**: Low

Add visual debugging overlays:
- Show parallax depth values on screen
- Show FPS counter
- Show current time/weather state
- Highlight layer boundaries

```kotlin
if (debugEnabled) {
    canvas.drawText("Time: $timeOfDayHours", 10f, 30f, debugPaint)
    canvas.drawText("Parallax: ($px, $py)", 10f, 60f, debugPaint)
    canvas.drawText("FPS: $currentFps", 10f, 90f, debugPaint)
}
```

---

### Idea 6: Layer Visibility Toggles for Debugging

**Priority**: Low
**Effort**: Low

Add debug preferences to toggle individual layers:
```xml
<SwitchPreference app:key="pref_debug_show_sky" app:defaultValue="true"/>
<SwitchPreference app:key="pref_debug_show_clouds" app:defaultValue="true"/>
<SwitchPreference app:key="pref_debug_show_mountains" app:defaultValue="true"/>
<SwitchPreference app:key="pref_debug_show_terrain" app:defaultValue="true"/>
```

This helps isolate rendering issues.

---

## Session History

### Changes Applied This Session

1. ✅ Velocity scaling reduced (ParallaxSensorManager: 15→8, ParallaxCalculator: 3→1.2)
2. ✅ Depth formula fixed (multiplication instead of addition)
3. ✅ Smoothing formula fixed (higher = more filtering)
4. ✅ Boundary momentum clearing added
5. ✅ Atmospheric haze gradient cached
6. ✅ @Volatile annotations for thread safety
7. ✅ Error handling in layer rendering
8. ✅ Smooth time transitions (SimulationController)
9. ✅ Eased color transitions (RenderConstants)
10. ✅ Mountain default width fixed (500→175)
11. ✅ Thread-safe data patterns for all layers (CloudLayer, TerrainLayer, MountainLayer)
12. ✅ Extracted magic numbers to RenderConstants
13. ✅ Created architecture documentation (ARCHITECTURE.md)
14. ✅ Created parallax system documentation (PARALLAX_SYSTEM.md)
15. ✅ Created rendering pipeline documentation (RENDERING.md)
16. ✅ Switched to TYPE_GRAVITY sensor (reduces drift from hand jitter/walking)
17. ✅ Upgraded to lockHardwareCanvas() for GPU-accelerated rendering
18. ✅ Added fallback to accelerometer if gravity sensor unavailable
19. ✅ Fixed race condition in ParallaxCalculator (synchronized pending velocity access)
20. ✅ Removed duplicate weather/seasonal overlay rendering (now only in ParallaxScene)
21. ✅ Removed duplicate canvas clear (now only in WeatherWallpaper)
22. ✅ Fixed getOffsetForDepth() allocations (use primitive methods to avoid per-frame Pair creation)

### Remaining Work

1. 🟡 Preview update issue (may need further investigation)
2. 🟡 Consider adding minutes slider for time
3. 🟡 Background thread for preference reload (deferred - atomic swaps already provide safety)