# Utility Classes Analysis

## ColorMath.kt (27 lines)
**Purpose:** Platform-independent color utility for darkening ARGB colors (night shading).

**Approach:**
- `shadeRgb(argb, shadeStrength)`: Multiplies R/G/B by `1 − 0.65 × strength` (max strength keeps ~35% brightness)
- Adds subtle blue lift (`+10 × strength` to B channel) to prevent muddy gray nights — perceptual color-science technique
- Bitwise ARGB unpacking/repacking (no android.graphics.Color dependency)
- Alpha channel preserved unchanged; all values clamped to [0, 255]
- Pure Kotlin stdlib — no Android imports, unit-testable on JVM

---

## SeasonHelper.kt (213 lines)
**Purpose:** Centralized season detection with gradual blending transitions.

**Approach:**
- `TerrainSeason` enum: SPRING, SUMMER, AUTUMN, WINTER with `next()`/`prev()` cycle
- `SeasonBlend` data class: `(primarySeason, previousSeason, transitionProgress)` with computed `isTransitioning`, `primaryWeight`, `previousWeight`
- During first `transitionDays` (default 14) of each season, `SeasonBlend` interpolates from previous to current
- Winter wrapping handles Jan/Feb by rolling `seasonStartCal` back one year
- **Smoothstep easing:** `3t² − 2t³` for natural transition curves
- Debug mode can override DAY_OF_YEAR for seasonal visual testing
- Season boundaries: 1st of Mar/Jun/Sep/Dec — meteorological seasons
- `fromString()` accepts both "autumn" and "fall"
- Strategy via debug override: `getEffectiveSeasonBlend()` branches on debug flags
