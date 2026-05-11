# Parallax Package Analysis

## ParallaxSensorManager.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | YES | Handles 5 concerns: (1) rotation vector parallax, (2) gyroscope parallax, (3) accelerometer shake detection, (4) roll wiggle detection, (5) gyro wiggle detection | Extract `ShakeDetector` class for shake/wiggle logic |
| KISS | MODERATE | 300+ lines of shake detection state variables, multiple detection algorithms | Extracting ShakeDetector simplifies the class |
| Separation of Concerns | YES | Parallax sensing and gesture detection are orthogonal features mixed together | ShakeDetector should be a separate collaborator |
| Testability | MODERATE | Hard to unit test shake thresholds independently from sensor management | Extraction enables isolated testing |
| Resource safety | OK | Properly registers/unregisters sensor listeners in start()/stop() | |
| Android lifecycle | OK | start()/stop() properly manage sensor registration; clearCallbacks() prevents leaks | |
| Threading | MINOR | Sensor callbacks come on sensor thread, listener callback goes to render thread — no synchronization on `accumulatedX/Y` | Acceptable: float writes are atomic on ARM/x86 |
| Naming | OK | Clear constant names with documentation | |
| Immutability | OK | Configuration is set via methods before start() | |
| DIP | OK | Listener interfaces for decoupling | |

## ParallaxCalculator.kt

| Principle | Violation | Reason | Action |
|-----------|-----------|--------|--------|
| SRP | OK | Single purpose: smoothing and offset calculation | |
| KISS | OK | Physics-based smoothing is appropriately complex | |
| Testability | OK | Pure math, no Android deps | |
| Threading | MINOR | `update()` called from render thread, `updateFromSensor()` from sensor thread — offset values may be read during write | Acceptable for float writes; offsets are inherently approximate |
| Immutability | MINOR | Mutable state (smoothed offsets) is appropriate for animated value | |
| Naming | OK | | |
