package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R

/**
 * Events settings fragment - Essential toggles and navigation to sub-screens.
 * Individual event toggles are in EventsChooseFragment.
 */
class EventsSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events, rootKey)
        
        // Random spawn button (stays at L1)
        findPreference<Preference>("pref_spawn_event")?.setOnPreferenceClickListener {
            spawnRandomEvent()
            true
        }
    }
    
    private fun spawnRandomEvent() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val triggerTime = System.currentTimeMillis()
        prefs.edit()
            .putLong("trigger_spawn_event_time_preview", triggerTime)
            .putLong("trigger_spawn_event_time_wallpaper", triggerTime)
            .commit()
        
        Toast.makeText(context, R.string.toast_spawning_random_event, Toast.LENGTH_SHORT).show()
    }
}
