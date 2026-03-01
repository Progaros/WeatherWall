package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R
import info.olli.weatherlwp.simulation.WeatherHistory

/**
 * Sub-settings fragments for nested preference screens.
 * Each fragment loads a specific XML preference resource.
 * Fragments with custom logic (events spawn, system about) are in separate files.
 */

// ── Scene Sub-screens ────────────────────────────────────────────────

class SceneMountainsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_scene_mountains, rootKey)
    }
}

class SceneHillsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_scene_hills, rootKey)
    }
}

class SceneTreesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_scene_trees, rootKey)
    }
}

// ── Effects Sub-screens ──────────────────────────────────────────────

class EffectsSkyFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_sky, rootKey)
    }
}

class EffectsParallaxFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_parallax, rootKey)
    }
}

class EffectsCloudsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_clouds, rootKey)
    }
}

class EffectsWeatherShadowsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_weather_shadows, rootKey)
    }
}

class EffectsSeasonalFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_seasonal, rootKey)
    }
}

class EffectsVignetteFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_vignette, rootKey)
    }
}

class EffectsRiverFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_river, rootKey)
    }
}

class EffectsLakeFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects_lake, rootKey)
    }
}

// ── Events Sub-screens ───────────────────────────────────────────────

class EventsSeasonalFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events_seasonal, rootKey)
    }
}

class EventsShakeFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_events_shake, rootKey)
    }
}

// ── System Sub-screens ───────────────────────────────────────────────

class SystemIdleFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_system_idle, rootKey)
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
            val history = WeatherHistory(ctx)
            val snapshot = history.getSnapshot()
            val days = history.getHistory()

            val sb = StringBuilder()

            // Current computed levels
            sb.append("Snow depth: ${(snapshot.snowDepth * 100).toInt()}%")
            sb.append("  •  River level: ${(snapshot.riverLevel * 100).toInt()}%\n")

            // Today's data
            if (!snapshot.avgTempToday.isNaN()) {
                sb.append("Today: ${String.format("%.1f", snapshot.avgTempToday)}°C avg")
            }
            if (snapshot.totalRainToday > 0f || snapshot.totalSnowToday > 0f) {
                sb.append(", rain ${String.format("%.1f", snapshot.totalRainToday)}h")
                sb.append(", snow ${String.format("%.1f", snapshot.totalSnowToday)}h")
            }
            sb.append("\n")

            // Recent daily history
            if (days.isNotEmpty()) {
                sb.append("\nRecent history (${days.size} day${if (days.size != 1) "s" else ""}):\n")
                for (day in days.takeLast(7).reversed()) {
                    val tempStr = if (!day.avgTemp.isNaN()) "${String.format("%.0f", day.avgTemp)}°C" else "?"
                    val rangeStr = if (!day.minTemp.isNaN() && day.minTemp != Float.MAX_VALUE)
                        " (${String.format("%.0f", day.minTemp)}–${String.format("%.0f", day.maxTemp)})" else ""
                    val precipStr = buildList {
                        if (day.rainHours > 0.1f) add("rain ${String.format("%.1f", day.rainHours)}h")
                        if (day.snowHours > 0.1f) add("snow ${String.format("%.1f", day.snowHours)}h")
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
