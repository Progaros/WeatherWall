package com.example.weatherlwp.prefs

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.weatherlwp.BuildConfig
import com.example.weatherlwp.R
import com.example.weatherlwp.wallpaper.WeatherWallpaper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    // Easter egg tap counter
    private var versionTapCount = 0
    private var lastTapTime = 0L
    private val easterEggMessages = listOf(
        "🌲 The trees are watching...",
        "⛰️ Mountains whisper secrets",
        "🌧️ Even virtual rain is wet",
        "☁️ Cloud nine was full, try ten",
        "⚡ Zeus says hi!",
        "❄️ Brrr... cold code!",
        "🌙 The night is dark and full of pixels",
        "🎄 Ho ho ho!",
        "🎆 Party mode activated!",
        "🦆 Quack! Wrong easter egg..."
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Version preference with easter egg
        findPreference<Preference>("pref_version")?.apply {
            summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            
            setOnPreferenceClickListener {
                handleVersionTap()
                true
            }
        }

        // Manual time slider - only enabled when auto day/night is OFF
        val dayNightPref = findPreference<SwitchPreferenceCompat>("pref_daynight_enabled")
        val manualTimePref = findPreference<SeekBarPreference>("pref_manual_time")
        
        // Format time display (minutes -> HH:MM)
        fun formatTimeMinutes(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return String.format("%02d:%02d", h, m)
        }
        
        // Set initial state and summary
        manualTimePref?.isEnabled = !(dayNightPref?.isChecked ?: true)
        manualTimePref?.summary = "Time: ${formatTimeMinutes(manualTimePref?.value ?: 720)}"
        
        // Update summary on change
        manualTimePref?.setOnPreferenceChangeListener { pref, newValue ->
            (pref as SeekBarPreference).summary = "Time: ${formatTimeMinutes(newValue as Int)}"
            true
        }
        
        // Listen for day/night toggle changes
        dayNightPref?.setOnPreferenceChangeListener { _, newValue ->
            manualTimePref?.isEnabled = !(newValue as Boolean)
            true
        }

        // Preview wallpaper
        findPreference<Preference>("pref_preview")?.setOnPreferenceClickListener {
            openWallpaperPreview()
            true
        }

        // Check Breezy Weather
        findPreference<Preference>("pref_check_breezy")?.setOnPreferenceClickListener {
            checkBreezyWeather()
            true
        }

        // Export debug log
        findPreference<Preference>("pref_export_log")?.setOnPreferenceClickListener {
            exportDebugLog()
            true
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
            Toast.makeText(context, "Cannot open wallpaper preview", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkBreezyWeather() {
        // First check if we're receiving weather data (this is the most important indicator)
        val lastUpdate = com.example.weatherlwp.weather.WeatherRepository.getLastUpdate(requireContext())
        val currentType = com.example.weatherlwp.weather.WeatherRepository.getWeatherType(requireContext())
        val hasRecentData = lastUpdate > 0 && (System.currentTimeMillis() - lastUpdate) < 2 * 60 * 60 * 1000 // Less than 2 hours old
        
        if (hasRecentData) {
            // We have recent data - BW is working!
            val ageMinutes = (System.currentTimeMillis() - lastUpdate) / 60000
            val dataAge = if (ageMinutes < 60) "${ageMinutes}min ago" else "${ageMinutes / 60}h ago"
            
            val message = "✅ Breezy Weather connected!\n" +
                "Weather: $currentType\n" +
                "Last update: $dataAge"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        
        // Data is stale or no data - check if BW is installed
        val isStaleData = lastUpdate > 0 // We have data but it's old
        val pm = requireContext().packageManager
        val breezyPackages = listOf(
            "org.breezyweather",
            "org.breezyweather.debug",
            "org.breezyweather.freenet",
            "wangdaye.com.geometricweather",
            "com.breezyweather",
            "com.breezyweather.debug"
        )

        var foundPkg: String? = null
        for (pkg in breezyPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                foundPkg = pkg
                break
            } catch (e: PackageManager.NameNotFoundException) {
                // Continue checking
            }
        }

        if (foundPkg != null) {
            if (isStaleData) {
                val ageHours = (System.currentTimeMillis() - lastUpdate) / (60 * 60 * 1000)
                val message = "⚠️ Breezy Weather data is stale (${ageHours}h old)\n\n" +
                    "Last weather: $currentType\n" +
                    "BW may have stopped sending updates."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else {
                val message = "⚠️ Breezy Weather found ($foundPkg) but no data received.\n\n" +
                    "Please enable in BW:\nSettings → External Modules → Send Gadgetbridge data"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "❌ Breezy Weather not installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDebugLog() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            sb.appendLine("=== WeatherWall Debug Log ===")
            sb.appendLine("Generated: ${dateFormat.format(Date())}")
            sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine()
            
            sb.appendLine("=== Settings ===")
            prefs.all.forEach { (key, value) ->
                sb.appendLine("$key = $value")
            }
            sb.appendLine()
            
            // Capture recent logcat errors for WeatherWall
            sb.appendLine("=== Recent Errors (Logcat) ===")
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d", "-t", "200", "-v", "time",
                    "WeatherWallpaper:E", "ParallaxScene:E", "MainActivity:E", "*:S"
                ))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var errorCount = 0
                while (reader.readLine().also { line = it } != null && errorCount < 50) {
                    sb.appendLine(line)
                    errorCount++
                }
                reader.close()
                process.waitFor()
                if (errorCount == 0) {
                    sb.appendLine("(No recent errors found)")
                }
            } catch (e: Exception) {
                sb.appendLine("(Could not read logcat: ${e.message})")
            }
            sb.appendLine()
            
            // Add all WeatherWall logs (not just errors)
            sb.appendLine("=== All WeatherWall Logs ===")
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d", "-t", "100", "-v", "time",
                    "WeatherWallpaper:*", "ParallaxScene:*", "MainActivity:*", "CloudLayer:*", "*:S"
                ))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var logCount = 0
                while (reader.readLine().also { line = it } != null && logCount < 100) {
                    sb.appendLine(line)
                    logCount++
                }
                reader.close()
                process.waitFor()
                if (logCount == 0) {
                    sb.appendLine("(No recent logs found)")
                }
            } catch (e: Exception) {
                sb.appendLine("(Could not read logcat: ${e.message})")
            }
            sb.appendLine()
            
            sb.appendLine("=== End of Log ===")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString())
                putExtra(Intent.EXTRA_SUBJECT, "WeatherWall Debug Log")
            }
            startActivity(Intent.createChooser(shareIntent, "Share Log"))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to export log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun handleVersionTap() {
        val now = System.currentTimeMillis()
        
        // Reset counter if more than 1 second between taps
        if (now - lastTapTime > 1000) {
            versionTapCount = 0
        }
        lastTapTime = now
        versionTapCount++
        
        when (versionTapCount) {
            3 -> Toast.makeText(context, "Keep tapping... 👀", Toast.LENGTH_SHORT).show()
            5 -> {
                // Show random easter egg message
                val message = easterEggMessages.random()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                versionTapCount = 0
            }
        }
    }
}
