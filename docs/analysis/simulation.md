# Simulation System Analysis

## SimulationController.kt (253 lines)
**Purpose:** Facade/Mediator — single entry point coordinating clock, settings, weather history, and weather repository. Produces immutable SimulationState each frame.

**Approach:**
- **Immutable Snapshot pattern:** Creates new `SimulationState` data class each frame — render threads get consistent, thread-safe view
- **Wind Gust Simulation:** Procedural gusts with randomized intervals. Low wind (<0.15) suppresses gusts. Intensity ramps via `rampSpeed`, decays via `gustDecay`. Intervals adapt to base wind (1–2s high wind, 4–7s low wind)
- **Exponential Time Smoothing:** Frame-rate-independent lerp via `baseRate * deltaSec`. Large time jumps (>2h) use slow rates (1.8/s) for pleasant transitions; small gaps use fast rates (18/s). First frame skips smoothing
- **Wind Smoothing:** Separate smoothers for strength (~1.5s to target) and direction (handles 0°/360° wraparound via shortest angular distance normalized to ±180°)
- **Precipitation Intensity:** Weather-type-specific scaling — rain min 20% floor, snow 15%, hail 30%
- Golden/Blue Hour booleans from rawTime relative to dynamic sunrise/sunset ± fixed durations
- `deltaMs` clamped to [0, 100]ms to prevent physics explosion after unlock

**Dependencies:** SimulationClock, SimulationSettings, WeatherHistory, WeatherRepository (static), SeasonHelper

---

## SimulationState.kt (286 lines)
**Purpose:** Immutable per-frame snapshot — single source of truth for rendering layers.

**Approach:**
- `data class` with all `val` properties — created fresh each frame
- **Light Level:** SmoothStep (cubic Hermite: $t^2(3-2t)$) over 1-hour transition around sunrise/sunset. Night=0.15, Day=1.0
- **Atmosphere Phase:** Returns [-1, 1]: -1=night, -0.5=blue hour, 0=golden hour, 1=day
- **Shadow System:** Light source angle pre-calculated for sun arc (π left → 0 right across 6am–6pm). Shadow offset uses pre-cached cos/sin to avoid per-tree trig. Moon shadows 40% intensity. Shadow length $\frac{1.5}{\sin(θ)}$ for longer shadows at low angles
- **Cloud Count from Cloud Cover:** Non-linear piecewise: 0–10%→0–2, 10–30%→2–8, 30–60%→8–22, 60–85%→22–45, 85–100%→45–50
- Pre-calculated wind direction cos/sin cached in private vals
- `CloudDensity` enum: NONE, LIGHT, MEDIUM, HEAVY, OVERCAST, AUTO
- `companion object { fun default() }` factory for testing
- `temperatureCelsius` defaults to `Float.NaN` — callers must check

**Concerns:** data class with 40+ properties — auto-generated `copy()/equals()/hashCode()/toString()` could be expensive if called in hot paths

---

## SimulationSettings.kt (188 lines)
**Purpose:** Reads and caches user preferences. Clean typed API.

**Approach:**
- **Lazy Reload:** `reload()` pulls all values at once; called by SimulationController on pref change
- `var ... private set` pattern — externally read-only, internally mutable
- Debug mode branching: when enabled, time switches to manual, wind uses slider, season/moon/cloud/weather can be overridden
- Int-to-Float normalization: preferences 0–100 → 0.0–1.0 (or 0–300 → 0.0–3.0)
- Weather override resolution: maps UI strings to WeatherType enum with valueOf() fallback
- Default wind 0.05 (5%) not 0.5 — avoids "illusion of 50% speed" when no weather data

---

## SimulationClock.kt (169 lines)
**Purpose:** Time provider — supports real-time and manual modes.

**Approach:**
- **Strategy pattern:** Real system clock vs fixed manual time based on `useRealTime`
- Two cached `Calendar` instances reused to avoid per-frame allocations
- `getTimeSeconds()` wraps to 24-hour period (mod 86400000ms) to prevent float jitter in sin() with large numbers — critical for animation smoothness
- `unixToLocalHour()` converts Unix timestamp → local hour-of-day float
- **Asymmetric dawn/dusk:** Dawn 1.5h, Dusk 3h — models longer warm-light afternoon
- `timeMultiplier` clamped to [0.1, 10] for safety
- `Double` intermediate for absoluteTimeMs to avoid precision loss at ~1.7 billion seconds

---

## WeatherHistory.kt (293 lines)
**Purpose:** Tracks weather over time to derive accumulated scene values (snow depth, river level).

**Approach:**
- **Producer-Consumer:** `@Synchronized` on mutating methods + `@Volatile cachedSnapshot` for lock-free render-thread reads
- **Event Sourcing (light):** Full history of daily summaries persisted; levels recomputable via `recalculateLevels()`
- JSON serialization via SharedPreferences with compressed single-letter keys (d, at, mn, mx, etc.)
- **Snow Physics:** Accumulates at 0.06/hr heavy snow. Melts at 0.008/°C/hr above 0°C. Rain washes at 0.03/hr. Hail splits 50/50
- **River Physics:** Rises from rainfall (0.04/hr) + snowmelt (0.03 per unit). Drains naturally 0.012/hr. Extra evaporation 0.004/hr above 15°C
- `applyPhysicsStep()` shared by hourly updates and full-history replay
- Hourly deduplication: records at most once per hour using dayId + hour tracking
- Day rollover: `flushDay()` aggregates intra-day accumulators. History trimmed to 14 days
- Temperature estimation when sensor unavailable: snow→−2°C, rain→12°C, clear→15°C
- `todayMinTemp = Float.MAX_VALUE`, `todayMaxTemp = Float.MIN_VALUE` — sentinel pattern
