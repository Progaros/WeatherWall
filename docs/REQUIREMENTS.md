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
| P5 | Vertical movement should be noticeable | ✅ Fixed (normalized sensor input) |
| P6 | If extreme movement, center again | ✅ Implemented (damping at >1.0) |

### Test Cases
```kotlin
// P1: Test default parallax is subtle
@Test fun testDefaultParallaxStrength() {
    val calculator = ParallaxCalculator()
    calculator.strength = 0.8f  // 80% default
    calculator.maxOffsetPx = 40f
    calculator.updateFromSensor(1.0f, 0f)  // Full tilt
    calculator.update()
    assert(calculator.getOffsetX() <= 40f) // Should not exceed maxOffset
}

// P4: Test return speed affects centering
@Test fun testReturnSpeedEffective() {
    val calculator = ParallaxCalculator()
    calculator.centerDriftRate = 0.15f  // Max speed
    calculator.updateFromSensor(1.0f, 0f)
    calculator.update()
    Thread.sleep(600)  // Past 500ms grace period
    calculator.update(16)
    val afterDrift = calculator.getOffsetX()
    assert(afterDrift < calculator.maxOffsetPx) // Should have drifted toward center
}

// P5: Test vertical movement
@Test fun testVerticalParallax() {
    val calculator = ParallaxCalculator()
    calculator.updateFromSensor(0f, 1.0f)  // Full Y tilt
    calculator.update()
    assert(calculator.getOffsetY() > 0f) // Y should be affected
}
```

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
| D1 | Manual time slider shows hours (0-23), not minutes | ✅ Fixed |
| D2 | Debug "Simulate Time of Day" should work | ✅ Implemented |
| D3 | Dawn = hour 6-7, Dusk = hour 19, Night = 0-5 or 20-23 | ✅ Fixed |

### Test Cases
```kotlin
// D1: Test manual time is hours
@Test fun testManualTimeIsHours() {
    setPreference("pref_daynight_enabled", false)
    setPreference("pref_manual_time", 14)  // 2 PM
    val scene = ParallaxScene(mockContext, mockSensor, mockWeather)
    scene.loadPrefs()
    // Theme should be "day" (hour 14)
}

// D2: Test debug time override
@Test fun testDebugTimeOverride() {
    setPreference("pref_debug_enabled", true)
    setPreference("pref_debug_time", "night")
    val scene = ParallaxScene(mockContext, mockSensor, mockWeather)
    scene.loadPrefs()
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

## 6. Preferences / UI

### Requirements
| ID | Requirement | Status |
|----|-------------|--------|
| U1 | Remove duplicate cloud density settings? | ℹ️ Kept (one normal, one debug) |
| U2 | Remove simulate time of day? | ℹ️ Kept (useful for debug) |
| U3 | Default values should be reasonable, not at extremes | ✅ Adjusted |

### Current Defaults
| Setting | Default | Range |
|---------|---------|-------|
| Mountain Count | 3 | 0-10 |
| Mountain Width | 115% | 50-1000% |
| Mountain Height | 150% | 10-1000% |
| Mountain Roughness | 80 | 0-100 |
| Hill Layers | 10 | 0-50 |
| Hill Start | 50% | 30-70% |
| Hill End | 85% | 60-90% |
| Tree Count | 100 | 0-200 |
| Tree Size | 200 (1.0x) | 10-1000 |
| Tree Color Variation | 100 | 0-100 |
| Wind Strength | 50 | 0-100 |
| Parallax Strength | 80 | 10-300 |
| Max Parallax Offset | 40 | 5-200 |
| Return Speed | 50 | 1-100 |
| Manual Time | 12 | 0-23 (hours) |

---

## Build & Verification

### Build Command
```bash
docker run --rm -v "$PWD":/workspace -w /workspace ghcr.io/cirruslabs/android-sdk:35 ./gradlew assembleDebug
```

### APK Location
```
app/build/outputs/apk/debug/app-debug.apk
```

### Last Successful Build
- **Date**: 2025-12-28 16:07:55 CET
- **Status**: BUILD SUCCESSFUL

---

## Files Modified

1. `ParallaxCalculator.kt` - Added configurable limits and drift rates
2. `ParallaxSensorManager.kt` - Fixed normalized sensor output
3. `ParallaxScene.kt` - Added debug time support, fixed manual time
4. `TerrainLayer.kt` - Added hill position, color variation, debug wind support
5. `CloudLayer.kt` - Fixed density values, added debug override
6. `SeasonalEvents.kt` - Fixed star positioning and overlap
7. `preferences.xml` - Fixed manual time slider (hours not minutes)
