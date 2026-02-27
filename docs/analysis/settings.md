# Settings System Analysis

## Architecture Overview
Settings use a hierarchical fragment structure:
- Root → Scene/Effects/Events/System tabs
- Each tab → sub-screens for specific categories
- 14 stub fragments (no logic) aggregated in SubSettingsFragments.kt
- 3 custom preference widgets for specialized UI needs

## RootSettingsFragment.kt (22 lines)
Pure XML delegation. Loads `pref_root`. Zero logic.

## SceneSettingsFragment.kt (15 lines)
Pure XML delegation. Loads `pref_scene`. Zero logic.

## EffectsSettingsFragment.kt (43 lines)
- **One-shot preference migration:** Maps old parallax strength scales (0–50 or 0–5) to new 1–100% scale
- Migration guard via `_migrated_v178` boolean flag
- Uses `coerceIn(1, 100)` for safe clamping

## EventsSettingsFragment.kt (37 lines)
- **SharedPreferences as IPC:** `spawnRandomEvent()` writes `System.currentTimeMillis()` to trigger keys for both preview and wallpaper
- Uses `commit()` (synchronous) to ensure wallpaper reads trigger immediately

## EventsChooseFragment.kt (57 lines)
- Maps 16 event types to spawn button handlers
- Each `SpawnSwitchPreference` gets a lambda via `onSpawnClickListener`
- Uses specific event preferences separate from random spawn

## SystemSettingsFragment.kt (42 lines)
- **Type conversion migration:** Old SeekBar idle timeout (Int) → new ListPreference (String)
- Finds closest valid value from `[5, 10, 30, 60, 120, 300]` using `minByOrNull { abs(it - intValue) }`
- Migration runs before `setPreferencesFromResource` to avoid crashes during inflation

## SystemAboutFragment.kt (120 lines)
- Version click → clipboard copy
- Breezy Weather connectivity check: data freshness + weather type + wind
- Weather data export: raw JSON pretty-printed via `JSONObject.toString(2)`
- Debug logging toggle: enables/disables DebugTracer; on disable copies trace to clipboard
- Settings export via SettingsExporter
- Wallpaper preview launch via `ACTION_CHANGE_LIVE_WALLPAPER`

## SubSettingsFragments.kt (103 lines)
14 stub fragment classes in one file:
- Scene: Mountains, Hills, Trees
- Effects: Sky, Parallax, Clouds, Weather/Shadows, Seasonal, Vignette, River
- Events: Seasonal, Shake
- System: Idle, Simulation

Convention-based: fragments with custom logic get separate files.

## SettingsExporter.kt (71 lines)
- Singleton utility exporting all SharedPreferences to human-readable clipboard format
- Type-annotated formatting (Bool, Int, Long, Float, String, Set)
- Entries sorted alphabetically; designed to help update PreferenceDefaults
- `internal` visibility on `formatEntry()` for test access

## SpawnSwitchPreference.kt (93 lines)
- **Composite Widget:** Switch toggle + inline spawn (dice) button + animated EventPreviewView in one preference row
- RecyclerView safety: removes `OnCheckedChangeListener` before setting `isChecked`, re-adds after
- Spawn button always enabled regardless of switch state
- Maps 16 preference keys to EventType enum values

## DebugOverlayPreference.kt (142 lines)
- **Split Touch Zone:** Uses MotionEvent.x to route taps — switch area (right) vs config area (left)
- `getLocationOnScreen()` computes divider X offset
- AlertDialog with `setMultiChoiceItems()` from DebugItem.entries
- `Handler(Looper.getMainLooper()).post { notifyChanged() }` defers RecyclerView notifications

## EventPreviewView.kt (636 lines)
- Custom View rendering 16 animated event previews using Canvas 2D drawing
- Runnable animation loop via `postDelayed(this, 16L)` for ~60fps
- Background color cycling via sin() wave (8-second sky blue ↔ dark night)
- All 16 animals/events are pure procedural art — no bitmap resources
- Attaches/detaches animator in onAttachedToWindow/onDetachedFromWindow to prevent leaks
- Relative sizing unit `unit = size * 0.15f` scales to any view size

## prefs/SettingsFragment.kt (190 lines)
**Legacy flat settings fragment.** Duplicates functionality now in settings/ package:
- Easter egg: 5 version taps → random shake hint
- BreezyWeather 6-package detection with 3-tier feedback
- Debug log export: device info + preferences + logcat via `Runtime.getRuntime().exec()`
- Same spawn trigger mechanism

## prefs/TimeSeekBarPreference.kt (55 lines)
- Custom SeekBarPreference displaying value as HH:MM time format
- `formatMinutesToTime()` converts minutes-since-midnight (0–1439) to HH:MM
- Hooks into both initial bind and drag-change listeners
