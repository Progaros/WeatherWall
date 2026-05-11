# Rendering Layers Analysis

## TerrainLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MODERATE | Renders hills + manages tree/bush/animal integration + provides terrain query APIs + handles river/lake callbacks | Acceptable: "terrain layer" is one visual responsibility; sub-renderers are already extracted |
| Composition over Inheritance | OK | Delegates to TreeRenderer and BushRenderer — good use of composition | |
| Law of Demeter | MODERATE | Exposes `groundEventDrawer`, `riverDrawer`, `lakeDrawer` callbacks — layers reach across boundaries | Acceptable for scene-graph depth integration |
| DIP | MINOR | Creates TreeRenderer/BushRenderer directly — they're implementation details | No action needed |
| Testability | MODERATE | Rendering logic untestable without Canvas mock; but generation logic is testable | |
| Resource safety | OK | Reuses Paint, Path, drawEntryBuffer | |
| Threading | OK | Single-writer (render thread) pattern | |

## MountainLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: generate and draw mountains | |
| Immutability | MINOR | Mountain data stored in mutable lists, regenerated on resize | Acceptable for rendering layer |
| KISS | OK | | |
| DRY | MINOR | Shadow/lighting logic overlaps with TreeRenderer shadow logic | Low priority — different visual elements |
| Testability | OK | Generation logic can be tested independently | |

## CloudLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MODERATE | Handles cloud generation, animation, bitmap caching, draw, and settings | Acceptable: "cloud layer" is one visual concern |
| Resource safety | MODERATE | Creates Bitmap objects for cloud puffs — must manage lifecycle | Verify bitmaps are recycled in destroy() |
| KISS | OK | Complex visuals but well-structured | |
| DRY | MINOR | Cloud color calculation may overlap with ambient tint logic in RenderConstants | Low priority |

## SkyLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MODERATE | Renders sky gradient + stars + sun + moon + sky vignette | Acceptable: all are "sky background" elements |
| Resource safety | OK | Caches gradients, avoids per-frame allocations | |
| KISS | OK | | |
| Immutability | OK | Stars generated once with fixed seed | |

## WeatherLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | MODERATE | Handles rain + snow + hail + lightning + fog + storm clouds | These are all "weather effects" — single visual concern |
| Resource safety | OK | Pre-allocated particle pools — no runtime allocations | |
| DRY | MINOR | Particle reset/spawn logic repeated per weather type | Could extract `ParticleSpawner` but adds complexity |
| KISS | OK | Enum-based dispatch is straightforward | |
| Naming | MINOR | Internal `WeatherType` enum shadows app-level `WeatherType` — confusing | Rename internal enum or use `EffectType` consistently |

## RiverLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not fully read | — | Uses WaterColorUtil for shared logic — good | |
| Composition | OK | Delegates color logic to WaterColorUtil | |

## LakeLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not fully read | — | Uses WaterColorUtil for shared logic — good | |
| DRY | OK | Shares color logic with RiverLayer via WaterColorUtil | |

## VignetteLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not fully read | — | Simple overlay — likely clean | |

## DebugOverlayLayer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not fully read | — | Debug-only rendering | |

## SeasonalEvents.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| Not fully read | — | Manages seasonal effects (fireworks, rainbow, etc.) | |

## TreeRenderer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: draw trees of various types | |
| DRY | OK | Shares color/noise logic via VegetationUtil | |
| KISS | OK | Complex tree shapes but organized by tree type | |
| Immutability | OK | Uses HillInfo data class for noise params | |

## BushRenderer.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: draw bushes | |
| DRY | OK | Shares logic via VegetationUtil | |
| Composition | OK | Used by TerrainLayer via composition | |
