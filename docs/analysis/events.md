# Event System Analysis

## RandomEvent.kt (114 lines) — Base Interface
**Purpose:** Contract for all animated events.

**Approach:**
- `EventLayer` enum: `FAR_SKY`, `MID_SKY`, `GROUND` for draw-order relative to terrain
- `HillHeightProvider` type alias: `(xRatio, depthFactor) -> Float` for terrain-following
- `HillInfo` data class: Y-ratio + actual `depthFactor`
- Absolute-time synchronization: `spawnTimeMs` + `durationMs` for deterministic progress
- Default implementations for `isActive()`, `getProgress()`, `update()`, `getCurrentYRatio()`
- Progress = `(currentTime - spawnTimeMs) / durationMs` gives 0.0–1.0+ ratio

---

## Ground Events

### SquirrelEvent.kt (152 lines)
- **Stop-and-go state machine:** `elapsedMs % (stopIntervalMs + stopDurationMs)` toggles running/paused
- Effective elapsed time subtracts accumulated stops for true movement distance
- Leg animation: `sin(t * 20f + phase)` for rapid running, 0 when stopped
- Bushy curved tail (quadratic Bezier), oval body, lighter belly, head, ear, eye, nose, cheek tuft, 4 animated legs
- Lazy color initialization for unit test compatibility
- Depth range 0.4–0.8

### DeerEvent.kt (193 lines)
- 20% fawn variant (0.6x scale, white spot markings)
- Grazing cycle: stops periodically, head lowers via `headLower = unit * 3f`
- Full anatomy: body, neck (quadratic Bezier), head, muzzle, ears, antlers (adults only facing right), legs with Bezier joints
- Distance-based alpha: `1f - distanceFactor * 0.4f`
- Fixed 15s duration

### FoxEvent.kt (136 lines)
- Detailed anatomy: bushy tail with cubic Bezier and white tip, body, belly, head, snout, triangular ears with pink inner ears, whiskers
- Fallback ground Y when no hill data
- Constant speed (`0.000025f`)

### RabbitEvent.kt (117 lines)
- **Hopping:** `sin(hopPhase).coerceAtLeast(0f)` — only positive half of sine for bounce-then-ground
- Front/back leg pairs alternate offset
- Composed entirely of ovals, rounded rectangles, circles, lines (no Path needed)
- Smaller than fox: base size 0.5–0.7

---

## Sky Events

### BirdFlockEvent.kt (107 lines)
- V-formation flock of 2–7 birds; count scales with distance
- Flap/glide cycle: 25% flapping, 75% gliding
- Stroke-only V-shapes (no fill) — minimal rendering footprint
- Parallax depth 0.25–0.50

### EagleEvent.kt (115 lines)
- Solo soaring raptor — no wing-flap (fixed outstretched pose)
- Complex Bezier wing paths with splayed primary feather tips
- Parallax depth 0.2–0.4

### OwlEvent.kt (188 lines)
- Night-only with glide-burst flap animation
- Custom `smoothstep01()` for position easing and flap envelope
- Eye glow brightens during flap bursts: `0.5f + 0.5f * envelope`
- Most anatomically detailed event: barrel body, facial disc, brow ridge, ear tufts, wing with dark flight feathers
- Banking rotation (-8° to +8°)

### BatEvent.kt (131 lines)
- Colony of 3–8 bats with independent chaotic flight
- Dual-frequency sine: `sin(freq * t) + sin(freq * 1.7 * t) * 0.5` for erratic paths
- Rapid wing beats at 25Hz
- Membrane wing shapes with angular Bezier paths
- Night-time counterpart to BirdFlockEvent

### AirplaneEvent.kt (124 lines)
- Swept-back wing Path polygons — no bitmaps
- LinearGradient contrail fading from transparent to semi-opaque white
- Distance-based scaling (0.6–1.0) modulates size, speed, contrail properties
- Ambient color blending at 30% factor

### HotAirBalloonEvent.kt (113 lines)
- Cubic Bézier envelope shape with dome tapering to narrow throat
- Stripe panel via matching Bézier curves
- Sinusoidal bobbing: `sin(t * 0.4f + bobPhase)`
- LOD optimization: rope lines only for closer balloons (distance < 0.7)

### ShootingStarEvent.kt (100 lines)
- FAR_SKY layer (behind mountains)
- Spawn delay (0.8–1.2s) before appearance
- Diagonal trajectory via angle (cos/sin at 23–40°)
- Dual-layer gradient trail: wider outer glow + narrower inner core
- Burn-up fade-out in final 20%: declining alpha + shrinking tail

### AlienEvent.kt (139 lines)
- Very rare night-only UFO with pulsing rim lights
- 6 evenly-distributed rim lights with color cycling via modular indexing
- Tractor beam with 3-stop vertical LinearGradient (only when `sin(phase * 0.5f) > 0.3f`)
- Gentle erratic movement via slow wobble frequencies

### SnowflakeEvent.kt (99 lines)
- 6-fold rotational symmetry: draw one arm, rotate canvas 6 times
- Fractal-like arms with sub-branches at 40–60% and 75% positions
- Stroke rendering with ROUND cap for delicate line-based arms
- Sinusoidal horizontal drift

### FireflyEvent.kt (80 lines)
- Cluster of 8–20 fireflies in foreground
- Flicker gating: only illuminate when `sin(t * speed * 3f + phase) > 0.2f`
- Dual-radius glow: bright core + larger dim glow (alpha/3)
- GROUND layer, depth 0.70–0.80

### ButterflyEvent.kt (129 lines)
- Cubic Bézier upper/lower wing lobes
- Asymmetric flutter via canvas.scale() for 3D flapping perspective
- Multi-frequency flight path: slow drift + medium wobble + secondary vertical wobble
- Wing spots scale with wing beat
- 4 color variants: Monarch Orange, Blue, Yellow, Pink

### DragonflyEvent.kt (141 lines)
- 4 independently animated translucent wings (front/rear pairs)
- Dart behavior: speed bursts when `sin(t * 0.3f) > 0.9f`
- Very fast wing beat at 40Hz
- Iridescent colors: Dodger Blue, Dark Turquoise, Lime Green
- Lazy color init for test compatibility

---

## Cross-Event Patterns
- All use absolute-time determinism (spawnTimeMs + progress) for preview/wallpaper sync
- All blend colors with `RenderConstants.blendWithAmbient()` for day/night
- Ground events follow terrain via HillHeightProvider; sky events ignore it
- 100% procedural vector drawing — zero bitmap assets
- `SimulationState` is imported but unused in all 16 event files
