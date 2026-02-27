# Parallax System Analysis

## ParallaxCalculator.kt (278 lines)
**Purpose:** Computes per-depth-layer displacement from device tilt for multi-layer parallax.

**Approach:**
- **Lock-free producer-consumer:** `AtomicReference<TiltPair>` for sensor thread → render thread handoff
- **tan() perspective curve:** Maps linear tilt → nonlinear displacement mimicking looking through a window. Precomputed `TAN_NORMALIZER`
- **Circular clamping:** Target position clamped to unit ellipse rather than rectangle, preventing diagonal "corner stretching"
- **Blended lerp + return-to-center:** `returnBlend` smoothly mixes tilt-following with center-return based on tilt magnitude — no abrupt mode switch
- **Edge easing:** Power-curve attenuation near boundaries. Only applied when moving away from center; inward movement unimpeded
- **Delta-time normalization:** `deltaTimeMs / 16f` scales lerp rates for frame-rate independence
- **Strategy-like depth:** `movementMultiplier(depthFactor)` blends foreground-heavy vs background-heavy models via `perspective` parameter
- Snap-to-target threshold (0.1px) prevents micro-oscillations
- `scaleFactor` clamped to [0.1, 2.0] for preview thumbnails
- Pure Kotlin — no Android dependencies, unit-testable on JVM

---

## ParallaxSensorManager.kt (588 lines)
**Purpose:** Hardware sensor lifecycle + raw sensor data → normalized tilt (-1..1). Also implements shake/wiggle gesture detection.

**Approach:**
- **Strategy pattern:** `ParallaxMode` enum selects ROTATION_VECTOR (delta accumulation) or GYROSCOPE (direct integration)
- **Observer:** Listener/ShakeListener callbacks decouple sensor processing from consumers
- **Rotation vector mode:** `SensorManager.getRotationMatrixFromVector` → `remapCoordinateSystem` → `getOrientation`. Delta-based accumulation clamped to ±1. Handles angle wraparound (±π) and display rotation
- **Gyroscope mode:** 3-sample moving average → angular velocity integration `angle += avgRate × dt × 1e-8 × 6 × DEG_TO_RAD`. Clamped to ±18°
- **Dual shake detection:**
  1. Accelerometer gForce with debounce and cooldown
  2. Gyro wiggle: stable-sign detection requiring `GYRO_SIGN_STABLE_SAMPLES` consecutive readings before counting direction change, plus 90ms minimum inter-change time to reject tremor
- **Pose gain:** Fades tilt sensitivity to zero as device approaches vertical (gimbal-lock region)
- **Hysteresis for isScreenDown:** Separate on/off/freeze thresholds prevent flip-flopping
- **Auto-return decay:** Exponential decay only when motion magnitude < 0.002 — preserves zero-latency during active tilting
- **Sensor warmup:** Discards first 30 events after `start()` to drain stale OS-queued events, preventing false shake on unlock
- `Averager` class: internal circular-buffer moving average with pre-allocated `FloatArray`
- Configurable shake sensitivity remaps a single 0–1 float to 6+ underlying thresholds via lerp
