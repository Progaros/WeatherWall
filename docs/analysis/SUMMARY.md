# WeatherWall — Comprehensive Architecture & Approach Summary
## For External Best Practice Review

> **WeatherWall** is an Android live wallpaper (v1.12.0, versionCode 155) that procedurally renders a scenic landscape with parallax depth, animated weather, wildlife events, and seasonal effects — all drawn in real-time using Canvas 2D with zero bitmap assets for scene elements.
> 
> **Target:** compileSdk 35, minSdk 26, Kotlin 1.9.22, AGP 8.11.1, Java 17
> **Dependencies:** androidx.core-ktx 1.12.0, appcompat 1.6.1, material 1.11.0, preference-ktx 1.2.1, kotlinx-coroutines-android 1.7.3
> **Testing:** JUnit 4.13.2, Mockito 5.8.0, mockito-kotlin 5.2.1, Robolectric 4.14.1
> **Privacy:** No INTERNET permission. Weather data comes from Breezy Weather broadcasts (Gadgetbridge protocol).

---

## 1. PROJECT STRUCTURE & FILE INVENTORY

### Package Layout (~50 Kotlin source files, ~12,500 lines total)
```
info.olli.weatherlwp/
├── WeatherWallApp.kt          (95)   — Application subclass, crash handler
├── MainActivity.kt            (371)  — Settings UI, live preview
├── PreferenceDefaults.kt      (117)  — Centralized default values
├── DebugTracer.kt             (155)  — Performance tracing
├── parallax/
│   ├── ParallaxCalculator.kt  (278)  — Depth-based parallax math
│   └── ParallaxSensorManager.kt (588) — Hardware sensors, shake detection
├── prefs/
│   ├── SettingsFragment.kt    (190)  — Legacy flat settings (deprecated)
│   └── TimeSeekBarPreference.kt (55) — HH:MM seekbar
├── rendering/
│   ├── SceneLayer.kt          (54)   — Layer interface & LayerType enum
│   ├── ParallaxScene.kt       (354)  — Scene orchestrator/mediator
│   ├── RenderConstants.kt     (393)  — Constants & utility functions
│   ├── SkyLayer.kt            (597)  — Sky gradient, stars, sun, moon
│   ├── CloudLayer.kt          (938)  — 3 render modes (cached/tiled/shader)
│   ├── MountainLayer.kt       (548)  — Mountain silhouettes, shadows, snow
│   ├── TerrainLayer.kt        (1666) — Hills, trees, bushes, seasonal decor
│   ├── RiverLayer.kt          (320)  — River with 3 visual styles
│   ├── WeatherLayer.kt        (810)  — Particle weather effects
│   ├── VignetteLayer.kt       (225)  — Edge darkening gradients
│   ├── DebugOverlayLayer.kt   (214)  — Performance HUD
│   ├── RandomEventLayer.kt    (538)  — Event spawn/lifecycle manager
│   ├── SeasonalEvents.kt      (1203) — Holiday effects (Christmas, NYE, etc.)
│   └── events/
│       ├── RandomEvent.kt     (114)  — Event interface + enums
│       ├── SquirrelEvent.kt   (152)  — Ground animal
│       ├── DeerEvent.kt       (193)  — Ground animal with fawn variant
│       ├── FoxEvent.kt        (136)  — Ground animal
│       ├── RabbitEvent.kt     (117)  — Ground animal with hopping
│       ├── BatEvent.kt        (131)  — Night sky colony (3-8)
│       ├── BirdFlockEvent.kt  (107)  — V-formation flock (2-7)
│       ├── EagleEvent.kt      (115)  — Solo soaring raptor
│       ├── OwlEvent.kt        (188)  — Night solo with glide-burst
│       ├── AirplaneEvent.kt   (124)  — Jet with contrail
│       ├── HotAirBalloonEvent.kt (113) — Drifting balloon with LOD
│       ├── AlienEvent.kt      (139)  — Rare night UFO
│       ├── ShootingStarEvent.kt (100) — FAR_SKY meteor
│       ├── SnowflakeEvent.kt  (99)   — 6-fold symmetry crystal
│       ├── FireflyEvent.kt    (80)   — Ground cluster (8-20)
│       ├── ButterflyEvent.kt  (129)  — 3D wing-beat perspective
│       └── DragonflyEvent.kt  (141)  — 4-wing dart behavior
├── settings/
│   ├── RootSettingsFragment.kt (22)  — Root stub
│   ├── SceneSettingsFragment.kt (15) — Scene stub
│   ├── EffectsSettingsFragment.kt (43) — Pref migration
│   ├── EventsSettingsFragment.kt (37) — Spawn triggers
│   ├── EventsChooseFragment.kt (57)  — Per-event toggles
│   ├── SystemSettingsFragment.kt (42) — Pref type migration
│   ├── SystemAboutFragment.kt (120)  — Debug/diagnostic tools
│   ├── SubSettingsFragments.kt (103) — 14 stub fragments
│   ├── SettingsExporter.kt    (71)   — Prefs → clipboard
│   ├── SpawnSwitchPreference.kt (93) — Custom composite widget
│   ├── DebugOverlayPreference.kt (142) — Split touch zone
│   └── EventPreviewView.kt   (636)  — 16 animated event previews
├── simulation/
│   ├── SimulationController.kt (253) — Simulation facade/mediator
│   ├── SimulationState.kt     (286)  — Immutable per-frame snapshot
│   ├── SimulationSettings.kt  (188)  — Preference reader/cache
│   ├── SimulationClock.kt     (169)  — Time provider (real/manual)
│   └── WeatherHistory.kt      (293)  — Accumulated weather tracking
├── util/
│   ├── ColorMath.kt           (27)   — Night shade color math
│   └── SeasonHelper.kt        (213)  — Season detection/blending
├── wallpaper/
│   └── WeatherWallpaper.kt   (239)   — WallpaperService entry point
└── weather/
    ├── WeatherType.kt         (12)   — Weather condition enum
    ├── WeatherRepository.kt   (239)  — SharedPreferences persistence
    └── BreezyWeatherReceiver.kt (123) — Broadcast receiver
```

---

## 2. CORE ARCHITECTURE

### 2.1 Data Flow — Produce-Once, Consume-Many
```
WeatherRepository (static) ←── BreezyWeatherReceiver (broadcast)
        │
        ▼
SimulationController ──▶ SimulationState (immutable data class)
  ├── SimulationClock          │
  ├── SimulationSettings       │ produced fresh each frame
  ├── WeatherHistory           │
  └── SeasonHelper             ▼
                        All Rendering Layers (read-only)
```
- `SimulationController` assembles a `SimulationState` each frame from clock/settings/history/weather
- `SimulationState` is an immutable `data class` with 40+ `val` properties — eliminates race conditions
- All rendering layers consume the single snapshot read-only — visual consistency within each frame

### 2.2 Rendering Pipeline — Layered Compositor
```
ParallaxScene (Mediator)
  ├── update() pass: iterate all layers, pass SimulationState + deltaTimeMs
  └── draw() pass: ordered multi-pass rendering
        1. SkyLayer (gradient, stars, sun, moon)
        2. CloudLayer (cached/tiled/AGSL shader)
        3. RandomEventLayer.drawFarSkyEvents() (shooting stars)
        4. SeasonalEvents.renderBehindMountains() (Christmas star, rainbow)
        5. MountainLayer (silhouettes, shadows, snow caps)
        6. RandomEventLayer.drawMidSkyEvents() (birds, planes, owl, bat)
        7. SeasonalEvents.renderBehindTerrain() (fireworks, dragon)
        8. TerrainLayer (hills, trees, bushes, ground animals interleaved)
        9. RiverLayer (water, sparkles, ice)
        10. WeatherLayer (rain, snow, hail, lightning, fog)
        11. SeasonalEvents update
        12. VignetteLayer (edge darkening)
        13. DebugOverlayLayer (performance HUD)
```

### 2.3 SceneLayer Interface
All layers implement: `resize(w,h)`, `update(state, deltaTimeMs)`, `draw(canvas, state, parallaxCalculator)`, `reloadSettings()`, `destroy()`. 
Exception: `SeasonalEvents` is NOT a SceneLayer — requires special integration wiring.

### 2.4 Render Loop
- Manual `Thread` with fixed-timestep, NOT Android `Choreographer`
- `lockHardwareCanvas()` with `lockCanvas()` fallback
- Frame delta clamped to 1–200ms (prevents physics explosions)
- **Idle mode:** After inactivity timeout, FPS drops from targetFps (10-60) to idleFps (5-20)
- Thread priority: `THREAD_PRIORITY_DISPLAY`; daemon thread; sleeps at least 1ms
- Same loop used in both `WeatherWallpaper` service and `MainActivity` preview

---

## 3. KEY DESIGN PATTERNS

### 3.1 Thread Safety Model
- **Immutable Snapshot:** `SimulationState` data class created fresh each frame — render thread consumes immutable object
- **`@Volatile` + immutable list:** Layer data lists (clouds, mountains, hills, trees, events) are regenerated as new immutable lists and assigned to `@Volatile` fields. Render thread sees consistent snapshot
- **`AtomicReference<TiltPair>`:** Lock-free sensor-to-render handoff in `ParallaxCalculator`
- **`@Synchronized` + `@Volatile cachedSnapshot`:** `WeatherHistory` uses synchronized writes + volatile read for single-writer/single-reader
- **Ring buffer with `synchronized`:** `DebugTracer` uses dedicated lock object
- **Thread-safe maps:** `Collections.synchronizedMap` for timing start times

### 3.2 Zero-Allocation Render Loop
- All `Paint`, `Path`, `RectF` objects pre-allocated as class fields, reused every frame
- Object pooling: WeatherLayer particles (300 rain, 200 snow, 150 hail), SeasonalEvents (800 firework particles, 50 rockets, 30 stars, 20 trails)
- Fixed-seed `Random(42)` for deterministic star/mountain generation (no per-frame allocations)
- Calendar instances cached and reused in `SimulationClock`
- Gradient caching with dirty-flag or threshold-based invalidation

### 3.3 Deterministic Event Synchronization
- Events use absolute-time progress: `(currentTime - spawnTimeMs) / durationMs`
- **Time-bucket spawning:** `absoluteTimeMs / 500` bucket produces same spawn decision in preview and wallpaper
- `Random(spawnTime + 99999)` ensures same event type selected at same time bucket
- Both instances share SharedPreferences triggers with separate keys (`_preview`/`_wallpaper` suffix)

### 3.4 Extended Canvas System
- Content generated for virtual canvas wider than screen (`height * 2.0`)
- `getExtendedWidth()` and `getExtendedOffset()` compute bounds
- Parallax reveals off-screen content as device tilts — prevents visible edges

### 3.5 SharedPreferences as IPC
- No ContentProvider or AIDL — wallpaper service and settings activity communicate via SharedPreferences
- Event spawn triggers: settings writes timestamp → wallpaper polls and detects new value
- Manual spawn uses `commit()` (synchronous) for immediate visibility
- Preference reload throttled to max 10/sec (100ms debounce)

---

## 4. RENDERING APPROACHES

### 4.1 Color System
- **Night shading:** `ColorMath.shadeRgb()` multiplies RGB by `1 - 0.65 × strength`, adds blue lift (+10 × strength to B) to prevent muddy grays
- **HSV interpolation:** `RenderConstants.lerpColorHSV()` handles hue wrapping (350°→10° clockwise)
- **6-phase ambient system:** Smooth ambient tint across night → pre-dawn → dawn → day → dusk → post-dusk → night with ease-in-out cubic
- **Atmospheric perspective:** Distance-based tint 5% foreground to 45% background + ambient blending
- **Ambient blending:** All events use `blendWithAmbient(color, ambientColor, factor)` for time-of-day adaptation
- **Lazy color initialization:** All `Color.rgb()`/`Color.parseColor()` calls use `by lazy {}` for unit test compatibility without Android framework

### 4.2 Sky Rendering (SkyLayer.kt — 597 lines)
- 3-stop LinearGradient (top → 40% → horizon) with 6-phase color transitions
- 120+ stars at fixed positions (Random(42)), twinkle via sin(), continuous night alpha via smoothstep
- Sun on parabolic arc: `1 - 4(dayProgress - 0.5)²`, with Rayleigh-inspired color shift
- Moon with real phase rendering: terminator ellipse clipping via arcTo(), maria rendering
- RadialGradient cache: only recreated when position/alpha change > threshold

### 4.3 Cloud System (CloudLayer.kt — 938 lines)
- **Three render modes:**
  1. **Cached:** Pre-rendered bitmap groups with `PorterDuffColorFilter(MULTIPLY)` for runtime tinting
  2. **Tiled:** 8×8 value noise with 3-octave smoothstep, seamless tiling, scrolling
  3. **Shader:** AGSL `RuntimeShader` (Android 13+) with 5-octave FBM, GPU-accelerated
- Async bitmap rendering via `Executors.newSingleThreadExecutor` + `AtomicBoolean` guard
- 75/25 back/front layer distribution; MAX_BITMAP_DIMENSION=800; storm speed 1.8×

### 4.4 Terrain System (TerrainLayer.kt — 1666 lines)
- **fBm noise generation:** 4-octave value noise with `hash()` → `smoothstep()` at frequencies 1×/2×/4×/8×
- **Tree wind:** Traveling wind wave with spatial coherence. Gust uses quadratic response (localGust²)
- **Y-based depth sorting:** Merges trees, bushes, animals into single draw list sorted by Y position
- **Seasonal decorations:** Flowers, mushrooms, fallen leaves, pinecones, berries, frost, snow — each with alpha-blended season transitions over 14-day period
- **GroundEventDrawer callback:** RandomEventLayer inserts animals between tree depth layers
- **Clearings (Lichtungen):** Random gaps in tree placement for natural forest breaks

### 4.5 Mountain Rendering (MountainLayer.kt — 548 lines)
- Dual-slope shadows: sun elevation drives shadow strength on left/right sides independently
- Cone-style shadow: stronger at base, lighter at peaks, with directional asymmetry
- 4-stop vertical gradient with atmospheric base fade
- Snow caps using exact slope points with jagged snow line and drip details

### 4.6 Weather Particles (WeatherLayer.kt — 810 lines)
- Pre-allocated particle pools (zero runtime allocations)
- Frame-rate independent physics: `deltaSec * 60f` normalization
- 1-5 parallax depth layers per particle type
- Rain: wind-angled streaks with world-dimming overlay
- Snow: sinusoidal horizontal drift
- Hail: 3D highlight (secondary circle offset up-left)
- Lightning: random walk bolt segments + 3-layer storm clouds
- Fog: dual-gradient with light-level-adaptive coloring, cached gradients

### 4.7 River (RiverLayer.kt — 320 lines)
- Three styles: Flat (shimmer line), Wavy (multi-sine surface with foam), Reflective (3-stop gradient with sparkles)
- Water level driven by WeatherHistory (rainfall, snowmelt, drainage)
- Freeze state at snowLevel > 0.05 && temp < 0°C
- Deterministic sparkle positions via hash-based pseudo-random

### 4.8 Event System (RandomEventLayer.kt + 16 event files)
- All 16 events are 100% procedural vector art — zero bitmap assets
- Three rendering layers: FAR_SKY (behind mountains), MID_SKY (between mountains/terrain), GROUND (interleaved with trees)
- Ground events follow terrain via `HillHeightProvider` callback
- Spawn chance: exponential mapping `0.0001 * 8000^((chance-10)/90)` for 0-100 slider → probability
- Max 8 concurrent events; seasonal/time-of-day restrictions
- **Animal animations:** Stop-and-go squirrel, grazing deer (20% fawn variant), hopping rabbit (half-sine bounce), erratic bat colony, V-formation birds, soaring eagle, glide-burst owl with eye glow, darting dragonfly (40Hz wings), asymmetric butterfly flutter
- **Sky events:** Contrail airplane, bobbing balloon (LOD ropes), pulsing UFO (tractor beam), burn-up shooting star, rotating snowflake (6-fold symmetry), flickering firefly cluster

### 4.9 Seasonal/Holiday Events (SeasonalEvents.kt — 1203 lines)
- Calendar-driven activation with circular day distance and configurable ±interval
- Pre-calculated trigonometry arrays for star rays (avoids Math.toRadians in render loop)
- Christmas: 8-point star with gradient trail, glow
- New Year: Starburst firework particles (tight 6-8 speed variance for clean shell), rocket launch with time-based explosion
- Chinese New Year: 12-segment dragon body with dual-frequency wave, complex head (cubic Bézier snout/jaw/antlers/whiskers)
- St. Patrick's: 7-band rainbow arc via SweepGradient + ComposeShader
- Shake-to-fireworks: Gaussian distribution for firework count variety

---

## 5. PARALLAX SYSTEM

### ParallaxCalculator.kt (278 lines)
- **tan() perspective curve:** Linear tilt → nonlinear displacement mimicking looking through a window
- **Circular clamping:** Unit ellipse prevents diagonal corner stretching
- **Blended lerp + return-to-center:** Smooth mix based on tilt magnitude, no abrupt mode switch
- **Edge easing:** Power-curve attenuation near boundaries, only when moving away from center
- **Depth multiplier:** Blends foreground-heavy vs background-heavy models via `perspective` parameter
- Delta-time normalized (`/16f`); snap-to-target at 0.1px; pure Kotlin (no Android deps)

### ParallaxSensorManager.kt (588 lines)
- **Dual sensor modes:** Rotation Vector (delta accumulation from rotation matrix) or Gyroscope (angular velocity integration)
- **Shake detection — two methods:**
  1. Accelerometer gForce with debounce/cooldown
  2. Gyro wiggle: stable-sign detection requiring consecutive consistent readings + 90ms inter-change time
- **Pose gain:** Fades sensitivity to zero near vertical (gimbal-lock region)
- **Hysteresis:** Separate on/off/freeze thresholds for screen-down detection
- **Warmup:** Discards first 30 sensor events after start() to drain stale OS-queued events
- Internal `Averager` class: pre-allocated FloatArray circular buffer

---

## 6. SIMULATION SYSTEM

### SimulationController.kt (253 lines) — Facade/Mediator
- Assembles SimulationState from 4 subsystems each frame
- **Wind gust simulation:** Procedural gusts with adaptive intervals (1-2s high wind, 4-7s low wind). Ramp up via rampSpeed, decay via gustDecay. Suppressed below 0.15 wind
- **Exponential time smoothing:** Frame-rate-independent lerp. Large jumps (>2h) → slow rate 1.8/s for pleasant transitions; small gaps → fast rate 18/s
- **Wind direction smoothing:** Handles 0°/360° wraparound via shortest angular distance (±180° normalization)
- Delta clamped to [0, 100]ms

### SimulationState.kt (286 lines) — Immutable Value Object
- `data class` with 40+ `val` properties, created fresh per frame
- **Light level:** SmoothStep cubic Hermite over 1-hour transition. Night=0.15, Day=1.0
- **Shadow system:** Pre-calculated light source angle for sun arc (π→0 across 6am–6pm). Cached cos/sin avoids per-tree trig. Moon shadows at 40% intensity. Shadow length 1.5/sin(θ) for dramatic low-angle shadows
- **Cloud count:** Non-linear piecewise mapping from 0-100% cloud cover to 0-50 cloud groups
- Wind direction cos/sin pre-calculated at construction

### SimulationClock.kt (169 lines) — Strategy Pattern
- Real-time vs manual mode
- Wraps timeSeconds to 24-hour period (mod 86400000ms) to prevent sin() float jitter
- Asymmetric dawn (1.5h) / dusk (3h) — models longer warm afternoon light
- Double intermediate for absoluteTimeMs (precision at ~1.7B seconds)

### WeatherHistory.kt (293 lines) — Accumulated Weather Physics
- Snow: accumulates 0.06/hr, melts 0.008/°C/hr above 0°C, rain washes 0.03/hr  
- River: rises from rain 0.04/hr + snowmelt 0.03/unit. Drains 0.012/hr. Evaporation 0.004/hr above 15°C
- Hourly deduplication; 14-day max history; JSON persistence with compressed keys
- Full-history replay via `recalculateLevels()` for correctness after reload
- Temperature estimation when no sensor: snow→−2°C, rain→12°C, clear→15°C

### SeasonHelper.kt (213 lines) — Season Blending
- `SeasonBlend` data class: (primary, previous, transitionProgress) with smoothstep easing (3t²−2t³)
- 14-day transition period between seasons
- Meteorological boundaries: Mar 1 / Jun 1 / Sep 1 / Dec 1
- Winter wrapping for Jan/Feb

---

## 7. WEATHER INTEGRATION

### Privacy-First Design
- NO internet permission in manifest
- Weather data arrives via broadcast from Breezy Weather app (Gadgetbridge protocol)
- `BreezyWeatherReceiver` maps OWM condition codes (200-804) to internal `WeatherType` enum
- Async processing: `goAsync()` + `ExecutorService` off main thread prevents ANR
- `pendingResult.finish()` guaranteed via `finally`

### WeatherRepository (239 lines) — SharedPreferences Persistence
- ExtendedWeatherData DTO: 12 fields (precip, cloud cover, humidity, visibility, pressure, moon, wind, temp)
- Temperature stored as Celsius × 10 (Int) for 0.1° precision without float issues
- Moon illumination: `(1 − cos(phase°)) / 2`
- Deterministic daily wind when no data: hash(daysSinceEpoch)
- Normalized getters pre-scaled for rendering (wind 0-1, visibility 0-1, etc.)
- Data freshness check with 1-hour threshold

---

## 8. SETTINGS SYSTEM

### Architecture
- Hierarchical PreferenceFragment structure: Root → 4 tabs (Scene/Effects/Events/System) → sub-screens
- 14 stub fragments in one file (SubSettingsFragments.kt) — only fragments with custom logic get separate files
- `PreferenceDefaults` object: centralized const val for all default values, validated by test

### Custom Preference Widgets
1. **SpawnSwitchPreference:** Switch + spawn button + animated EventPreviewView in one row
2. **DebugOverlayPreference:** Split touch zone (switch right / multi-select dialog left)
3. **TimeSeekBarPreference:** SeekBar displaying HH:MM format

### Settings IPC
- SharedPreferences as inter-process communication between settings activity and wallpaper service
- Event spawn triggers via timestamp writes (synchronous commit)
- Preference reload throttled at 100ms

### Data Migrations
- Parallax strength scale migration (v178): maps old 0-50 or 0-5 scale to new 1-100%
- Idle timeout type migration: Int SeekBar → String ListPreference with closest-value matching

---

## 9. BUILD & CONFIGURATION

### Build System
- Single-module Gradle project with Kotlin DSL
- Release: minification (R8) + resource shrinking enabled
- CI debug signing with consistent keystore
- Docker build support via Dockerfile.build

### Manifest
- No INTERNET permission — explicitly privacy-first
- `<queries>` block for live wallpaper service detection (Android 14+)
- BreezyWeatherReceiver exported to receive external broadcasts
- WeatherWallpaper service with BIND_WALLPAPER permission

### Application Class
- Global crash handler capturing structured crash logs
- Clipboard utility with Android 13+ sensitive data marking

---

## 10. TESTING APPROACH

### Test Infrastructure
- 20+ test files covering rendering, parallax, preferences, events, simulation
- Uses JUnit 4, Mockito, Robolectric
- `by lazy {}` pattern on Color.rgb() calls enables test execution without Android framework
- `PreferenceDefaultsTest` validates XML ↔ Kotlin default sync
- Pure Kotlin utilities (ColorMath, ParallaxCalculator) testable on plain JVM

### Test Coverage Areas
- Rendering constants and color math
- Parallax calculations
- Cloud layer behavior
- Event spawning and lifecycle
- Seasonal event activation
- Preference sync validation
- Build configuration
- Terrain/hill generation
- Weather repository

---

## 11. PERFORMANCE APPROACHES

### Render Loop Optimization
- Zero-allocation draw loop: pre-allocated Paint/Path/RectF objects
- Object pooling for all particle systems (weather, fireworks)
- Gradient caching with dirty-flag or threshold-based invalidation
- Async bitmap rendering for cloud groups (single-thread executor with AtomicBoolean guard)
- MAX_BITMAP_DIMENSION=800 prevents GPU texture overflow

### Battery Conservation
- Idle mode: FPS drops after inactivity (no parallax, weather, or events)
- Extended canvas reduced from 4.0× to 2.0× for fewer scene elements
- Settings polling throttled (100ms preferences, 1000ms seasonal)
- Sensor disabled when parallax is off
- Daemon threads for clean process exit

### Frame-Rate Independence
- `deltaSec * 60f` normalization for 60fps reference in weather particles
- `deltaTimeMs / 16f` for parallax lerp rates
- Clamped deltas prevent physics explosions (1-200ms for rendering, 0-100ms for simulation)
- Absolute-time progress for events instead of frame-counting

---

## 12. KNOWN CONCERNS & POTENTIAL IMPROVEMENTS

### Architecture
1. **SeasonalEvents is not a SceneLayer** — requires special wiring in ParallaxScene; architectural inconsistency
2. **TerrainLayer.kt (1666 lines)** — too many responsibilities: hills, trees, bushes, seasonal decorations, wind animation should be decomposed
3. **SeasonalEvents.kt (1203 lines)** — 4 distinct visual systems could be separate classes
4. **Legacy prefs/SettingsFragment.kt** — duplicates functionality now in settings/ package
5. **WeatherLayer dual API** — legacy `render(canvas, enabled)` + modern `render(canvas, state)` with duplicated code

### Rendering
6. **Dual vignette systems** — SkyLayer has night vignette AND VignetteLayer exists; potential visual stacking
7. **RiverLayer gradient allocation** — LinearGradient created every frame in wavy/reflective modes (no caching)
8. **AGSL shader embedded inline** — 150+ lines of shader code in a Kotlin string literal; could be a resource
9. **Rocket timing tied to frame counter** — SeasonalEvents uses frameCounter for rockets instead of delta time

### Data Quality
10. **SimulationState has 40+ properties** — auto-generated data class methods (copy/equals/hashCode) potentially expensive
11. **`Float.NaN` and `Int.MIN_VALUE` sentinels** — inconsistent null representation strategy
12. **Temperature > 100 heuristic** for Kelvin detection — architecturally fragile
13. **Unused `SimulationState` import** in all 16 event files

### Threading
14. **Some `@Volatile` fields** written from only one thread — unnecessary volatile overhead
15. **Settings polling** (1s interval in SeasonalEvents) vs SharedPreferences.OnSharedPreferenceChangeListener

### Missing Features
16. **HAIL weather type** defined but never mapped from BreezyWeather codes
17. **`setTimeMultiplier()` and `getElapsedTimeMs()`** appear unused in SimulationClock

---

## 13. MATHEMATICAL APPROACHES USED

### Noise & Terrain
- **fBm (Fractal Brownian Motion):** 4-octave value noise with hash→smoothstep for hill contours
- **Perlin-style noise:** 8×8 grid with smoothstep interpolation for cloud tiles
- **AGSL FBM shader:** 5-octave GPU-accelerated cloud noise

### Easing & Interpolation
- **SmoothStep:** $t^2(3-2t)$ — Hermite cubic for light level, sky transitions, season blending
- **Ease-in-out cubic:** $4t^3$ / $1-(-2t+2)^3/2$ for ambient color transitions
- **HSV interpolation with hue wrapping** — prevents muddy gray crossfades
- **Logarithmic sensitivity mapping:** $\min \times (\max/\min)^{(v-1)/99}$ for parallax slider

### Physics & Animation
- **Parabolic arc:** $1-4(p-0.5)^2$ for sun/moon celestial path
- **Wind quadratic gust response:** localGust² for snappy response
- **Half-sine bounce:** `sin(phase).coerceAtLeast(0f)` for rabbit hopping
- **Multi-frequency sine composition:** 2-3 sine waves at different frequencies for erratic animal paths, water waves
- **tan() perspective distortion** for parallax depth perception
- **Circular clamping via unit ellipse** for tilt bounds
- **Shortest angular distance** for wind direction smoothing (±180° normalization)
- **Moon phase illumination:** $(1-\cos(\text{phase}°))/2$
- **Shadow length:** $1.5/\sin(θ)$ for dramatic low-angle shadows

### Astronomy
- **Moon terminator:** Elliptical arcTo() clipping for waxing/waning phase rendering
- **Rayleigh-inspired sun color shifting** toward red at low elevation angles
- **Atmospheric haze:** Semi-transparent fog overlay increasing with distance

---

## 14. CONFIGURATION & EXTENSIBILITY

### User-Configurable Parameters (~80+ preferences)
- Parallax: strength, smoothing, depth separation, perspective, return speed, axis inversion, edge easing, sensor mode
- Clouds: speed, opacity, render mode (cached/tiled/shader), density
- Terrain: tree density, hill amplitude/frequency, sway speed, bush density
- Weather: layer count, wind multiplier
- Seasonal: per-event toggles, intervals, size/trail/frequency
- Events: 16 per-event enable toggles, spawn chance slider
- Visual: night shade intensity, vignette per-edge controls
- Performance: target FPS (10-60), idle FPS (5-20), idle timeout
- River: enable toggle, style (flat/wavy/reflective)
- Debug: overlay toggle, item selection, tracing

### Extensibility Points
- New `SceneLayer`: implement interface, add to ParallaxScene ordered list
- New `RandomEvent`: implement interface, add to RandomEventLayer spawn list + enable preference
- New `CloudRenderMode`: add enum value + draw method
- New `RiverStyle`: add enum value + draw method
- New `SeasonalEvent`: add to SeasonalEvents (or better: create separate class implementing SceneLayer)
- New `WeatherType`: add enum value + BreezyWeatherReceiver mapping + WeatherLayer effect

---

## 15. DEPENDENCY GRAPH (Simplified)

```
BreezyWeatherReceiver → WeatherRepository (SharedPreferences)
                              ↓
MainActivity ←→ ParallaxScene ←→ WeatherWallpaper
                    ↓
            SimulationController
            ├── SimulationClock
            ├── SimulationSettings ← PreferenceDefaults
            ├── WeatherHistory ← WeatherRepository
            └── SeasonHelper
                    ↓
            SimulationState (immutable)
                    ↓
    ┌───────────────┼───────────────┐
    ↓               ↓               ↓
SkyLayer    CloudLayer      TerrainLayer
MountainLayer RiverLayer    RandomEventLayer → 16 Events
WeatherLayer  VignetteLayer DebugOverlayLayer
SeasonalEvents
    ↑               ↑               ↑
    └───── ParallaxCalculator ◀── ParallaxSensorManager
                                    (hardware sensors)
```

All layers depend on: `SimulationState`, `ParallaxCalculator`, `RenderConstants`, `ColorMath`
Settings depend on: `PreferenceDefaults`, `PreferenceManager`, `SharedPreferences`
