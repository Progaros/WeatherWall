# Rendering Layer Analysis

## SceneLayer.kt (54 lines)
**Purpose:** Interface contract for all rendering layers.

**Approach:**
- `LayerType` enum identifies each layer: SKY, CLOUDS, MOUNTAINS, TERRAIN, RIVER, RANDOM_EVENTS, WEATHER, VIGNETTE, DEBUG_OVERLAY
- `SceneLayer` interface: 5 methods + 1 property: `layerType`, `resize(w,h)`, `update(state, deltaTimeMs)`, `draw(canvas, state, parallaxCalculator)`, `reloadSettings()`, `destroy()`
- Enum-based dispatch avoids `instanceof`/`is` checks in ParallaxScene

**Key patterns:** Interface Segregation, Strategy, Enum Dispatch

---

## ParallaxScene.kt (354 lines)
**Purpose:** Central scene orchestrator — coordinates all rendering layers, parallax, simulation, weather, seasonal events, preferences.

**Approach:**
- **Mediator** — single coordination point between sensors, simulation, layers, weather, and seasonal events. No layer talks directly to another
- Ordered multi-pass rendering: sky → clouds → far-sky events → seasonal behind mountains → mountains → mid-sky events → fireworks → terrain (with interleaved ground animals) → weather → seasonal update → vignette → debug overlay
- Manual spawn triggers via SharedPreferences polling with 100ms throttle; preview and wallpaper use separate trigger keys to avoid race conditions
- **Logarithmic parallax sensitivity:** Slider 1–100% mapped via `min * (max/min)^((val-1)/99)` for fine control at low values
- TerrainLayer.GroundEventDrawer callback enables terrain to interleave ground animal rendering at correct depths
- Shake gesture triggers fireworks via `seasonalEvents.triggerShakeFireworks()`
- `isPreview` flag differentiates MainActivity preview vs live wallpaper
- `lastCanvas`/`lastState` stored temporarily for ground event drawer callback, then nulled to prevent leaks

**Key patterns:** Mediator, Strategy, Observer, Composite, Callback/Delegate
**Dependencies:** SimulationController, ParallaxCalculator, ParallaxSensorManager, 8 layer types, WeatherLayer, SeasonalEvents

---

## RenderConstants.kt (393 lines)
**Purpose:** Centralized constants repository — 100+ constants and ~12 utility functions for rendering.

**Approach:**
- Singleton `object` with all constants
- All `Color.parseColor()`/`Color.rgb()` values use `by lazy` to prevent crashes in unit tests without Android framework
- **Extended canvas system:** Content generated for virtual canvas wider than screen (`height * 2.0`). Enables parallax reveal of off-screen content
- **Height scaling:** Normalizes element sizes relative to 1920px reference, clamped to 0.5 minimum
- **Parallax depth system:** Depth factors 0.0–1.0 (0=far/slow, 1=near/fast). Per-layer: sky 0.05, stars 0.10, clouds 0.15, mountains 0.20–0.35, hills 0.50–0.80
- **HSV color interpolation:** `lerpColorHSV()` handles hue wrapping (350°→10° goes through 0°, not 180°)
- **Atmospheric perspective:** 5% foreground to 45% background distance-based tint + ambient color blending
- **6-phase ambient system:** night → pre-dawn → dawn-to-day → day → day-to-dusk → post-dusk → night with ease-in-out cubic
- **Ease-in-out cubic:** Standard `4t³` / `1 - (-2t+2)³/2`
- Event duration safety: prevents division by zero, clamps 1s–3min

**Key patterns:** Constants Object, Lazy Initialization
**Dependencies:** ColorMath.shadeRgb() for night shade

---

## SkyLayer.kt (597 lines)
**Purpose:** Renders sky background gradient, twinkling stars, sun (with Rayleigh scattering), and moon (with accurate phase rendering).

**Approach:**
- 120+ stars with fixed seed `Random(42)` for deterministic placement
- 6-phase sky color transition: night→dawn→day→dusk→night using dynamic sunrise/sunset
- Continuous interpolation via `computeNightAlpha()` for star/vignette fade (no hard boolean checks)
- Sun with parabolic arc `1 - 4(dayProgress - 0.5)²`, Rayleigh-inspired color shifting, configurable arc height
- **Moon phase rendering:** Real `moonPhase` (0–1) computes illumination fraction, determines waxing/waning, clips lit area using `arcTo()` with terminator ellipse — most complex geometry in file
- RadialGradient cache: only recreated when position/alpha changes by >10 units or >2px
- 3-stop LinearGradient for atmospheric realism (top→40%→horizon)
- Pre-allocated Path/RectF objects reused every frame (zero-alloc draw loop)
- `moonShadowColor` parsed once at init

**Key patterns:** Cache invalidation with thresholds, Pre-allocated objects, Parabolic arc
**Concerns:** context nullable with silent no-op reloadSettings(); dual vignette overlap with VignetteLayer

---

## CloudLayer.kt (938 lines)
**Purpose:** Renders clouds with three modes: Cached (pre-rendered bitmaps), Tiled (scrolling noise texture), Shader (AGSL RuntimeShader on Android 13+).

**Approach:**
- **Strategy pattern** via `CloudRenderMode` enum
- **Object pooling:** Cloud groups pre-allocated; bitmaps reused
- **Async bitmap rendering:** `Executors.newSingleThreadExecutor` with `AtomicBoolean` guard prevents duplicate renders
- **Color tinting at draw time:** `PorterDuffColorFilter(MULTIPLY)` — dawn/dusk transitions don't require bitmap regeneration
- `@Volatile` + immutable list for thread-safe group list
- **Perlin-style value noise:** 8×8 grid with smoothstep, 3 octaves, seamless tiling
- **AGSL FBM shader:** 5-octave FBM with tuned coverage→threshold mapping
- **Storm boost:** Thunderstorm multiplies speed by 1.8×
- MAX_BITMAP_DIMENSION=800 prevents GPU texture overflow
- Bitmap `recycle()` with null-first pattern prevents use-after-recycle
- Graceful executor shutdown in `destroy()` with 2s timeout

**Key patterns:** Strategy, Object Pooling, Async Rendering
**Concerns:** 150+ lines of AGSL shader code embedded inline

---

## MountainLayer.kt (548 lines)
**Purpose:** Mountain silhouettes with dual-slope shadow, atmospheric perspective, winter snow caps.

**Approach:**
- Dual-slope rendering: left/right sides with independent shadow strengths
- **Cone-style shadow system:** `sunElevation` from `getLightSourceAngle()`. Both slopes get base shadow from low sun + directional asymmetry via `lightBias`. Stronger shadows at base, lighter at peaks
- Jagged ridge line with random horizontal displacement
- 4-stop vertical gradient: peak→mid→base(atmospheric)→transparent
- **Snow caps:** Use exact slope points to build snow cap Path — no edge gaps. Jagged snow line with random drips. Gradient from opaque white at peak to semi-transparent
- Heights scaled by screen height for orientation-independent proportions
- Overlap pixel (1f) between left/right paths prevents anti-aliasing seam
- Cached haze gradient: only recreated when ambient color or height changes

**Key patterns:** Volatile + immutable list, Ambient color tinting

---

## TerrainLayer.kt (1666 lines)
**Purpose:** Rolling hills with fBm noise, conifer trees with wind animation, bushes, seasonal decorations (flowers, mushrooms, leaves, pinecones, berries, frost, snow).

**Approach:**
- **Fractal Brownian Motion (fBm):** 4-octave value noise with `hash()` → `smoothstep()` for natural terrain contours
- **GroundEventDrawer callback:** Interface enables RandomEventLayer to insert animals between tree depth layers
- `treesByDepth` map built once during generation, avoids per-frame filter() allocations
- **Season transition blending:** `getSeasonWeight()` returns smooth 0-1 values during `seasonTransitionDays`; all decorations use alpha blending
- **Traveling wind wave:** Trees sway with spatial coherence — nearby trees move similarly, creating visible wind "waves"
- Wind system combines windStrength, windGust, windMultiplier, treeSwaySpeed. Gust uses quadratic response (localGust²) for snap
- **Y-based depth sorting:** Merges trees, bushes, animals into single list sorted by Y (higher Y = closer = drawn last)
- Dynamic snow: weather-driven snowLevel overrides calendar winter snow
- Clearings (Lichtungen): random gaps scaled by treeSparsity
- 19 `@Volatile` fields; 15 pre-allocated Paint objects
- `autumnTreeColor()` varies hue per tree index `(index * 37) % 3`
- Tree shadows use trigonometric light angle from `state.getShadowOffset()`

**Key patterns:** fBm noise, Callback, Pre-grouped data, Y-sort depth
**Concerns:** File too large (1666 lines) — seasonal decorations, bush rendering, tree wind, hill generation could be separate classes

---

## RiverLayer.kt (320 lines)
**Purpose:** River with three visual styles (Flat, Wavy, Reflective). Water level from weather history (rainfall/snowmelt/drainage). Frozen state when temp < 0°C.

**Approach:**
- **Strategy pattern** via `RiverStyle` enum
- Multi-sine wave surface (3 superimposed waves) for organic water
- Deterministic sparkles: hash-based pseudo-random `((i * 7919 + seed) % 10000)` with flow-phase animation
- Freeze detection: `snowLevel > 0.05 && temperature < 0°C`
- Color palette: WATER_DAY, WATER_DAWN, WATER_DUSK, WATER_NIGHT, WATER_DEEP, ICE_COLOR
- Flow phase overflow protection at 1000f
- NaN check for temperature (defensive against upstream data quality issues)

**Concerns:** LinearGradient created every frame in wavy/reflective modes (no caching)

---

## WeatherLayer.kt (810 lines)
**Purpose:** Weather particles — Rain, Snow, Hail, Lightning (storm clouds), Fog.

**Approach:**
- **Object pooling:** Pre-allocated arrays — Rain 300, Snow 200, Hail 150 particles, zero runtime allocations
- **Layer depth system:** `getLayerScale(layer)` and `getLayerDepth(layer)` create parallax depth with 1-5 layers
- **Frame-rate independence:** `deltaSec * 60f` normalizes to 60fps reference
- Storm clouds: 3 pre-allocated layers with time-based drift
- Lightning: random walk segments from top to bottom
- Fog gradient caching: only recreated when light level changes >5%
- Hail has 3D highlight: secondary smaller circle offset up-left
- Snow with sinusoidal horizontal drift
- Wind-angled rain streaks with world-dimming overlay

**Key patterns:** Object Pooling, Strategy
**Concerns:** Dual API surface (legacy `render(canvas, enabled)` + modern `render(canvas, state)`) with duplicated logic

---

## VignetteLayer.kt (225 lines)
**Purpose:** Dark gradient overlays on screen edges for icon visibility. Per-edge controls: distance, fade%, alpha.

**Approach:**
- **Dirty flag** (`gradientsDirty`): gradients only rebuilt on resize/settings change
- 3-stop LinearGradient: solid→solid→transparent with configurable solidRatio
- Per-edge parametric control (top/bottom/left/right independent)
- Alpha conversion: `alpha * 2.55f` (0-100% to 0-255)
- `paint.isAntiAlias = false` correctly disabled for gradients

---

## DebugOverlayLayer.kt (214 lines)
**Purpose:** Debug HUD — FPS, frame time, power mode, time, weather, wind, clouds, events, moon phase, parallax.

**Approach:**
- Rolling average FPS: 30-frame sample, avoids jitter
- Configurable via StringSet preference for which items to display
- Warning highlights: FPS < 25 or frame time > 30ms shown orange
- Dynamic panel sizing: measures text widths for minimum background width
- Text scales with screen height: `(h / 2000f).coerceIn(0.6f, 1.5f)`
- Monospace typeface for alignment

---

## RandomEventLayer.kt (538 lines)
**Purpose:** Manages spawning, updating, drawing of 16 random ambient events (birds, planes, animals, etc.).

**Approach:**
- **Time-bucket determinism:** Each 500ms bucket `absoluteTimeMs / 500` produces same spawn decision — preview and wallpaper stay in sync
- **Volatile + immutable list:** Thread-safe event list with copy-on-write
- **Seasonal restrictions:** Butterflies spring/summer, fireflies summer nights, squirrels autumn, deer autumn/winter
- **Layer-based drawing:** `EventLayer.FAR_SKY`, `MID_SKY`, `GROUND` for z-order relative to mountains/terrain
- **Spawn chance scaling:** Steep exponential `0.0001 * 8000^((chance-10)/90)` maps 0-100 slider to 0→0.9 per-500ms probability
- Max concurrent events capped at 8
- Robust error handling: each event draw wrapped in try/catch
- `hillHeightProvider` set by TerrainLayer for terrain-following animals
- `getGroundEventsInRangeWithY()` returns (yRatio, drawLambda) pairs for Y-sorted interleaving with trees

**Key patterns:** Time-bucket determinism, Volatile + immutable list, Layered drawing

---

## SeasonalEvents.kt (1203 lines)
**Purpose:** Holiday effects: Christmas star with trail, New Year fireworks with rockets, Chinese New Year dragon, St. Patrick's Day rainbow.

**Approach:**
- Pre-calculated trigonometry: `STAR_RAY_COS[]`, `STAR_RAY_SIN[]` — avoids Math.toRadians in render loop
- **Object pooling:** 800 firework particles, 50 rockets, 30 stars, 20 trail particles — all pre-allocated
- Calendar-driven activation: `circularDayDistance()` with configurable ±interval per event
- **Year-wrapping distance:** Dec 31→Jan 1 = 1 day
- Shake-to-fireworks works year-round; uses Gaussian distribution for count variety
- Render layer splitting: `renderBehindMountains()` and `renderBehindTerrain()` for z-ordering
- Christmas star: 8-point star with pre-calculated geometry, gradient trail, glow
- Firework starburst: radial pattern with tight speed variance (6-8) for clean shell
- Rainbow: 7-band arc using SweepGradient + ComposeShader for vertical fade
- Chinese dragon: 12-segment body with dual-frequency wave, complex head with cubic Bézier snout/jaw/antlers/whiskers
- Settings polling every 1s via `System.currentTimeMillis()`
- `heightScale` factor ensures consistent sizing across densities

**Key patterns:** Object Pooling, Calendar-driven, Pre-calculated trig
**Concerns:** Not a SceneLayer (special wiring needed); file too large (1203 lines); frame-counter timing for rockets (ties to render rate)
