package info.olli.weatherlwp.settings

import android.os.Bundle
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
     * Migrate parallax strength from old 0-50 scale to new 0-5 scale.
     * Old default was 10 (on 0-50), new default is 3 (on 0-5).
     * Scale factor: divide by 10.
     */
    private fun migrateParallaxStrengthPreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val key = "pref_parallax_strength"
        val migrationKey = "pref_parallax_strength_migrated_v175"
        
        // Only migrate once
        if (prefs.getBoolean(migrationKey, false)) return
        
        val currentValue = prefs.getInt(key, -1)
        if (currentValue > 5) {
            // Old scale value detected, migrate by dividing by 10
            val newValue = (currentValue / 10).coerceIn(0, 5)
            prefs.edit()
                .putInt(key, newValue)
                .putBoolean(migrationKey, true)
                .apply()
        } else {
            // Already on new scale or never set
            prefs.edit().putBoolean(migrationKey, true).apply()
        }
    }
}
