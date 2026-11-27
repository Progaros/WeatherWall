package info.olli.weatherlwp.simulation

import java.time.Instant
import java.util.TimeZone

/**
 * Picks the sunrise/sunset the sky follows. Fresh provider times are used as-is; otherwise the
 * times are computed for today from the location implied by the last provider data (or the time
 * zone), so day length keeps following the seasons even without weather updates.
 */
object SunTimes {
    /** Provider times older than this belong to another day and get projected to today. */
    const val FRESH_MS = 20 * 3_600_000L
    /** Mid-latitude fallback when no provider data ever revealed the latitude. */
    const val DEFAULT_LATITUDE = 48.0

    /**
     * @param sunriseSec provider sunrise (Unix seconds, ≤ 0 = unknown)
     * @param storedLatitude latitude inferred from earlier provider data, if any
     * @return sunrise and sunset in Unix seconds; (-1, -1) keeps the clock defaults
     */
    fun resolve(
        sunriseSec: Long,
        sunsetSec: Long,
        nowMs: Long,
        zone: TimeZone,
        storedLatitude: Double?
    ): Pair<Long, Long> {
        val haveProvider = sunriseSec > 0 && sunsetSec > 0
        if (haveProvider && kotlin.math.abs(nowMs - sunriseSec * 1000L) < FRESH_MS) {
            return Pair(sunriseSec, sunsetSec)
        }
        val inferred = if (haveProvider) Astronomy.locationFromSunTimes(sunriseSec * 1000L, sunsetSec * 1000L) else null
        val latitude = inferred?.first ?: storedLatitude ?: DEFAULT_LATITUDE
        val longitude = inferred?.second ?: (zone.rawOffset / 3_600_000.0 * 15.0)
        val localDate = Instant.ofEpochMilli(nowMs).atZone(zone.toZoneId()).toLocalDate()
        val times = Astronomy.sunriseSunset(localDate.toEpochDay(), latitude, longitude)
            ?: return if (haveProvider) Pair(sunriseSec, sunsetSec) else Pair(-1L, -1L)
        return Pair(times.first / 1000L, times.second / 1000L)
    }
}
