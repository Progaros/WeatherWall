# Architecture

## Package Structure

```
com.example.weatherlwp/
├── MainActivity.kt           # Settings activity with live preview
├── wallpaper/
│   └── WeatherWallpaper.kt   # WallpaperService + Engine + Render Loop
├── rendering/
│   ├── ParallaxScene.kt      # Main scene renderer (sky, mountains, hills, trees, clouds)
│   ├── WeatherLayer.kt       # Weather particle effects (rain, snow, lightning)
│   ├── SeasonalEvents.kt     # Christmas star, New Year fireworks
│   ├── DayNightCycle.kt      # Time-based sky gradients (utility)
│   └── CloudLayer.kt         # Standalone cloud renderer (utility)
├── parallax/
│   └── ParallaxSensorManager.kt  # Accelerometer → parallax offsets
├── prefs/
│   └── SettingsFragment.kt   # PreferenceFragmentCompat UI
└── weather/
    ├── WeatherType.kt        # Weather condition enum
    ├── WeatherRepository.kt  # Stores weather data in SharedPreferences
    └── BreezyWeatherReceiver.kt  # Receives Gadgetbridge broadcasts
```

## Layer Order (Painter's Algorithm)

Layers are drawn back-to-front:

1. **Sky** - Gradient background, moves opposite to tilt (depth illusion)
2. **Stars** - Visible at night/dusk, small white dots
3. **Clouds** - Fluffy shapes with blur, drift slowly, size-based transparency
4. **Mountains** - Low-poly peaks with light/shadow sides, configurable height/width
5. **Hills** - Rolling terrain, configurable vertical range (lower start / upper end)
6. **Trees** - Pine silhouettes on hills, wind sway from Breezy Weather
7. **Weather Effects** - Rain, snow, lightning particles
8. **Seasonal Events** - Christmas star with trail (Bethlehem star), New Year fireworks

## Parallax Depth System

Each layer has a `depthFactor` determining movement intensity:

| Layer | Depth Factor | Behavior |
|-------|-------------|----------|
| Sky | -0.15 | Moves opposite (far illusion) |
| Stars | -0.10 | Subtle opposite movement |
| Clouds | -0.08 | Behind mountains |
| Mountains | 0.05-0.15 | Nearly static |
| Hills | 0.30-0.50 | Moderate movement |
| Trees | 0.30-0.50 | Matches hill layer |

## Rendering Pipeline

```
Sensor Event → ParallaxSensorManager (low-pass filter α=0.85)
                      ↓
              onOffsetChanged(x, y)
                      ↓
              ParallaxScene.ox/oy updated
                      ↓
              render(canvas) @ 20-30 FPS
                      ↓
              Each layer translated by (ox * depthFactor)
                      ↓
              Canvas posted to SurfaceHolder
```

## Breezy Weather Integration

```
Breezy Weather App
        ↓ (Gadgetbridge broadcast: ACTION_GENERIC_WEATHER)
BreezyWeatherReceiver.onReceive()
        ↓ (JSON parsing)
WeatherRepository.updateWeather()
        ↓ (SharedPreferences)
ParallaxScene/WeatherWallpaper reads on loadSettings()
```

**Data stored:**
- `weather_current_type` - CLEAR, RAIN, SNOW, CLOUDY, FOG, THUNDERSTORM
- `weather_wind_speed` - m/s for tree sway intensity
- `weather_sunrise/sunset` - Unix timestamps for day/night cycle
- `weather_last_update` - Staleness check

## Settings System

Settings stored in SharedPreferences, loaded reactively:

1. User changes setting in SettingsFragment
2. `OnSharedPreferenceChangeListener` triggers
3. `scene.loadSettings()` called
4. Affected generators run (`generateMountains()`, `generateHills()`, etc.)
5. Next `render()` uses new values

**Live Update Tracking:**
- `lastMountainWidthScale` - Regenerate mountains on width change
- `lastMountainHeightScale` - Regenerate mountains on height change
- `lastHillLowerStart/lastHillUpperEnd` - Regenerate hills + trees on range change

## Settings Categories

- **Scene**: Mountains (count, width, height), Hills (count, range), Trees
- **3D Parallax**: Enable/disable, sensitivity
- **Visual Effects**: Clouds, stars, weather overlays
- **Weather & Connections**: Breezy Weather integration toggle
- **Seasonal**: Christmas events, New Year fireworks
- **Simulation & Debug**: Override time, weather type, FPS display
