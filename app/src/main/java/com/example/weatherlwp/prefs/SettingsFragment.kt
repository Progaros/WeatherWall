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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {
    private var versionTapCount = 0
    private var lastTapTime = 0L

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Version preference with Easter Egg
        findPreference<Preference>("pref_version")?.apply {
            summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                handleVersionTap()
                true
            }
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

    private fun handleVersionTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > 1500) {
            versionTapCount = 0
        }
        lastTapTime = now
        versionTapCount++

        when (versionTapCount) {
            3 -> Toast.makeText(context, "2 more taps...", Toast.LENGTH_SHORT).show()
            4 -> Toast.makeText(context, "1 more tap...", Toast.LENGTH_SHORT).show()
            5 -> {
                versionTapCount = 0
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val easterEnabled = prefs.getBoolean("pref_easter_enabled", true)
                if (easterEnabled) {
                    val currentMode = prefs.getBoolean("easter_mode_active", false)
                    prefs.edit().putBoolean("easter_mode_active", !currentMode).apply()
                    val msg = if (!currentMode) "🥚 Easter Mode Activated!" else "Easter Mode Deactivated"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Easter Egg is disabled in settings", Toast.LENGTH_SHORT).show()
                }
            }
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
        val pm = requireContext().packageManager
        val breezyPackages = listOf(
            "org.breezyweather",
            "wangdaye.com.geometricweather"
        )

        var found = false
        for (pkg in breezyPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                found = true
                Toast.makeText(context, "✅ Breezy Weather found: $pkg", Toast.LENGTH_LONG).show()
                break
            } catch (e: PackageManager.NameNotFoundException) {
                // Continue checking
            }
        }

        if (!found) {
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
            sb.appendLine("=== End of Log ===")

            val logFile = File(requireContext().cacheDir, "weatherwall_debug.txt")
            logFile.writeText(sb.toString())

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
}
