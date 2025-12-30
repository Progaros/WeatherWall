package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

/**
 * Events settings fragment - Random events and Seasonal
 */
class EventsSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events, rootKey)
        
        // Set up spawn button listeners for each event type
        setupSpawnButton("pref_event_airplane", "Airplane")
        setupSpawnButton("pref_event_birds", "Bird Flock")
        setupSpawnButton("pref_event_eagle", "Eagle")
        setupSpawnButton("pref_event_owl", "Owl")
        setupSpawnButton("pref_event_balloon", "Hot Air Balloon")
        setupSpawnButton("pref_event_shootingstar", "Shooting Star")
        setupSpawnButton("pref_event_alien", "UFO")
        setupSpawnButton("pref_event_butterfly", "Butterfly")
        setupSpawnButton("pref_event_rabbit", "Rabbit")
        setupSpawnButton("pref_event_fox", "Fox")
        setupSpawnButton("pref_event_fireflies", "Fireflies")
        
        // Random spawn button
        findPreference<Preference>("pref_spawn_event")?.setOnPreferenceClickListener {
            spawnRandomEvent()
            true
        }
    }
    
    private fun setupSpawnButton(key: String, eventName: String) {
        val pref = findPreference<SpawnSwitchPreference>(key)
        pref?.onSpawnClickListener = {
            spawnEvent(key, eventName)
        }
    }
    
    private fun spawnEvent(key: String, eventName: String) {
        // Broadcast to the wallpaper service to spawn this event
        val intent = android.content.Intent("info.olli.weatherlwp.SPAWN_EVENT")
        intent.putExtra("event_key", key)
        intent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(intent)
        
        Toast.makeText(context, "Spawning $eventName...", Toast.LENGTH_SHORT).show()
    }
    
    private fun spawnRandomEvent() {
        val intent = android.content.Intent("info.olli.weatherlwp.SPAWN_EVENT")
        intent.putExtra("event_key", "random")
        intent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(intent)
        
        Toast.makeText(context, "Spawning random event...", Toast.LENGTH_SHORT).show()
    }
}
