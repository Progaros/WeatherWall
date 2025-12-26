package com.example.weatherlwp.prefs

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.example.weatherlwp.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}
