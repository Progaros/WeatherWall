package info.olli.weatherlwp.prefs

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
import info.olli.weatherlwp.BuildConfig
import info.olli.weatherlwp.R
import info.olli.weatherlwp.wallpaper.WeatherWallpaper
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
        "� Try shaking your phone...",
        "📱 Shake it 3 times!",
        "✨ Give your phone a shake!"
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

        // Manual time slider - uses custom TimeSeekBarPreference for HH:MM display
        val manualTimePref = findPreference<TimeSeekBarPreference>("pref_manual_time")
        
        // Set initial summary with HH:MM format
        manualTimePref?.summary = "Time: ${TimeSeekBarPreference.formatMinutesToTime(manualTimePref?.value ?: 720)}"
        
        // Update summary on change
        manualTimePref?.setOnPreferenceChangeListener { pref, newValue ->
            (pref as TimeSeekBarPreference).summary = "Time: ${TimeSeekBarPreference.formatMinutesToTime(newValue as Int)}"
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

        // Spawn random event button
        findPreference<Preference>("pref_spawn_event")?.setOnPreferenceClickListener {
            spawnRandomEvent()
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
        val lastUpdate = info.olli.weatherlwp.weather.WeatherRepository.getLastUpdate(requireContext())
        val currentType = info.olli.weatherlwp.weather.WeatherRepository.getWeatherType(requireContext())
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
        // Runtime.exec("logcat") + waitFor can block for seconds; build the log off the
        // UI thread to avoid ANRs, then post the share intent back on the main thread.
        val appContext = requireContext().applicationContext
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
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
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    var errorCount = 0
                    while (reader.readLine().also { line = it } != null && errorCount < 50) {
                        sb.appendLine(line)
                        errorCount++
                    }
                    reader.close()
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroy()
                    }
                    if (errorCount == 0) {
                        sb.appendLine("(No recent errors found)")
                    }
                } finally {
                    process.destroy()
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
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    var logCount = 0
                    while (reader.readLine().also { line = it } != null && logCount < 100) {
                        sb.appendLine(line)
                        logCount++
                    }
                    reader.close()
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroy()
                    }
                    if (logCount == 0) {
                        sb.appendLine("(No recent logs found)")
                    }
                } finally {
                    process.destroy()
                }
            } catch (e: Exception) {
                sb.appendLine("(Could not read logcat: ${e.message})")
            }
            sb.appendLine()
            
            sb.appendLine("=== End of Log ===")

            val logText = sb.toString()
            mainHandler.post {
                if (!isAdded) return@post
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, logText)
                        putExtra(Intent.EXTRA_SUBJECT, "WeatherWall Debug Log")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Log"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to export log: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            mainHandler.post {
                if (isAdded) Toast.makeText(context, "Failed to export log: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        }.start()
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
            5 -> {
                // Show random easter egg message
                val message = easterEggMessages.random()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                versionTapCount = 0
            }
        }
    }
    
    private fun spawnRandomEvent() {
        // Use timestamp-based preference trigger that all active scenes check
        // Write to BOTH preview and wallpaper keys with same timestamp (for deterministic spawning)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val triggerTime = System.currentTimeMillis()
        prefs.edit()
            .putLong("trigger_spawn_event_time_preview", triggerTime)
            .putLong("trigger_spawn_event_time_wallpaper", triggerTime)
            .commit()
        Toast.makeText(context, "🎬 Spawning random event...", Toast.LENGTH_SHORT).show()
    }
}
