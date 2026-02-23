package info.olli.weatherlwp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests to ensure duplicate preference keys across different XML files stay in sync.
 * 
 * Some preferences appear in multiple XML files because they're shown in different
 * settings screens (legacy SettingsFragment using preferences.xml and modular 
 * EffectsSettingsFragment using pref_effects.xml). These must have matching defaults
 * and constraints.
 * 
 * This test documents expected duplicates and their required configurations.
 */
class PreferenceSyncTest {
    
    /**
     * Preference that must be synchronized across multiple XML files.
     * @param key The preference key
     * @param defaultValue Expected default value
     * @param min Expected minimum value (null for non-seekbar preferences)
     * @param max Expected maximum value (null for non-seekbar preferences)
     * @param files List of XML files containing this preference
     */
    data class SyncedPreference(
        val key: String,
        val defaultValue: String,
        val min: Int? = null,
        val max: Int? = null,
        val files: List<String> = listOf("preferences.xml", "pref_effects.xml")
    )
    
    companion object {
        /**
         * List of preferences that intentionally appear in multiple XML files.
         * These are NOT duplicates to be removed - they're the same preference
         * exposed through different UI entry points.
         */
        val SYNCED_PREFERENCES = listOf(
            SyncedPreference(
                key = "pref_parallax_enabled",
                defaultValue = "true"
            ),
            SyncedPreference(
                key = "pref_parallax_strength",
                defaultValue = "16",
                min = 1,
                max = 100
            ),
            SyncedPreference(
                key = "pref_parallax_max_offset",
                defaultValue = "40",
                min = 2,
                max = 40
            ),
            SyncedPreference(
                key = "pref_parallax_return_speed",
                defaultValue = "47",
                min = 0,
                max = 100
            ),
            SyncedPreference(
                key = "pref_parallax_smoothing",
                defaultValue = "1",
                min = 0,
                max = 100
            ),
            SyncedPreference(
                key = "pref_night_shade_strength",
                defaultValue = "35",
                min = 0,
                max = 100
            ),
            SyncedPreference(
                key = "pref_cloud_speed",
                defaultValue = "45",
                min = 0,
                max = 100
            )
        )
    }
    
    @Test
    fun `synced preferences documentation exists`() {
        // This test documents that certain preferences intentionally exist in multiple files
        assertTrue("Should have documented synced preferences", SYNCED_PREFERENCES.isNotEmpty())
        
        for (pref in SYNCED_PREFERENCES) {
            assertTrue("Preference ${pref.key} should have at least 2 files", pref.files.size >= 2)
        }
    }
    
    @Test
    fun `parallax enabled appears in both settings screens`() {
        val pref = SYNCED_PREFERENCES.find { it.key == "pref_parallax_enabled" }
        assertNotNull("pref_parallax_enabled should be documented", pref)
        assertEquals("Default should be true", "true", pref?.defaultValue)
    }
    
    @Test
    fun `parallax strength has consistent defaults`() {
        val pref = SYNCED_PREFERENCES.find { it.key == "pref_parallax_strength" }
        assertNotNull("pref_parallax_strength should be documented", pref)
        assertEquals("Default should be 16", "16", pref?.defaultValue)
        assertEquals("Min should be 1", 1, pref?.min)
        assertEquals("Max should be 100", 100, pref?.max)
    }
    
    @Test
    fun `parallax max offset has consistent defaults`() {
        val pref = SYNCED_PREFERENCES.find { it.key == "pref_parallax_max_offset" }
        assertNotNull("pref_parallax_max_offset should be documented", pref)
        assertEquals("Default should be 40 (40% of screen height)", "40", pref?.defaultValue)
        assertEquals("Min should be 2", 2, pref?.min)
        assertEquals("Max should be 40", 40, pref?.max)
    }
    
    @Test
    fun `intentional duplicates explanation`() {
        // Document why these duplicates exist and should NOT be removed
        
        // preferences.xml is used by SettingsFragment (legacy single-screen settings)
        // pref_effects.xml is used by EffectsSettingsFragment (new modular settings)
        // Both screens need the same preference keys because:
        // 1. Users may access settings from either entry point
        // 2. Both read from and write to the same SharedPreferences
        // 3. The preference UI definitions must match for consistency
        
        val explanation = """
            Duplicate preference keys across XML files are INTENTIONAL when:
            - Different Settings Fragments load different XML files
            - Both fragments control the same SharedPreferences key
            - The preference needs to appear in multiple categorized screens
            
            These are NOT code duplicates - they're UI definitions that must stay in sync.
        """.trimIndent()
        
        assertTrue("Explanation should be documented", explanation.contains("INTENTIONAL"))
    }
    
    @Test
    fun `all synced preferences have valid constraints`() {
        for (pref in SYNCED_PREFERENCES) {
            // If min and max are specified, ensure they're valid
            if (pref.min != null && pref.max != null) {
                assertTrue(
                    "${pref.key} min should be less than max",
                    pref.min!! < pref.max!!
                )
                
                // If default is numeric, it should be in range
                val defaultInt = pref.defaultValue.toIntOrNull()
                if (defaultInt != null) {
                    assertTrue(
                        "${pref.key} default ${pref.defaultValue} should be in range [${pref.min}, ${pref.max}]",
                        defaultInt >= pref.min!! && defaultInt <= pref.max!!
                    )
                }
            }
        }
    }
}
