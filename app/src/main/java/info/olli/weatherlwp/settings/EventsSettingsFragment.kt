package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R
import info.olli.weatherlwp.rendering.events.ManualEventSpawns

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
        val now = System.currentTimeMillis()
        ManualEventSpawns.enqueue(prefs, ManualEventSpawns.randomEventId(now), now)
        
        Toast.makeText(context, R.string.toast_spawning_random_event, Toast.LENGTH_SHORT).show()
    }
}
