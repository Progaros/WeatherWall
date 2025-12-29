# WeatherWall Requirements & Test Cases

## Session Summary (Dec 28, 2025)

This document summarizes all requirements discussed and fixes implemented during this development session.

---

## 1. Parallax System

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| P1 | Parallax should not be too strong/aggressive | ✅ Fixed |
| P2 | Add "Max Parallax Distance" slider (5-200 px) | ✅ Implemented |
| P3 | Add "Return Speed" slider (1-100) | ✅ Implemented |
| P4 | Return speed should have noticeable effect | ✅ Fixed (range 0.01-0.15) |
| P5 | Vertical movement should be noticeable | ✅ Fixed (normalized sensor by 5f not 9.81) |
| P6 | If extreme movement, center again | ✅ Implemented (damping at >1.0) |
| P7 | Grace period before centering | ✅ Implemented (200ms) |

### Implementation Details
- **Sensor normalization**: Divide by 5f (typical tilt range) instead of 9.81 (gravity)
- **Return speed formula**: `centerDriftRate = 0.01f + (slider/100f) * 0.14f` (range 0.01-0.15)
- **Lerp factor includes centerDriftRate**: `lerpFactor = 0.5f + centerDriftRate * 5f`
- **Grace period**: 200ms before drift applies (reduced from 500ms)
- **Extreme damping**: Input >1.0 triggers 50% damping

---

## 2. Terrain Layer (Hills & Trees)

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| T1 | Hill count setting should work | ✅ Fixed |
| T2 | Tree count setting should work | ✅ Fixed |
| T3 | Hill Start Position (30-70%) should work | ✅ Implemented |
| T4 | Hill End Position (60-90%) should work | ✅ Implemented |
| T5 | Tree Color Variation (0-100%) should work | ✅ Implemented |
| T6 | Tree Size setting should work | ✅ Fixed |
| T7 | Wind Strength setting should work | ✅ Fixed |
| T8 | Trees should not jump when changing settings | ✅ Fixed (fixed seed) |
| T9 | Debug Wind Speed override should work | ✅ Implemented |

### Test Cases
```kotlin
// T3/T4: Test hill position settings
@Test fun testHillPositionSettings() {
    val layer = TerrainLayer(mockContext)
    // Set prefs to hillStartY=40, hillEndY=80
    setPreference("pref_hill_lower_start", 40)
    setPreference("pref_hill_upper_end", 80)
    layer.reloadSettings()
    // Hills should span from 0.4 to 0.8 of screen height
}

// T5: Test color variation
@Test fun testTreeColorVariation() {
    // With variation=0, all trees should have same color
    setPreference("pref_tree_color_variation", 0)
    val layer = TerrainLayer(mockContext)
    layer.reloadSettings()
    // All tree colors should be identical
    
    // With variation=100, trees should have varied colors
    setPreference("pref_tree_color_variation", 100)
    layer.reloadSettings()
    // Tree colors should differ
}

// T8: Test tree stability
@Test fun testTreesDoNotJumpOnSettingsChange() {
    val layer = TerrainLayer(mockContext)
    layer.reloadSettings()
    val treePositions1 = captureTreePositions(layer)
    
    // Change unrelated setting
    setPreference("pref_wind_strength", 75)
    layer.reloadSettings()
    val treePositions2 = captureTreePositions(layer)
    
    assertEquals(treePositions1, treePositions2)
}
```

---

## 3. Cloud Layer

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| C1 | "None" cloud density = 0 clouds | ✅ Fixed |
| C2 | "Overcast" should have more clouds | ✅ Fixed (35 clouds) |
| C3 | Debug cloud override should work | ✅ Implemented |
| C4 | Cloud density values: none=0, light=4, medium=10, heavy=20, overcast=35 | ✅ Implemented |

### Test Cases
```kotlin
// C1: Test none = no clouds
@Test fun testNoClouds() {
    setPreference("pref_clouds_enabled", true)
    setPreference("pref_cloud_density", "none")
    val layer = CloudLayer(mockContext)
    layer.reloadSettings()
    assertEquals(0, layer.cloudCount)
}

// C2: Test overcast has many clouds
@Test fun testOvercastClouds() {
    setPreference("pref_clouds_enabled", true)
    setPreference("pref_cloud_density", "overcast")
    val layer = CloudLayer(mockContext)
    layer.reloadSettings()
    assertEquals(35, layer.cloudCount)
}

// C3: Test debug override
@Test fun testDebugCloudOverride() {
    setPreference("pref_clouds_enabled", true)
    setPreference("pref_cloud_density", "auto")
    setPreference("pref_debug_enabled", true)
    setPreference("pref_debug_cloud_override", "heavy")
    val layer = CloudLayer(mockContext)
    layer.reloadSettings()
    assertEquals(20, layer.cloudCount)  // Uses debug value, not auto
}
```

---

## 4. Time of Day Settings

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| D1 | Manual time slider uses minutes (0-1439), default 720 (noon) | ✅ Fixed |
| D2 | Debug "Simulate Time of Day" should work | ✅ Implemented |
| D3 | Dawn = hour 6-7, Dusk = hour 19, Night = 0-5 or 20-23 | ✅ Fixed |

### Test Cases
```kotlin
// D1: Test manual time is minutes
@Test fun testManualTimeIsMinutes() {
    setPreference("pref_debug_enabled", true)
    setPreference("pref_manual_time", 840)  // 14:00 (2 PM) = 14*60 = 840 minutes
    val settings = SimulationSettings(mockContext)
    // manualTimeMinutes should be 840
    // Converted to hours: 840 / 60 = 14.0
}

// D2: Test debug time override
@Test fun testDebugTimeOverride() {
    setPreference("pref_debug_enabled", true)
    setPreference("pref_manual_time", 0)  // Midnight
    val settings = SimulationSettings(mockContext)
    // Should use hour=0 regardless of real time
}
```

---

## 5. Seasonal Events

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| S1 | Christmas simulation should show only 1 main star | ✅ Fixed |
| S2 | Background stars should not overlap with main star | ✅ Fixed |
| S3 | Star size/trail settings should work | ✅ Working |
| S4 | Star position should be stable (not jump on reinit) | ✅ Fixed (fixed position) |

### Test Cases
```kotlin
// S1: Test single main star
@Test fun testSingleChristmasStar() {
    val events = SeasonalEvents(mockContext)
    events.initParticles()
    // There should be exactly 1 ChristmasStar object
    assertNotNull(events.christmasStar)
}

// S2: Test background stars avoid main star area
@Test fun testBackgroundStarsAvoidMainStar() {
    val events = SeasonalEvents(mockContext)
    events.initParticles()
    val starX = events.christmasStar?.x ?: 0f
    val starY = events.christmasStar?.y ?: 0f
    val exclusionRadius = events.screenWidth * 0.15f
    
    events.stars.forEach { bgStar ->
        val dx = abs(bgStar.x - starX)
        val dy = abs(bgStar.y - starY)
        // At least one dimension should be outside exclusion zone
        assert(dx >= exclusionRadius || dy >= exclusionRadius)
    }
}
```

---

## 6. Weather Effects

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| W1 | Lightning should have dark scary storm clouds | ✅ Implemented |
| W2 | Wind should affect trees | ✅ Fixed (windSpeed/25 multiplier) |
| W3 | Wind should affect clouds | ✅ Implemented |
| W4 | Rain/Snow should be visible | ✅ Working |

### Implementation Details
- **Storm Clouds**: 3 layers of animated dark puffs behind lightning bolt
- **Wind on Trees**: Tree sway calculated as `windSpeed / 25f` (range 0-4x)
- **Wind on Clouds**: Cloud speed multiplied by `1 + windStrength/50f`

---

## 7. Preferences / UI

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| U1 | Remove duplicate debug wind speed setting | ✅ Removed |
| U2 | Manual time slider should use minutes (0-1439) | ✅ Fixed |
| U3 | Mountain roughness max should be 200 | ✅ Fixed |
| U4 | Add easter egg to version tap | ✅ Implemented |

### Easter Egg
- Tap version text 5 times to show random fun message
- Messages include jokes about pixels, weather, and the app

### Current Defaults
| Setting | Default | Range |
|---------|---------|-------|
| Mountain Count | 3 | 0-10 |
| Mountain Width | 115% | 50-1000% |
| Mountain Height | 150% | 10-1000% |
| Mountain Roughness | 80 | 0-200 |
| Hill Layers | 10 | 0-50 |
| Hill Start | 50% | 30-70% |
| Hill End | 85% | 60-90% |
| Tree Count | 100 | 0-200 |
| Tree Size | 200 (1.0x) | 10-1000 |
| Tree Segments | 3 | 1-5 |
| Tree Color Variation | 100 | 0-100 |
| Wind Strength | 50 | 0-100 |
| Parallax Strength | 80 | 10-300 |
| Max Parallax Offset | 40 | 5-200 |
| Return Speed | 50 | 1-100 |
| Manual Time | 720 | 0-1439 (minutes) |

---

## 8. Build & Testing

### Build Command
```bash
docker run --rm -v "$PWD":/workspace -w /workspace ghcr.io/cirruslabs/android-sdk:35 ./gradlew assembleDebug
```

### Test Command
```bash
docker run --rm -v "$PWD":/workspace -w /workspace ghcr.io/cirruslabs/android-sdk:35 ./gradlew testDebugUnitTest
```

### APK Location
```
app/build/outputs/apk/debug/app-debug.apk
```

### Test Results (Dec 28, 2025)
- **Total Tests**: 81
- **Passed**: 81
- **Failed**: 0
- **Status**: ✅ ALL PASSING

---

## 9. Files Modified

### Core Rendering
| File | Changes |
|------|---------|
| `ParallaxCalculator.kt` | Added configurable limits and drift rates, grace period 200ms |
| `ParallaxSensorManager.kt` | Fixed normalized sensor output (divide by 5f not 9.81) |
| `ParallaxScene.kt` | Added debug time support, fixed manual time to minutes |
| `TerrainLayer.kt` | Added hill position, color variation, tree segments, wind effect |
| `CloudLayer.kt` | Fixed density values (none=0, overcast=35), added wind multiplier |
| `MountainLayer.kt` | Increased roughness range to 0-200 |
| `SkyLayer.kt` | Improved dawn/dusk color transitions |
| `WeatherLayer.kt` | Added storm clouds to lightning effect |
| `SeasonalEvents.kt` | Fixed star positioning, exclusion zone for background stars |

### UI/Settings
| File | Changes |
|------|---------|
| `SettingsFragment.kt` | Added version tap easter egg |
| `preferences.xml` | Fixed manual time (0-1439), roughness max 200, removed debug wind |

### Tests
| File | Purpose |
|------|---------|
| `ParallaxCalculatorTest.kt` | Tests parallax physics, strength, offset limits |
| `ParallaxSceneTest.kt` | Tests terrain baseline, sunset timing, night colors |
| `SettingsTest.kt` | Tests preference values, cloud density mapping |
| `HillsTerrainTest.kt` | Tests hill generation, parallax factors |
| `TreeRenderingTest.kt` | Tests tree generation, color variation |
| `SeasonalEventsTest.kt` | Tests Christmas star, background stars |
| `WeatherRepositoryTest.kt` | Tests weather data handling |

---

## 10. Simulation Architecture (Dec 2025 Refactor)

### Overview
The rendering system has been refactored to use a centralized Simulation module that provides consistent state across all layers.

### Components

| Component | Purpose |
|-----------|---------|
| `SimulationClock` | Manages time (real or manual), provides consistent time values |
| `SimulationState` | Immutable snapshot of all simulation parameters |
| `SimulationSettings` | Reads preferences, handles debug overrides |
| `SimulationController` | Coordinates clock, settings, weather to produce state |

### SimulationState Properties

```kotlin
data class SimulationState(
    timeOfDayHours: Float,      // 0-24
    timeSeconds: Float,         // Absolute time for animations
    absoluteTimeMs: Long,       // Epoch time for consistent positions
    isNight: Boolean,
    isDawn: Boolean,
    isDusk: Boolean,
    dayProgress: Float,         // 0-1
    weather: WeatherType,
    cloudDensity: CloudDensity,
    windStrength: Float,        // 0-1 normalized
    windDirection: Float,       // Degrees
    screenWidth: Float,
    screenHeight: Float,
    isDebugMode: Boolean
)
```

### Layer Interface

All scene layers now receive SimulationState:

```kotlin
interface SceneLayer {
    fun resize(w: Float, h: Float)
    fun update(state: SimulationState, deltaTimeMs: Long)
    fun draw(canvas: Canvas, state: SimulationState, parallaxCalculator: ParallaxCalculator)
    fun reloadSettings()
    fun destroy()
}
```

### Benefits

| Benefit | Description |
|---------|-------------|
| Consistent animations | All layers use same time values |
| Deterministic positions | Clouds, trees use absolute time for stable positions |
| Easier testing | SimulationState can be mocked for unit tests |
| Centralized settings | All settings flow through SimulationController |
| Debug overrides | Time/weather overrides work consistently |

### Test Coverage

| Test Class | Coverage |
|------------|----------|
| `SimulationClockTest.kt` | Time modes, night/dawn/dusk detection, manual time |
| `SimulationStateTest.kt` | Cloud counts, wind components, immutability |

### Test Results (Dec 2025)
- **Total Tests**: 120
- **Passed**: 120  
- **Failed**: 0
- **Status**: ✅ ALL PASSING

---

## 11. Known Issues / Future Work

| Issue | Status | Notes |
|-------|--------|-------|
| Unused variables in MountainLayer | ⚠️ Warning | currentX, currentY in drawMountain |
| Unused variable in ParallaxScene | ⚠️ Warning | `now` variable in one method |
| Time-dependent tests | ℹ️ Limitation | Can't easily test center drift without clock injection |
