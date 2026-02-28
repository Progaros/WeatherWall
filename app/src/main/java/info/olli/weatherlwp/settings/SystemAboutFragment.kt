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
import info.olli.weatherlwp.WeatherWallApp
import info.olli.weatherlwp.wallpaper.WeatherWallpaper
import info.olli.weatherlwp.weather.WeatherRepository

/**
 * Sub-screen for About & Debug settings.
 * Includes click handlers for version, debug logging, weather data, etc.
 */
class SystemAboutFragment : PreferenceFragmentCompat() {
    
    // Easter egg: rapid version clicks
    private var versionClickCount = 0
    private var firstClickTime = 0L
    
    private val easterEggMessages = arrayOf(
        "\uD83C\uDF24\uFE0F You found the weather wizard!",
        "\uD83C\uDF08 Somewhere over the rainbow...",
        "\u26C8\uFE0F Thunder and lightning, very very frightening!",
        "\uD83C\uDF27\uFE0F It's raining code!",
        "\u2744\uFE0F Let it snow, let it snow, let it snow!",
        "\uD83C\uDF1E Here comes the sun, doo-doo-doo-doo",
    )
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_system_about, rootKey)
        
        // Version info
        findPreference<Preference>("pref_version")?.apply {
            summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                val now = System.currentTimeMillis()
                
                // Reset counter if >2s since first click
                if (now - firstClickTime > 2000L) {
                    versionClickCount = 0
                    firstClickTime = now
                }
                versionClickCount++
                
                if (versionClickCount > 3) {
                    // Easter egg!
                    val msg = easterEggMessages[(now % easterEggMessages.size).toInt()]
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    versionClickCount = 0
                } else {
                    // Normal: copy version
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Version", summary)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Version copied", Toast.LENGTH_SHORT).show()
                }
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
            isChecked = DebugTracer.isEnabled()
        }
        
        // Copy all settings to clipboard
        findPreference<Preference>("pref_copy_settings")?.setOnPreferenceClickListener {
            copyAllSettingsToClipboard()
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
    
    private fun copyWeatherDataToClipboard() {
        val context = requireContext()
        val rawJson = WeatherRepository.getRawJson(context)
        
        if (rawJson != null) {
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
    
    private fun copyAllSettingsToClipboard() {
        val context = requireContext()
        val export = SettingsExporter.export(context)
        WeatherWallApp.copyToClipboard(context, export, "WeatherWall Settings")
        Toast.makeText(context, "All settings copied to clipboard", Toast.LENGTH_SHORT).show()
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
