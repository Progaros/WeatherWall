package info.olli.weatherlwp.util

import android.content.SharedPreferences
import java.util.Calendar

/**
 * Centralized season detection and management.
 * 
 * Provides consistent season detection across:
 * - TerrainLayer (flowers, grass, snow)
 * - MountainLayer (snow caps)
 * - SeasonalEvents (holidays)
 * 
 * Seasons:
 * - Spring: March-May (months 2-4)
 * - Summer: June-August (months 5-7)
 * - Autumn: September-November (months 8-10)
 * - Winter: December-February (months 11, 0, 1)
 */
object SeasonHelper {
    
    enum class TerrainSeason {
        SPRING, SUMMER, AUTUMN, WINTER;
        
        fun toStringKey(): String = name.lowercase()
        
        companion object {
            fun fromString(value: String): TerrainSeason? = when (value.lowercase()) {
                "spring" -> SPRING
                "summer" -> SUMMER
                "autumn", "fall" -> AUTUMN
                "winter" -> WINTER
                else -> null
            }
        }
    }
    
    /**
     * Get the current calendar-based terrain season.
     */
    fun getCurrentCalendarSeason(): TerrainSeason {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.MONTH)) {
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> TerrainSeason.SPRING
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> TerrainSeason.SUMMER
            Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> TerrainSeason.AUTUMN
            else -> TerrainSeason.WINTER // Dec, Jan, Feb
        }
    }
    
    /**
     * Get the current calendar-based terrain season as a string key.
     * Returns: "spring", "summer", "autumn", or "winter"
     */
    fun getCurrentCalendarSeasonKey(): String = getCurrentCalendarSeason().toStringKey()
    
    /**
     * Get the effective terrain season considering debug override.
     * 
     * @param debugEnabled Whether debug/simulation mode is enabled
     * @param seasonOverride The override value from preferences ("auto", "spring", "summer", "autumn", "winter")
     * @return The effective season as a string key
     */
    fun getEffectiveTerrainSeasonKey(debugEnabled: Boolean, seasonOverride: String): String {
        if (!debugEnabled || seasonOverride == "auto") {
            return getCurrentCalendarSeasonKey()
        }
        return seasonOverride
    }
    
    /**
     * Get the effective terrain season from SharedPreferences.
     * 
     * @param prefs SharedPreferences containing debug_enabled and terrain_season
     * @return The effective season as a string key
     */
    fun getEffectiveTerrainSeasonKey(prefs: SharedPreferences): String {
        val debugEnabled = prefs.getBoolean("pref_debug_enabled", false)
        val seasonOverride = prefs.getString("pref_terrain_season", "auto") ?: "auto"
        return getEffectiveTerrainSeasonKey(debugEnabled, seasonOverride)
    }
    
    /**
     * Check if the given season key represents winter.
     */
    fun isWinter(seasonKey: String): Boolean = seasonKey == "winter"
    
    /**
     * Check if the given season key represents spring.
     */
    fun isSpring(seasonKey: String): Boolean = seasonKey == "spring"
    
    /**
     * Check if the given season key represents autumn.
     */
    fun isAutumn(seasonKey: String): Boolean = seasonKey == "autumn"
    
    /**
     * Check if the given season key represents summer.
     */
    fun isSummer(seasonKey: String): Boolean = seasonKey == "summer"
}
