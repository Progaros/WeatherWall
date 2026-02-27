# Core App Files Analysis

## WeatherWallApp.kt (95 lines)
**Purpose:** Custom `Application` subclass — global crash handler, clipboard utility, singleton access.

**Approach:**
- Singleton pattern via `@Volatile` companion object, set in `onCreate()`
- Chain of Responsibility for crash handling: wraps the default `UncaughtExceptionHandler`, builds a structured crash log (thread, timestamp, stack trace, device info), stores in `lastCrashLog`, then re-throws to system handler
- Crash handler itself wrapped in `try/catch` to prevent recursive crashes
- `copyToClipboard()` marks clip data as sensitive on Android 13+ (`EXTRA_IS_SENSITIVE`)

**Key patterns:** Singleton, Chain of Responsibility, defensive crash handling
**Dependencies:** None — no project class dependencies

---

## MainActivity.kt (371 lines)
**Purpose:** Main settings activity with a live parallax preview surface, tabbed settings (ViewPager2 + TabLayout), and FAB for wallpaper application.

**Approach:**
- Observer pattern: `SharedPreferences.OnSharedPreferenceChangeListener` for live preview updates
- Preference reload throttled to 10/sec (`PREF_RELOAD_THROTTLE_MS = 100L`) to prevent slider drag flicker
- Dedicated `PreviewRenderThread` with fixed-timestep game loop at 30fps
- Render loop uses `lockHardwareCanvas()` with software fallback, real `deltaTimeMs` clamped to 1–200ms
- Preview pane resize via drag handle with snap-back threshold, persisted in dp
- FPS tracking logged every 5 seconds
- Sub-settings navigation replaces ViewPager with fragment container, manages back stack
- `isWallpaperApplied()` compares active wallpaper `ComponentName`; hides FAB when applied
- Thread is daemon so it won't prevent process exit
- `setZOrderMediaOverlay(true)` keeps UI above SurfaceView

**Key patterns:** Observer, Game Loop, Fragment Navigation, Adapter (ViewPager2)
**Dependencies:** ParallaxScene, ParallaxSensorManager, WeatherRepository, 4 settings fragments

---

## PreferenceDefaults.kt (117 lines)
**Purpose:** Single source of truth for all preference default values — prevents drift between XML `android:defaultValue` and Kotlin `getInt()`/`getString()` calls.

**Approach:**
- Kotlin `object` singleton with only `const val` properties
- Covers all categories: parallax, clouds, trees/terrain, weather, seasonal/events, visual, river, decorations, performance
- String values for `ListPreference` compatibility (`"5"`, `"30"`, `"wavy"`, etc.)
- Comments document valid ranges and serve as lightweight schema documentation
- Validated by `PreferenceDefaultsTest` for XML sync

**Key patterns:** Constants Object, Data Dictionary
**Dependencies:** Referenced by virtually every component that reads preferences

---

## DebugTracer.kt (155 lines)
**Purpose:** Lightweight toggleable performance tracing — collects function timing data, generates reports.

**Approach:**
- Singleton `object` with ring buffer (`arrayOfNulls<TraceEntry>(500)`) — modular write index, avoids O(n) list removals
- `traceStart(label)` / `traceEnd(label)` pairs using `System.nanoTime()` for high-res timing
- `@Volatile` on `enabled` for zero-overhead hot-path guard (`if (!enabled) return`)
- `startTimes` uses `Collections.synchronizedMap` for concurrent render thread access
- Ring buffer has dedicated `ringLock` object for `synchronized` blocks
- Report groups timing entries by label, computes count/total/avg/max ms, then chronological log
- `disableAndCopy(context)` one-call convenience to stop + clipboard + Toast

**Key patterns:** Singleton, Ring Buffer, Start/Stop Timing
**Dependencies:** Called from rendering hot paths; reports accessed from SystemSettingsFragment
