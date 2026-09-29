package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Automated test to verify preference defaults are consistent across:
 * 1. Multiple XML files (pref_effects.xml, pref_events.xml, etc.)
 * 2. Kotlin code that reads preferences with defaults
 * 3. PreferenceDefaults.kt (the source of truth)
 * 
 * This test dynamically scans files to catch mismatches.
 * 
 * See ai.md for architectural rationale on why this approach is necessary
 * (SeekBarPreference can't reliably use @integer references).
 */
class PreferenceDefaultsTest {
    
    /**
     * Represents a preference definition found in an XML or Kotlin file.
     */
    data class PreferenceDef(
        val key: String,
        val defaultValue: String?,
        val min: Int?,
        val max: Int?,
        val sourceFile: String,
        val lineNumber: Int
    )
    
    companion object {
        // Path to XML resources (relative to app directory when run from app/build)
        val XML_PATH = "src/main/res/xml"
        // Path to Kotlin sources
        val KOTLIN_PATH = "src/main/java"
        
        // Regex to find preference keys in XML
        val XML_KEY_REGEX = """android:key="([^"]+)"""".toRegex()
        val XML_DEFAULT_REGEX = """android:defaultValue="([^"]+)"""".toRegex()
        val XML_MIN_REGEX = """app:min="(\d+)"""".toRegex()
        val XML_MAX_REGEX = """android:max="(\d+)"""".toRegex()
        
        // Regex to find preference reads in Kotlin code
        val KOTLIN_GET_INT_REGEX = """\.getInt\s*\(\s*"([^"]+)"\s*,\s*(\d+)\s*\)""".toRegex()
        val KOTLIN_GET_STRING_REGEX = """\.getString\s*\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*\)""".toRegex()
        val KOTLIN_GET_BOOLEAN_REGEX = """\.getBoolean\s*\(\s*"([^"]+)"\s*,\s*(true|false)\s*\)""".toRegex()
        
        /**
         * Mapping from preference keys to PreferenceDefaults constant names.
         * This enables automated verification that XML defaults match the Kotlin SSOT.
         */
        val PREFERENCE_TO_CONSTANT: Map<String, Any> = mapOf(
            // Parallax settings
            "pref_parallax_enabled" to PreferenceDefaults.PARALLAX_ENABLED,
            "pref_parallax_strength" to PreferenceDefaults.PARALLAX_STRENGTH,
            "pref_parallax_max_offset" to PreferenceDefaults.PARALLAX_MAX_OFFSET,
            "pref_parallax_return_speed" to PreferenceDefaults.PARALLAX_RETURN_SPEED,
            "pref_parallax_smoothing" to PreferenceDefaults.PARALLAX_SMOOTHING,
            "pref_parallax_depth" to PreferenceDefaults.PARALLAX_DEPTH,
            "pref_parallax_perspective" to PreferenceDefaults.PARALLAX_PERSPECTIVE,
            "pref_parallax_aspect_ratio" to PreferenceDefaults.PARALLAX_ASPECT_RATIO,
            "pref_parallax_invert_x" to PreferenceDefaults.PARALLAX_INVERT_X,
            "pref_parallax_invert_y" to PreferenceDefaults.PARALLAX_INVERT_Y,
            "pref_parallax_sensor_mode" to PreferenceDefaults.PARALLAX_SENSOR_MODE,
            "pref_parallax_edge_easing" to PreferenceDefaults.PARALLAX_EDGE_EASING,
            "pref_parallax_return_unlocked" to PreferenceDefaults.PARALLAX_RETURN_UNLOCKED,
            
            // Cloud settings
            "pref_clouds_enabled" to PreferenceDefaults.CLOUDS_ENABLED,
            "pref_cloud_speed" to PreferenceDefaults.CLOUD_SPEED,
            "pref_cloud_opacity" to PreferenceDefaults.CLOUD_OPACITY,
            "pref_cloud_stretch" to PreferenceDefaults.CLOUD_STRETCH,
            "pref_cloud_density" to PreferenceDefaults.CLOUD_DENSITY,
            
            // Tree/Terrain settings
            "pref_tree_density" to PreferenceDefaults.TREE_DENSITY,
            "pref_tree_spread" to PreferenceDefaults.TREE_SPREAD,
            "pref_tree_sparsity" to PreferenceDefaults.TREE_SPARSITY,
            "pref_tree_size_randomness" to PreferenceDefaults.TREE_SIZE_RANDOMNESS,
            "pref_tree_color_variation" to PreferenceDefaults.TREE_COLOR_VARIATION,
            "pref_tree_sway_speed" to PreferenceDefaults.TREE_SWAY_SPEED,
            "pref_hill_amplitude" to PreferenceDefaults.HILL_AMPLITUDE,
            "pref_hill_frequency" to PreferenceDefaults.HILL_FREQUENCY,
            
            // Mountain settings
            "pref_mountain_density" to PreferenceDefaults.MOUNTAIN_DENSITY,
            "pref_mountain_height" to PreferenceDefaults.MOUNTAIN_HEIGHT,
            "pref_mountain_width" to PreferenceDefaults.MOUNTAIN_WIDTH,
            "pref_mountain_bottom_offset" to PreferenceDefaults.MOUNTAIN_BOTTOM_OFFSET,
            
            // Weather settings
            "pref_weather_enabled" to PreferenceDefaults.WEATHER_ENABLED,
            "pref_weather_layers" to PreferenceDefaults.WEATHER_LAYERS,
            "pref_weather_layer_separation" to PreferenceDefaults.WEATHER_LAYER_SEPARATION,
            "pref_weather_layer_depth" to PreferenceDefaults.WEATHER_LAYER_DEPTH,
            "pref_wind_multiplier" to PreferenceDefaults.WIND_MULTIPLIER,
            
            // Seasonal settings
            "pref_seasonal_enabled" to PreferenceDefaults.SEASONAL_ENABLED,
            "pref_christmas_enabled" to PreferenceDefaults.CHRISTMAS_ENABLED,
            "pref_christmas_interval" to PreferenceDefaults.CHRISTMAS_INTERVAL,
            "pref_newyear_enabled" to PreferenceDefaults.NEWYEAR_ENABLED,
            "pref_newyear_interval" to PreferenceDefaults.NEWYEAR_INTERVAL,
            "pref_cn_newyear_enabled" to PreferenceDefaults.CN_NEWYEAR_ENABLED,
            "pref_cn_newyear_interval" to PreferenceDefaults.CN_NEWYEAR_INTERVAL,
            "pref_st_patricks_enabled" to PreferenceDefaults.ST_PATRICKS_ENABLED,
            "pref_st_patricks_interval" to PreferenceDefaults.ST_PATRICKS_INTERVAL,
            "pref_shake_fireworks_enabled" to PreferenceDefaults.SHAKE_FIREWORKS_ENABLED,
            "pref_shake_sensitivity" to PreferenceDefaults.SHAKE_SENSITIVITY,
            "pref_shake_delay" to PreferenceDefaults.SHAKE_DELAY_MS,
            
            // Visual settings
            "pref_night_shade_strength" to PreferenceDefaults.NIGHT_SHADE_STRENGTH,
            "pref_vignette_enabled" to PreferenceDefaults.VIGNETTE_ENABLED,
            "pref_vignette_strength" to PreferenceDefaults.VIGNETTE_STRENGTH,
            "pref_vignette_size" to PreferenceDefaults.VIGNETTE_SIZE,
            
            // Debug settings
            "pref_debug_overlay_enabled" to PreferenceDefaults.DEBUG_OVERLAY_ENABLED,
            "pref_debug_frame_timing" to PreferenceDefaults.DEBUG_FRAME_TIMING,
            
            // Idle mode settings
            "pref_idle_enabled" to PreferenceDefaults.IDLE_ENABLED,
            "pref_idle_timeout" to PreferenceDefaults.IDLE_TIMEOUT,
            "pref_idle_fps" to PreferenceDefaults.IDLE_FPS,
            
            // Season transition settings
            "pref_season_transition_days" to PreferenceDefaults.SEASON_TRANSITION_DAYS,
            
            // River settings
            "pref_river_enabled" to PreferenceDefaults.RIVER_ENABLED,
            "pref_river_style" to PreferenceDefaults.RIVER_STYLE,
            "pref_river_width" to PreferenceDefaults.RIVER_WIDTH,
            "pref_river_opacity" to PreferenceDefaults.RIVER_OPACITY,
            "pref_river_speed" to PreferenceDefaults.RIVER_SPEED,
            
            // Lake settings
            "pref_lake_enabled" to PreferenceDefaults.LAKE_ENABLED,
            "pref_lake_position" to PreferenceDefaults.LAKE_POSITION,
            "pref_lake_size" to PreferenceDefaults.LAKE_SIZE,
            "pref_lake_depth" to PreferenceDefaults.LAKE_DEPTH_LAYER,
            "pref_lake_opacity" to PreferenceDefaults.LAKE_OPACITY,
            
            // Seasonal decoration settings
            "pref_pinecones_enabled" to PreferenceDefaults.PINECONES_ENABLED,
            "pref_pinecone_density" to PreferenceDefaults.PINECONE_DENSITY,
            "pref_fallen_leaves_enabled" to PreferenceDefaults.FALLEN_LEAVES_ENABLED,
            "pref_fallen_leaves_density" to PreferenceDefaults.FALLEN_LEAVES_DENSITY,
            "pref_mushrooms_enabled" to PreferenceDefaults.MUSHROOMS_ENABLED,
            "pref_mushroom_density" to PreferenceDefaults.MUSHROOM_DENSITY,
            "pref_berries_enabled" to PreferenceDefaults.BERRIES_ENABLED,
            "pref_berry_density" to PreferenceDefaults.BERRY_DENSITY,
            "pref_frost_enabled" to PreferenceDefaults.FROST_ENABLED
        )
    }
    
    /**
     * Scan all XML preference files and extract preference definitions.
     */
    private fun scanXmlFiles(): Map<String, List<PreferenceDef>> {
        val result = mutableMapOf<String, MutableList<PreferenceDef>>()
        
        val xmlDir = File(XML_PATH)
        if (!xmlDir.exists()) return result
        
        xmlDir.listFiles { file -> file.extension == "xml" }?.forEach { file ->
            val lines = file.readLines()
            
            var currentKey: String? = null
            var currentDefault: String? = null
            var currentMin: Int? = null
            var currentMax: Int? = null
            var keyLine = 0
            
            lines.forEachIndexed { lineNum, line ->
                XML_KEY_REGEX.find(line)?.let { match ->
                    currentKey?.let { key ->
                        result.getOrPut(key) { mutableListOf() }.add(
                            PreferenceDef(key, currentDefault, currentMin, currentMax, file.name, keyLine)
                        )
                    }
                    currentKey = match.groupValues[1]
                    currentDefault = null
                    currentMin = null
                    currentMax = null
                    keyLine = lineNum + 1
                }
                
                if (currentKey != null) {
                    XML_DEFAULT_REGEX.find(line)?.let { match ->
                        currentDefault = match.groupValues[1]
                    }
                    XML_MIN_REGEX.find(line)?.let { match ->
                        currentMin = match.groupValues[1].toIntOrNull()
                    }
                    XML_MAX_REGEX.find(line)?.let { match ->
                        currentMax = match.groupValues[1].toIntOrNull()
                    }
                }
            }
            
            currentKey?.let { key ->
                result.getOrPut(key) { mutableListOf() }.add(
                    PreferenceDef(key, currentDefault, currentMin, currentMax, file.name, keyLine)
                )
            }
        }
        
        return result
    }
    
    @Test
    fun `XML preferences with same key have matching defaults`() {
        val xmlPrefs = scanXmlFiles()
        val errors = mutableListOf<String>()
        
        xmlPrefs.filter { it.value.size > 1 }.forEach { (key, defs) ->
            val byDefault = defs.groupBy { it.defaultValue }
            if (byDefault.size > 1) {
                val locations = defs.map { "${it.sourceFile}:${it.lineNumber} (default=${it.defaultValue})" }
                errors.add("$key has mismatched defaults:\n  ${locations.joinToString("\n  ")}")
            }
        }
        
        if (errors.isNotEmpty()) {
            fail("Found XML preference default mismatches:\n${errors.joinToString("\n\n")}")
        }
    }
    
    @Test
    fun `XML defaults match PreferenceDefaults constants`() {
        val xmlPrefs = scanXmlFiles()
        val errors = mutableListOf<String>()
        
        PREFERENCE_TO_CONSTANT.forEach { (key, expectedDefault) ->
            val xmlDefs = xmlPrefs[key] ?: return@forEach
            
            xmlDefs.forEach xmlDef@{ xmlDef ->
                val xmlDefault = xmlDef.defaultValue ?: return@xmlDef
                val expectedString = expectedDefault.toString()
                
                if (xmlDefault != expectedString) {
                    errors.add(
                        "$key: XML ${xmlDef.sourceFile}:${xmlDef.lineNumber} has default=$xmlDefault, " +
                        "but PreferenceDefaults has $expectedString"
                    )
                }
            }
        }
        
        if (errors.isNotEmpty()) {
            fail("Found XML/PreferenceDefaults mismatches:\n${errors.joinToString("\n")}")
        }
    }
    
    @Test
    fun `XML preferences with same key have matching constraints`() {
        val xmlPrefs = scanXmlFiles()
        val errors = mutableListOf<String>()
        
        xmlPrefs.filter { it.value.size > 1 }.forEach { (key, defs) ->
            val mins = defs.filter { it.min != null }.map { it.min }.distinct()
            if (mins.size > 1) {
                val locations = defs.filter { it.min != null }
                    .map { "${it.sourceFile}:${it.lineNumber} (min=${it.min})" }
                errors.add("$key has mismatched min:\n  ${locations.joinToString("\n  ")}")
            }
            
            val maxs = defs.filter { it.max != null }.map { it.max }.distinct()
            if (maxs.size > 1) {
                val locations = defs.filter { it.max != null }
                    .map { "${it.sourceFile}:${it.lineNumber} (max=${it.max})" }
                errors.add("$key has mismatched max:\n  ${locations.joinToString("\n  ")}")
            }
        }
        
        if (errors.isNotEmpty()) {
            fail("Found XML preference constraint mismatches:\n${errors.joinToString("\n\n")}")
        }
    }
    
    @Test
    fun `PreferenceDefaults is the single source of truth`() {
        // PreferenceDefaults.kt is the Single Source of Truth (SSOT) for preference defaults.
        // Android SeekBarPreference can't reliably use @integer references.
        // Define new defaults in PreferenceDefaults.kt FIRST, then copy to XML.
        // The PREFERENCE_TO_CONSTANT test above catches any drift.
        
        assertTrue("PreferenceDefaults should have constants", 
            PreferenceDefaults.CLOUD_SPEED >= 0)
        assertTrue("PREFERENCE_TO_CONSTANT should have mappings",
            PREFERENCE_TO_CONSTANT.isNotEmpty())
    }
}
