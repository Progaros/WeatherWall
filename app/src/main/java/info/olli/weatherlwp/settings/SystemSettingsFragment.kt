package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

/**
 * System settings fragment - Essential toggles and navigation to sub-screens.
 * Detailed settings (simulation, about/debug) are in separate sub-fragments.
 */
class SystemSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Run migrations before loading preferences
        migrateIdleTimeoutPreference()
        
        setPreferencesFromResource(R.xml.pref_system, rootKey)
    }
    
    /**
     * Migrate pref_idle_timeout from Integer (old SeekBar) to String (new ListPreference).
     * This handles the case where users upgrading from v1.7.4 have an Int stored but
     * ListPreference expects a String, which causes a ClassCastException.
     */
    private fun migrateIdleTimeoutPreference() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val key = "pref_idle_timeout"
        
        val allPrefs = prefs.all
        val storedValue = allPrefs[key]
        
        if (storedValue is Int) {
            val intValue = storedValue
            val validValues = listOf(5, 10, 30, 60, 120, 300)
            val closestValue = validValues.minByOrNull { kotlin.math.abs(it - intValue) } ?: 5
            
            prefs.edit()
                .remove(key)
                .putString(key, closestValue.toString())
                .apply()
        }
    }
}
