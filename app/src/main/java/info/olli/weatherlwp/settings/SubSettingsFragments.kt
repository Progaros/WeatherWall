package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

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
    }
}
