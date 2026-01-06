package info.olli.weatherlwp.simulation

import java.util.Calendar
import java.util.TimeZone

/**
 * Manages time for the simulation.
 * 
 * Provides consistent time values that can be:
 * - Real time (follows system clock)
 * - Manual time (fixed hour set by user)
 * - Accelerated/decelerated for effects
 * 
 * Can use actual sunrise/sunset times from weather data when available.
 * All layers should use this clock to ensure consistent behavior.
 */
class SimulationClock {
    
    private var useRealTime = true
    private var manualTimeMinutes = 720  // Minutes since midnight (default: noon)
    private var timeMultiplier = 1.0f
    
    // Sunrise/sunset hours (can be updated from weather data)
    // Default values are reasonable approximations
    private var sunriseHour = 6.0f
    private var sunsetHour = 20.0f
    
    // Base time for animation consistency (doesn't change on reload)
    private val startTimeMs = System.currentTimeMillis()
    
    /**
     * Update sunrise/sunset times from weather data (Unix timestamps in seconds).
     * Pass -1 for unknown values to use defaults.
     */
    fun updateSunTimes(sunriseUnix: Long, sunsetUnix: Long) {
        if (sunriseUnix > 0) {
            sunriseHour = unixToLocalHour(sunriseUnix)
        }
        if (sunsetUnix > 0) {
            sunsetHour = unixToLocalHour(sunsetUnix)
        }
    }
    
    /**
     * Convert Unix timestamp (seconds) to local hour of day (0-24).
     */
    private fun unixToLocalHour(unixSeconds: Long): Float {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = unixSeconds * 1000
        return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    }
    
    /**
     * Get current time of day as hours (0.0 - 24.0).
     * Uses real time or manual time based on settings.
     */
    fun getTimeOfDayHours(): Float {
        return if (useRealTime) {
            val now = java.util.Calendar.getInstance()
            val hours = now.get(java.util.Calendar.HOUR_OF_DAY)
            val minutes = now.get(java.util.Calendar.MINUTE)
            hours + minutes / 60f
        } else {
            manualTimeMinutes / 60f
        }
    }
    
    /**
     * Get minutes since midnight (0-1439).
     */
    fun getTimeOfDayMinutes(): Int {
        return if (useRealTime) {
            val now = java.util.Calendar.getInstance()
            now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        } else {
            manualTimeMinutes
        }
    }
    
    /**
     * Get elapsed simulation time in milliseconds.
     * Used for animations - consistent even across reloads because it's based on epoch time.
     * This allows wind, clouds, trees to have deterministic positions at any given real-world time.
     */
    fun getElapsedTimeMs(): Long {
        // Use Double for multiplication to avoid precision loss with large epoch times
        return ((System.currentTimeMillis() - startTimeMs).toDouble() * timeMultiplier).toLong()
    }
    
    /**
     * Get absolute simulation time in milliseconds (since epoch).
     * Use this when you need positions to be consistent regardless of app restart.
     */
    fun getAbsoluteTimeMs(): Long {
        // Use Double for multiplication to avoid precision loss with large epoch times
        return (System.currentTimeMillis().toDouble() * timeMultiplier).toLong()
    }
    
    /**
     * Get simulation time in seconds for smoother animations.
     * 
     * IMPORTANT: This wraps within a 24-hour period (86400 seconds) to avoid
     * floating-point precision issues with large numbers. Using epoch time directly
     * (1.7+ billion seconds) causes sin() and other math operations to lose precision.
     * 
     * The wrap ensures:
     * - Animations stay smooth (no jitter from float precision loss)
     * - Time is still consistent across preview and live wallpaper
     * - Positions reset every 24 hours (imperceptible for animations)
     */
    fun getTimeSeconds(): Float {
        val absoluteMs = getAbsoluteTimeMs()
        // Wrap to 24-hour period (86400 seconds = 86400000 ms)
        val wrappedMs = absoluteMs % 86400000L
        return wrappedMs / 1000f
    }
    
    /**
     * Check if it's currently night (before sunrise or after sunset).
     */
    fun isNight(): Boolean {
        val hour = getTimeOfDayHours()
        // Night is before sunrise or after sunset
        return hour < sunriseHour || hour >= sunsetHour
    }
    
    /**
     * Check if it's dawn (sunrise transition, ~1.5 hours around sunrise).
     */
    fun isDawn(): Boolean {
        val hour = getTimeOfDayHours()
        // Dawn window: from sunrise to 1.5 hours after (e.g., 6:00-7:30 with default sunrise=6)
        return hour >= sunriseHour && hour < (sunriseHour + 1.5f)
    }
    
    /**
     * Check if it's dusk (sunset transition, ~3 hours before sunset).
     */
    fun isDusk(): Boolean {
        val hour = getTimeOfDayHours()
        // Dusk window: from 3 hours before sunset to sunset (e.g., 17:00-20:00 with default sunset=20)
        return hour >= (sunsetHour - 3.0f) && hour < sunsetHour
    }
    
    /**
     * Get normalized day progress (0 = midnight, 0.5 = noon, 1 = midnight).
     */
    fun getDayProgress(): Float {
        return getTimeOfDayHours() / 24f
    }
    
    // Settings
    
    fun setUseRealTime(useReal: Boolean) {
        useRealTime = useReal
    }
    
    fun setManualTime(minutes: Int) {
        manualTimeMinutes = minutes.coerceIn(0, 1439)
    }
    
    fun setTimeMultiplier(multiplier: Float) {
        timeMultiplier = multiplier.coerceIn(0.1f, 10f)
    }
    
    fun isUsingRealTime(): Boolean = useRealTime
    fun getManualTimeMinutes(): Int = manualTimeMinutes
    
    /**
     * Get current sunrise hour (0-24).
     * This can be from weather data or default value.
     */
    fun getSunriseHour(): Float = sunriseHour
    
    /**
     * Get current sunset hour (0-24).
     * This can be from weather data or default value.
     */
    fun getSunsetHour(): Float = sunsetHour
}
