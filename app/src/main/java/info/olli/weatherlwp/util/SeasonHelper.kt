package info.olli.weatherlwp.util

import android.content.SharedPreferences
import java.util.Calendar

/**
 * Centralized season detection and management with gradual transitions.
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
 * 
 * Transition system:
 * Each season boundary has a configurable transition period (default 14 days).
 * During transitions, a blend factor (0–1) interpolates between the outgoing
 * and incoming season. This drives gradual changes in terrain colors, snow
 * coverage, flower density, etc.
 */
object SeasonHelper {
    
    /** Default transition period in days (how many days the blend takes). */
    const val DEFAULT_TRANSITION_DAYS = 14
    
    enum class TerrainSeason {
        SPRING, SUMMER, AUTUMN, WINTER;
        
        fun toStringKey(): String = name.lowercase()
        
        /** Get the next season in the cycle. */
        fun next(): TerrainSeason = when (this) {
            SPRING -> SUMMER
            SUMMER -> AUTUMN
            AUTUMN -> WINTER
            WINTER -> SPRING
        }
        
        /** Get the previous season in the cycle. */
        fun prev(): TerrainSeason = when (this) {
            SPRING -> WINTER
            SUMMER -> SPRING
            AUTUMN -> SUMMER
            WINTER -> AUTUMN
        }
        
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
     * Blend state between two seasons during a transition period.
     * 
     * @property primarySeason The season we are transitioning INTO (or current if no transition)
     * @property previousSeason The season we are transitioning FROM (same as primary if no transition)
     * @property transitionProgress 0.0 = fully previous season, 1.0 = fully primary season
     */
    data class SeasonBlend(
        val primarySeason: TerrainSeason,
        val previousSeason: TerrainSeason,
        val transitionProgress: Float
    ) {
        /** True if we are in the middle of a transition. */
        val isTransitioning: Boolean get() = transitionProgress < 1f && previousSeason != primarySeason
        
        /** Weight for the primary (incoming) season effects (0–1). */
        val primaryWeight: Float get() = transitionProgress
        
        /** Weight for the previous (outgoing) season effects (0–1). */
        val previousWeight: Float get() = 1f - transitionProgress
    }
    
    // Season boundary dates: (month, day) when each season starts
    // These are the first day of each season
    private val SEASON_STARTS = mapOf(
        TerrainSeason.SPRING to Pair(Calendar.MARCH, 1),
        TerrainSeason.SUMMER to Pair(Calendar.JUNE, 1),
        TerrainSeason.AUTUMN to Pair(Calendar.SEPTEMBER, 1),
        TerrainSeason.WINTER to Pair(Calendar.DECEMBER, 1)
    )
    
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
     * Get the current season blend with gradual transition.
     * 
     * During the first [transitionDays] of each season, the blend interpolates
     * from the previous season to the current one using smooth easing.
     * 
     * @param transitionDays Number of days for transition (0 = instant, 30 = slow)
     * @return SeasonBlend with transition progress
     */
    fun getCurrentSeasonBlend(transitionDays: Int = DEFAULT_TRANSITION_DAYS): SeasonBlend {
        return getSeasonBlendForDate(Calendar.getInstance(), transitionDays)
    }
    
    /**
     * Get season blend for a specific date (useful for testing).
     */
    fun getSeasonBlendForDate(cal: Calendar, transitionDays: Int = DEFAULT_TRANSITION_DAYS): SeasonBlend {
        if (transitionDays <= 0) {
            val season = getSeasonForDate(cal)
            return SeasonBlend(season, season, 1f)
        }
        
        val currentSeason = getSeasonForDate(cal)
        val (startMonth, startDay) = SEASON_STARTS[currentSeason]!!
        
        // Calculate days since start of current season
        val seasonStartCal = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.MONTH, startMonth)
            set(Calendar.DAY_OF_MONTH, startDay)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // If we're in Jan/Feb (winter) and season start is December,
        // the start was in the previous year
        if (currentSeason == TerrainSeason.WINTER && cal.get(Calendar.MONTH) < Calendar.MARCH) {
            seasonStartCal.add(Calendar.YEAR, -1)
        }
        
        val daysSinceStart = ((cal.timeInMillis - seasonStartCal.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
        
        if (daysSinceStart >= transitionDays) {
            // Past transition period: fully in current season
            return SeasonBlend(currentSeason, currentSeason, 1f)
        }
        
        // Within transition period: blend from previous to current
        val rawProgress = daysSinceStart.toFloat() / transitionDays
        // Apply smoothstep easing for natural-looking transition
        val progress = smoothStep(rawProgress)
        val previousSeason = currentSeason.prev()
        
        return SeasonBlend(currentSeason, previousSeason, progress)
    }
    
    /**
     * Get season for a specific date.
     */
    private fun getSeasonForDate(cal: Calendar): TerrainSeason {
        return when (cal.get(Calendar.MONTH)) {
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> TerrainSeason.SPRING
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> TerrainSeason.SUMMER
            Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> TerrainSeason.AUTUMN
            else -> TerrainSeason.WINTER
        }
    }
    
    /**
     * Get the effective season blend considering debug override.
     * 
     * @param debugEnabled Whether debug/simulation mode is active
     * @param seasonOverride Season override key ("auto", "spring", etc.)
     * @param transitionDays Transition period in days
     * @param dayOfYearOverride Day-of-year override (0=disabled, 1-365=override)
     */
    fun getEffectiveSeasonBlend(
        debugEnabled: Boolean,
        seasonOverride: String,
        transitionDays: Int = DEFAULT_TRANSITION_DAYS,
        dayOfYearOverride: Int = 0
    ): SeasonBlend {
        if (debugEnabled && seasonOverride != "auto") {
            val season = TerrainSeason.fromString(seasonOverride)
                ?: return getCurrentSeasonBlend(transitionDays)
            return SeasonBlend(season, season, 1f)  // No transition in debug override
        }
        // If day-of-year override is active in debug mode, simulate that date
        if (debugEnabled && dayOfYearOverride > 0) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_YEAR, dayOfYearOverride.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_YEAR)))
            return getSeasonBlendForDate(cal, transitionDays)
        }
        return getCurrentSeasonBlend(transitionDays)
    }

    /**
     * Cubic smoothstep for natural easing.
     */
    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
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
