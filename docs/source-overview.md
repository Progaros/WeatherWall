# WeatherWall Source Code Overview

Generated: 2026-01-06

This document provides a comprehensive overview of all source files in the WeatherWall Android Live Wallpaper project.

---

## Core Application

### [WeatherWallApp.kt](../app/src/main/java/info/olli/weatherlwp/WeatherWallApp.kt)
Application class with global crash handler that captures stack traces to clipboard.

### [MainActivity.kt](../app/src/main/java/info/olli/weatherlwp/MainActivity.kt)
Settings activity with tabbed UI (Scene/Effects/Events/System) and live preview SurfaceView. Handles preference changes with throttling to avoid flicker during slider drags.

### [DebugTracer.kt](../app/src/main/java/info/olli/weatherlwp/DebugTracer.kt)
Lightweight debug tracing utility for performance profiling. Collects timing data and copies report to clipboard.

---

## Wallpaper Service

### [WeatherWallpaper.kt](../app/src/main/java/info/olli/weatherlwp/wallpaper/WeatherWallpaper.kt)
Main WallpaperService that creates the WeatherEngine. Handles visibility changes, idle mode battery optimization, and frame rendering via ParallaxScene.

**Issues Fixed:**
- ✅ Removed dead code: `WeatherLayer`, `SeasonalEvents` imports and unused instances (ParallaxScene owns these)
- ✅ Removed unused preferences: `weatherEnabled`, `seasonalEnabled`, `currentWeather`, `useBreezyWeather`, `debugEnabled`
- ✅ Refactored `checkSceneActivity()` to use `scene.isWeatherActive()` instead of local instance

---

## Parallax System

### [parallax/ParallaxSensorManager.kt](../app/src/main/java/info/olli/weatherlwp/parallax/ParallaxSensorManager.kt)
Manages sensor input (rotation vector or gyroscope) for parallax effect. Supports shake detection, delta-based motion tracking, and works at any device orientation.

### [parallax/ParallaxCalculator.kt](../app/src/main/java/info/olli/weatherlwp/parallax/ParallaxCalculator.kt)
Calculates parallax offset values using position-based tilt-following with smooth interpolation. Thread-safe via AtomicReference.

---

## Simulation System

### [simulation/SimulationController.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationController.kt)
Main controller coordinating clock, settings, and weather data to produce consistent SimulationState for all layers.

### [simulation/SimulationState.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationState.kt)
Immutable data class representing the current simulation state (time, weather, wind, lighting, etc.). Single source of truth for rendering.

### [simulation/SimulationSettings.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationSettings.kt)
Reads user preferences and debug overrides from SharedPreferences.

### [simulation/SimulationClock.kt](../app/src/main/java/info/olli/weatherlwp/simulation/SimulationClock.kt)
Manages time for simulation - real time or manual override. Handles sunrise/sunset from weather data.

---

## Weather System

### [weather/WeatherType.kt](../app/src/main/java/info/olli/weatherlwp/weather/WeatherType.kt)
Enum defining weather types: CLEAR, PARTLY_CLOUDY, CLOUDY, OVERCAST, RAIN, SNOW, HAIL, THUNDERSTORM, FOG.

### [weather/WeatherProvider.kt](../app/src/main/java/info/olli/weatherlwp/weather/WeatherProvider.kt)
Interface for weather data providers (get weather type, wind speed, sunrise/sunset).

### [weather/BreezyWeatherProvider.kt](../app/src/main/java/info/olli/weatherlwp/weather/BreezyWeatherProvider.kt)
Implementation that reads from WeatherRepository (populated by BreezyWeatherReceiver).

### [weather/WeatherRepository.kt](../app/src/main/java/info/olli/weatherlwp/weather/WeatherRepository.kt)
Singleton repository storing weather data in SharedPreferences. Provides extended weather data (precipitation, cloud cover, moon phase, etc.).

### [weather/BreezyWeatherReceiver.kt](../app/src/main/java/info/olli/weatherlwp/weather/BreezyWeatherReceiver.kt)
BroadcastReceiver that parses Gadgetbridge intent from Breezy Weather app and stores in WeatherRepository.

---

## Rendering Layers

### [rendering/ParallaxScene.kt](../app/src/main/java/info/olli/weatherlwp/rendering/ParallaxScene.kt)
Main scene renderer coordinating all layers. Manages layer draw order, spawn triggers, and settings propagation.

### [rendering/SceneLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/SceneLayer.kt)
Interface for scene layers (resize, update, draw, reloadSettings, destroy).

### [rendering/RenderConstants.kt](../app/src/main/java/info/olli/weatherlwp/rendering/RenderConstants.kt)
Centralized constants for rendering - colors, parallax factors, timing thresholds, size calculations. Makes tuning easier.

### [rendering/SkyLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/SkyLayer.kt)
Renders sky gradient, stars, sun, and moon with day/night transitions.

### [rendering/CloudLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/CloudLayer.kt)
Renders clouds with wind-based movement. Uses pre-cached bitmap groups for performance. Supports cached, tiled, and AGSL shader modes.

### [rendering/MountainLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/MountainLayer.kt)
Renders mountain silhouettes with procedural jagged shapes, snow caps, and atmospheric haze.

### [rendering/TerrainLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/TerrainLayer.kt)
Renders rolling hills and trees with wind animation. Supports seasonal variations (flowers, autumn colors, snow). Integrates ground events via callback.

### [rendering/WeatherLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/WeatherLayer.kt)
Renders weather effects: rain, snow, hail, lightning, fog. Uses particle pooling to avoid allocations.

**Issues Found:**
- ⚠️ **Deprecated code**: `WeatherType` enum marked @deprecated (use EffectType)
- ⚠️ **Deprecated code**: `setWeather()` and `render(canvas, enabled)` methods marked @deprecated

### [rendering/SeasonalEvents.kt](../app/src/main/java/info/olli/weatherlwp/rendering/SeasonalEvents.kt)
Holiday effects: Christmas star with trail, New Year fireworks, St. Patrick's rainbow. Shake-to-fireworks feature.

### [rendering/RandomEventLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/RandomEventLayer.kt)
Manages random events (birds, planes, animals). Time-bucket based spawning for deterministic sync between preview and wallpaper.

### [rendering/VignetteLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/VignetteLayer.kt)
Draws dark gradient overlays on screen edges for icon visibility.

### [rendering/DebugOverlayLayer.kt](../app/src/main/java/info/olli/weatherlwp/rendering/DebugOverlayLayer.kt)
Debug overlay showing FPS, frame time, weather info, etc. Configurable via settings.

---

## Random Events (rendering/events/)

Each event implements the `RandomEvent` interface with `layer`, `depthFactor`, `spawnTimeMs`, `durationMs`, and draw methods.

| File | Description | Layer |
|------|-------------|-------|
| [RandomEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/RandomEvent.kt) | Base interface for all events | - |
| [AirplaneEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/AirplaneEvent.kt) | Airplane with contrail | MID_SKY |
| [BirdFlockEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/BirdFlockEvent.kt) | V-formation bird flock | MID_SKY |
| [EagleEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/EagleEvent.kt) | Soaring eagle silhouette | MID_SKY |
| [OwlEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/OwlEvent.kt) | Night-only owl silhouette | MID_SKY |
| [HotAirBalloonEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/HotAirBalloonEvent.kt) | Drifting hot air balloon | FAR_SKY |
| [ShootingStarEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/ShootingStarEvent.kt) | Night shooting star | FAR_SKY |
| [AlienEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/AlienEvent.kt) | Rare UFO sighting | FAR_SKY |
| [ButterflyEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/ButterflyEvent.kt) | Spring/Summer butterfly | GROUND |
| [DragonflyEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/DragonflyEvent.kt) | Summer dragonfly | GROUND |
| [RabbitEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/RabbitEvent.kt) | Hopping rabbit | GROUND |
| [FoxEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/FoxEvent.kt) | Walking fox | GROUND |
| [SquirrelEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/SquirrelEvent.kt) | Autumn squirrel | GROUND |
| [DeerEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/DeerEvent.kt) | Deer (autumn/winter) | GROUND |
| [FireflyEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/FireflyEvent.kt) | Summer night fireflies | GROUND |
| [BatEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/BatEvent.kt) | Night bat colony | GROUND |
| [SnowflakeEvent.kt](../app/src/main/java/info/olli/weatherlwp/rendering/events/SnowflakeEvent.kt) | Winter decorative snowflake | GROUND |

---

## Settings Fragments

### [settings/RootSettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/settings/RootSettingsFragment.kt)
Root navigation fragment with links to sub-screens.

### [settings/SceneSettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/settings/SceneSettingsFragment.kt)
Mountains, hills, trees settings.

### [settings/EffectsSettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/settings/EffectsSettingsFragment.kt)
Wind, parallax, clouds settings. Includes parallax strength migration.

### [settings/EventsSettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/settings/EventsSettingsFragment.kt)
Random events and seasonal event settings. Sets up spawn button listeners.

### [settings/SystemSettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/settings/SystemSettingsFragment.kt)
Weather, performance, simulation, and about settings. Includes idle timeout migration.

---

## Custom Preferences

### [settings/SpawnSwitchPreference.kt](../app/src/main/java/info/olli/weatherlwp/settings/SpawnSwitchPreference.kt)
Custom preference with animated preview and inline spawn button.

### [settings/EventPreviewView.kt](../app/src/main/java/info/olli/weatherlwp/settings/EventPreviewView.kt)
Custom view rendering animated event previews (airplane, birds, animals, etc.).

### [settings/DebugOverlayPreference.kt](../app/src/main/java/info/olli/weatherlwp/settings/DebugOverlayPreference.kt)
Custom preference for debug overlay with multi-select item dialog.

### [prefs/SettingsFragment.kt](../app/src/main/java/info/olli/weatherlwp/prefs/SettingsFragment.kt)
Legacy settings fragment (older single-page layout). Contains Breezy Weather check and easter egg.

### [prefs/TimeSeekBarPreference.kt](../app/src/main/java/info/olli/weatherlwp/prefs/TimeSeekBarPreference.kt)
SeekBar that displays time in HH:MM format (for manual time setting).

---

## Utilities

### [util/ColorMath.kt](../app/src/main/java/info/olli/weatherlwp/util/ColorMath.kt)
Color helpers that avoid android.graphics.Color for JVM unit testing. Provides `shadeRgb()` for night darkening.

### [util/SeasonHelper.kt](../app/src/main/java/info/olli/weatherlwp/util/SeasonHelper.kt)
Centralized season detection (Spring/Summer/Autumn/Winter) based on calendar date or debug override.

---

## XML Resources

### Preference Screens

| File | Description |
|------|-------------|
| [xml/pref_root.xml](../app/src/main/res/xml/pref_root.xml) | Root navigation to Scene/Effects/Events/System |
| [xml/pref_scene.xml](../app/src/main/res/xml/pref_scene.xml) | Mountains, hills, trees settings |
| [xml/pref_effects.xml](../app/src/main/res/xml/pref_effects.xml) | Sky, wind, parallax, clouds, seasonal terrain, vignette settings |
| [xml/pref_events.xml](../app/src/main/res/xml/pref_events.xml) | Random events and seasonal events with spawn buttons |
| [xml/pref_system.xml](../app/src/main/res/xml/pref_system.xml) | Time/weather, performance, simulation, about |
| [xml/preferences.xml](../app/src/main/res/xml/preferences.xml) | Legacy single-page preferences (690 lines - older layout) |
| [xml/wallpaper.xml](../app/src/main/res/xml/wallpaper.xml) | Wallpaper service metadata |

### Layouts

| File | Description |
|------|-------------|
| [layout/pref_widget_spawn_switch.xml](../app/src/main/res/layout/pref_widget_spawn_switch.xml) | Custom widget with preview + spawn button + switch |
| [layout/pref_widget_debug_overlay.xml](../app/src/main/res/layout/pref_widget_debug_overlay.xml) | Debug overlay preference switch widget |

### Values

| File | Description |
|------|-------------|
| [values/arrays.xml](../app/src/main/res/values/arrays.xml) | List preference entries/values for FPS, weather, seasons, sensor modes, etc. |

---

## Code Quality Issues Found

### Dead Code (to remove)
1. **WeatherWallpaper.kt**: `private val weather = WeatherLayer()` - created but never used (ParallaxScene has its own)
2. **WeatherWallpaper.kt**: `private lateinit var seasonal: SeasonalEvents` - created but only used for loadSettings() which is redundant
3. **WeatherWallpaper.kt**: `import info.olli.weatherlwp.rendering.SeasonalEvents` - unused after cleanup
4. **WeatherWallpaper.kt**: `import info.olli.weatherlwp.rendering.WeatherLayer` - unused after cleanup

### Deprecated Code (consider cleanup)
1. **WeatherLayer.kt**: `WeatherType` enum marked @deprecated - use `EffectType` instead
2. **WeatherLayer.kt**: `setWeather()` method marked @deprecated
3. **WeatherLayer.kt**: `render(canvas, enabled)` method marked @deprecated

### DRY Violations
- None significant found - code is well-structured with centralized constants in RenderConstants.kt

### Potential Improvements
- Consider consolidating preferences.xml (legacy) with the newer tabbed pref_*.xml files
- The deprecated WeatherLayer.WeatherType could be removed in favor of EffectType

---

## Architecture Summary

```
MainActivity (Settings UI)
    └── ParallaxScene (Preview)

WeatherWallpaper (Service)
    └── WeatherEngine
        └── ParallaxScene (Live Wallpaper)
            ├── SimulationController → SimulationState
            ├── ParallaxCalculator ← ParallaxSensorManager
            └── Layers:
                ├── SkyLayer (sky, stars, sun, moon)
                ├── CloudLayer (clouds)
                ├── MountainLayer (mountains, snow caps)
                ├── RandomEventLayer (birds, animals, etc.)
                ├── TerrainLayer (hills, trees)
                ├── WeatherLayer (rain, snow, lightning, fog)
                ├── SeasonalEvents (Christmas star, fireworks)
                ├── VignetteLayer (edge darkening)
                └── DebugOverlayLayer (FPS, debug info)
```

Weather data flows: BreezyWeatherReceiver → WeatherRepository → SimulationController → SimulationState → Layers
