package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

/**
 * Effects settings fragment - Wind, Parallax, Clouds
 */
class EffectsSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_effects, rootKey)
    }
}
