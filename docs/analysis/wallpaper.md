# Wallpaper Service Analysis

## WeatherWallpaper.kt (239 lines)
**Purpose:** Android Live Wallpaper service entry point — hosts the render loop driving the entire scene.

**Approach:**
- `WallpaperService` with inner `WeatherEngine` extending `WallpaperService.Engine`
- Manual render loop on dedicated `Thread` with fixed-timestep (not Android `Choreographer`)
- Thread priority set to `THREAD_PRIORITY_DISPLAY` for smoother rendering
- `lockHardwareCanvas()` with `lockCanvas()` fallback for hardware-accelerated rendering
- Frame delta clamped to 1–200ms to prevent physics explosions after pauses
- **Idle mode battery optimization:** After configurable inactivity timeout (no parallax, weather, or events activity), FPS drops from `targetFps` (10-60) to `idleFps` (5-20). Activity detected by querying `scene.isParallaxActive()`, `isWeatherActive()`, `hasActiveEvents()`
- Performance logging every 5 seconds with avg/max frame time and actual FPS
- `@Volatile` on `isVisible` for thread-safe visibility signaling
- `thread.join(500)` in `stopRender()` prevents race conditions on surface destruction
- `lastFrameTimeMs` reset to 0 on visibility change to avoid delta spike after resuming
- Render thread sleeps at least 1ms even when over-budget to prevent 100% CPU
- `SharedPreferences.OnSharedPreferenceChangeListener` reloads settings on any change

**Key patterns:** Template Method (lifecycle), Observer (preferences), Game Loop
**Dependencies:** ParallaxScene (delegates all rendering), ParallaxSensorManager, WeatherRepository, PreferenceDefaults
