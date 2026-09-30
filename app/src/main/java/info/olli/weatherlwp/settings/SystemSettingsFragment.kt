package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.Preference
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

        findPreference<Preference>("pref_reset_defaults")?.setOnPreferenceClickListener {
            showResetConfirmationDialog()
            true
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset All Settings")
            .setMessage("This will restore all preferences to their default values. This cannot be undone.")
            .setPositiveButton("Reset") { _, _ ->
                SettingsResetManager.resetAllToDefaults(requireContext())
                // Reload this fragment to reflect the changes
                preferenceScreen.removeAll()
                setPreferencesFromResource(R.xml.pref_system, null)
                findPreference<Preference>("pref_reset_defaults")?.setOnPreferenceClickListener {
                    showResetConfirmationDialog()
                    true
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            
            prefs.edit {
                remove(key)
                putString(key, closestValue.toString())
            }
        }
    }
}
