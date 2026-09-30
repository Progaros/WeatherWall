package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R
import info.olli.weatherlwp.simulation.WeatherHistory
import java.util.Locale

/**
 * Generic sub-screen that loads the preference XML named by the `xml` argument.
 * Set it in XML via `<extra android:name="xml" android:value="pref_..." />`;
 * MainActivity.onPreferenceStartFragment forwards `pref.extras` as fragment arguments.
 * Fragments with custom logic (simulation, events choose, system about) are separate classes.
 */
class XmlPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val name = arguments?.getString(ARG_XML)
        val resId = XML_BY_NAME[name]
            ?: throw IllegalArgumentException("Unknown preference XML '$name' for XmlPreferenceFragment")
        setPreferencesFromResource(resId, rootKey)
    }

    companion object {
        const val ARG_XML = "xml"

        // Explicit R.xml references (not getIdentifier) keep these resources alive under shrinkResources.
        private val XML_BY_NAME = mapOf(
            "pref_scene_mountains" to R.xml.pref_scene_mountains,
            "pref_scene_hills" to R.xml.pref_scene_hills,
            "pref_scene_trees" to R.xml.pref_scene_trees,
            "pref_effects_sky" to R.xml.pref_effects_sky,
            "pref_effects_parallax" to R.xml.pref_effects_parallax,
            "pref_effects_clouds" to R.xml.pref_effects_clouds,
            "pref_effects_weather_shadows" to R.xml.pref_effects_weather_shadows,
            "pref_effects_seasonal" to R.xml.pref_effects_seasonal,
            "pref_effects_vignette" to R.xml.pref_effects_vignette,
            "pref_effects_river" to R.xml.pref_effects_river,
            "pref_effects_lake" to R.xml.pref_effects_lake,
            "pref_events_seasonal" to R.xml.pref_events_seasonal,
            "pref_events_shake" to R.xml.pref_events_shake,
            "pref_system_idle" to R.xml.pref_system_idle,
        )
    }
}

class SystemSimulationFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_system_simulation, rootKey)
        updateWeatherDataDisplay()
    }

    private fun updateWeatherDataDisplay() {
        val ctx = context ?: return
        val displayPref = findPreference<androidx.preference.Preference>("pref_weather_data_display") ?: return

        try {
            val locale = Locale.getDefault()
            val history = WeatherHistory(ctx)
            val snapshot = history.getSnapshot()
            val days = history.getHistory()

            val sb = StringBuilder()

            // Current computed levels
            sb.append("Snow depth: ${(snapshot.snowDepth * 100).toInt()}%")
            sb.append("  •  River level: ${(snapshot.riverLevel * 100).toInt()}%\n")

            // Today's data
            if (!snapshot.avgTempToday.isNaN()) {
                sb.append("Today: ${String.format(locale, "%.1f", snapshot.avgTempToday)}°C avg")
            }
            if (snapshot.totalRainToday > 0f || snapshot.totalSnowToday > 0f) {
                sb.append(", rain ${String.format(locale, "%.1f", snapshot.totalRainToday)}h")
                sb.append(", snow ${String.format(locale, "%.1f", snapshot.totalSnowToday)}h")
            }
            sb.append("\n")

            // Recent daily history
            if (days.isNotEmpty()) {
                sb.append("\nRecent history (${days.size} day${if (days.size != 1) "s" else ""}):\n")
                for (day in days.takeLast(7).reversed()) {
                    val tempStr = if (!day.avgTemp.isNaN()) "${String.format(locale, "%.0f", day.avgTemp)}°C" else "?"
                    val rangeStr = if (!day.minTemp.isNaN() && day.minTemp != Float.MAX_VALUE)
                        " (${String.format(locale, "%.0f", day.minTemp)}–${String.format(locale, "%.0f", day.maxTemp)})" else ""
                    val precipStr = buildList {
                        if (day.rainHours > 0.1f) add("rain ${String.format(locale, "%.1f", day.rainHours)}h")
                        if (day.snowHours > 0.1f) add("snow ${String.format(locale, "%.1f", day.snowHours)}h")
                    }.joinToString(", ").ifEmpty { "dry" }
                    sb.append("  • $tempStr$rangeStr — $precipStr\n")
                }
            } else {
                sb.append("\nNo weather data collected yet.")
            }

            displayPref.summary = sb.toString().trimEnd()
        } catch (e: Exception) {
            displayPref.summary = "Error loading weather data: ${e.message}"
        }
    }
}
