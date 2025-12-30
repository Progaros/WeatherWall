# WeatherWall

[![Build Status](https://ci.olli.info/api/badges/4/status.svg)](https://ci.olli.info/repos/4)  
[Download APK](https://git.olli.info/Oliver/WeatherWall/releases/latest)

**Open-source, privacy-respecting, battery-friendly Android Live Wallpaper** with low-poly vector layers, weather effects, seasonal events, and optional 3D parallax.

<p align="center">
  <img src="app/src/main/res/logo.png" width="128" alt="WeatherWall Logo">
</p>

## ✨ Features

### 🎨 Visual Effects
- **Low-poly parallax scene** with layered mountains, hills, and pine trees
- **Dynamic day/night cycle** with smooth sky gradients (dawn, day, dusk, night)
- **Weather effects**: Rain, Snow, Lightning with particle systems
- **Fog overlay** for atmospheric depth
- **Seasonal events**: Christmas stars (Dec 15-26), New Year fireworks (Dec 27 - Jan 7)

### 🦅 Random Events
Animated wildlife and sky events appear randomly throughout the day:
- **Day**: Airplanes with contrails, bird flocks, eagles, butterflies, hot air balloons
- **Night**: Shooting stars, fireflies, owls, UFOs (very rare!)
- **Any time**: Rabbits hopping, foxes walking

### 🌲 Terrain System
- Layered hills with Perlin noise-based procedural generation
- Pine trees distributed along hillsides with configurable density and spread
- Realistic wind-based tree sway with ambient movement
- Sparsity control for natural treeline gaps

### 🔋 Battery-First Design
- Adaptive FPS (10-60 FPS configurable)
- Sensors only active when wallpaper is visible
- Zero per-frame allocations (pre-allocated paints, paths, particle pools)
- Efficient low-poly rendering without complex shaders

### 🔒 Privacy-First
- No trackers, analytics, or ads
- No INTERNET permission required
- Optional Breezy Weather integration for real weather data
- All processing done locally on device

### ⚙️ Customization
- Parallax strength, smoothing, and depth separation
- Tree density, spread, sparsity, and sway speed
- Individual event toggles (enable/disable specific animals)
- Mountain and hill count, height, roughness

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
app/src/main/java/info/olli/weatherlwp/
├── MainActivity.kt              # Settings activity
├── parallax/
│   ├── ParallaxCalculator.kt    # Parallax offset calculations
│   └── ParallaxSensorManager.kt # Accelerometer-based parallax input
├── prefs/
│   └── SettingsFragment.kt      # Preferences UI with event spawning
├── rendering/
│   ├── CloudLayer.kt            # Animated cloud shapes
│   ├── MountainLayer.kt         # Background mountain silhouettes
│   ├── ParallaxScene.kt         # Main scene renderer and layer orchestration
│   ├── RandomEventLayer.kt      # Wildlife and sky event management
│   ├── RenderConstants.kt       # Shared rendering constants
│   ├── SeasonalEvents.kt        # Christmas/New Year effects
│   ├── SkyLayer.kt              # Gradient sky and sun/moon
│   ├── TerrainLayer.kt          # Hills and trees with wind animation
│   ├── WeatherLayer.kt          # Rain/Snow/Lightning particles
│   └── events/                  # Individual event implementations
│       ├── AirplaneEvent.kt
│       ├── AlienEvent.kt
│       ├── BirdFlockEvent.kt
│       ├── ButterflyEvent.kt
│       ├── EagleEvent.kt
│       ├── FireflyEvent.kt
│       ├── FoxEvent.kt
│       ├── HotAirBalloonEvent.kt
│       ├── OwlEvent.kt
│       ├── RabbitEvent.kt
│       ├── RandomEvent.kt       # Event interface and EventLayer enum
│       └── ShootingStarEvent.kt
├── simulation/
│   ├── SimulationClock.kt       # Time-based day/night calculations
│   ├── SimulationController.kt  # Main simulation state manager
│   ├── SimulationSettings.kt    # Preference reader
│   └── SimulationState.kt       # Immutable state snapshot per frame
├── wallpaper/
│   └── WeatherWallpaper.kt      # WallpaperService implementation
└── weather/
    ├── BreezyWeatherProvider.kt # Breezy Weather integration
    ├── BreezyWeatherReceiver.kt # Broadcast receiver for weather updates
    ├── WeatherProvider.kt       # Weather data interface
    ├── WeatherRepository.kt     # Weather data storage
    └── WeatherType.kt           # Weather type enumeration
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

