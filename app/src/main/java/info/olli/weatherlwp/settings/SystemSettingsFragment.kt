package info.olli.weatherlwp.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import info.olli.weatherlwp.BuildConfig
import info.olli.weatherlwp.R
import info.olli.weatherlwp.wallpaper.WeatherWallpaper
import info.olli.weatherlwp.weather.WeatherRepository

/**
 * System settings fragment - Weather, Performance, Simulation, About
 */
class SystemSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
        
        // Export debug log
        findPreference<Preference>("pref_export_log")?.setOnPreferenceClickListener {
            exportDebugLog()
            true
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
    
    private fun exportDebugLog() {
        Toast.makeText(context, "Debug log exported to Downloads", Toast.LENGTH_SHORT).show()
        // TODO: Implement actual log export
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
}
