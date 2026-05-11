# SOLID & Extended Principles — Fix Summary

## Priority Fixes (Worth implementing)

### 1. Extract `WindGustSimulator` from SimulationController (SRP)
**Violation**: SimulationController.kt mixes wind gust physics with state assembly.
**Action**: Extract `calculateWindGust`, `smoothWindStrength`, `smoothWindDirection` into a `WindSimulator` class.
**Benefit**: Independently testable wind physics, cleaner controller.

### 2. Extract `ShakeDetector` from ParallaxSensorManager (SRP, Separation of Concerns)
**Violation**: ParallaxSensorManager handles both parallax input AND shake/wiggle gesture detection.
**Action**: Extract shake detection into `ShakeDetector` class with its own state.
**Benefit**: ~100 lines of state variables and detection logic become a focused, testable class.

### 3. Deduplicate draw-event iteration in RandomEventLayer (DRY)
**Violation**: `drawEventsByLayer`, `drawGroundEventsInRange`, `drawGroundEventsBeforeDepth`, `drawGroundEventsAfterDepth` repeat the same try-catch iteration pattern.
**Action**: Extract `inline fun forEachMatchingEvent(predicate, action)` helper.
**Benefit**: One place to maintain error handling and iteration logic.

### 4. Remove DRY violation in SimulationSettings type coercion (DRY)
**Violation**: `getBoolean`/`getInt`/`getLong`/`getString` instance methods duplicate the `snapBoolean`/`snapInt`/`snapString` lambdas in `fromSnapshot()`.
**Action**: Make companion methods delegate to instance methods (or vice versa).
**Benefit**: Single coercion logic path.

### 5. Cache LinearGradient in events that create per-frame (Resource safety)
**Violation**: AirplaneEvent, ShootingStarEvent, HotAirBalloonEvent, FireflyEvent create new Shader objects every frame.
**Action**: Cache gradient when parameters haven't changed, or use pre-computed color arrays.
**Benefit**: Reduces GC pressure on render thread (60fps × gradient allocations).

### 6. Remove atmosphere phase duplication in SimulationState (DRY)
**Violation**: `getAtmospherePhase()` re-derives golden/blue hour logic that's already computed as `isGoldenHour`/`isBlueHour` constructor params.
**Action**: Rewrite `getAtmospherePhase()` to use existing booleans.
**Benefit**: Single source of truth for time-of-day categorization.

## Low Priority (Acknowledged but not worth the churn)

| Issue | Why Skip |
|-------|----------|
| SimulationState has 80+ params | Grouping into sub-objects would break all call sites with minimal benefit |
| WeatherRepository is a singleton | Android pattern; mocking via Robolectric if needed |
| RenderConstants mixes constants + utils | Object is well-organized with sections; splitting adds import noise |
| ParallaxScene creates layers directly | Standard Android rendering pattern — no need for DI framework |
| Events don't use `withParallax` extension | Minor — each event's draw is self-contained and readable |
| WeatherLayer internal WeatherType shadows app-level | Already has `EffectType` — just needs deprecated annotation cleanup |

## Architecture Strengths (No action needed)

- **Immutable state**: SimulationState and SimulationSettings are proper immutable snapshots
- **Composition over inheritance**: TerrainLayer delegates to TreeRenderer/BushRenderer
- **Interface segregation**: SceneLayer is minimal; RandomEvent uses default methods
- **Open/Closed**: New events, layers, weather types can be added without modification
- **Fail-fast**: Values coerced at boundaries throughout
- **Thread safety**: Volatile + immutable list pattern for cross-thread event access
- **Android lifecycle**: Proper cleanup in onDestroy, start/stop sensor management
- **Coroutine safety**: SupervisorJob, proper cancellation, callbackFlow with awaitClose
- **Deterministic layout**: DeterministicLayout + absolute time ensures preview/wallpaper sync
