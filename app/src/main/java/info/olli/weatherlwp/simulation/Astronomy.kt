package info.olli.weatherlwp.simulation

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Self-contained ephemeris so calendar-driven visuals never depend on stale provider data.
 *
 * Lunar phases follow Meeus, "Astronomical Algorithms" (2nd ed.) ch. 49 including the planetary
 * terms, the Sun ch. 25 (low accuracy), ΔT the Espenak & Meeus polynomials. Checked against the
 * NASA/Espenak phase tables: within one minute for 2001–2100.
 */
object Astronomy {

    private const val JD_UNIX_EPOCH = 2440587.5
    private const val MS_PER_DAY = 86_400_000.0
    private const val SYNODIC_MONTH = 29.530588861
    private const val TROPICAL_YEAR = 365.242189
    /** JDE of the mean new moon of 2000-01-06 (lunation k = 0). */
    private const val NEW_MOON_EPOCH = 2451550.09766
    private const val CHINA_UTC_OFFSET_DAYS = 8.0 / 24.0
    /** Standard sunrise altitude: refraction plus the Sun's semi-diameter. */
    private const val SUNRISE_ALTITUDE_DEG = -0.833
    /** Below this solar declination day length barely depends on latitude. */
    private const val MIN_DECLINATION_FOR_LATITUDE = 4.0
    /** Average time the Moon spends between rising and transit. */
    private const val MOON_HALF_ARC_HOURS = 6.2f

    fun julianDay(epochMs: Long): Double = epochMs / MS_PER_DAY + JD_UNIX_EPOCH

    /** Moon phase 0..1 (0 = new, 0.25 first quarter, 0.5 full, 0.75 last quarter). */
    fun moonPhase(epochMs: Long): Double {
        val jd = julianDay(epochMs)
        var k = floor((jd - NEW_MOON_EPOCH) / SYNODIC_MONTH)
        while (lunarPhaseJd(k) > jd) k -= 1.0
        while (lunarPhaseJd(k + 1.0) <= jd) k += 1.0
        // Interpolate between the true quarters so full/half moons land on their real instants.
        var start = lunarPhaseJd(k)
        for (quarter in 1..4) {
            val end = lunarPhaseJd(k + quarter * 0.25)
            if (jd < end || quarter == 4) return ((quarter - 1) + (jd - start) / (end - start)) / 4.0
            start = end
        }
        return 0.0
    }

    /** Illuminated fraction of the lunar disc for a [moonPhase] value. */
    fun moonIllumination(phase: Double): Double = (1.0 - cos(2.0 * Math.PI * phase)) / 2.0

    /** Apparent ecliptic longitude of the Sun in degrees (0 = March equinox). */
    fun solarLongitude(epochMs: Long): Double = sunLongitude(julianDay(epochMs))

    /**
     * Sunrise and sunset (epoch ms) on the UTC calendar day [epochDay] for an observer at
     * [latitudeDeg]/[longitudeDeg] (east positive), or null during polar day or night.
     */
    fun sunriseSunset(epochDay: Long, latitudeDeg: Double, longitudeDeg: Double): Pair<Long, Long>? {
        var noonMinutes = 720.0 - 4.0 * longitudeDeg
        var sun = sunEquatorial(epochDay + JD_UNIX_EPOCH + noonMinutes / 1440.0)
        noonMinutes = 720.0 - 4.0 * longitudeDeg - sun.equationOfTimeMinutes
        sun = sunEquatorial(epochDay + JD_UNIX_EPOCH + noonMinutes / 1440.0)
        noonMinutes = 720.0 - 4.0 * longitudeDeg - sun.equationOfTimeMinutes

        val phi = rad(latitudeDeg)
        val delta = rad(sun.declinationDeg)
        val cosH0 = (sin(rad(SUNRISE_ALTITUDE_DEG)) - sin(phi) * sin(delta)) / (cos(phi) * cos(delta))
        if (cosH0 < -1.0 || cosH0 > 1.0) return null
        val halfDayMinutes = Math.toDegrees(acos(cosH0)) * 4.0
        val dayStartMs = epochDay * 86_400_000L
        return Pair(
            dayStartMs + ((noonMinutes - halfDayMinutes) * 60_000.0).roundToLong(),
            dayStartMs + ((noonMinutes + halfDayMinutes) * 60_000.0).roundToLong()
        )
    }

    /**
     * Observer position implied by one day's sunrise and sunset (epoch ms): longitude from solar
     * noon, latitude from day length. Latitude is null near the equinoxes, when day length is
     * about 12 h everywhere. Returns null for inconsistent input.
     */
    fun locationFromSunTimes(sunriseMs: Long, sunsetMs: Long): Pair<Double?, Double>? {
        val dayMs = sunsetMs - sunriseMs
        if (dayMs <= 0L || dayMs >= 86_400_000L) return null
        val noonMs = sunriseMs + dayMs / 2
        val sun = sunEquatorial(julianDay(noonMs))
        val noonUtcMinutes = Math.floorMod(noonMs, 86_400_000L) / 60_000.0
        val longitude = wrap180((720.0 - sun.equationOfTimeMinutes - noonUtcMinutes) / 4.0)

        if (abs(sun.declinationDeg) < MIN_DECLINATION_FOR_LATITUDE) return Pair(null, longitude)
        // sin(h0) = sinφ·sinδ + cosφ·cosδ·cosH0  →  a·sinφ + b·cosφ = c
        val halfDay = rad(dayMs / 86_400_000.0 * 180.0)
        val delta = rad(sun.declinationDeg)
        val a = sin(delta)
        val b = cos(delta) * cos(halfDay)
        val c = sin(rad(SUNRISE_ALTITUDE_DEG))
        val r = sqrt(a * a + b * b)
        if (abs(c / r) > 1.0) return Pair(null, longitude)
        val theta = atan2(b, a)
        val base = asin(c / r)
        val latitude = listOf(base - theta, PI - base - theta)
            .map { Math.toDegrees(it).let { deg -> wrap180(deg) } }
            .firstOrNull { it in -90.0..90.0 }
        return Pair(latitude, longitude)
    }

    /**
     * Position of the Moon along its path from rising (0) to setting (1) at local [hour], or null
     * while it is below the horizon. It transits [phase] × 24 h after the Sun: a new moon travels
     * with the Sun, a full moon is highest at midnight.
     */
    fun moonArcProgress(hour: Float, phase: Float, solarNoonHour: Float): Float? {
        val rise = solarNoonHour + phase * 24f - MOON_HALF_ARC_HOURS
        val sinceRise = ((hour - rise) % 24f + 24f) % 24f
        return if (sinceRise <= 2f * MOON_HALF_ARC_HOURS) sinceRise / (2f * MOON_HALF_ARC_HOURS) else null
    }

    private class SunEquatorial(val declinationDeg: Double, val equationOfTimeMinutes: Double)

    private fun sunEquatorial(jdUt: Double): SunEquatorial {
        val jde = jdUt + deltaTSeconds(yearOf(jdUt)) / 86_400.0
        val t = (jde - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val omega = rad(125.04 - 1934.136 * t)
        val lambda = rad(sunLongitude(jdUt))
        val epsilon = rad(23.439291 - 0.0130042 * t + 0.00256 * cos(omega))
        val alpha = norm360(Math.toDegrees(atan2(cos(epsilon) * sin(lambda), cos(lambda))))
        val declination = Math.toDegrees(asin(sin(epsilon) * sin(lambda)))
        // Meeus 28.1 with Δψ ≈ −0.00478°·sin Ω
        val equationOfTime = wrap180(l0 - 0.0057183 - alpha - 0.00478 * sin(omega) * cos(epsilon))
        return SunEquatorial(declination, equationOfTime * 4.0)
    }

    /** Chinese New Year of Gregorian [year] as epoch day of its date in China Standard Time. */
    fun chineseNewYearEpochDay(year: Int): Long {
        val month11 = lunationContainingSolstice(year - 1)
        val nextMonth11 = lunationContainingSolstice(year)
        // In a 13-month year the first month without a principal term is intercalary;
        // if that is month 11 or 12, New Year moves one lunation later.
        val leap = nextMonth11 - month11 == 13L &&
            (!hasPrincipalTerm(month11 + 1) || !hasPrincipalTerm(month11 + 2))
        return chinaDay(newMoonJd(month11 + if (leap) 3 else 2))
    }

    /**
     * JD (UT) of the true lunar phase for lunation [k]: integer = new moon, +0.25 first quarter,
     * +0.5 full moon, +0.75 last quarter; k = 0 is the new moon of 2000-01-06.
     */
    internal fun lunarPhaseJd(k: Double): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        var jde = NEW_MOON_EPOCH + SYNODIC_MONTH * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2
        val m = rad(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3)
        val mp = rad(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4)
        val f = rad(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4)
        val om = rad(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3)

        jde += when (val quarter = ((k - floor(k)) * 4).roundToInt() % 4) {
            0, 2 -> {
                val c = if (quarter == 0) NEW_MOON_TERMS else FULL_MOON_TERMS
                c[0] * sin(mp) + c[1] * e * sin(m) + c[2] * sin(2 * mp) + c[3] * sin(2 * f) +
                    c[4] * e * sin(mp - m) + c[5] * e * sin(mp + m) + c[6] * e * e * sin(2 * m) +
                    c[7] * sin(mp - 2 * f) + c[8] * sin(mp + 2 * f) + c[9] * e * sin(2 * mp + m) +
                    c[10] * sin(3 * mp) + c[11] * e * sin(m + 2 * f) + c[12] * e * sin(m - 2 * f) +
                    c[13] * e * sin(2 * mp - m) + c[14] * sin(om) + c[15] * sin(mp + 2 * m) +
                    c[16] * sin(2 * mp - 2 * f) + c[17] * sin(3 * m) + c[18] * sin(mp + m - 2 * f) +
                    c[19] * sin(2 * mp + 2 * f) + c[20] * sin(mp + m + 2 * f) + c[21] * sin(mp - m + 2 * f) +
                    c[22] * sin(mp - m - 2 * f) + c[23] * sin(3 * mp + m) + c[24] * sin(4 * mp)
            }
            else -> {
                val w = 0.00306 - 0.00038 * e * cos(m) + 0.00026 * cos(mp) -
                    0.00002 * cos(mp - m) + 0.00002 * cos(mp + m) + 0.00002 * cos(2 * f)
                val terms = -0.62801 * sin(mp) + 0.17172 * e * sin(m) - 0.01183 * e * sin(mp + m) +
                    0.00862 * sin(2 * mp) + 0.00804 * sin(2 * f) + 0.00454 * e * sin(mp - m) +
                    0.00204 * e * e * sin(2 * m) - 0.00180 * sin(mp - 2 * f) - 0.00070 * sin(mp + 2 * f) -
                    0.00040 * sin(3 * mp) - 0.00034 * e * sin(2 * mp - m) + 0.00032 * e * sin(m + 2 * f) +
                    0.00032 * e * sin(m - 2 * f) - 0.00028 * e * e * sin(mp + 2 * m) +
                    0.00027 * e * sin(2 * mp + m) - 0.00017 * sin(om) - 0.00005 * sin(mp - m - 2 * f) +
                    0.00004 * sin(2 * mp + 2 * f) - 0.00004 * sin(mp + m + 2 * f) + 0.00004 * sin(mp - 2 * m) +
                    0.00003 * sin(mp + m - 2 * f) + 0.00003 * sin(3 * m) + 0.00002 * sin(2 * mp - 2 * f) +
                    0.00002 * sin(mp - m + 2 * f) - 0.00002 * sin(3 * mp + m)
                if (quarter == 1) terms + w else terms - w
            }
        }

        for (i in PLANETARY_COEFFS.indices) {
            var arg = PLANETARY_BASE[i] + PLANETARY_RATE[i] * k
            if (i == 0) arg -= 0.009173 * t2
            jde += PLANETARY_COEFFS[i] * sin(rad(arg))
        }
        return jde - deltaTSeconds(yearOf(jde)) / 86_400.0
    }

    /** ΔT = TT − UT in seconds (Espenak & Meeus, Five Millennium Canon). */
    internal fun deltaTSeconds(year: Double): Double {
        val t = year - 2000.0
        val u = (year - 1820.0) / 100.0
        return when {
            year < 2005.0 -> 63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t +
                0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
            year < 2050.0 -> 62.92 + 0.32217 * t + 0.005589 * t * t
            year < 2150.0 -> -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - year)
            else -> -20.0 + 32.0 * u * u
        }
    }

    private fun yearOf(jd: Double): Double = 2000.0 + (jd - 2451545.0) / 365.25

    private fun sunLongitude(jdUt: Double): Double {
        val jde = jdUt + deltaTSeconds(yearOf(jdUt)) / 86_400.0
        val t = (jde - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = rad(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val center = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val omega = rad(125.04 - 1934.136 * t)
        return norm360(l0 + center - 0.00569 - 0.00478 * sin(omega))
    }

    /** JD (UT) of the first instant at or after [fromJd] when the Sun's longitude is [targetDeg]. */
    private fun solarLongitudeJd(targetDeg: Double, fromJd: Double): Double {
        var jd = fromJd + norm360(targetDeg - sunLongitude(fromJd)) * TROPICAL_YEAR / 360.0
        repeat(4) { jd += wrap180(targetDeg - sunLongitude(jd)) * TROPICAL_YEAR / 360.0 }
        return jd
    }

    private fun newMoonJd(k: Long): Double = lunarPhaseJd(k.toDouble())

    private fun chinaDay(jd: Double): Long = floor(jd - JD_UNIX_EPOCH + CHINA_UTC_OFFSET_DAYS).toLong()

    /** Lunation whose first day (China date) is on or before [day]. */
    private fun lunationStartingOnOrBefore(day: Long): Long {
        var k = floor((day + JD_UNIX_EPOCH - NEW_MOON_EPOCH) / SYNODIC_MONTH).toLong()
        while (chinaDay(newMoonJd(k)) > day) k--
        while (chinaDay(newMoonJd(k + 1)) <= day) k++
        return k
    }

    private fun lunationContainingSolstice(year: Int): Long {
        val dec1Jd = LocalDate.of(year, 12, 1).toEpochDay() + JD_UNIX_EPOCH
        return lunationStartingOnOrBefore(chinaDay(solarLongitudeJd(270.0, dec1Jd)))
    }

    /** Whether lunation [k] contains a principal solar term (Sun longitude a multiple of 30°). */
    private fun hasPrincipalTerm(k: Long): Boolean {
        val startDay = chinaDay(newMoonJd(k))
        val endDay = chinaDay(newMoonJd(k + 1))
        val startJd = startDay + JD_UNIX_EPOCH - CHINA_UTC_OFFSET_DAYS
        val target = norm360(ceil(sunLongitude(startJd) / 30.0) * 30.0)
        return chinaDay(solarLongitudeJd(target, startJd)) < endDay
    }

    private fun rad(deg: Double): Double = Math.toRadians(deg)

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private fun wrap180(deg: Double): Double = norm360(deg + 180.0) - 180.0

    private val NEW_MOON_TERMS = doubleArrayOf(
        -0.40720, 0.17241, 0.01608, 0.01039, 0.00739, -0.00514, 0.00208, -0.00111, -0.00057, 0.00056,
        -0.00042, 0.00042, 0.00038, -0.00024, -0.00017, -0.00007, 0.00004, 0.00004, 0.00003, 0.00003,
        -0.00003, 0.00003, -0.00002, -0.00002, 0.00002
    )
    private val FULL_MOON_TERMS = doubleArrayOf(
        -0.40614, 0.17302, 0.01614, 0.01043, 0.00734, -0.00515, 0.00209, -0.00111, -0.00057, 0.00056,
        -0.00042, 0.00042, 0.00038, -0.00024, -0.00017, -0.00007, 0.00004, 0.00004, 0.00003, 0.00003,
        -0.00003, 0.00003, -0.00002, -0.00002, 0.00002
    )
    private val PLANETARY_COEFFS = doubleArrayOf(
        0.000325, 0.000165, 0.000164, 0.000126, 0.000110, 0.000062, 0.000060,
        0.000056, 0.000047, 0.000042, 0.000040, 0.000037, 0.000035, 0.000023
    )
    private val PLANETARY_BASE = doubleArrayOf(
        299.77, 251.88, 251.83, 349.42, 84.66, 141.74, 207.14,
        154.84, 34.52, 207.19, 291.34, 161.72, 239.56, 331.55
    )
    private val PLANETARY_RATE = doubleArrayOf(
        0.107408, 0.016321, 26.651886, 36.412478, 18.206239, 53.303771, 2.453732,
        7.306860, 27.261239, 0.121824, 1.844379, 24.198154, 25.513099, 3.592518
    )
}
