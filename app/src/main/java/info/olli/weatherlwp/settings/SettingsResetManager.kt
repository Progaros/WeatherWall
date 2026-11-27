package info.olli.weatherlwp.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.BuildConfig
import info.olli.weatherlwp.R

/**
 * Applies XML preference defaults: once per app version for missing keys, or on a full reset.
 */
object SettingsResetManager {

    private const val KEY_DEFAULTS_VERSION = "prefs_defaults_version"

    /** All XML preference resources that define defaults. */
    private val defaultXmlResources = intArrayOf(
        R.xml.pref_scene,
        R.xml.pref_scene_trees,
        R.xml.pref_scene_mountains,
        R.xml.pref_scene_hills,
        R.xml.pref_effects,
        R.xml.pref_effects_parallax,
        R.xml.pref_effects_clouds,
        R.xml.pref_effects_sky,
        R.xml.pref_effects_vignette,
        R.xml.pref_effects_weather_shadows,
        R.xml.pref_effects_seasonal,
        R.xml.pref_effects_river,
        R.xml.pref_effects_lake,
        R.xml.pref_events,
        R.xml.pref_events_choose,
        R.xml.pref_events_seasonal,
        R.xml.pref_events_shake,
        R.xml.pref_system,
        R.xml.pref_system_idle,
        R.xml.pref_system_simulation,
        R.xml.pref_system_about,
    )

    /**
     * Writes XML defaults for keys that are not set yet, so the wallpaper renders what the
     * settings screens show even before they are opened. Existing values are never touched.
     */
    fun ensureDefaults(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) == BuildConfig.VERSION_CODE) return
        applyXmlDefaults(context)
        prefs.edit { putInt(KEY_DEFAULTS_VERSION, BuildConfig.VERSION_CODE) }
    }

    /**
     * Clears all preferences and re-applies XML defaults.
     * Call from a confirmation dialog to avoid accidental resets.
     */
    fun resetAllToDefaults(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit { clear() }
        applyXmlDefaults(context)
    }

    private fun applyXmlDefaults(context: Context) {
        // readAgain=true: the shared "defaults applied" flag would otherwise skip all but the first file.
        for (xmlRes in defaultXmlResources) {
            PreferenceManager.setDefaultValues(context, xmlRes, true)
        }
    }
}
