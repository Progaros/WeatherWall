package info.olli.weatherlwp.rendering.seasonal

import android.util.Log
import info.olli.weatherlwp.PreferenceDefaults
import info.olli.weatherlwp.rendering.SeasonalEvents.Season
import info.olli.weatherlwp.simulation.SimulationSettings
import java.util.Calendar

/**
 * Determines the active seasonal event from the date, per-event toggles/intervals
 * and debug overrides. Results are cached to avoid Calendar allocations every frame.
 */
internal class HolidaySchedule(initialSettings: SimulationSettings) {

    @Volatile private var currentSettings: SimulationSettings = initialSettings

    // Cached season result to avoid Calendar allocations every frame (3× per frame)
    private var cachedSeason: Season = Season.NONE
    private var cachedSeasonTimeMs: Long = 0L

    // Per-event toggles (each seasonal event can be individually enabled/disabled)
    private var christmasEnabled = true
    private var newyearEnabled = true
    private var cnNewyearEnabled = true
    private var stPatricksEnabled = true

    // Per-event intervals (±days around the center holiday date)
    private var christmasInterval = 5   // ±days around Dec 25
    private var newyearInterval = 7     // ±days around Jan 1
    private var cnNewyearInterval = 14  // ±days around Feb 1
    private var stPatricksInterval = 2  // ±days around Mar 17

    fun applySettings(settings: SimulationSettings) {
        currentSettings = settings

        // Per-event toggles
        christmasEnabled = settings.getBoolean("pref_christmas_enabled", PreferenceDefaults.CHRISTMAS_ENABLED)
        newyearEnabled = settings.getBoolean("pref_newyear_enabled", PreferenceDefaults.NEWYEAR_ENABLED)
        cnNewyearEnabled = settings.getBoolean("pref_cn_newyear_enabled", PreferenceDefaults.CN_NEWYEAR_ENABLED)
        stPatricksEnabled = settings.getBoolean("pref_st_patricks_enabled", PreferenceDefaults.ST_PATRICKS_ENABLED)

        // Per-event intervals (±days)
        christmasInterval = settings.getInt("pref_christmas_interval", PreferenceDefaults.CHRISTMAS_INTERVAL).coerceIn(0, 30)
        newyearInterval = settings.getInt("pref_newyear_interval", PreferenceDefaults.NEWYEAR_INTERVAL).coerceIn(0, 30)
        cnNewyearInterval = settings.getInt("pref_cn_newyear_interval", PreferenceDefaults.CN_NEWYEAR_INTERVAL).coerceIn(0, 30)
        stPatricksInterval = settings.getInt("pref_st_patricks_interval", PreferenceDefaults.ST_PATRICKS_INTERVAL).coerceIn(0, 30)
    }

    fun getCurrentSeason(): Season {
        val now = System.currentTimeMillis()
        if (now - cachedSeasonTimeMs < SEASON_CACHE_TTL_MS) {
            return cachedSeason
        }

        val settings = currentSettings

        // Check if seasonal events are enabled
        try {
            val seasonalEnabled = settings.getBoolean("pref_seasonal_enabled", true)
            if (!seasonalEnabled) {
                cachedSeason = Season.NONE
                cachedSeasonTimeMs = now
                return Season.NONE
            }

            val debugEnabled = settings.getBoolean("pref_debug_enabled", false)
            if (debugEnabled) {
                val debugSeason = settings.getString("pref_debug_season", "auto")
                val result = when (debugSeason) {
                    "christmas" -> Season.CHRISTMAS
                    "newyear" -> Season.NEW_YEAR
                    "cn_newyear" -> Season.CN_NEW_YEAR
                    "st_patricks" -> Season.ST_PATRICKS
                    "none" -> Season.NONE
                    else -> null
                }
                if (result != null) {
                    cachedSeason = result
                    cachedSeasonTimeMs = now
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(SEASONAL_TAG, "Error reading debug prefs", e)
        }

        val result = getSeasonByDate()
        cachedSeason = result
        cachedSeasonTimeMs = now
        return result
    }

    /**
     * Determine season from current date using circular day distance.
     * Each event has a center date and ±interval days.
     * Priority order: Christmas → New Year → CN New Year → St. Patrick's.
     */
    fun getSeasonByDate(
        cal: Calendar = Calendar.getInstance()
    ): Season {
        val currentDoy = cal.get(Calendar.DAY_OF_YEAR)
        val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)

        // Check in priority order (first match wins on overlap days)
        if (christmasEnabled && circularDayDistance(currentDoy, dayOfYear(Calendar.DECEMBER, 25, cal), daysInYear) <= christmasInterval) {
            return Season.CHRISTMAS
        }
        if (newyearEnabled && circularDayDistance(currentDoy, dayOfYear(Calendar.JANUARY, 1, cal), daysInYear) <= newyearInterval) {
            return Season.NEW_YEAR
        }
        if (cnNewyearEnabled && circularDayDistance(currentDoy, dayOfYear(Calendar.FEBRUARY, 1, cal), daysInYear) <= cnNewyearInterval) {
            return Season.CN_NEW_YEAR
        }
        if (stPatricksEnabled && circularDayDistance(currentDoy, dayOfYear(Calendar.MARCH, 17, cal), daysInYear) <= stPatricksInterval) {
            return Season.ST_PATRICKS
        }

        return Season.NONE
    }

    companion object {
        private const val SEASON_CACHE_TTL_MS = 10_000L  // Re-check season every 10 seconds

        /**
         * Circular distance between two day-of-year values,
         * correctly wrapping around year-end (e.g. Dec 31→Jan 1 = 1 day).
         */
        fun circularDayDistance(day1: Int, day2: Int, daysInYear: Int = 365): Int {
            val diff = kotlin.math.abs(day1 - day2)
            return minOf(diff, daysInYear - diff)
        }

        /**
         * Get the day-of-year for a given month and day.
         * @param month 0-indexed Calendar month (Calendar.JANUARY=0, Calendar.DECEMBER=11)
         */
        fun dayOfYear(month: Int, day: Int, referenceCal: Calendar = Calendar.getInstance()): Int {
            val c = referenceCal.clone() as Calendar
            c.set(Calendar.MONTH, month)
            c.set(Calendar.DAY_OF_MONTH, day)
            return c.get(Calendar.DAY_OF_YEAR)
        }
    }
}
