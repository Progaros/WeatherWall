package info.olli.weatherlwp.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import info.olli.weatherlwp.BuildConfig
import info.olli.weatherlwp.DebugTracer
import info.olli.weatherlwp.R
import info.olli.weatherlwp.wallpaper.WeatherWallpaper
import info.olli.weatherlwp.weather.WeatherRepository

/**
 * System settings fragment - Weather, Performance, Simulation, About
 */
class SystemSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Migrate pref_idle_timeout from Int (SeekBar) to String (ListPreference)
        migrateIdleTimeoutPreference()
        
        setPreferencesFromResource(R.xml.pref_system, rootKey)
        
        // Version info
        findPreference<Preference>("pref_version")?.apply {
            summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                // Copy version to clipboard on click
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Version", summary)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Version copied", Toast.LENGTH_SHORT).show()
                true
            }
        }
        
        // Check Breezy Weather connection
        findPreference<Preference>("pref_check_breezy")?.setOnPreferenceClickListener {
            checkBreezyConnection()
            true
        }
        
        // Copy weather data to clipboard
        findPreference<Preference>("pref_copy_weather_data")?.setOnPreferenceClickListener {
            copyWeatherDataToClipboard()
            true
        }
        
        // Debug logging toggle
        findPreference<SwitchPreferenceCompat>("pref_debug_logging")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    DebugTracer.enable()
                    Toast.makeText(context, "Debug tracing enabled", Toast.LENGTH_SHORT).show()
                } else {
                    DebugTracer.disableAndCopy(requireContext())
                }
                true
            }
            // Sync toggle with actual tracer state
            isChecked = DebugTracer.isEnabled()
        }
        
        // Preview wallpaper
        findPreference<Preference>("pref_preview")?.setOnPreferenceClickListener {
            openWallpaperPreview()
            true
        }
    }
    
    private fun checkBreezyConnection() {
        val context = requireContext()
        val isFresh = WeatherRepository.isDataFresh(context)
        val weather = WeatherRepository.getWeatherType(context)
        val wind = WeatherRepository.getNormalizedWindStrength(context)
        
        val message = if (isFresh) {
            "✓ Connected!\nWeather: $weather\nWind: ${(wind * 100).toInt()}%"
        } else {
            "✗ No data. Open Breezy Weather to update."
        }
        
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    
    private fun copyWeatherDataToClipboard() {
        val context = requireContext()
        val rawJson = WeatherRepository.getRawJson(context)
        
        if (rawJson != null) {
            // Pretty print the JSON
            val prettyJson = try {
                org.json.JSONObject(rawJson).toString(2)
            } catch (e: Exception) {
                rawJson
            }
            
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Weather Data", prettyJson)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Weather data copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No weather data available. Open Breezy Weather first.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun openWallpaperPreview() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(requireContext(), WeatherWallpaper::class.java)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open preview", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Migrate pref_idle_timeout from Integer (old SeekBar) to String (new ListPreference).
     * This handles the case where users upgrading from v1.7.4 have an Int stored but
     * ListPreference expects a String, which causes a ClassCastException.
     */
    private fun migrateIdleTimeoutPreference() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val key = "pref_idle_timeout"
        
        // Check if the stored value is an Integer (old format)
        val allPrefs = prefs.all
        val storedValue = allPrefs[key]
        
        if (storedValue is Int) {
            // Migrate: Convert Int to closest valid String value
            val intValue = storedValue
            val validValues = listOf(5, 10, 30, 60, 120, 300)
            val closestValue = validValues.minByOrNull { kotlin.math.abs(it - intValue) } ?: 5
            
            // Remove old Int value and save as String
            prefs.edit()
                .remove(key)
                .putString(key, closestValue.toString())
                .apply()
        }
    }
}
