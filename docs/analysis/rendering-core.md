# Rendering Core Analysis

## ParallaxScene.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Handles: layer coordination, manual spawn trigger processing, settings reload/distribution, sensor callback routing, frame timing diagnostics | Extract `ManualSpawnTriggerProcessor` for trigger logic |
| Resource/Memory safety | YES | `lastCanvas` and `lastState` stored as mutable class fields for callback access during draw; risk of holding stale references across frames | Already nulled at end of render() — acceptable, add comment |
| Law of Demeter | MODERATE | Reaches into `randomEventLayer`, `terrainLayer`, `seasonalEvents` for specific draw methods (drawFarSkyEvents, drawBehindMountains, etc.) during render() | Acceptable for scene-graph pattern — orchestrator naturally coordinates layers |
| DIP | MINOR | Creates all layers directly (`SkyLayer(context)`, etc.) — no injection | Acceptable for Android rendering — layers are implementation details, not swappable |
| KISS | MODERATE | `render()` method is 80+ lines with complex draw-order logic (comments explain well though) | Accept as is — draw order IS complex and comments help |
| Threading | MINOR | `currentSettings` is `@Volatile` but `processManualSpawnTriggers()` reads multiple keys non-atomically | Acceptable: single-writer on render thread, settings object is immutable snapshot |
| Testability | MODERATE | Tightly coupled to Android Canvas — untestable without instrumentation | Accept: rendering code is inherently visual; tested via PIT/coverage on logic |
| Naming | OK | Clear class and method names | |

## SceneLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| ISP | OK | Interface is minimal and focused | |
| OCP | OK | New layers can be added without modifying interface | |
| Naming | OK | | |
| Design | OK | Clean abstraction | |

## RenderConstants.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Combines: (1) constants, (2) coordinate math functions, (3) color interpolation utilities, (4) ambient tint computation, (5) ThreadLocal HSV arrays | Split color utility functions to a `RenderColorUtil` object |
| KISS | OK | Individual functions are clear | |
| DRY | MINOR | `lerpColor` and `lerpColorHSV` both exist — usage is context-appropriate | No action — different color spaces for different needs |
| Testability | OK | All functions are pure (except lazy Color.parseColor) | |
| Immutability | OK | Object with val constants and pure functions | |
| Thread safety | OK | Uses ThreadLocal for HSV arrays | |

## WorldViewportTransform.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not read | — | Likely a small coordinate helper | Low priority |

## ViewportCulling.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not read | — | Likely culling utility | Low priority |

## CanvasHelper.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not read | — | Canvas extension utilities | Low priority |

## DeterministicLayout.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: deterministic seed generation | |
| KISS | OK | Simple hash mixing | |
| Testability | OK | Pure function | |

## VegetationUtil.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MINOR | Combines noise functions + color utilities + season weight | Acceptable: all serve "vegetation terrain" domain |
| DRY | OK | Shared by TreeRenderer and BushRenderer — eliminates duplication | |
| Testability | OK | Pure functions, no Android deps | |
| KISS | OK | | |
