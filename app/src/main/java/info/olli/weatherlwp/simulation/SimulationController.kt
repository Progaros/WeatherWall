package info.olli.weatherlwp.simulation

import android.content.Context
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import info.olli.weatherlwp.util.SeasonHelper

/**
 * Main controller for the simulation.
 * 
 * Coordinates the clock, settings, and weather data to produce
 * a consistent SimulationState that layers can use for rendering.
 */
class SimulationController(private val context: Context) {
    
    private val clock = SimulationClock()
    @Volatile private var settings = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
    private val weatherHistory = WeatherHistory(context)
    private val windSimulator = WindSimulator()
    
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    
    // For smooth time transitions
    private var lastSmoothedTime = 0f
    private var timeInitialized = false  // Skip smoothing on first frame
    
    // Frame delta tracking
    private var lastUpdateMs = 0L
    private var lastRecordAttemptMs = 0L

    // Season blend is date-based (changes ~once/day) yet getEffectiveSeasonBlend allocates
    // Calendars; cache it and recompute only when the local day or a relevant setting changes.
    private var cachedSeasonBlend: SeasonHelper.SeasonBlend? = null
    private var seasonCacheDayBucket = Long.MIN_VALUE
    private var seasonCacheDebug = false
    private var seasonCacheOverride: String? = null
    private var seasonCacheTransitionDays = Int.MIN_VALUE
    private var seasonCacheDayOverride = Int.MIN_VALUE

    init {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        applySettings(SimulationSettings.fromSnapshot(snapshot))
    }
    
    /**
     * Apply a new immutable settings snapshot.
     */
    fun applySettings(newSettings: SimulationSettings) {
        settings = newSettings

        // Update clock with settings
        clock.setUseRealTime(newSettings.useRealTime)
        clock.setManualTime(newSettings.getEffectiveTimeMinutes())

        // Update clock with actual sunrise/sunset times from weather data
        val sunrise = WeatherRepository.getSunrise(context)
        val sunset = WeatherRepository.getSunset(context)
        clock.updateSunTimes(sunrise, sunset)
    }

    /**
     * Backward-compatible fallback for callers that still force a direct prefs reload.
     */
    fun reloadSettings() {
        val snapshot = PreferenceManager.getDefaultSharedPreferences(context).all
        applySettings(SimulationSettings.fromSnapshot(snapshot))
    }
    
    /**
     * Reset timing state after visibility changes to avoid delta spikes.
     * Call this when the wallpaper becomes visible again.
     */
    fun resetTiming() {
        lastUpdateMs = 0L
        windSimulator.reset()
        // Reset time smoothing so sun/moon position initializes immediately without jumping
        timeInitialized = false
    }
    
    /**
     * Update screen dimensions.
     */
    fun resize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }
    
    /**
     * Effective season blend, cached across frames. Recomputes only when the local calendar
     * day rolls over or a season-related setting changes, avoiding per-frame Calendar allocation.
     */
    private fun effectiveSeasonBlend(s: SimulationSettings): SeasonHelper.SeasonBlend {
        val offsetMs = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
        val dayBucket = (System.currentTimeMillis() + offsetMs) / 86_400_000L
        val cached = cachedSeasonBlend
        if (cached != null &&
            dayBucket == seasonCacheDayBucket &&
            s.debugEnabled == seasonCacheDebug &&
            s.terrainSeasonOverride == seasonCacheOverride &&
            s.seasonTransitionDays == seasonCacheTransitionDays &&
            s.seasonDayOfYearOverride == seasonCacheDayOverride
        ) {
            return cached
        }
        val blend = SeasonHelper.getEffectiveSeasonBlend(
            s.debugEnabled,
            s.terrainSeasonOverride,
            s.seasonTransitionDays,
            s.seasonDayOfYearOverride
        )
        cachedSeasonBlend = blend
        seasonCacheDayBucket = dayBucket
        seasonCacheDebug = s.debugEnabled
        seasonCacheOverride = s.terrainSeasonOverride
        seasonCacheTransitionDays = s.seasonTransitionDays
        seasonCacheDayOverride = s.seasonDayOfYearOverride
        return blend
    }

    /**
     * Get current simulation state.
     * This is called every frame to get the latest state.
     */
    fun getState(): SimulationState {
        val currentSettings = settings

        // Get real weather from repository
        val realWeather = WeatherRepository.getWeatherType(context)
        val effectiveWeather = currentSettings.getEffectiveWeather(realWeather)
        
        // Get wind - use weather data when available, settings when in debug mode
        val weatherWindStrength = WeatherRepository.getNormalizedWindStrength(context)
        val effectiveWind = if (currentSettings.useWeatherWind && WeatherRepository.isDataFresh(context)) {
            // Use weather wind directly (not blended) when weather data is fresh
            weatherWindStrength
        } else {
            currentSettings.windStrength
        }
        
        // Calculate wind gust
        val currentMs = System.currentTimeMillis()
        val deltaMs = if (lastUpdateMs > 0) (currentMs - lastUpdateMs).coerceIn(0, 100) else 16L
        lastUpdateMs = currentMs
        val deltaSec = deltaMs / 1000f
        
        val windGust = windSimulator.calculateWindGust(effectiveWind, deltaMs)
        
        // Get extended weather data from repository
        val extendedData = WeatherRepository.getExtendedData(context)
        val precipProbability = extendedData.precipProbability / 100f
        val cloudCover = extendedData.cloudCover / 100f
        val humidity = extendedData.humidity / 100f
        val visibility = WeatherRepository.getNormalizedVisibility(context)
        val moonIllumination = WeatherRepository.getMoonIllumination(context)
        // In debug/simulation mode, use manual moon phase; otherwise use weather data
        val moonPhase = if (currentSettings.debugEnabled) {
            currentSettings.moonPhase  // Already 0-1
        } else {
            extendedData.moonPhase / 360f  // Normalize to 0-1
        }
        val rawWindDirection = extendedData.windDirection.toFloat()
        
        // Smooth wind values to prevent tree jumping on weather updates
        val smoothedWind = windSimulator.smoothStrength(effectiveWind, deltaSec)
        val smoothedDir = windSimulator.smoothDirection(rawWindDirection, deltaSec)
        
        // Calculate effective precipitation intensity based on weather type and probability
        val precipIntensity = when (effectiveWeather) {
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> precipProbability * 0.8f + 0.2f  // Min 20% intensity
            WeatherType.SNOW -> precipProbability * 0.7f + 0.15f
            WeatherType.HAIL -> precipProbability * 0.9f + 0.3f
            else -> precipProbability * 0.3f  // Light background precipitation possibility
        }
        
        // Smooth time transitions for eased color changes
        val rawTime = clock.getTimeOfDayHours()
        val smoothedTime = smoothTimeTransitions(rawTime, deltaSec)
        
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
        
        // Record weather history (throttled to once per minute; deduplicates internally to once per hour)
        // In debug mode, pass the effective (overridden) weather so history tracks simulated conditions
        if (currentMs - lastRecordAttemptMs >= 60_000L) {
            lastRecordAttemptMs = currentMs
            if (currentSettings.debugEnabled) {
                val overridePrecipProb = when (effectiveWeather) {
                    WeatherType.RAIN, WeatherType.THUNDERSTORM, WeatherType.SNOW, WeatherType.HAIL -> 0.85f
                    else -> 0f
                }
                weatherHistory.recordCurrentWeather(
                    overrideWeather = effectiveWeather,
                    overridePrecipProb = overridePrecipProb
                )
            } else {
                weatherHistory.recordCurrentWeather()
            }
        }
        val historySnapshot = weatherHistory.getSnapshot()
        
        // Apply simulation overrides for snow/river levels when enabled
        val effectiveSnowLevel = if (currentSettings.useSimulatedWeatherHistory)
            currentSettings.simulatedSnowLevel else historySnapshot.snowDepth
        val effectiveRiverLevel = if (currentSettings.useSimulatedWeatherHistory)
            currentSettings.simulatedRiverLevel else historySnapshot.riverLevel
        
        // Season transition blend (cached; recomputed only on day rollover or setting change)
        val seasonTransitionDays = currentSettings.seasonTransitionDays
        val seasonBlend = effectiveSeasonBlend(currentSettings)
        
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
            cloudDensity = currentSettings.cloudDensity,
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
            windMultiplier = currentSettings.windMultiplier,
            treeSwaySpeed = currentSettings.treeSwaySpeed,
            mountainShadowsEnabled = currentSettings.mountainShadowsEnabled,
            treeShadowsEnabled = currentSettings.treeShadowsEnabled,
            shadowStrength = currentSettings.shadowStrength,
            nightShadeStrength = currentSettings.nightShadeStrength,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            snowLevel = effectiveSnowLevel,
            riverLevel = effectiveRiverLevel,
            temperatureCelsius = extendedData.temperatureCelsius,
            weatherDataFresh = WeatherRepository.isDataFresh(context),
            seasonTransitionProgress = seasonBlend.transitionProgress,
            currentSeason = seasonBlend.primarySeason.toStringKey(),
            previousSeason = seasonBlend.previousSeason.toStringKey(),
            seasonTransitionDays = seasonTransitionDays,
            isDebugMode = currentSettings.debugEnabled
        )
    }
    
    /**
     * Smooth time transitions to prevent color jumps when using time slider.
     * On first call (wallpaper load), skip smoothing to avoid sun/moon jumping.
     *
     * Uses frame-rate-independent exponential smoothing: the base lerp rate is
     * scaled by `deltaSec` so behaviour is consistent across 30/60/120 Hz devices.
     */
    private fun smoothTimeTransitions(rawTime: Float, deltaSec: Float): Float {
        // On first call, initialize immediately to avoid jump from 0 to current time
        if (!timeInitialized) {
            timeInitialized = true
            lastSmoothedTime = rawTime
            return rawTime
        }
        
        // Use circular (modular) distance to handle midnight wrap-around correctly.
        // Without this, 23.99→0.01 would compute delta=23.98 and lerp through all hours.
        var delta = rawTime - lastSmoothedTime
        if (delta > 12f) delta -= 24f
        if (delta < -12f) delta += 24f
        
        val timeDelta = kotlin.math.abs(delta)
        
        // Very small delta: just use raw time to avoid floating point drift
        if (timeDelta < 0.001f) {
            lastSmoothedTime = rawTime
            return rawTime
        }
        
        // Base lerp rate (per second). Larger gaps use slower rates so the
        // transition is visually pleasant; smaller gaps converge quickly.
        val baseRate = when {
            timeDelta > 2f -> 1.8f       // Large jump (e.g. time slider): slow start
            timeDelta > 0.5f -> 4.8f     // Medium jump: moderate speed
            timeDelta > 0.1f -> 9.0f     // Small gap: faster to catch up
            else -> 18.0f                // Tiny gap: quick finish
        }
        
        // Frame-rate-independent factor: convert per-second rate to per-frame
        val lerpFactor = (baseRate * deltaSec).coerceIn(0f, 1f)
        
        lastSmoothedTime = (lastSmoothedTime + delta * lerpFactor).mod(24f)
        return lastSmoothedTime
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
