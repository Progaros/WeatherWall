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
- **ParallaxCalculator.kt**: Core logic for calculating 3D perspective shifts from tilt. Uses high lerp rate (0.9) for near-instant response (~20ms latency), matching reference app behavior. Handles return-to-center when device is leveled.
- **ParallaxSensorManager.kt**: Handles accelerometer/gyroscope/rotation vector sensors. Gyroscope mode uses direct output (no filter) with 3-sample averaging for minimal latency. Rotation vector mode uses high filter alpha (0.85) for fast response.
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
- **SeasonalEvents.kt**: Helper to determine which events can spawn in current season/weather.
- **Events**: `AirplaneEvent`, `AlienEvent`, `BatEvent`, `BirdFlockEvent`, `ButterflyEvent`, `DeerEvent`, `DragonflyEvent`, `EagleEvent`, `FireflyEvent`, `FoxEvent`, `HotAirBalloonEvent`, `OwlEvent`, `RabbitEvent`, `ShootingStarEvent`, `SnowflakeEvent`, `SquirrelEvent`.

## Weather Data
- **WeatherRepository.kt**: Repository for fetching/storing weather data.
- **WeatherProvider.kt**: Interface for weather data sources.
- **BreezyWeatherProvider.kt**: Implementation for Breezy Weather app integration.
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
