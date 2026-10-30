package info.olli.weatherlwp.simulation

import android.content.Context
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import info.olli.weatherlwp.util.SeasonHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Inclusive [start, end] window on a 24 h clock; bounds outside [0, 24) wrap past midnight. */
internal fun inHourWindow(time: Float, start: Float, end: Float): Boolean {
    val t = normalizeHour(time)
    val s = normalizeHour(start)
    val e = normalizeHour(end)
    return if (s <= e) t in s..e else t >= s || t <= e
}

private fun normalizeHour(hour: Float): Float {
    val h = hour % 24f
    return if (h < 0f) h + 24f else h
}

/**
 * Main controller for the simulation.
 * 
 * Coordinates the clock, settings, and weather data to produce
 * a consistent SimulationState that layers can use for rendering.
 */
class SimulationController(
    private val context: Context,
    private val windSimulator: WindSimulator = WindSimulator(),
    private val historyExecutor: ExecutorService = WeatherHistory.recordExecutor,
    private val weatherHistory: WeatherHistory = WeatherHistory.shared(context)
) {
    
    private val clock = SimulationClock()
    @Volatile private var settings = SimulationSettings.fromSnapshot(emptyMap<String, Any?>())
    private val historyRecordPending = AtomicBoolean(false)
    private var destroyed = false
    private var stateSnapshot = SimulationState.default()
    
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    
    // For smooth time transitions
    private var lastSmoothedTime = 0f
    private var timeInitialized = false  // Skip smoothing on first frame
    
    // Frame delta tracking
    private var lastUpdateMs: Long? = null
    private var lastRecordAttemptMs: Long? = null
    private var appliedSunrise = Long.MIN_VALUE
    private var appliedSunset = Long.MIN_VALUE
    private var appliedSunHourBucket = Long.MIN_VALUE

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
        syncSunTimes()
    }

    /**
     * Weather updates no longer trigger settings reloads, so pick up new sun times here.
     * Re-resolved hourly so projected (stale or missing provider) times follow the date.
     */
    private fun syncSunTimes() {
        val sunrise = WeatherRepository.getSunrise(context)
        val sunset = WeatherRepository.getSunset(context)
        val nowMs = System.currentTimeMillis()
        val hourBucket = nowMs / 3_600_000L
        if (sunrise == appliedSunrise && sunset == appliedSunset && hourBucket == appliedSunHourBucket) return
        appliedSunrise = sunrise
        appliedSunset = sunset
        appliedSunHourBucket = hourBucket
        val (rise, set) = SunTimes.resolve(
            sunrise, sunset, nowMs, java.util.TimeZone.getDefault(), WeatherRepository.getInferredLatitude(context)
        )
        clock.updateSunTimes(rise, set)
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
        lastUpdateMs = null
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

    fun getState(): SimulationState = stateSnapshot

    private var cachedMoonPhase = 0f
    private var cachedMoonPhaseMs = Long.MIN_VALUE / 2

    /** The phase moves ~0.0006 per hour, so recomputing once a minute is plenty. */
    private fun astronomicalMoonPhase(nowMs: Long): Float {
        if (kotlin.math.abs(nowMs - cachedMoonPhaseMs) >= 60_000L) {
            cachedMoonPhase = Astronomy.moonPhase(nowMs).toFloat()
            cachedMoonPhaseMs = nowMs
        }
        return cachedMoonPhase
    }

    fun advance(frameTimeMs: Long = System.nanoTime() / 1_000_000L) {
        if (destroyed || lastUpdateMs == frameTimeMs) return
        val currentSettings = settings
        syncSunTimes()

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
        val deltaMs = lastUpdateMs?.let { (frameTimeMs - it).coerceIn(0, 100) } ?: 16L
        lastUpdateMs = frameTimeMs
        val deltaSec = deltaMs / 1000f
        
        val windGust = windSimulator.calculateWindGust(effectiveWind, deltaMs, frameTimeMs)
        
        // Get extended weather data from repository
        val extendedData = WeatherRepository.getExtendedData(context)
        val precipProbability = extendedData.precipProbability / 100f
        val cloudCover = extendedData.cloudCover / 100f
        val humidity = extendedData.humidity / 100f
        val visibility = WeatherRepository.getNormalizedVisibility(context)
        val moonPhase = if (currentSettings.debugEnabled) {
            currentSettings.moonPhase  // Already 0-1
        } else {
            astronomicalMoonPhase(clock.getAbsoluteTimeMs())
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

        // Moonlight only while the Moon is up, dimmer close to the horizon.
        val moonArc = Astronomy.moonArcProgress(rawTime, moonPhase, (sunriseHours + sunsetHours) / 2f)
        val moonAltitude = moonArc?.let { (kotlin.math.sin(Math.PI * it).toFloat() * 2f).coerceAtMost(1f) } ?: 0f
        val moonIllumination = Astronomy.moonIllumination(moonPhase.toDouble()).toFloat() * moonAltitude
        
        recordWeatherIfDue(frameTimeMs, currentSettings, effectiveWeather)
        val historySnapshot = weatherHistory.getSnapshot()
        
        // Apply simulation overrides for snow/river levels when enabled
        val effectiveSnowLevel = if (currentSettings.useSimulatedWeatherHistory)
            currentSettings.simulatedSnowLevel else historySnapshot.snowDepth
        val effectiveRiverLevel = if (currentSettings.useSimulatedWeatherHistory)
            currentSettings.simulatedRiverLevel else historySnapshot.riverLevel
        
        // Season transition blend (cached; recomputed only on day rollover or setting change)
        val seasonTransitionDays = currentSettings.seasonTransitionDays
        val seasonBlend = effectiveSeasonBlend(currentSettings)
        
        stateSnapshot = SimulationState(
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

    private fun recordWeatherIfDue(frameTimeMs: Long, currentSettings: SimulationSettings, effectiveWeather: WeatherType) {
        val previousAttempt = lastRecordAttemptMs
        if (previousAttempt != null && frameTimeMs - previousAttempt < 60_000L) return
        if (!historyRecordPending.compareAndSet(false, true)) return
        lastRecordAttemptMs = frameTimeMs
        historyExecutor.execute {
            try {
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
            } finally {
                historyRecordPending.set(false)
            }
        }
    }

    fun destroy() {
        destroyed = true
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
