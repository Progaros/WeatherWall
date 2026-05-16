# WeatherWall

[![Build Status](https://ci.olli.info/api/badges/4/status.svg)](https://ci.olli.info/repos/4)

📥 **[Download Latest APK](https://git.olli.info/Oliver/WeatherWall/releases/latest)**

**Open-source, privacy-respecting, battery-friendly Android Live Wallpaper** with low-poly vector layers, weather effects, seasonal events, and optional 3D parallax.

<p align="center">
  <img src="app/src/main/res/logo.png" width="128" alt="WeatherWall Logo">
</p>

## Features

### Visual Effects
- **Low-poly parallax scene** with layered mountains, hills, and trees (conifer and deciduous)
- **Dynamic day/night cycle** with smooth sky gradients (dawn, day, dusk, night)
- **Procedural moon phases** with accurate waxing/waning cycle
- **Weather effects**: Rain, Snow, Lightning, Hail with particle systems
- **Fog overlay** for atmospheric depth
- **Cloud layers** with wind-driven movement and soft blur
- **Vignette overlay** (optional)
- **Seasonal events**: Christmas stars, New Year fireworks, Chinese New Year, St. Patrick's rainbow
- **River and Lake** water features with flow animation (optional)

### Random Events
Animated wildlife and sky events appear randomly:
- **Day**: Airplanes with contrails, bird flocks, eagles, butterflies, dragonflies, hot air balloons, ducks
- **Night**: Shooting stars, fireflies, owls, bats, snowflakes, UFOs (very rare!)
- **Any time**: Rabbits, foxes, deer, squirrels, frogs, fish jumps

### Terrain System
- Layered hills with Perlin noise-based procedural generation
- Two tree types: **needle (conifer)** and **leaf (deciduous)** with configurable ratio
- Bushes with berry decorations
- Seasonal decorations: flowers (with cluster grouping), mushrooms (with cluster grouping), fallen leaves, pinecones, frost overlay
- **Foliage accumulation**: leaves pile up preferentially under deciduous trees, with configurable wind scatter
- Realistic wind-based tree and bush sway
- Configurable hill count, amplitude, frequency, roughness

### Battery-First Design
- Adaptive FPS (idle mode reduces to configurable FPS)
- Sensors only active when wallpaper is visible
- Zero per-frame allocations (pre-allocated paints, paths, particle pools)
- **Parallax optimization**: small decorations (flowers, mushrooms, leaves) share hill-layer parallax instead of individual per-object computation
- Efficient low-poly rendering without complex shaders

### Privacy-First
- No trackers, analytics, or ads
- No INTERNET permission required
- Optional Breezy Weather integration for real weather data
- All processing done locally on device

### Breezy Weather Integration
WeatherWall can receive real-time weather data from [Breezy Weather](https://github.com/breezy-weather/breezy-weather):

**Supported data:**
- Sunrise/sunset times, weather conditions (rain, snow, thunderstorm, fog, clouds)
- Wind speed & direction (drives cloud movement and tree sway)
- Cloud cover, moon phase & times, temperature, humidity, pressure

**Setup:**
1. Install Breezy Weather from F-Droid or GitHub
2. Open Breezy Weather → Settings → External Modules
3. Enable "Send Gadgetbridge data" → "Broadcast to all"

Without Breezy Weather, sensible defaults are used (sunrise 6:00, sunset 20:00).

## Screenshots

<p align="center">
  <img src="docs/screenshots/Screenshot_1.png" width="180" alt="Dawn clouds">
  <img src="docs/screenshots/Screenshot_2.png" width="180" alt="Clear day">
  <img src="docs/screenshots/Screenshot_3.png" width="180" alt="Snowy day">
  <img src="docs/screenshots/Screenshot_4.png" width="180" alt="Rainy dusk">
  <img src="docs/screenshots/Screenshot_5.png" width="180" alt="Night sky">
</p>

## Configuration

All settings are accessible through the wallpaper preferences UI. Key configuration options and their defaults:

| Setting | Key | Default | Range | Description |
|---------|-----|---------|-------|-------------|
| Parallax Enabled | `pref_parallax_enabled` | `true` | bool | Enable 3D parallax via gyroscope/rotation |
| Parallax Strength | `pref_parallax_strength` | `16` | 1–100 | Log-scale input sensitivity |
| Parallax Depth | `pref_parallax_depth` | `70` | 0–100 | Layer separation intensity |
| Cloud Speed | `pref_cloud_speed` | `45` | 0–100 | Cloud movement speed |
| Cloud Density | `pref_cloud_density` | `100` | 50–300 | Cloud count multiplier |
| Tree Density | `pref_tree_density` | `30` | 0–200 | Tree coverage (50=normal, 200=dense) |
| Leaf Tree Ratio | `pref_leaf_tree_ratio` | `50` | 0–100 | % deciduous trees |
| Bush Density | `pref_bush_density` | `30` | 0–100 | Bush coverage |
| Hill Amplitude | `pref_hill_amplitude` | `354` | 0–1000 | Hill vertical height |
| Hill Frequency | `pref_hill_frequency` | `170` | 25–200 | Hill density pattern |
| River Enabled | `pref_river_enabled` | `false` | bool | Water river overlay |
| Lake Enabled | `pref_lake_enabled` | `false` | bool | Lake overlay |
| **Flower Cluster** | `pref_flower_cluster_enabled` | `false` | bool | Group flowers in clusters |
| Flower Cluster Density | `pref_flower_cluster_density` | `40` | 0–100 | Cluster center probability |
| Flower Cluster Radius | `pref_flower_cluster_radius` | `30` | 0–100 | Scatter radius |
| Flower Cluster Min/Max | `pref_flower_cluster_min/max_count` | `3/8` | 1–20 | Flowers per cluster |
| **Mushroom Cluster** | `pref_mushroom_cluster_enabled` | `false` | bool | Group mushrooms in clusters |
| Mushroom Cluster Density | `pref_mushroom_cluster_density` | `30` | 0–100 | Cluster center probability |
| Mushroom Cluster Radius | `pref_mushroom_cluster_radius` | `25` | 0–100 | Scatter radius |
| Mushroom Cluster Min/Max | `pref_mushroom_cluster_min/max_count` | `2/5` | 1–15 | Mushrooms per cluster |
| **Foliage Under Tree Bias** | `pref_foliage_under_tree_bias` | `60` | 0–100 | % boost for leaves near trees |
| Foliage Matching Tree Bias | `pref_foliage_matching_tree_bias` | `80` | 0–100 | Extra bias for deciduous trees |
| Foliage Wind Scatter | `pref_foliage_wind_scatter_strength` | `40` | 0–100 | Wind pushes leaves from trees |
| Foliage Wind Direction | `pref_foliage_wind_direction` | `0` | 0–360 | Scatter direction (degrees) |
| Foliage Random Scatter | `pref_foliage_random_scatter` | `30` | 0–100 | Random scatter radius |

## Build

```bash
# Clone and build
git clone https://git.olli.info/Oliver/WeatherWall.git
cd WeatherWall
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Docker Build (Recommended for CI)

```bash
# Build cached builder image (once)
docker build -t weatherwall-builder -f Dockerfile.build .

# Run tests
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew test --no-daemon

# Build debug APK
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew assembleDebug --no-daemon
```

## Testing

```bash
./gradlew test              # Unit tests
./gradlew lintDebug         # Lint checks
./gradlew pitest            # PIT mutation testing
```

## Architecture

```
app/src/main/java/info/olli/weatherlwp/
├── PreferenceDefaults.kt           # Single source of truth for all defaults
├── parallax/
│   ├── ParallaxCalculator.kt       # Delta-based parallax physics
│   └── ParallaxSensorManager.kt    # Gyroscope/rotation vector input
├── rendering/
│   ├── ParallaxScene.kt            # Main scene orchestrator
│   ├── SceneLayer.kt               # Layer interface
│   ├── SkyLayer.kt                 # Sky gradient, sun/moon, stars
│   ├── CloudLayer.kt               # Animated clouds with caching
│   ├── MountainLayer.kt            # Background mountain silhouettes
│   ├── TerrainLayer.kt             # Hills, trees, bushes, seasonal decorations
│   ├── TreeRenderer.kt             # Needle/leaf tree generation and rendering
│   ├── BushRenderer.kt             # Bush generation with berries
│   ├── VegetationUtil.kt           # Shared noise, color, season utilities
│   ├── RiverLayer.kt / LakeLayer.kt  # Water features
│   ├── WeatherLayer.kt             # Rain/snow/lightning particles
│   ├── SeasonalEvents.kt           # Christmas/New Year/St. Patrick's
│   ├── RandomEventLayer.kt         # Wildlife and sky event management
│   ├── RenderConstants.kt          # All rendering constants
│   ├── DeterministicLayout.kt      # Stable seed derivation
│   ├── WorldViewportTransform.kt   # World-to-screen coordinate mapping
│   └── events/                     # 20 individual event implementations
├── simulation/
│   ├── SimulationController.kt     # Main simulation state manager
│   ├── SimulationClock.kt          # Time calculations
│   ├── SimulationSettings.kt       # Immutable settings snapshot
│   ├── SimulationState.kt          # Immutable per-frame state
│   ├── WindSimulator.kt            # Wind gust simulation
│   └── WeatherHistory.kt           # Snow/rain accumulation tracking
├── settings/                       # Preferences UI fragments
├── wallpaper/
│   └── WeatherWallpaper.kt         # WallpaperService implementation
└── weather/
    ├── BreezyWeatherReceiver.kt    # Broadcast receiver
    ├── WeatherRepository.kt        # Weather data storage
    └── WeatherType.kt              # Weather type enumeration
```

## Performance Notes

- **Hill-layer parallax sharing**: Trees, bushes, and small decorations on the same hill share a single parallax offset computation instead of per-object calls
- **Cell-based generation**: All vegetation uses deterministic cell indices for stable placement across viewport changes
- **Zero-allocation rendering**: Pre-allocated Paint, Path, and particle pool objects
- **Gradient caching**: Hill gradients are cached and only regenerated on bucketed ambient color changes
- **Viewport culling**: Off-screen objects are skipped before any draw calls

## Requirements

- Android 8.0+ (API 26)
- Android Studio Hedgehog or newer
- Gradle 8.13+

## License

MIT License — see [LICENSE](LICENSE).

## Links

- **Repository**: [git.olli.info/Oliver/WeatherWall](https://git.olli.info/Oliver/WeatherWall)
- **CI/CD**: [ci.olli.info](https://ci.olli.info/repos/4)
- **Issues**: [Report a bug](https://git.olli.info/Oliver/WeatherWall/issues)

