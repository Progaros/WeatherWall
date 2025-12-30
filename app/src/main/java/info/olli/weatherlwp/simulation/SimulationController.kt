package info.olli.weatherlwp.simulation

import android.content.Context
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import kotlin.math.sin

/**
 * Main controller for the simulation.
 * 
 * Coordinates the clock, settings, and weather data to produce
 * a consistent SimulationState that layers can use for rendering.
 */
class SimulationController(private val context: Context) {
    
    private val clock = SimulationClock()
    private val settings = SimulationSettings(context)
    
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    
    // For smooth time transitions
    private var lastSmoothedTime = 0f
    
    // Wind gust simulation
    private var gustPhase = 0f
    private var nextGustTime = 0L
    private var gustIntensity = 0f
    private var gustDecay = 0f
    private var lastUpdateMs = 0L
    
    /**
     * Reload settings from preferences.
     */
    fun reloadSettings() {
        settings.reload()
        
        // Update clock with settings
        clock.setUseRealTime(settings.useRealTime)
        clock.setManualTime(settings.getEffectiveTimeMinutes())
    }
    
    /**
     * Update screen dimensions.
     */
    fun resize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }
    
    /**
     * Get current simulation state.
     * This is called every frame to get the latest state.
     */
    fun getState(): SimulationState {
        // Get real weather from repository
        val realWeather = WeatherRepository.getWeatherType(context)
        val effectiveWeather = settings.getEffectiveWeather(realWeather)
        
        // Get wind - combine settings with weather
        val weatherWindStrength = WeatherRepository.getNormalizedWindStrength(context)
        val effectiveWind = if (settings.useWeatherWind && WeatherRepository.isDataFresh(context)) {
            // Blend settings wind with weather wind
            (settings.windStrength + weatherWindStrength) / 2f
        } else {
            settings.windStrength
        }
        
        // Calculate wind gust
        val currentMs = System.currentTimeMillis()
        val deltaMs = if (lastUpdateMs > 0) (currentMs - lastUpdateMs).coerceIn(0, 100) else 16L
        lastUpdateMs = currentMs
        
        val windGust = calculateWindGust(effectiveWind, deltaMs)
        
        // Smooth time transitions for eased color changes
        val rawTime = clock.getTimeOfDayHours()
        val smoothedTime = smoothTimeTransitions(rawTime)
        
        return SimulationState(
            timeOfDayHours = rawTime,
            timeSeconds = clock.getTimeSeconds(),
            absoluteTimeMs = clock.getAbsoluteTimeMs(),
            isNight = clock.isNight(),
            isDawn = clock.isDawn(),
            isDusk = clock.isDusk(),
            dayProgress = clock.getDayProgress(),
            smoothedTimeOfDayHours = smoothedTime,
            smoothedProgress = smoothedTime / 24f,
            weather = effectiveWeather,
            cloudDensity = settings.cloudDensity,
            windStrength = effectiveWind.coerceIn(0f, 1f),
            windDirection = 0f,  // Default: wind blows right
            windGust = windGust,
            windMultiplier = settings.windMultiplier,
            treeSwaySpeed = settings.treeSwaySpeed,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isDebugMode = settings.debugEnabled
        )
    }
    
    /**
     * Calculate dynamic wind gusts.
     * - Low wind: gentle gusts every 4-6 seconds
     * - High wind: strong gusts every 1-2 seconds
     */
    private fun calculateWindGust(baseWind: Float, deltaMs: Long): Float {
        val currentMs = System.currentTimeMillis()
        
        // Decay existing gust
        gustIntensity = (gustIntensity - gustDecay * deltaMs / 1000f).coerceAtLeast(0f)
        
        // Check if it's time for a new gust
        if (currentMs >= nextGustTime) {
            // Gust interval: 1-2s at high wind, 4-7s at low wind
            val minInterval = (1000 + (1 - baseWind) * 3000).toLong()  // 1000-4000ms
            val maxInterval = (2000 + (1 - baseWind) * 5000).toLong()  // 2000-7000ms
            val interval = minInterval + (Math.random() * (maxInterval - minInterval)).toLong()
            nextGustTime = currentMs + interval
            
            // Gust intensity: stronger at higher base wind
            // At low wind (0.2): small gusts 0.3-0.5
            // At high wind (0.8): strong gusts 0.7-1.0
            val minGust = 0.2f + baseWind * 0.5f
            val maxGust = 0.4f + baseWind * 0.6f
            gustIntensity = minGust + Math.random().toFloat() * (maxGust - minGust)
            
            // Decay rate: faster at higher wind (gusts are shorter but more frequent)
            gustDecay = 0.3f + baseWind * 0.5f  // 0.3-0.8 intensity per second
        }
        
        return gustIntensity
    }
    
    /**
     * Smooth time transitions to prevent color jumps when using time slider.
     */
    private fun smoothTimeTransitions(rawTime: Float): Float {
        val timeDelta = kotlin.math.abs(rawTime - lastSmoothedTime)
        return if (timeDelta > 0.5f) {
            // Large change: lerp gradually
            val lerpFactor = 0.05f
            lastSmoothedTime = lastSmoothedTime + (rawTime - lastSmoothedTime) * lerpFactor
            lastSmoothedTime
        } else {
            // Small change: use raw time
            lastSmoothedTime = rawTime
            rawTime
        }
    }
    
    /**
     * Get the clock for direct time queries.
     */
    fun getClock(): SimulationClock = clock
    
    /**
     * Get the settings for direct access.
     */
    fun getSettings(): SimulationSettings = settings
}
