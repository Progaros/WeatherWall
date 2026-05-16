# WeatherWall

[![Build Status](https://ci.olli.info/api/badges/4/status.svg)](https://ci.olli.info/repos/4)

📥 **[Download Latest APK](https://git.olli.info/Oliver/WeatherWall/releases/latest)**

Android live wallpaper. Low-poly vector scene with layered terrain, weather particles, seasonal decorations, and gyroscope-driven parallax. No analytics, no internet permission.

<p align="center">
  <img src="app/src/main/res/logo.png" width="128" alt="WeatherWall Logo">
</p>

## Screenshots

<p align="center">
  <img src="docs/screenshots/Screenshot_1.png" width="180" alt="Dawn clouds">
  <img src="docs/screenshots/Screenshot_2.png" width="180" alt="Clear day">
  <img src="docs/screenshots/Screenshot_3.png" width="180" alt="Snowy day">
  <img src="docs/screenshots/Screenshot_4.png" width="180" alt="Rainy dusk">
  <img src="docs/screenshots/Screenshot_5.png" width="180" alt="Night sky">
</p>

## Architecture

### Rendering Pipeline

`ParallaxScene` is the top-level orchestrator. Each frame:

1. `SimulationController` computes a `SimulationState` (time, season, wind, light level)
2. `ParallaxCalculator` reads gyroscope delta and produces per-depth parallax offsets
3. Layers draw back-to-front in this order:

```
SkyLayer          → gradient sky, sun/moon, stars
MountainLayer     → background mountain silhouettes
CloudLayer        → wind-driven animated clouds (cached bitmap)
TerrainLayer      → hills, trees, bushes, seasonal decorations
  ├── RiverLayer/LakeLayer (interleaved between terrain depth layers)
  └── RandomEventLayer (animals/wildlife interleaved by Y-sort)
WeatherLayer      → rain/snow/lightning/hail particles
SeasonalEvents    → Christmas/New Year/St. Patrick's overlays
VignetteLayer     → optional post-process
DebugOverlayLayer → FPS/timing when enabled
```

Each layer implements `SceneLayer` (resize, update, draw, reloadSettings).

### TerrainLayer

The heaviest layer. Manages:
- **Hill generation**: N hills (default 5), each with fBm noise for contour, sorted by depth factor
- **Tree/bush rendering**: Delegated to `TreeRenderer` and `BushRenderer`
- **Seasonal decorations**: Flowers, mushrooms, fallen leaves, snow caps, frost — all pre-generated at terrain-generation time, drawn per-frame with season-weight alpha blending

### Procedural Generation

All placement uses `DeterministicLayout` for seed-stable positioning:
- `hashToFloat(seed, index, salt)` → zero-allocation float in [0,1)
- `random(seed, section, index)` → seeded `kotlin.random.Random`
- Cell-based layout: world is divided into cells; each cell hashes to decide presence/position

#### Trees

`TreeRenderer` places trees along hill contours using cell-based layout.
Two types: `TreeType.NEEDLE` (conifer, stays green) and `TreeType.LEAF` (deciduous, autumn colors, winter bare branches). Ratio configurable via `pref_leaf_tree_ratio`.

Trees are grouped by hill depth factor (`treesByDepth` map). This grouping drives:
- Draw-order sorting (Y-based within each depth layer)
- Foliage accumulation (leaf query by hill index)
- Parallax sharing (see below)

#### Vegetation Clustering

Flowers and mushrooms support optional cluster placement. The algorithm:

1. Cell grid determines cluster center candidates (probability = clusterDensity × baseDensity)
2. Each cluster spawns N items (configurable min/max)
3. Items scatter using **uniform-disk sampling** (`sqrt(u) * r`) to avoid center bias
4. **Smoothstep falloff** reduces item size near cluster edge (configurable via `clusterFalloff`)
5. **Noise displacement** breaks circular symmetry (configurable via `clusterNoise`, uses `hashToFloat` sub-seeds)

When clustering is disabled, items fall back to uniform cell-based placement.

#### Foliage Accumulation

Fallen leaves have two placement modes that combine:

1. **Tree-biased**: For each tree on a hill, generate leaves near its base
   - Leaf count scales with `foliageUnderTreeBias`, tree canopy size (`tree.scale`), and density
   - Deciduous trees produce `(1 + foliageMatchingTreeBias)×` more leaves than conifers
   - Uses uniform-disk scatter with distance-based size falloff (leaves nearer trunk are larger)
   - Asymmetric wind scatter: directional offset + crosswind jitter

2. **Uniform background**: Cell-based ambient ground cover, density reduced when tree-biased mode is active

### Parallax Strategy

`ParallaxCalculator` produces X/Y offsets per depth factor from gyroscope input.

**Per-hill computation**: Each hill layer computes `(px, py)` once via `getOffsetXForDepth(depthFactor)`. All objects on that hill — trees, bushes, flowers, mushrooms, leaves, frost — render inside `canvas.translate(px, py)`.

**Why small objects skip individual parallax**: Flowers, mushrooms, fallen leaves, and ground clutter are tiny (2-7px on screen). At the parallax strength range used (hills span depth 0.50–0.80), the maximum per-object offset difference within a single hill layer is sub-pixel. Computing individual parallax per flower would cost `getOffsetForDepth()` calls with zero visible benefit. Instead, these objects inherit their parent hill's parallax offset, which is already computed for the hill fill.

Trees and bushes also share the hill parallax offset (not individual), but this matters more for them since they're larger. The sub-pixel error is still invisible at the depth spread within a single hill.

### Performance Design

- **Zero per-frame allocation**: Pre-allocated `Paint`, `Path`, `DrawEntry` buffer, particle pools
- **Generation-time placement**: All vegetation positions computed in `generateSeasonalDecorations()` and stored in immutable lists. Draw loop only iterates and renders.
- **Gradient caching**: Hill gradients are keyed by (hillIndex, snowFlag) and only rebuilt when bucketed ambient color changes
- **Viewport culling**: Decorations outside `[-0.15w, 1.15w]` screen bounds are skipped before any draw call
- **Change detection**: `reloadSettings()` tracks previous values of all generation-affecting settings; only regenerates when something actually changed
- **Cell-based determinism**: Viewport resizes don't regenerate unless the visible world range escapes the pre-loaded margin (1.25× buffer)

## Configuration

All settings are in the wallpaper preferences UI. Defaults are in `PreferenceDefaults.kt`.

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| Parallax Enabled | `pref_parallax_enabled` | `true` | bool |
| Parallax Strength | `pref_parallax_strength` | `16` | 1–100 |
| Parallax Depth | `pref_parallax_depth` | `70` | 0–100 |
| Cloud Speed | `pref_cloud_speed` | `45` | 0–100 |
| Cloud Density | `pref_cloud_density` | `100` | 50–300 |
| Tree Density | `pref_tree_density` | `30` | 0–200 |
| Leaf Tree Ratio | `pref_leaf_tree_ratio` | `50` | 0–100 |
| Bush Density | `pref_bush_density` | `30` | 0–100 |
| Hill Amplitude | `pref_hill_amplitude` | `354` | 0–1000 |
| Hill Frequency | `pref_hill_frequency` | `170` | 25–200 |
| **Flower Cluster** | `pref_flower_cluster_enabled` | `false` | bool |
| Cluster Density | `pref_flower_cluster_density` | `40` | 0–100 |
| Cluster Radius | `pref_flower_cluster_radius` | `30` | 0–100 |
| Cluster Min/Max | `pref_flower_cluster_min/max_count` | `3/8` | 1–20 |
| Cluster Falloff | `pref_flower_cluster_falloff` | `50` | 0–100 |
| Cluster Noise | `pref_flower_cluster_noise` | `30` | 0–100 |
| **Mushroom Cluster** | `pref_mushroom_cluster_enabled` | `false` | bool |
| Cluster Density | `pref_mushroom_cluster_density` | `30` | 0–100 |
| Cluster Radius | `pref_mushroom_cluster_radius` | `25` | 0–100 |
| Cluster Min/Max | `pref_mushroom_cluster_min/max_count` | `2/5` | 1–15 |
| Cluster Falloff | `pref_mushroom_cluster_falloff` | `50` | 0–100 |
| Cluster Noise | `pref_mushroom_cluster_noise` | `30` | 0–100 |
| **Foliage Tree Bias** | `pref_foliage_under_tree_bias` | `60` | 0–100 |
| Matching Tree Bias | `pref_foliage_matching_tree_bias` | `80` | 0–100 |
| Wind Scatter | `pref_foliage_wind_scatter_strength` | `40` | 0–100 |
| Wind Direction | `pref_foliage_wind_direction` | `0` | 0–360 |
| Random Scatter | `pref_foliage_random_scatter` | `30` | 0–100 |

## Build

```bash
git clone https://git.olli.info/Oliver/WeatherWall.git
cd WeatherWall
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Docker (CI)

```bash
docker build -t weatherwall-builder -f Dockerfile.build .
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew test --no-daemon
docker run --rm -v "$PWD":/workspace -w /workspace weatherwall-builder ./gradlew :app:pitest --no-daemon
```

## Testing

```bash
./gradlew test              # Unit tests (Robolectric)
./gradlew lintDebug         # Lint checks
./gradlew :app:pitest       # PIT mutation testing
```

## Requirements

- Android 8.0+ (API 26)
- Gradle 8.13+, JDK 17

## License

MIT — see [LICENSE](LICENSE).

## Links

- [Repository](https://git.olli.info/Oliver/WeatherWall)
- [CI/CD](https://ci.olli.info/repos/4)
- [Issues](https://git.olli.info/Oliver/WeatherWall/issues)

