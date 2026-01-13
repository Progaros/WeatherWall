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
    private var timeInitialized = false  // Skip smoothing on first frame
    
    // Wind gust simulation
    private var gustPhase = 0f
    private var nextGustTime = 0L
    private var gustIntensity = 0f
    private var gustDecay = 0f
    private var lastUpdateMs = 0L
    private var targetGust = 0f      // Target gust intensity to ramp towards
    private var smoothedGust = 0f    // Smoothed output for gradual transitions
    
    // Smooth wind transitions (prevent jumping when weather data changes)
    private var smoothedWindStrength = 0.5f
    private var smoothedWindDirection = 0f
    
    /**
     * Reload settings from preferences.
     */
    fun reloadSettings() {
        settings.reload()
        
        // Update clock with settings
        clock.setUseRealTime(settings.useRealTime)
        clock.setManualTime(settings.getEffectiveTimeMinutes())
        
        // Update clock with actual sunrise/sunset times from weather data
        val sunrise = WeatherRepository.getSunrise(context)
        val sunset = WeatherRepository.getSunset(context)
        clock.updateSunTimes(sunrise, sunset)
    }
    
    /**
     * Reset timing state after visibility changes to avoid delta spikes.
     * Call this when the wallpaper becomes visible again.
     */
    fun resetTiming() {
        lastUpdateMs = 0L
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
        
        // Get wind - use weather data when available, settings when in debug mode
        val weatherWindStrength = WeatherRepository.getNormalizedWindStrength(context)
        val effectiveWind = if (settings.useWeatherWind && WeatherRepository.isDataFresh(context)) {
            // Use weather wind directly (not blended) when weather data is fresh
            weatherWindStrength
        } else {
            settings.windStrength
        }
        
        // Calculate wind gust
        val currentMs = System.currentTimeMillis()
        val deltaMs = if (lastUpdateMs > 0) (currentMs - lastUpdateMs).coerceIn(0, 100) else 16L
        lastUpdateMs = currentMs
        val deltaSec = deltaMs / 1000f
        
        val windGust = calculateWindGust(effectiveWind, deltaMs)
        
        // Get extended weather data from repository
        val extendedData = WeatherRepository.getExtendedData(context)
        val precipProbability = extendedData.precipProbability / 100f
        val cloudCover = extendedData.cloudCover / 100f
        val humidity = extendedData.humidity / 100f
        val visibility = WeatherRepository.getNormalizedVisibility(context)
        val moonIllumination = WeatherRepository.getMoonIllumination(context)
        // In debug/simulation mode, use manual moon phase; otherwise use weather data
        val moonPhase = if (settings.debugEnabled) {
            settings.moonPhase  // Already 0-1
        } else {
            extendedData.moonPhase / 360f  // Normalize to 0-1
        }
        val rawWindDirection = extendedData.windDirection.toFloat()
        
        // Smooth wind values to prevent tree jumping on weather updates
        val smoothedWind = smoothWindStrength(effectiveWind, deltaSec)
        val smoothedDir = smoothWindDirection(rawWindDirection, deltaSec)
        
        // Calculate effective precipitation intensity based on weather type and probability
        val precipIntensity = when (effectiveWeather) {
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> precipProbability * 0.8f + 0.2f  // Min 20% intensity
            WeatherType.SNOW -> precipProbability * 0.7f + 0.15f
            WeatherType.HAIL -> precipProbability * 0.9f + 0.3f
            else -> precipProbability * 0.3f  // Light background precipitation possibility
        }
        
        // Smooth time transitions for eased color changes
        val rawTime = clock.getTimeOfDayHours()
        val smoothedTime = smoothTimeTransitions(rawTime)
        
        // Get dynamic sun times from clock (set from weather data or defaults)
        val sunriseHours = clock.getSunriseHour()
        val sunsetHours = clock.getSunsetHour()
        
        // Calculate golden hour and blue hour flags
        val goldenHourDuration = 0.75f  // ~45 mins
        val blueHourDuration = 0.5f     // ~30 mins
        val isGoldenHour = rawTime in sunriseHours..(sunriseHours + goldenHourDuration) ||
                           rawTime in (sunsetHours - goldenHourDuration)..sunsetHours
        val isBlueHour = rawTime in (sunriseHours - blueHourDuration)..sunriseHours ||
                         rawTime in sunsetHours..(sunsetHours + blueHourDuration)
        
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
            sunriseHours = sunriseHours,
            sunsetHours = sunsetHours,
            isGoldenHour = isGoldenHour,
            isBlueHour = isBlueHour,
            weather = effectiveWeather,
            cloudDensity = settings.cloudDensity,
            precipProbability = precipProbability.coerceIn(0f, 1f),
            precipIntensity = precipIntensity.coerceIn(0f, 1f),
            cloudCover = cloudCover.coerceIn(0f, 1f),
            humidity = humidity.coerceIn(0f, 1f),
            visibility = visibility.coerceIn(0f, 1f),
            pressure = extendedData.pressure,
            moonPhase = moonPhase.coerceIn(0f, 1f),
            moonIllumination = moonIllumination.coerceIn(0f, 1f),
            windStrength = smoothedWind.coerceIn(0f, 1f),
            windDirection = smoothedDir,
            windGust = windGust.coerceIn(0f, 1f),  // Clamp to valid range
            windMultiplier = settings.windMultiplier,
            treeSwaySpeed = settings.treeSwaySpeed,
            mountainShadowsEnabled = settings.mountainShadowsEnabled,
            treeShadowsEnabled = settings.treeShadowsEnabled,
            shadowStrength = settings.shadowStrength,
            nightShadeStrength = settings.nightShadeStrength,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isDebugMode = settings.debugEnabled
        )
    }
    
    /**
     * Calculate dynamic wind gusts with smooth ramp-up and decay.
     * - Low wind: gentle gusts every 4-6 seconds
     * - High wind: strong gusts every 1-2 seconds
     * - Gusts ramp up gradually for natural feel
     */
    private fun calculateWindGust(baseWind: Float, deltaMs: Long): Float {
        val currentMs = System.currentTimeMillis()
        // Cap deltaSec to 100ms to prevent explosion after visibility changes
        val deltaSec = (deltaMs / 1000f).coerceIn(0f, 0.1f)
        
        // No gusts in calm conditions - fixes "constant shaking" at low wind
        if (baseWind < 0.15f) {
            targetGust = 0f
            smoothedGust = (smoothedGust - deltaSec * 0.5f).coerceAtLeast(0f)  // Decay quickly
            gustIntensity = smoothedGust
            return smoothedGust
        }
        
        // Decay target gust intensity (this is what we're ramping towards)
        targetGust = (targetGust - gustDecay * deltaSec).coerceIn(0f, 1f)
        
        // Check if it's time for a new gust
        if (currentMs >= nextGustTime) {
            // Gust interval: 1-2s at high wind, 4-7s at low wind
            val minInterval = (1000 + (1 - baseWind) * 3000).toLong()  // 1000-4000ms
            val maxInterval = (2000 + (1 - baseWind) * 5000).toLong()  // 2000-7000ms
            val interval = minInterval + (Math.random() * (maxInterval - minInterval)).toLong()
            nextGustTime = currentMs + interval
            
            // Gust intensity: proportional to base wind (no fixed floor)
            // At low wind (0.2): small gusts 0.1-0.25
            // At high wind (0.8): strong gusts 0.5-0.8
            val minGust = baseWind * 0.5f
            val maxGust = baseWind * 0.3f + baseWind * 0.7f
            targetGust = minGust + Math.random().toFloat() * (maxGust - minGust)
            
            // Decay rate: faster at higher wind (gusts are shorter but more frequent)
            gustDecay = 0.3f + baseWind * 0.5f  // 0.3-0.8 intensity per second
        }
        
        // Smooth the gust output: ramp up quickly (0.3s) but decay follows target
        // This prevents instant jumps when a new gust starts
        val rampSpeed = 3.0f  // How fast to approach target (higher = faster ramp)
        val diff = targetGust - smoothedGust
        if (diff > 0) {
            // Ramping up: approach target smoothly
            smoothedGust += diff * rampSpeed * deltaSec
            if (smoothedGust > targetGust) smoothedGust = targetGust
        } else {
            // Decaying: follow target directly (already smooth from targetGust decay)
            smoothedGust = targetGust
        }
        
        // Ensure output stays in valid range
        smoothedGust = smoothedGust.coerceIn(0f, 1f)
        gustIntensity = smoothedGust
        return smoothedGust
    }
    
    /**
     * Smooth time transitions to prevent color jumps when using time slider.
     * On first call (wallpaper load), skip smoothing to avoid sun/moon jumping.
     */
    private fun smoothTimeTransitions(rawTime: Float): Float {
        // On first call, initialize immediately to avoid jump from 0 to current time
        if (!timeInitialized) {
            timeInitialized = true
            lastSmoothedTime = rawTime
            return rawTime
        }
        
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
     * Smooth wind strength to prevent tree jumps when weather data updates.
     */
    private fun smoothWindStrength(targetStrength: Float, deltaSec: Float): Float {
        // Smooth approach: ~1.5 seconds to reach target
        val smoothFactor = 0.7f * deltaSec
        smoothedWindStrength += (targetStrength - smoothedWindStrength) * smoothFactor.coerceIn(0f, 1f)
        return smoothedWindStrength
    }
    
    /**
     * Smooth wind direction, handling 0/360 wraparound properly.
     */
    private fun smoothWindDirection(targetDirection: Float, deltaSec: Float): Float {
        // Calculate shortest angular distance (handle wraparound)
        var delta = targetDirection - smoothedWindDirection
        
        // Normalize to -180..180
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        
        // Smooth approach: ~2 seconds to reach target direction
        val smoothFactor = 0.5f * deltaSec
        smoothedWindDirection += delta * smoothFactor.coerceIn(0f, 1f)
        
        // Normalize result to 0..360
        while (smoothedWindDirection < 0f) smoothedWindDirection += 360f
        while (smoothedWindDirection >= 360f) smoothedWindDirection -= 360f
        
        return smoothedWindDirection
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
