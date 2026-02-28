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
    const val PARALLAX_STRENGTH = 16  // 1-100%, log scale (subtle but responsive)
    const val PARALLAX_MAX_OFFSET = 40  // % of screen height (full range for immersive depth)
    const val PARALLAX_RETURN_SPEED = 47  // 0-100 (faster re-centering)
    const val PARALLAX_SMOOTHING = 1  // 1-100 (very responsive, minimal lag)
    const val PARALLAX_DEPTH = 70  // 0-100 (strong 3D layer separation)
    const val PARALLAX_PERSPECTIVE = 100  // 0-100
    const val PARALLAX_ASPECT_RATIO = 100  // 50-150
    const val PARALLAX_INVERT_X = false
    const val PARALLAX_INVERT_Y = true  // natural Y-axis for most devices
    const val PARALLAX_SENSOR_MODE = "gyroscope"  // smoother sensor input
    const val PARALLAX_EDGE_EASING = 10  // 0-100, slight slowdown near edges
    const val PARALLAX_RETURN_UNLOCKED = true  // true=fast return regardless of position
    
    // === Cloud Settings ===
    const val CLOUDS_ENABLED = true
    const val CLOUD_SPEED = 45  // 0-100, visible cloud movement
    const val CLOUD_OPACITY = 60  // 10-100, clearly visible clouds
    const val CLOUD_STRETCH = 300  // stretch factor
    const val CLOUD_DENSITY = 100  // 50-300
    const val CLOUD_RENDER_MODE = "cached"
    
    // === Tree/Terrain Settings ===
    const val TREE_DENSITY = 50  // 0-100
    const val TREE_SIZE_RANDOMNESS = 50  // 0-100 (natural variation)
    const val TREE_COLOR_VARIATION = 50  // 0-100 (moderate diversity)
    const val TREE_SWAY_SPEED = 100  // 0-300
    const val HILL_AMPLITUDE = 354  // 0-1000 (tall rolling hills)
    const val HILL_FREQUENCY = 170  // 25-200 (denser hill pattern)
    
    // === Mountain Settings ===
    const val MOUNTAIN_BOTTOM_OFFSET = 0  // -30 to 30
    
    // === Weather Settings ===
    const val WEATHER_ENABLED = true
    const val WEATHER_LAYERS = 3  // 1-5
    const val WEATHER_LAYER_SEPARATION = 30  // 0-100
    const val WEATHER_LAYER_DEPTH = 20  // 0-100
    const val WIND_MULTIPLIER = 200  // 0-300, wind effect strength %
    
    // === Seasonal Settings ===
    const val SEASONAL_ENABLED = true
    const val SEASON_TRANSITION_DAYS = 14  // 0-30, gradual season blending
    const val CHRISTMAS_ENABLED = true
    const val CHRISTMAS_INTERVAL = 1  // ±days around Dec 25 (tight window)
    const val NEWYEAR_ENABLED = true
    const val NEWYEAR_INTERVAL = 1  // ±days around Jan 1 (tight window)
    const val CN_NEWYEAR_ENABLED = false  // opt-in for Chinese New Year
    const val CN_NEWYEAR_INTERVAL = 0  // only on the day itself
    const val ST_PATRICKS_ENABLED = true
    const val ST_PATRICKS_INTERVAL = 0  // only on March 17
    const val FIREWORK_FREQUENCY = 45  // 0-100
    const val FIREWORK_AMOUNT = 100  // 10-200, particles per explosion
    const val SHAKE_FIREWORKS_ENABLED = true
    const val SHAKE_SENSITIVITY = 16  // 0-100 (harder shake needed, avoids accidental)
    const val SHAKE_DELAY_MS = 500  // 0-5000ms, short delay after screen on
    
    // === Visual Settings ===
    const val NIGHT_SHADE_STRENGTH = 35  // 0-100
    const val VIGNETTE_ENABLED = true
    const val VIGNETTE_STRENGTH = 30  // 0-100
    const val VIGNETTE_SIZE = 60  // 20-100
    
    // === River Settings ===
    const val RIVER_ENABLED = false  // Off by default (opt-in)
    const val RIVER_STYLE = "wavy"   // "flat", "wavy", "reflective"
    const val RIVER_WIDTH = 40       // 0-100 (% of foreground terrain width)
    const val RIVER_OPACITY = 70     // 0-100
    const val RIVER_SPEED = 50       // 0-100 flow animation speed
    
    // === Seasonal Decoration Settings ===
    const val PINECONES_ENABLED = true
    const val PINECONE_DENSITY = 30  // 0-100
    const val FALLEN_LEAVES_ENABLED = true
    const val FALLEN_LEAVES_DENSITY = 50  // 0-100
    const val MUSHROOMS_ENABLED = true
    const val MUSHROOM_DENSITY = 20  // 0-100
    const val BERRIES_ENABLED = true
    const val BERRY_DENSITY = 30     // 0-100
    const val FROST_ENABLED = true   // Winter frost overlay
    
    // === Bush Settings ===
    const val BUSHES_ENABLED = true
    const val BUSH_DENSITY = 30      // 0-100 percentage
    const val BUSH_SIZE = 100        // 50-200 percentage scale
    
    // === Tree Type Settings ===
    const val LEAF_TREE_RATIO = 50   // 0-100: percentage of deciduous trees (0=all needle, 100=all leaf)
    
    // === Debug Settings ===
    const val DEBUG_OVERLAY_ENABLED = false
    const val DEBUG_FRAME_TIMING = false
    
    // === Idle Mode Settings ===
    const val IDLE_ENABLED = true
    const val IDLE_TIMEOUT = "5"  // seconds (as String for ListPreference)
    const val IDLE_FPS = 20  // 5-20 (higher idle FPS for smoother idle)
    const val MAX_FPS = "30" // 30, 60, 90, 120 (as String for ListPreference)
}
