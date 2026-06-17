package info.olli.weatherlwp.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Exports all SharedPreferences to a developer-friendly clipboard string.
 *
 * The output format is designed so the user can tweak values in the app,
 * copy them out, and update [info.olli.weatherlwp.PreferenceDefaults] easily.
 *
 * Output is sorted alphabetically, with type annotations so the developer
 * knows what cast each value needs (Int, Boolean, String, Float, Long, Set).
 */
object SettingsExporter {

    /**
     * Reads every entry in the default SharedPreferences and returns a
     * human-readable, sorted, one-line-per-pref dump.
     *
     * Example output:
     * ```
     * # WeatherWall Settings Export
     * pref_cloud_speed = 5          # Int
     * pref_parallax_enabled = true  # Boolean
     * pref_parallax_sensor_mode = "gyroscope"  # String
     * ```
     */
    fun export(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return export(prefs)
    }

    /**
     * Overload that accepts a [SharedPreferences] directly (makes testing easy).
     */
    fun export(prefs: SharedPreferences): String {
        val all = prefs.all  // Map<String, Any?>
        if (all.isEmpty()) return "# WeatherWall Settings Export\n# (no preferences stored)"

        val lines = all.entries
            .sortedBy { it.key }
            .mapNotNull { (key, value) -> formatEntry(key, value) }

        return buildString {
            appendLine("# WeatherWall Settings Export")
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    /**
     * Format a single preference entry as `key = value  # Type`.
     * Strings are quoted so they are unambiguous in the output.
     */
    internal fun formatEntry(key: String, value: Any?): String? {
        if (value == null) return null

        return when (value) {
            is Boolean -> "$key = $value  # Boolean"
            is Int     -> "$key = $value  # Int"
            is Long    -> "$key = $value  # Long"
            is Float   -> "$key = $value  # Float"
            is String  -> "$key = \"${escapeString(value)}\"  # String"
            is Set<*>  -> {
                val items = value.filterIsInstance<String>().sorted().joinToString(", ")
                "$key = [$items]  # StringSet"
            }
            else       -> "$key = $value  # ${value::class.simpleName}"
        }
    }

    /**
     * Escape characters that would otherwise break the `key = "value"` line format:
     * backslash, double-quote, and CR/LF. Backslash is escaped first so the later
     * replacements are not double-escaped.
     */
    private fun escapeString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}
