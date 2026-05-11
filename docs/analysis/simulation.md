# Simulation Package Analysis

## SimulationState.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Data class contains computation logic (getLightLevel, getAtmospherePhase, getShadowOffset, getCloudCount, cloudCoverToCloudCount) that should be in a separate calculator | Extract time/light calculations to a `LightCalculator` object |
| Immutability | OK | Data class is immutable — good |  |
| KISS | MINOR | 80+ constructor parameters make it hard to reason about | Consider grouping into sub-objects (WeatherData, LightingData, WindData) in a future refactor |
| DRY | YES | `getAtmospherePhase()` re-derives golden/blue hour durations already present as constructor params (`isGoldenHour`, `isBlueHour`) | Use the existing boolean fields or remove duplication |
| Testability | OK | Pure functions, no Android deps | |
| Naming | OK | Clear property names | |

## SimulationSettings.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MINOR | `fromSnapshot()` does both parsing AND business logic (debug mode branching) | Acceptable for a factory method, no action needed |
| DRY | YES | `getBoolean`, `getInt`, `getLong`, `getString` type coercion logic is duplicated between instance methods and `fromSnapshot()` helper lambdas | Extract shared coercion into private helpers |
| Immutability | OK | Immutable data class — good | |
| OCP | OK | New settings can be added without modifying existing behavior | |
| Testability | OK | Pure, no Android deps at runtime | |

## SimulationController.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Manages 4 separate concerns: (1) time smoothing, (2) wind gust physics, (3) wind direction smoothing, (4) state assembly | Extract `WindGustSimulator` class for gust logic |
| DIP | MINOR | Directly accesses `WeatherRepository` singleton and `PreferenceManager` | Already uses injected `context`; repository could be injected for testability |
| Testability | MODERATE | Hard to test gust physics in isolation because it's interleaved with state assembly | Extracting gust simulator enables unit testing |
| Threading | MINOR | `settings` is `@Volatile` but `getState()` reads many non-volatile fields (smoothedWindStrength, smoothedGust, etc.) that are mutated | Acceptable for single-writer (render thread) pattern, but document assumption |
| KISS | OK | Smoothing logic is complex but necessary for visual quality | |
| Fail-fast | OK | Coerces all outputs to valid ranges | |

## SimulationClock.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: time management | |
| Immutability | MINOR | Mutable class with setters — acceptable for stateful time source | |
| Threading | MINOR | Uses ThreadLocal for Calendar instances — good for thread safety | |
| Testability | MODERATE | `getTimeOfDayHours()` uses `System.currentTimeMillis()` directly — hard to test time-dependent behavior | Accept: clock is inherently time-dependent; tests use `setManualTime()` |
| KISS | OK | Simple and clear | |

## SimulationSettingsRepository.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: settings observation | |
| Resource safety | OK | Implements Closeable, cancels scope and job | |
| Coroutine correctness | OK | Uses SupervisorJob, callbackFlow with awaitClose | |
| DIP | OK | Takes Context, wraps SharedPreferences | |

## WeatherHistory.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MODERATE | Combines data recording, physics simulation, persistence (JSON), and snapshot caching | Acceptable due to tightly coupled domain logic |
| Threading | OK | All public methods synchronized + volatile snapshot | |
| Fail-fast | OK | Coerces values, handles NaN temperatures | |
| Error handling | MINOR | JSON parsing uses try-catch silently — acceptable for corrupt data recovery | |
| Testability | MODERATE | Uses `System.currentTimeMillis()` and SharedPreferences — hard to unit test time advancement | Could inject clock for testing, but low priority |
| Resource safety | OK | No leaks — uses SharedPreferences (singleton) | |
