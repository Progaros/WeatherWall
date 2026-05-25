package info.olli.weatherlwp.settings

import android.content.Context
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.R

/**
 * Handles resetting all preferences to their default values.
 * Clears the SharedPreferences and re-applies XML defaults.
 */
object SettingsResetManager {

    /** All XML preference resources that define defaults. */
    private val defaultXmlResources = intArrayOf(
        R.xml.pref_system,
        R.xml.pref_effects_parallax,
        R.xml.pref_effects_clouds,
        R.xml.pref_effects_sky,
        R.xml.pref_effects_vignette,
        R.xml.pref_effects_weather_shadows,
        R.xml.pref_effects_seasonal,
        R.xml.pref_effects_river,
        R.xml.pref_effects_lake,
        R.xml.pref_scene_trees,
        R.xml.pref_scene_mountains,
        R.xml.pref_scene_hills,
        R.xml.pref_events_seasonal,
        R.xml.pref_events_shake,
        R.xml.pref_system_idle,
        R.xml.pref_system_simulation,
    )

    /**
     * Clears all preferences and re-applies XML defaults.
     * Call from a confirmation dialog to avoid accidental resets.
     */
    fun resetAllToDefaults(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().apply()

        for (xmlRes in defaultXmlResources) {
            PreferenceManager.setDefaultValues(context, xmlRes, true)
        }
    }
}
