package info.olli.weatherlwp.simulation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import info.olli.weatherlwp.weather.WeatherRepository
import info.olli.weatherlwp.weather.WeatherType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Tracks weather conditions over time to derive accumulated snow depth,
 * river water level, and other history-dependent scene properties.
 *
 * ## Data Model
 * - Records hourly weather snapshots (temp, precip type, intensity)
 * - Aggregates into daily summaries stored as JSON in SharedPreferences
 * - Keeps up to [MAX_HISTORY_DAYS] days of history
 *
 * ## Derived Values
 * - **Snow depth** (0–1): Accumulates from snowfall, decays from warm temps and rain
 * - **River level** (0–1): Rises from rainfall + snowmelt, drains over time
 *
 * Thread safety: All public methods are synchronized. Render thread reads
 * cached values via [getSnapshot]; recording happens on weather-update thread.
 */
class WeatherHistory(private val context: Context) {

    companion object {
        private const val TAG = "WeatherHistory"
        private const val PREFS_NAME = "weather_history"
        private const val KEY_DAILY_HISTORY = "daily_history_json"
        private const val KEY_LAST_RECORD_HOUR = "last_record_hour"
        private const val KEY_SNOW_DEPTH = "snow_depth"
        private const val KEY_RIVER_LEVEL = "river_level"
        const val MAX_HISTORY_DAYS = 14

        // Physics tuning
        private const val SNOW_ACCUMULATION_RATE = 0.06f   // per hour of heavy snow
        private const val SNOW_MELT_RATE_PER_DEGREE = 0.008f // per °C above 0 per hour
        private const val SNOW_RAIN_WASH_RATE = 0.03f      // per hour of rain on snow
        private const val RIVER_RAIN_RATE = 0.04f           // per hour of heavy rain
        private const val RIVER_SNOWMELT_RATE = 0.03f       // per unit of snow melted
        private const val RIVER_DRAIN_RATE = 0.012f         // natural drainage per hour
        private const val RIVER_EVAPORATION_RATE = 0.004f   // additional loss per hour when warm
    }

    /** Immutable snapshot for the render thread. */
    data class Snapshot(
        val snowDepth: Float = 0f,      // 0–1 normalized snow depth
        val riverLevel: Float = 0f,     // 0–1 normalized river water level
        val avgTempToday: Float = Float.NaN,
        val totalRainToday: Float = 0f,   // accumulated rain hours today
        val totalSnowToday: Float = 0f,   // accumulated snow hours today
        val daysOfData: Int = 0
    )

    /** One day's aggregated weather data. */
    data class DaySummary(
        val dayId: Int,             // days since epoch
        val avgTemp: Float,         // average temp in °C (NaN if unknown)
        val minTemp: Float,
        val maxTemp: Float,
        val rainHours: Float,       // weighted precipitation hours (rain)
        val snowHours: Float,       // weighted precipitation hours (snow)
        val samples: Int
    )

    private val historyPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Running accumulation state
    private var snowDepth: Float = 0f
    private var riverLevel: Float = 0f
    private var dailyHistory: MutableList<DaySummary> = mutableListOf()

    // Intra-day accumulators
    private var todayTempSum = 0f
    private var todayTempCount = 0
    private var todayMinTemp = Float.MAX_VALUE
    private var todayMaxTemp = Float.MIN_VALUE
    private var todayRainHours = 0f
    private var todaySnowHours = 0f
    private var currentDayId = 0
    private var lastRecordHour = -1

    @Volatile
    private var cachedSnapshot = Snapshot()

    init {
        loadFromPrefs()
        recalculateLevels()
        updateSnapshot()
    }

    // ========================= Public API =========================

    /**
     * Thread-safe snapshot for the render thread.
     * Call from SimulationController.getState() without synchronization.
     */
    fun getSnapshot(): Snapshot = cachedSnapshot

    /**
     * Record current weather conditions. Call periodically (e.g., every frame or on weather update).
     * Internally deduplicates to at most one sample per hour.
     */
    @Synchronized
    fun recordCurrentWeather() {
        val cal = Calendar.getInstance()
        val dayId = dayIdFrom(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // Only record once per hour
        if (dayId == currentDayId && hour == lastRecordHour) return

        // Day rollover?
        if (dayId != currentDayId && currentDayId > 0) {
            flushDay()
        }
        currentDayId = dayId
        lastRecordHour = hour

        // Read current weather
        val weatherType = WeatherRepository.getWeatherType(context)
        val extData = WeatherRepository.getExtendedData(context)
        val temp = extData.temperatureCelsius
        val precipProb = extData.precipProbability / 100f

        // Accumulate intra-day
        if (!temp.isNaN()) {
            todayTempSum += temp
            todayTempCount++
            if (temp < todayMinTemp) todayMinTemp = temp
            if (temp > todayMaxTemp) todayMaxTemp = temp
        }

        // Weighted precip hours: intensity × precipProbability
        val precipWeight = precipProb.coerceIn(0f, 1f)
        when (weatherType) {
            WeatherType.SNOW -> todaySnowHours += precipWeight
            WeatherType.RAIN, WeatherType.THUNDERSTORM -> todayRainHours += precipWeight
            WeatherType.HAIL -> {
                todayRainHours += precipWeight * 0.5f
                todaySnowHours += precipWeight * 0.5f
            }
            else -> { /* no precip */ }
        }

        // Update running levels for this hour
        updateLevelsForHour(weatherType, temp, precipWeight)

        // Persist
        saveToPrefs()
        updateSnapshot()
    }

    /**
     * Force recalculation of snow/river from full history.
     * Use after loading or when settings change.
     */
    @Synchronized
    fun recalculateLevels() {
        snowDepth = 0f
        riverLevel = 0f

        for (day in dailyHistory) {
            simulateDayOnLevels(day)
        }

        // Clamp
        snowDepth = snowDepth.coerceIn(0f, 1f)
        riverLevel = riverLevel.coerceIn(0f, 1f)
        updateSnapshot()
    }

    /**
     * Get daily history for UI or debugging.
     */
    @Synchronized
    fun getHistory(): List<DaySummary> = dailyHistory.toList()

    /**
     * Clear all history (for testing or reset).
     */
    @Synchronized
    fun clearHistory() {
        dailyHistory.clear()
        snowDepth = 0f
        riverLevel = 0f
        todayTempSum = 0f
        todayTempCount = 0
        todayMinTemp = Float.MAX_VALUE
        todayMaxTemp = Float.MIN_VALUE
        todayRainHours = 0f
        todaySnowHours = 0f
        lastRecordHour = -1
        saveToPrefs()
        updateSnapshot()
    }

    // ========================= Internal =========================

    private fun updateLevelsForHour(
        weatherType: WeatherType,
        temp: Float,
        precipWeight: Float
    ) {
        val effectiveTemp = if (temp.isNaN()) estimateTempFromWeather(weatherType) else temp

        val isSnowing = weatherType == WeatherType.SNOW || weatherType == WeatherType.HAIL
        val snowRate = if (weatherType == WeatherType.HAIL) 0.5f else 1f
        val snowHours = if (isSnowing) precipWeight * snowRate else 0f
        val isRaining = weatherType == WeatherType.RAIN || weatherType == WeatherType.THUNDERSTORM
        val rainHours = if (isRaining) precipWeight else 0f

        applyPhysicsStep(effectiveTemp, snowHours, rainHours, hours = 1)
    }

    /**
     * Simulate a full day's effect on snow/river levels (for recalculation from history).
     */
    private fun simulateDayOnLevels(day: DaySummary) {
        val temp = if (day.avgTemp.isNaN()) 10f else day.avgTemp
        val hours = day.samples.coerceAtLeast(1)

        applyPhysicsStep(temp, day.snowHours, day.rainHours, hours)
    }

    /**
     * Core physics step shared by both hourly updates and day simulation.
     * @param temp Effective temperature in °C
     * @param snowHours Weighted snow accumulation hours
     * @param rainHours Weighted rain hours
     * @param hours Number of hours this step represents (1 for hourly, N for daily)
     */
    private fun applyPhysicsStep(temp: Float, snowHours: Float, rainHours: Float, hours: Int) {
        // Snow accumulation
        snowDepth += SNOW_ACCUMULATION_RATE * snowHours

        // Snow melting
        if (temp > 0f) {
            val melt = SNOW_MELT_RATE_PER_DEGREE * temp * hours
            val melted = minOf(melt, snowDepth)
            snowDepth -= melted
            riverLevel += melted * RIVER_SNOWMELT_RATE
        }

        // Rain washes snow and fills river
        if (rainHours > 0f) {
            if (snowDepth > 0f) {
                val washed = minOf(SNOW_RAIN_WASH_RATE * rainHours, snowDepth)
                snowDepth -= washed
                riverLevel += washed * RIVER_SNOWMELT_RATE
            }
            riverLevel += RIVER_RAIN_RATE * rainHours
        }

        // Natural drainage
        riverLevel -= RIVER_DRAIN_RATE * hours
        if (temp > 15f) {
            riverLevel -= RIVER_EVAPORATION_RATE * hours
        }

        // Clamp
        snowDepth = snowDepth.coerceIn(0f, 1f)
        riverLevel = riverLevel.coerceIn(0f, 1f)
    }

    /** Estimate temperature when no sensor data available. */
    private fun estimateTempFromWeather(type: WeatherType): Float = when (type) {
        WeatherType.SNOW -> -2f
        WeatherType.HAIL -> 2f
        WeatherType.FOG -> 5f
        WeatherType.RAIN, WeatherType.THUNDERSTORM -> 12f
        else -> 15f  // Clear/cloudy default
    }

    private fun flushDay() {
        if (todayTempCount == 0 && todayRainHours == 0f && todaySnowHours == 0f) return

        val summary = DaySummary(
            dayId = currentDayId,
            avgTemp = if (todayTempCount > 0) todayTempSum / todayTempCount else Float.NaN,
            minTemp = if (todayMinTemp != Float.MAX_VALUE) todayMinTemp else Float.NaN,
            maxTemp = if (todayMaxTemp != Float.MIN_VALUE) todayMaxTemp else Float.NaN,
            rainHours = todayRainHours,
            snowHours = todaySnowHours,
            samples = todayTempCount.coerceAtLeast(
                (todayRainHours + todaySnowHours).toInt().coerceAtLeast(1)
            )
        )
        dailyHistory.add(summary)

        // Trim old data
        while (dailyHistory.size > MAX_HISTORY_DAYS) {
            dailyHistory.removeAt(0)
        }

        // Reset accumulators
        todayTempSum = 0f
        todayTempCount = 0
        todayMinTemp = Float.MAX_VALUE
        todayMaxTemp = Float.MIN_VALUE
        todayRainHours = 0f
        todaySnowHours = 0f
    }

    private fun updateSnapshot() {
        cachedSnapshot = Snapshot(
            snowDepth = snowDepth,
            riverLevel = riverLevel,
            avgTempToday = if (todayTempCount > 0) todayTempSum / todayTempCount else Float.NaN,
            totalRainToday = todayRainHours,
            totalSnowToday = todaySnowHours,
            daysOfData = dailyHistory.size
        )
    }

    // ========================= Persistence =========================

    private fun saveToPrefs() {
        val json = JSONArray()
        for (day in dailyHistory) {
            json.put(JSONObject().apply {
                put("d", day.dayId)
                put("at", if (day.avgTemp.isNaN()) JSONObject.NULL else day.avgTemp.toDouble())
                put("mn", if (day.minTemp.isNaN()) JSONObject.NULL else day.minTemp.toDouble())
                put("mx", if (day.maxTemp.isNaN()) JSONObject.NULL else day.maxTemp.toDouble())
                put("rh", day.rainHours.toDouble())
                put("sh", day.snowHours.toDouble())
                put("s", day.samples)
            })
        }

        historyPrefs.edit()
            .putString(KEY_DAILY_HISTORY, json.toString())
            .putInt(KEY_LAST_RECORD_HOUR, lastRecordHour)
            .putFloat(KEY_SNOW_DEPTH, snowDepth)
            .putFloat(KEY_RIVER_LEVEL, riverLevel)
            .apply()
    }

    private fun loadFromPrefs() {
        snowDepth = historyPrefs.getFloat(KEY_SNOW_DEPTH, 0f)
        riverLevel = historyPrefs.getFloat(KEY_RIVER_LEVEL, 0f)
        lastRecordHour = historyPrefs.getInt(KEY_LAST_RECORD_HOUR, -1)
        currentDayId = dayIdFrom(Calendar.getInstance())

        val jsonStr = historyPrefs.getString(KEY_DAILY_HISTORY, null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            dailyHistory.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                dailyHistory.add(DaySummary(
                    dayId = obj.getInt("d"),
                    avgTemp = if (obj.isNull("at")) Float.NaN else obj.getDouble("at").toFloat(),
                    minTemp = if (obj.isNull("mn")) Float.NaN else obj.getDouble("mn").toFloat(),
                    maxTemp = if (obj.isNull("mx")) Float.NaN else obj.getDouble("mx").toFloat(),
                    rainHours = obj.getDouble("rh").toFloat(),
                    snowHours = obj.getDouble("sh").toFloat(),
                    samples = obj.getInt("s")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load weather history", e)
            dailyHistory.clear()
        }
    }

    private fun dayIdFrom(cal: Calendar): Int {
        // Days since epoch (unix day number)
        return (cal.timeInMillis / (24L * 60 * 60 * 1000)).toInt()
    }
}
