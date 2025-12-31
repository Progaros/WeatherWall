# Settings Guide

Complete reference for all WeatherWall configuration options.

## Scene Settings

### Random Seed
- **Key**: `pref_random_seed`
- **Range**: 1-999
- **Default**: 42
- **Description**: Changes the procedural generation of mountains, hills, trees, and clouds. Same seed always produces same layout.

### Mountains

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Mountain Count | `pref_mountain_count` | 0-10 | 7 | Number of mountain peaks in the background |
| Mountain Width | `pref_mountain_width` | 50-1000% | 175 | Width multiplier for mountain bases |
| Mountain Height | `pref_mountain_height` | 10-1000% | 115 | Height multiplier for mountain peaks |
| Mountain Roughness | `pref_mountain_roughness` | 0-200% | 80 | Jaggedness of mountain silhouette edges |
| Ridge Roughness | `pref_mountain_ridge_roughness` | 0-200% | 50 | Jaggedness of mountain center ridges |

### Hills

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Hill Layers | `pref_hill_count` | 0-20 | 5 | Number of rolling terrain layers (0=none, 5=normal, 20=many) |
| Hills Start | `pref_hill_lower_start` | 30-70% | 60 | Where far hills appear on screen (Y position) |
| Hills End | `pref_hill_upper_end` | 60-100% | 95 | Where near hills appear on screen (Y position) |
| Hill Roughness | `pref_hill_roughness` | 0-200 | 100 | Hill curve detail (0=smooth, 200=rough) |
| Hill Amplitude | `pref_hill_amplitude` | 0-200 | 100 | Hill vertical height |
| Hill Frequency | `pref_hill_frequency` | 25-200 | 100 | How many hills visible per layer |

### Trees

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Tree Density | `pref_tree_density` | 0-200 | 50 | Tree coverage (0=none, 50=normal, 200=dense forest) |
| Tree Spread | `pref_tree_spread` | 0-100 | 80 | Horizontal coverage (0=peaks only, 100=full hillside) |
| Tree Sparsity | `pref_tree_sparsity` | 0-100 | 30 | Gap probability (0=dense, 100=sparse with gaps) |
| Tree Size Randomness | `pref_tree_size_randomness` | 0-100 | 30 | Size variation (0=uniform, 100=varied) |
| Tree Height | `pref_tree_size` | 0-500 | 200 | Height multiplier (100=1x) |
| Tree Width | `pref_tree_width` | 0-300 | 100 | Width multiplier (100=1x) |
| Tree Color Variation | `pref_tree_color_variation` | 0-100 | 100 | Color variation between trees |
| Tree Segments | `pref_tree_segments` | 1-3 | 1 | Triangle segments per tree |

---

## Wind & Animation

### Wind Settings

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Wind Multiplier | `pref_wind_multiplier` | 0-300% | 100 | Scales wind effect intensity |
| Tree Sway Speed | `pref_tree_sway_speed` | 0-200 | 100 | Animation speed (0=frozen, 200=fast) |

---

## 3D Parallax Settings

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Enable Parallax | `pref_parallax_enabled` | on/off | on | Toggle sensor-based parallax |
| Sensitivity | `pref_parallax_strength` | 0-50 | 25 | Parallax movement sensitivity |
| Max Offset | `pref_parallax_max_offset` | 5-200px | 80 | Maximum parallax displacement |
| Return Speed | `pref_parallax_return_speed` | 1-100 | 50 | Center drift speed (1=instant, 100=slow) |
| Smoothing | `pref_parallax_smoothing` | 1-100 | 40 | Input smoothing (higher=smoother) |
| Depth Separation | `pref_parallax_depth` | 0-100 | 35 | Layer depth difference |

---

## Random Events

### Master Toggle
- **Key**: `pref_events_enabled`
- **Default**: on
- **Description**: Enable/disable all random events

### Event Frequency
- **Key**: `pref_event_frequency`
- **Range**: 0-100
- **Default**: 50
- **Description**: How often events spawn (0=rare, 100=frequent)

### Individual Events

| Event | Key | When | Description |
|-------|-----|------|-------------|
| Airplanes | `pref_event_airplane` | Day | Planes with contrails across sky |
| Bird Flocks | `pref_event_birds` | Day | Groups of birds flying in formation |
| Eagles | `pref_event_eagle` | Day | Soaring eagles with wing flapping |
| Butterflies | `pref_event_butterfly` | Day | Colorful butterflies fluttering |
| Hot Air Balloons | `pref_event_balloon` | Day, clear | Balloons drifting slowly |
| Rabbits | `pref_event_rabbit` | Any time | Hopping across terrain |
| Foxes | `pref_event_fox` | Any time | Walking across terrain |
| Shooting Stars | `pref_event_shootingstar` | Night, clear | Streaks across night sky |
| Fireflies | `pref_event_fireflies` | Night | Flickering lights |
| Owls | `pref_event_owl` | Night | Owl silhouettes flying |
| UFOs | `pref_event_alien` | Night (very rare) | Alien spacecraft |

---

## Cloud Settings

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Enable Clouds | `pref_clouds_enabled` | on/off | on | Show animated clouds |
| Cloud Density | `pref_cloud_density` | 0-50 | 10 | Number of cloud shapes |

---

## Sky & Celestial Bodies

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Show Sun | `pref_sun_enabled` | on/off | on | Display sun during daytime hours |
| Show Moon | `pref_moon_enabled` | on/off | on | Display moon during nighttime hours |
| Celestial Size | `pref_celestial_size` | 50-200% | 100 | Size of sun and moon |

### Sun Behavior
- Visible from 6am to 6pm (18:00)
- Follows arc across sky: rises on left, peaks at noon, sets on right
- Includes warm glow effect

### Moon Behavior
- Visible from 6pm to 6am
- Follows arc across sky during night hours
- Crescent shadow effect for realism

---

## Shadows & Lighting

| Setting | Key | Range | Default | Description |
|---------|-----|-------|---------|-------------|
| Enable Shadows | `pref_shadows_enabled` | on/off | on | Cast shadows from sun position |
| Shadow Strength | `pref_shadow_strength` | 0-100% | 50 | Shadow darkness intensity |
| Cloud Shadows | `pref_cloud_shadows` | on/off | off | Clouds cast ground shadows |

### Light Level System
- Light levels automatically adjust based on time of day
- Night: 15% light level (darker fog, reduced shadows)
- Dawn/Dusk transitions: Gradual fade between 15-100%
- Day: 100% light level

---

## Weather & Time

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Use Breezy Weather | `pref_use_breezy_weather` | on | Get real weather data |
| Auto Day/Night | `pref_daynight_enabled` | on | Change sky based on real time |
| Max FPS | `pref_max_fps` | 30 | Frame rate limit (10-60) |

---

## Debug Settings

| Setting | Key | Description |
|---------|-----|-------------|
| Debug Mode | `pref_debug_enabled` | Enable manual overrides |
| Manual Time | `pref_manual_time` | Set specific time (0-1440 minutes) |
| Wind Strength | `pref_wind_strength` | Manual wind level |
| Weather Type | `pref_weather_type` | Force specific weather |
