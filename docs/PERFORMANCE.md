# Performance & Battery

## Frame Rate
- Max FPS: 20–30 (configurable via `pref_fps`)
- Frame time limiting with `Thread.sleep()`
- FPS logging available in debug mode

## Rendering Optimizations
- Render only when visible (`onVisibilityChanged`)
- No object allocations per frame:
  - Paint objects pre-allocated in `init {}`
  - Path objects reused via `reset()`
  - Particle arrays fixed-size and pooled
- Lazy regeneration: elements only rebuilt when settings change
- Low-poly shapes: mountains use ~6 vertices, clouds use RadialGradient blur

## Sensor Management
- Sensors paused when wallpaper not visible
- Using `SENSOR_DELAY_GAME` for responsive parallax
- Low-pass filter (α=0.85) smooths accelerometer jitter

## Particle Systems
- **Rain**: Fixed pool of 100 particles, recycled when off-screen
- **Snow**: Fixed pool of 50 particles with drift
- **Fireworks**: Pool of 5 explosions × 30 particles each
- **Christmas Star Trail**: 8 trail particles, persistent

## Memory Patterns
- Data classes for scene elements (Mountain, Hill, Tree, Cloud)
- Pre-computed values stored (gradients, vertex positions)
- Bitmap-free rendering (Canvas primitives only)

## Profiling Tips
- Enable FPS display in debug settings
- Log frames >33ms (at 30 FPS target)
- Use Android Profiler to check for allocations during `render()`
