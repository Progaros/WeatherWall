# WeatherWall

[![Build Status](https://ci.olli.info/api/badges/4/status.svg)](https://ci.olli.info/repos/4)

**Open-source, privacy-respecting, battery-friendly Android Live Wallpaper** with low-poly vector layers, weather effects, seasonal events, and optional 3D parallax.

<p align="center">
  <img src="app/src/main/res/logo.png" width="128" alt="WeatherWall Logo">
</p>

## ✨ Features

### 🎨 Visual Effects
- **Low-poly parallax scene** with layered mountains and pine trees
- **Dynamic day/night cycle** with smooth sky gradients (dawn, day, dusk, night)
- **Weather effects**: Rain, Snow, Lightning with particle systems
- **Fog overlay** for atmospheric depth
- **Seasonal events**: Christmas stars (Dec 15-26), New Year fireworks (Dec 27 - Jan 7)

### 🔋 Battery-First Design
- Adaptive FPS (20-30 FPS configurable)
- Sensors only active when wallpaper is visible
- Zero per-frame allocations (pre-allocated paints, paths, particle pools)
- Efficient low-poly rendering without complex shaders

### 🔒 Privacy-First
- No trackers, analytics, or ads
- No INTERNET permission required
- Optional Breezy Weather integration for real weather data
- All processing done locally on device

### ⚙️ Customization
- Parallax strength adjustment
- Tree count and size controls
- Toggle individual effects (fog, weather, day/night, seasonal)

## 📱 Screenshots

*Coming soon*

## 🚀 Quick Start

### Build from Source
```bash
# Clone the repository
git clone https://git.olli.info/Oliver/WeatherWall.git
cd WeatherWall

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build with Docker (Recommended for CI)

For faster repeated builds, use the pre-cached Docker image:

```bash
# Build the cached builder image (one-time, ~5 min)
docker build -t weatherwall-builder -f Dockerfile.build .

# Run tests (~30 sec with cached image)
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder "./gradlew test --no-daemon"

# Build debug APK (~20 sec with cached image)
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder "./gradlew assembleDebug --no-daemon"

# Run tests and build together
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder "./gradlew test assembleDebug --no-daemon"
```

**Without cached image** (slower, downloads Gradle each time):
```bash
docker run --rm -v "$PWD":/workspace -w /workspace ghcr.io/cirruslabs/android-sdk:35 ./gradlew test assembleDebug --no-daemon
```

### Build Release
```bash
./gradlew assembleRelease
```

## 🧪 Testing

The project includes 71 unit tests covering all major features:

```bash
# Run all tests locally
./gradlew test

# Run tests in Docker
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder "./gradlew test --no-daemon"

# View test report
open app/build/reports/tests/testDebugUnitTest/index.html
```

**Test Coverage:**
- `ParallaxSceneTest` - Terrain baseline, parallax factors, color transitions
- `ParallaxSensorManagerTest` - Accelerometer axis mapping, smoothing
- `DayNightCycleTest` - Time periods, sunset colors, manual time override
- `WeatherRepositoryTest` - Data freshness, staleness detection
- `SeasonalEventsTest` - Christmas/New Year detection, debug overrides
- `CloudLayerTest` - Soft edges, density levels
- `TreeRenderingTest` - Placement, depth coloring, scaling
- `HillsTerrainTest` - Layer ordering, parallax matching
- `BuildConfigTest` - Version codes, surface lifecycle

## 🏗️ Project Structure

```
app/src/main/java/com/example/weatherlwp/
├── MainActivity.kt              # Settings activity
├── easter/
│   └── MultiTapDetector.kt      # Easter egg gesture detection
├── parallax/
│   └── ParallaxSensorManager.kt # Accelerometer-based parallax
├── prefs/
│   └── SettingsFragment.kt      # Preferences UI
├── rendering/
│   ├── DayNightCycle.kt         # Time-based sky gradients
│   ├── FogLayer.kt              # Fog overlay effect
│   ├── ParallaxScene.kt         # Main scene renderer
│   ├── SeasonalEvents.kt        # Christmas/New Year effects
│   └── WeatherLayer.kt          # Rain/Snow/Lightning particles
└── wallpaper/
    └── WeatherWallpaper.kt      # WallpaperService implementation
```

## 🔧 Requirements

- Android 8.0+ (API 26)
- Android Studio Hedgehog or newer (for development)
- Gradle 8.13+

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 🔗 Links

- **Repository**: [git.olli.info/Oliver/WeatherWall](https://git.olli.info/Oliver/WeatherWall)
- **CI/CD**: [ci.olli.info](https://ci.olli.info/repos/4)
- **Issues**: [Report a bug](https://git.olli.info/Oliver/WeatherWall/issues)

