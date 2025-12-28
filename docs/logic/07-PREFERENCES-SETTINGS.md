# Preferences & Settings

## Overview

Settings are stored in `SharedPreferences` and loaded into member variables for performance. The app avoids reading preferences every frame.

## Preference Keys

### Visual Settings

| Key | Type | Default | Range | Description |
|-----|------|---------|-------|-------------|
| `pref_parallax_strength` | Int | 50 | 0-100 | Parallax intensity (50 = 1.0x) |
| `pref_tree_count` | Int | 20 | 0-100 | Number of trees |
| `pref_tree_size` | Int | 100 | 0-1000 | Tree scale (100 = 1.0x) |
| `pref_tree_segments` | Int | 3 | 1-5 | Tiers per tree |
| `pref_tree_color_variation` | Int | 30 | 0-100 | Color variation (%) |
| `pref_mountain_count` | Int | 3 | 0-10 | Number of mountains |
| `pref_mountain_width` | Int | 175 | 50-300 | Width scale (100 = 1.0x) |
| `pref_mountain_height` | Int | 100 | 10-1000 | Height scale |
| `pref_mountain_roughness` | Int | 50 | 0-100 | Jaggedness |
| `pref_hill_count` | Int | 3 | 0-25 | Rolling hill layers |
| `pref_hill_lower_start` | Int | 55 | 30-70 | Far hill Y position (%) |
| `pref_hill_upper_end` | Int | 75 | 60-90 | Near hill Y position (%) |

### Cloud Settings

| Key | Type | Default | Values | Description |
|-----|------|---------|--------|-------------|
| `pref_clouds_enabled` | Boolean | true | - | Master cloud toggle |
| `pref_cloud_density` | String | "auto" | none/light/medium/heavy/overcast/auto | Cloud amount |

### Weather Settings

| Key | Type | Default | Values | Description |
|-----|------|---------|--------|-------------|
| `pref_weather_enabled` | Boolean | true | - | Weather effects toggle |
| `pref_weather_type` | String | "auto" | auto/none/rain/snow/lightning/fog | Weather override |
| `pref_use_breezy_weather` | Boolean | true | - | Use Breezy Weather data |
| `pref_wind_strength` | Int | 50 | 0-100 | Manual wind speed |

### Day/Night Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `pref_daynight_enabled` | Boolean | true | Auto time-based colors |
| `pref_manual_time` | Int | 720 | Manual time (0-1439 minutes) |

### Seasonal Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `pref_seasonal_enabled` | Boolean | true | Holiday effects toggle |
| `pref_christmas_star_size` | Int | 100 | Star scale (50-200%) |
| `pref_christmas_star_trail` | Int | 100 | Trail length (0-200%) |

### Performance Settings

| Key | Type | Default | Range | Description |
|-----|------|---------|-------|-------------|
| `pref_max_fps` | String | "30" | 10-60 | Target FPS |

### Debug Settings

| Key | Type | Default | Values | Description |
|-----|------|---------|--------|-------------|
| `pref_debug_enabled` | Boolean | false | - | Debug mode toggle |
| `pref_debug_time` | String | "auto" | auto/night/dawn/day/dusk | Override time |
| `pref_debug_season` | String | "auto" | auto/christmas/newyear/none | Override season |
| `pref_debug_cloud_override` | String | "auto" | auto/none/light/medium/heavy/overcast | Override clouds |
| `pref_debug_wind_speed` | Int | 0 | 0-100 | Override wind |

## Weather Repository Keys

Written by `BreezyWeatherReceiver`:

| Key | Type | Description |
|-----|------|-------------|
| `weather_current_type` | String | WeatherType enum name |
| `weather_sunrise` | Long | Sunrise Unix timestamp (seconds) |
| `weather_sunset` | Long | Sunset Unix timestamp (seconds) |
| `weather_wind_speed` | Float | Wind speed in m/s |
| `weather_last_update` | Long | Last update timestamp (millis) |

## Settings Loading

### ParallaxScene.loadSettings()

Called on:
1. Initial creation
2. Visibility change
3. Preference change via listener

```kotlin
fun loadSettings() {
    // Convert slider values to usable ranges
    parallaxStrength = prefs.getInt("pref_parallax_strength", 50) / 50f
    treeScale = (prefs.getInt("pref_tree_size", 100) / 100f).coerceIn(0.1f, 10f)
    
    // Track previous values for regeneration triggers
    if (mountainCount != lastMountainCount) {
        lastMountainCount = mountainCount
        generateMountains()
    }
}
```

### Cloud Density Mapping

```kotlin
val (count, opacityMult) = when (cloudDensity) {
    "none" -> Pair(0, 1.0f)
    "light" -> Pair(8, 0.4f)
    "medium" -> Pair(15, 0.65f)
    "heavy" -> Pair(25, 0.9f)
    "overcast" -> Pair(40, 1.1f)
    "auto" -> Pair(12, 0.6f)
    else -> Pair(12, 0.6f)
}
```

## Live Update Listener

`WeatherWallpaper` listens for real-time Breezy Weather updates:

```kotlin
private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == "weather_current_type" || key == "weather_wind_speed") {
        loadPrefs()
        Log.d(TAG, "Live weather update received: $key")
    }
}

override fun onCreate(surfaceHolder: SurfaceHolder) {
    prefs.registerOnSharedPreferenceChangeListener(prefsListener)
}

override fun onDestroy() {
    prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
}
```

## Value Coercion

All values are safely bounded:

```kotlin
// Prevent divide-by-zero
treeScale = if (rawTreeSize <= 0) 0.1f else {
    (rawTreeSize.toFloat() / 100f).coerceIn(0.1f, 10f)
}

// Ensure proper ordering
if (hillLowerStart >= hillUpperEnd) {
    hillUpperEnd = (hillLowerStart + 0.10f).coerceAtMost(0.90f)
}
```

## Issues Identified

1. **No preference change listener for visual settings**: Only weather keys trigger live reload
2. **Duplicate preference reading**: Some settings read in both `loadSettings()` and render loop
3. **String parsing**: FPS stored as String requiring `toIntOrNull()` fallback

