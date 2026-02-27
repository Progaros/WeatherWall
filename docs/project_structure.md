# WeatherWall Project Structure

This document provides a summary of the project's source code to assist with maintenance and refactoring.

## Core Application
- **WeatherWallApp.kt**: Application entry point, initializes DI/Repositories.
- **MainActivity.kt**: Configuration activity, host for settings fragments.

## Wallpaper Service
- **WeatherWallpaper.kt**: The active wallpaper service. Handles surface creation, touch events, and render loop.

## Simulation System
- **SimulationController.kt**: Main loop orchestrator. Updates clock, weather, and scene state.
- **SimulationState.kt**: Data class holding current world state (time, weather, season, etc.).
- **SimulationClock.kt**: Manages time progression (real-time or fast-forward).
- **SimulationSettings.kt**: Helper for loading general simulation preferences.

## Parallax System
- **ParallaxCalculator.kt**: Core logic for calculating 3D perspective shifts from tilt. When smoothing=0, uses direct mode for instant response. Supports lerp-based smoothing when slider is increased. Handles return-to-center when device is leveled. Uses tan() perspective curve for natural depth perception. Features:
  - **Edge Easing** (0-100): Power curve that slows movement near screen edges. At 0=linear, higher values make center more responsive while edges barely move. Uses `(1 - normalizedDistance)^(1 + edgeEasing*3)`.
  - **Return Unlocked**: When enabled, return-to-center is not slowed by edge easing (only outward movement is affected).
  - **Max Offset**: Configurable 0-40% of screen height (default 20%).
- **ParallaxSensorManager.kt**: Handles accelerometer/gyroscope/rotation vector sensors. Gyroscope mode: raw values to 3-sample averagers, no deadzone/decay, display rotation applied after accumulation. Formula: `angle += avgRate * deltaTimeNanos * 1e-8 * 6 * DEG_TO_RAD`, clamped to ±18°. Also handles shake detection with configurable unlock delay (0-2500ms) to prevent accidental triggers after phone unlock. Shake gesture uses stable sign detection + debouncing: requires 2 consecutive samples of new direction before counting a change, minimum 90ms between accepted changes, and defaults to 6 direction changes (3 full wiggles) to trigger. Additionally, a sensor warmup period (first 30 sensor events) is ignored after start() to prevent stale queued events from triggering shakes immediately after screen unlock. Both accelerometer and gyroscope paths decrement the warmup counter independently.
- **ParallaxScene.kt**: Composition root for the visual scene. Manages all layers and passes render calls.

## Rendering System (Canvas)
- **SceneLayer.kt**: Interface for all renderable layers.
- **SkyLayer.kt**: Renders background gradient, sun, moon, stars.
- **CloudLayer.kt**: Renders procedural cloud system.
- **MountainLayer.kt**: Renders background mountains with parallax.
- **TerrainLayer.kt**: Renders foreground terrain (hills, trees).
- **WeatherLayer.kt**: Renders particle effects (rain, snow, fog).
- **VignetteLayer.kt**: Renders edge darkening for atmosphere.
- **DebugOverlayLayer.kt**: Renders FPS, stats, and debug info.
- **RenderConstants.kt**: Shared rendering constants (colors, sizes).

## Event System (Animated Elements)
- **RandomEventLayer.kt**: Manager for spawning and updating random events.
- **RandomEvent.kt**: Base class for all events.
- **SeasonalEvents.kt**: Helper to determine which events can spawn in current season/weather. Also handles fireworks (shake-triggered and seasonal). Settings are reloaded centrally once per frame via `updateSettingsIfNeeded()`. Rockets use time-based explosion (75-119 frames after launch, ~1.25-2.0s) to ensure explosions occur above mountain level. Chinese New Year dragon includes visibility culling to skip rendering when completely off-screen (prevents despawn artifacts).
- **Events**: `AirplaneEvent`, `AlienEvent`, `BatEvent`, `BirdFlockEvent`, `ButterflyEvent`, `DeerEvent`, `DragonflyEvent`, `EagleEvent`, `FireflyEvent`, `FoxEvent`, `HotAirBalloonEvent`, `OwlEvent`, `RabbitEvent`, `ShootingStarEvent`, `SnowflakeEvent`, `SquirrelEvent`.

## Weather Data
- **WeatherRepository.kt**: Repository for fetching/storing weather data.
- **BreezyWeatherReceiver.kt**: Broadcast receiver for external weather updates.
- **WeatherType.kt**: Enum defining supported weather conditions.

## Settings & UI
- **PreferenceDefaults.kt**: Single Source of Truth for preference default values.
- **RootSettingsFragment.kt**: Main settings screen.
- **SceneSettingsFragment.kt**: "Scene" category settings.
- **EffectsSettingsFragment.kt**: "Effects" category settings.
- **EventsSettingsFragment.kt**: "Events" category settings.
- **SystemSettingsFragment.kt**: "System" category settings.
- **SpawnSwitchPreference.kt**: Custom preference widget for event toggles.
- **DebugOverlayPreference.kt**: Custom preference for debug overlay config.
- **EventPreviewView.kt**: Custom view for previewing animated events in settings.

## Resources (XML)
- **preferences.xml**: Legacy/Main preference definitions.
- **pref_root.xml**: Root preference categories.
- **pref_scene.xml**: Scene-appearance preferences.
- **pref_effects.xml**: Parallax and weather effect preferences.
- **pref_events.xml**: Random event spawn preferences.
- **pref_system.xml**: System/Performance preferences.
