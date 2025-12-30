package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

/**
 * Scene settings fragment - Mountains, Hills, Trees
 */
class SceneSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_scene, rootKey)
    }
}
