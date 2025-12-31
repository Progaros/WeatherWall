package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R

/**
 * Events settings fragment - Random events and Seasonal
 */
class EventsSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events, rootKey)
        
        // Set up spawn button listeners for each event type
        setupSpawnButton("pref_event_airplane", "airplane", "Airplane")
        setupSpawnButton("pref_event_birds", "birds", "Bird Flock")
        setupSpawnButton("pref_event_eagle", "eagle", "Eagle")
        setupSpawnButton("pref_event_owl", "owl", "Owl")
        setupSpawnButton("pref_event_balloon", "balloon", "Hot Air Balloon")
        setupSpawnButton("pref_event_shootingstar", "shootingstar", "Shooting Star")
        setupSpawnButton("pref_event_alien", "alien", "UFO")
        setupSpawnButton("pref_event_butterfly", "butterfly", "Butterfly")
        setupSpawnButton("pref_event_rabbit", "rabbit", "Rabbit")
        setupSpawnButton("pref_event_fox", "fox", "Fox")
        setupSpawnButton("pref_event_fireflies", "fireflies", "Fireflies")
        
        // Random spawn button
        findPreference<Preference>("pref_spawn_event")?.setOnPreferenceClickListener {
            spawnRandomEvent()
            true
        }
    }
    
    private fun setupSpawnButton(prefKey: String, eventType: String, eventName: String) {
        val pref = findPreference<SpawnSwitchPreference>(prefKey)
        pref?.onSpawnClickListener = {
            spawnEvent(eventType, eventName)
        }
    }
    
    private fun spawnEvent(eventType: String, eventName: String) {
        // Use timestamp-based preference trigger that all active scenes check
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit()
            .putString("trigger_spawn_specific_event", eventType)
            .putLong("trigger_spawn_specific_event_time", System.currentTimeMillis())
            .apply()
        
        Toast.makeText(context, "Spawning $eventName...", Toast.LENGTH_SHORT).show()
    }
    
    private fun spawnRandomEvent() {
        // Use timestamp-based preference trigger that all active scenes check
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit()
            .putLong("trigger_spawn_event_time", System.currentTimeMillis())
            .apply()
        
        Toast.makeText(context, "Spawning random event...", Toast.LENGTH_SHORT).show()
    }
}
