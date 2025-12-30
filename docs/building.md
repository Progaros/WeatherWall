# Building & Testing

Complete guide for building WeatherWall from source.

## Prerequisites

- **JDK 17+** - Required for Gradle 8.x
- **Android SDK 35** - Target API level
- **Git** - For cloning the repository

Or use Docker (recommended for CI):
- **Docker** - No other dependencies needed

## Building Locally

### Clone Repository

```bash
git clone https://git.olli.info/Oliver/WeatherWall.git
cd WeatherWall
```

### Build Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Build Release APK

```bash
./gradlew assembleRelease
```

Note: Release builds require signing configuration.

### Install on Device

```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or directly
./gradlew installDebug
```

## Building with Docker

Docker builds are reproducible and don't require local Android SDK installation.

### Build Cached Image (Recommended)

First, build the cached builder image (one-time, ~5 minutes):

```bash
docker build -t weatherwall-builder -f Dockerfile.build .
```

This image pre-downloads all Gradle dependencies for faster subsequent builds.

### Run Builds

```bash
# Build debug APK (~20 seconds with cache)
docker run --rm -v "$PWD":/workspace weatherwall-builder \
    "./gradlew assembleDebug --no-daemon"

# Run tests (~30 seconds with cache)
docker run --rm -v "$PWD":/workspace weatherwall-builder \
    "./gradlew test --no-daemon"

# Build and test together
docker run --rm -v "$PWD":/workspace weatherwall-builder \
    "./gradlew test assembleDebug --no-daemon"
```

### Without Cached Image

Slower but works without building the image first:

```bash
docker run --rm -v "$PWD":/app -w /app \
    ghcr.io/cirruslabs/android-sdk:35 \
    ./gradlew assembleDebug --no-daemon
```

## Running Tests

WeatherWall includes comprehensive unit tests covering all major features.

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests "info.olli.weatherlwp.ParallaxSceneTest"
```

### View Test Report

After running tests, open the HTML report:

```bash
# Linux/macOS
xdg-open app/build/reports/tests/testDebugUnitTest/index.html

# macOS alternative
open app/build/reports/tests/testDebugUnitTest/index.html
```

### Test Coverage Areas

| Test Class | Coverage |
|------------|----------|
| `ParallaxSceneTest` | Terrain baseline, parallax factors, color transitions |
| `ParallaxCalculatorTest` | Offset calculations, smoothing, center drift |
| `ParallaxSensorManagerTest` | Accelerometer axis mapping |
| `SimulationClockTest` | Day/night cycle, time calculations |
| `SimulationStateTest` | Immutable state properties |
| `SeasonalEventsTest` | Christmas/New Year detection |
| `WeatherRepositoryTest` | Data freshness, staleness detection |
| `TreeRenderingTest` | Placement, depth coloring, scaling |
| `HillsTerrainTest` | Layer ordering, parallax matching |
| `BuildConfigTest` | Version codes, surface lifecycle |
| `SettingsTest` | Preference reading |

## Continuous Integration

The project uses Woodpecker CI. Pipeline stages:

1. **Test** - Run all unit tests
2. **Build** - Create debug APK
3. **Archive** - Store APK as artifact

### CI Configuration

See `.woodpecker.yml` for pipeline configuration.

### Running CI Locally

Simulate CI environment with Docker:

```bash
docker run --rm -v "$PWD":/workspace weatherwall-builder \
    "./gradlew clean test assembleDebug --no-daemon"
```

## Gradle Tasks Reference

| Task | Description |
|------|-------------|
| `assembleDebug` | Build debug APK |
| `assembleRelease` | Build release APK |
| `installDebug` | Install debug APK via ADB |
| `test` | Run all unit tests |
| `clean` | Delete build outputs |
| `dependencies` | List all dependencies |
| `tasks` | List all available tasks |

## Troubleshooting

### Gradle Wrapper Missing

If `./gradlew` doesn't exist or is corrupted:

```bash
gradle wrapper --gradle-version 8.13
```

### SDK Not Found

Ensure `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
```

### Build Cache Issues

Clear all caches and rebuild:

```bash
./gradlew clean
rm -rf ~/.gradle/caches
./gradlew assembleDebug
```

### Docker Permission Issues

On Linux, the Docker build runs as UID 1000. If your user has a different UID:

```bash
docker run --rm -u $(id -u):$(id -g) \
    -v "$PWD":/workspace weatherwall-builder \
    "./gradlew assembleDebug --no-daemon"
```
