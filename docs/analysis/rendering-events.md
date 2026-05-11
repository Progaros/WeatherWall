# Rendering Events Analysis

## RandomEvent.kt (Interface)

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| ISP | OK | Interface has sensible defaults, implementations only override what they need | |
| OCP | OK | New events can be added without modifying interface | |
| Naming | OK | Clear names | |
| Design | OK | Clean contract with absolute-time-based synchronization | |

## EventTraversal.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Shared path calculation utility | |
| DRY | OK | Centralizes horizontal path math for all traversing events | |
| KISS | OK | | |
| Testability | OK | Pure functions | |

## RandomEventLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Manages: event lifecycle, spawning logic, draw-layer routing, Y-sorting, debug spawn, settings | Could extract `EventSpawner` for spawn logic |
| Resource safety | MODERATE | `groundDrawCanvas`, `groundDrawState`, `groundDrawParallaxCalculator` stored as fields for callback-based drawing — nulled after use | Already mitigated with null clearing; add comment |
| DRY | YES | `drawEventsByLayer`, `drawGroundEventsInRange`, `drawGroundEventsBeforeDepth`, `drawGroundEventsAfterDepth` repeat the same event-iteration + try-catch pattern | Extract `drawFilteredEvents(predicate)` helper |
| OCP | OK | New event types added by adding to `spawnRandomEvent()` | |
| KISS | MINOR | Object pool pattern (GroundEventDrawEntryImpl) adds complexity but needed for perf | |
| Testability | MODERATE | Spawning logic testable; draw logic requires Canvas | |
| Threading | OK | Volatile + immutable list pattern for thread-safe events | |

## Individual Event Classes (AirplaneEvent, BirdFlockEvent, etc.)

All 19 event classes follow the same pattern. Common analysis:

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Each event handles its own animation and drawing | |
| LSP | OK | All correctly implement RandomEvent interface | |
| DRY | MODERATE | Many events repeat: (1) LinearGradient creation in draw() (per-frame allocation), (2) parallax offset save/translate/restore pattern, (3) ambient color blending | Extract `EventDrawHelper` for common patterns; use `Canvas.withParallax()` extension already in EventTraversal |
| Resource safety | YES | `LinearGradient` created per-frame in `draw()` for contrails/glows — allocates on every frame | Cache gradient or use pre-computed color stops |
| KISS | OK | Individual events are straightforward | |
| Immutability | OK | Events are created with fixed params and don't mutate path data | |
| Composition | OK | Uses EventTraversal for path calculation | |
| Naming | OK | | |
| Fail-fast | OK | Progress coerced to valid ranges | |

### Specific per-event notes:
- **AirplaneEvent**: Creates `LinearGradient` per frame for contrail — should cache
- **ShootingStarEvent**: Same gradient issue for trail
- **HotAirBalloonEvent**: Multiple gradient allocations per frame
- **FireflyEvent**: Creates RadialGradient per frame for glow
- **All ground events** (Rabbit, Fox, Deer, Squirrel, Duck, Frog): Correctly use HillHeightProvider for terrain following
- **All traversing events**: Correctly use EventTraversal.randomHorizontalPath
