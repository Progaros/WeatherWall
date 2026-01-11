# WeatherWall

[![Build Status](https://ci.olli.info/api/badges/4/status.svg)](https://ci.olli.info/repos/4)

📥 **[Download Latest APK](https://git.olli.info/Oliver/WeatherWall/releases/latest)**

**Open-source, privacy-respecting, battery-friendly Android Live Wallpaper** with low-poly vector layers, weather effects, seasonal events, and optional 3D parallax.

<p align="center">
  <img src="app/src/main/res/logo.png" width="128" alt="WeatherWall Logo">
</p>

## ✨ Features

### 🎨 Visual Effects
- **Low-poly parallax scene** with layered mountains, hills, and pine trees
- **Dynamic day/night cycle** with smooth sky gradients (dawn, day, dusk, night)
- **Procedural moon phases** with accurate waxing/waning cycle
- **Weather effects**: Rain, Snow, Lightning with particle systems
- **Fog overlay** for atmospheric depth
- **Cloud layers** with wind-driven movement and soft blur
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

### ☀️ Breezy Weather Integration
WeatherWall can receive real-time weather data from [Breezy Weather](https://github.com/breezy-weather/breezy-weather) (privacy-friendly weather app):

**Supported data:**
- **Sunrise/sunset times** - Accurate dawn and dusk transitions based on your location
- **Weather conditions** - Rain, snow, thunderstorm, fog, clouds affect visual effects
- **Wind speed & direction** - Drives cloud movement and tree sway
- **Cloud cover** - Adjusts cloud layer density
- **Moon phase & times** - Accurate lunar cycle display

**Setup:**
1. Install Breezy Weather from F-Droid or GitHub
2. Open Breezy Weather → Settings → External Modules
3. Enable "Send Gadgetbridge data" → "Broadcast to all"
4. Weather data will be received automatically

Without Breezy Weather, sensible defaults are used (sunrise 6:00, sunset 20:00).

### ⚙️ Customization
- Parallax strength, smoothing, return speed, and depth separation
- Sun/moon arc height, celestial size, moon enable/disable
- Tree density, spread, sparsity, and sway speed
- Cloud speed, opacity, density, and stretch
- Individual event toggles (enable/disable specific animals)
- Mountain and hill count, height, roughness
- Simulation mode for testing time, weather, wind, moon phase

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

For faster repeated builds, use the pre-cached Docker image.
**Note:** You must mount your current directory to `/workspace` so the builder can access your source code.

```bash
# 1. Build the cached builder image (run once or when dependencies change)
docker build -t weatherwall-builder -f Dockerfile.build .

# 2. Run commands (mounts current directory to /workspace)
# Run Unit Tests
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew test --no-daemon

# Run Lint Checks
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew lintDebug --no-daemon

# Build Debug APK
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew assembleDebug --no-daemon
```

## 🧪 Testing

The project includes 535+ unit tests covering all major features:

```bash
# Run all tests locally
./gradlew test

# Run lint checks
./gradlew lintDebug

# View test report
open app/build/reports/tests/testDebugUnitTest/index.html
```

**CI Pipeline:**
- Lint checks (Android Lint)
- Unit tests (535+ tests)
- Debug APK build
- Release publishing (on version bump)

**Test Coverage:**
- `ParallaxSceneTest` - Terrain baseline, parallax factors, color transitions
- `ParallaxSensorManagerTest` - Rotation matrix, orientation extraction, latency optimization
- `ParallaxCalculatorTest` - Return speed, tilt thresholds, latency optimization
- `DayNightCycleTest` - Time periods, sunset colors, manual time override
- `WeatherRepositoryTest` - Data freshness, staleness detection
- `SeasonalEventsTest` - Christmas/New Year detection, debug overrides
- `CloudLayerTest` - Soft edges, density levels, bitmap sizing
- `SkyLayerTest` - Moon phases, celestial rendering
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
│   ├── CloudLayer.kt            # Animated cloud shapes with caching
│   ├── MountainLayer.kt         # Background mountain silhouettes
│   ├── ParallaxScene.kt         # Main scene renderer and layer orchestration
│   ├── RandomEventLayer.kt      # Wildlife and sky event management
│   ├── RenderConstants.kt       # Shared rendering constants
│   ├── SceneLayer.kt            # Layer interface for all renderers
│   ├── SeasonalEvents.kt        # Christmas/New Year effects
│   ├── SkyLayer.kt              # Gradient sky, sun/moon with phases
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

