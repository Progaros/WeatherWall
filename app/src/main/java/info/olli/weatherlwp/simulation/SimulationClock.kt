package info.olli.weatherlwp.simulation

/**
 * Manages time for the simulation.
 * 
 * Provides consistent time values that can be:
 * - Real time (follows system clock)
 * - Manual time (fixed hour set by user)
 * - Accelerated/decelerated for effects
 * 
 * All layers should use this clock to ensure consistent behavior.
 */
class SimulationClock {
    
    private var useRealTime = true
    private var manualTimeMinutes = 720  // Minutes since midnight (default: noon)
    private var timeMultiplier = 1.0f
    
    // Base time for animation consistency (doesn't change on reload)
    private val startTimeMs = System.currentTimeMillis()
    
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
     * Check if it's currently night (for theme purposes).
     */
    fun isNight(): Boolean {
        val hour = getTimeOfDayHours()
        return hour < 6f || hour >= 20f
    }
    
    /**
     * Check if it's dawn (6:00-7:30).
     */
    fun isDawn(): Boolean {
        val hour = getTimeOfDayHours()
        return hour >= 6f && hour < 7.5f
    }
    
    /**
     * Check if it's dusk (17:00-20:00).
     */
    fun isDusk(): Boolean {
        val hour = getTimeOfDayHours()
        return hour >= 17f && hour < 20f
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
}
