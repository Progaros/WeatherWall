package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R

/**
 * Effects settings fragment - Wind, Parallax, Clouds
 */
class EffectsSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects, rootKey)
        migrateParallaxStrengthPreference()
    }
    
    /**
     * Migrate parallax strength to new 1-100% scale with log mapping.
     * - Old 0-50 scale: divide by 2, then clamp 1-100
     * - Old 0-5 scale: multiply by 5, then clamp 1-100
     * - New scale: 1-100%, default 25%
     */
    private fun migrateParallaxStrengthPreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val key = "pref_parallax_strength"
        val migrationKey = "pref_parallax_strength_migrated_v178"
        
        // Only migrate once
        if (prefs.getBoolean(migrationKey, false)) return
        
        val currentValue = try {
            prefs.getInt(key, -1)
        } catch (_: ClassCastException) {
            // Preference stored as a different type (e.g. String from old version)
            prefs.edit { remove(key) }
            -1
        }
        val newValue = when {
            currentValue <= 0 -> 1  // Off → minimum (1%)
            currentValue > 50 -> currentValue.coerceIn(1, 100)  // Already 1-100 scale
            currentValue > 5 -> (currentValue / 2).coerceIn(1, 100)  // Old 0-50 → halve it
            else -> (currentValue * 5).coerceIn(1, 100)  // Old 0-5 → multiply by 5
        }
        
        prefs.edit {
            putInt(key, newValue)
            putBoolean(migrationKey, true)
        }
    }
}
