package info.olli.weatherlwp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R

/**
 * Sub-screen for choosing which event types are enabled.
 * Includes spawn button handlers for each SpawnSwitchPreference.
 */
class EventsChooseFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events_choose, rootKey)
        
        // Set up spawn button listeners for each event type
        setupSpawnButton("pref_event_airplane", "airplane", "Airplane")
        setupSpawnButton("pref_event_birds", "birds", "Bird Flock")
        setupSpawnButton("pref_event_eagle", "eagle", "Eagle")
        setupSpawnButton("pref_event_owl", "owl", "Owl")
        setupSpawnButton("pref_event_balloon", "balloon", "Hot Air Balloon")
        setupSpawnButton("pref_event_shootingstar", "shootingstar", "Shooting Star")
        setupSpawnButton("pref_event_alien", "alien", "UFO")
        setupSpawnButton("pref_event_butterfly", "butterfly", "Butterfly")
        setupSpawnButton("pref_event_dragonfly", "dragonfly", "Dragonfly")
        setupSpawnButton("pref_event_rabbit", "rabbit", "Rabbit")
        setupSpawnButton("pref_event_fox", "fox", "Fox")
        setupSpawnButton("pref_event_squirrel", "squirrel", "Squirrel")
        setupSpawnButton("pref_event_fireflies", "fireflies", "Fireflies")
        setupSpawnButton("pref_event_snowflake", "snowflake", "Snowflake")
        setupSpawnButton("pref_event_bat", "bat", "Bat Colony")
        setupSpawnButton("pref_event_deer", "deer", "Deer")
        setupSpawnButton("pref_event_fish", "fish", "Fish")
        setupSpawnButton("pref_event_duck", "duck", "Duck")
        setupSpawnButton("pref_event_frog", "frog", "Frog")
    }
    
    private fun setupSpawnButton(prefKey: String, eventType: String, eventName: String) {
        val pref = findPreference<SpawnSwitchPreference>(prefKey)
        pref?.onSpawnClickListener = {
            spawnEvent(eventType, eventName)
        }
    }
    
    private fun spawnEvent(eventType: String, eventName: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val triggerTime = System.currentTimeMillis()
        prefs.edit()
            .putString("trigger_spawn_specific_event_preview", eventType)
            .putLong("trigger_spawn_specific_event_time_preview", triggerTime)
            .putString("trigger_spawn_specific_event_wallpaper", eventType)
            .putLong("trigger_spawn_specific_event_time_wallpaper", triggerTime)
            .commit()
        
        Toast.makeText(context, "Spawning $eventName...", Toast.LENGTH_SHORT).show()
    }
}
