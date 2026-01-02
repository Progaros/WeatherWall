package info.olli.weatherlwp.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.R

/**
 * Root settings fragment with navigation to sub-screens.
 * 
 * Uses app:fragment attribute to navigate to category sub-screens:
 * - Scene: Mountains, hills, trees, landscape
 * - Effects: Parallax, clouds, weather, seasons
 * - Events: Random animals, seasonal events
 * - System: Performance, about, preview
 */
class RootSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_root, rootKey)
    }
}
