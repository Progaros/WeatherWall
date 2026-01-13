package info.olli.weatherlwp

/**
 * Single source of truth for all preference default values.
 * 
 * WHY THIS EXISTS:
 * - Android preference XML files (preferences.xml, pref_effects.xml, etc.) require
 *   hardcoded android:defaultValue attributes
 * - Kotlin code that reads preferences also needs defaults in getInt()/getString() calls
 * - Having the same value in multiple places leads to drift and bugs
 * 
 * USAGE:
 * 1. Always use these constants in Kotlin when reading preferences:
 *    ```kotlin
 *    val value = prefs.getInt("pref_cloud_speed", PreferenceDefaults.CLOUD_SPEED)
 *    ```
 * 
 * 2. When adding new preferences:
 *    a. Add the default here first
 *    b. Use the constant in Kotlin code
 *    c. Copy the value to XML files (android:defaultValue="...")
 *    d. The PreferenceDefaultsTest will catch if they drift apart
 * 
 * NOTE: Android XML doesn't support integer resource references for SeekBarPreference
 * defaultValue, so we must keep XML defaults in sync manually. The test ensures this.
 */
object PreferenceDefaults {
    
    // === Parallax Settings ===
    const val PARALLAX_ENABLED = true
    const val PARALLAX_STRENGTH = 25  // 1-100%, log scale
    const val PARALLAX_MAX_OFFSET = 20  // % of screen height (increased from 15)
    const val PARALLAX_RETURN_SPEED = 15  // 0-100
    const val PARALLAX_SMOOTHING = 30  // 1-100
    const val PARALLAX_DEPTH = 33  // 0-100
    const val PARALLAX_PERSPECTIVE = 100  // 0-100
    const val PARALLAX_ASPECT_RATIO = 100  // 50-150
    const val PARALLAX_INVERT_X = false
    const val PARALLAX_INVERT_Y = false
    const val PARALLAX_SENSOR_MODE = "rotation_vector"
    const val PARALLAX_EDGE_EASING = 0  // 0-100, 0=linear, 100=extreme quadratic
    const val PARALLAX_RETURN_UNLOCKED = true  // true=fast return regardless of position
    
    // === Cloud Settings ===
    const val CLOUDS_ENABLED = true
    const val CLOUD_SPEED = 5  // 0-100, where 50=normal
    const val CLOUD_OPACITY = 40  // 10-100
    const val CLOUD_STRETCH = 300  // stretch factor
    const val CLOUD_DENSITY = 100  // 50-300
    const val CLOUD_RENDER_MODE = "cached"
    
    // === Tree/Terrain Settings ===
    const val TREE_DENSITY = 50  // 0-100
    const val TREE_SIZE_RANDOMNESS = 30  // 0-100
    const val TREE_COLOR_VARIATION = 100  // 0-100
    const val TREE_SWAY_SPEED = 100  // 0-300
    const val HILL_AMPLITUDE = 100  // 0-500
    const val HILL_FREQUENCY = 100  // 50-200
    
    // === Mountain Settings ===
    const val MOUNTAIN_BOTTOM_OFFSET = 0  // -30 to 30
    
    // === Weather Settings ===
    const val WEATHER_ENABLED = true
    const val WEATHER_LAYERS = 3  // 1-5
    const val WEATHER_LAYER_SEPARATION = 30  // 0-100
    const val WEATHER_LAYER_DEPTH = 20  // 0-100
    
    // === Seasonal Settings ===
    const val SEASONAL_ENABLED = true
    const val SHAKE_FIREWORKS_ENABLED = true
    const val SHAKE_SENSITIVITY = 50  // 0-100
    const val SHAKE_DELAY_MS = 500  // 0-2000ms, delay after screen on before shake triggers
    
    // === Visual Settings ===
    const val NIGHT_SHADE_STRENGTH = 35  // 0-100
    const val VIGNETTE_ENABLED = true
    const val VIGNETTE_STRENGTH = 30  // 0-100
    const val VIGNETTE_SIZE = 60  // 20-100
    
    // === Debug Settings ===
    const val DEBUG_OVERLAY_ENABLED = false
    const val DEBUG_FRAME_TIMING = false
    
    // === Idle Mode Settings ===
    const val IDLE_ENABLED = true
    const val IDLE_TIMEOUT = "5"  // seconds (as String for ListPreference)
    const val IDLE_FPS = 10  // 5-20
    const val MAX_FPS = "30" // 30, 60, 90, 120 (as String for ListPreference)
}
