package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R
import info.olli.weatherlwp.rendering.events.EventSpec
import info.olli.weatherlwp.rendering.events.ManualEventSpawns

/**
 * Sub-screen for choosing which event types are enabled.
 * Includes spawn button handlers for each SpawnSwitchPreference.
 */
class EventsChooseFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events_choose, rootKey)
        
        for (spec in EventSpec.entries) {
            setupSpawnButton(spec.prefKey, spec.id, spec.displayName)
        }
    }
    
    private fun setupSpawnButton(prefKey: String, eventType: String, eventName: String) {
        val pref = findPreference<SpawnSwitchPreference>(prefKey)
        pref?.onSpawnClickListener = {
            spawnEvent(eventType, eventName)
        }
    }
    
    private fun spawnEvent(eventType: String, eventName: String) {
        ManualEventSpawns.enqueue(PreferenceManager.getDefaultSharedPreferences(requireContext()), eventType)
        
        Toast.makeText(context, getString(R.string.toast_spawning_event, eventName), Toast.LENGTH_SHORT).show()
    }
}
