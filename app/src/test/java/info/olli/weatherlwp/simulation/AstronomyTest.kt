package info.olli.weatherlwp.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

class AstronomyTest {

    private fun utcMs(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Distance between two phases on the 0..1 circle. */
    private fun phaseDistance(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 1.0 - it) }

    @Test
    fun `phases match the NASA Espenak table across the century`() {
        // UT instants from eclipse.gsfc.nasa.gov/phase/phases2001.html
        val reference = listOf(
            utcMs(2001, 1, 24, 13, 7) to 0.0,
            utcMs(2026, 2, 17, 12, 1) to 0.0,
            utcMs(2026, 3, 3, 11, 38) to 0.5,
            utcMs(2026, 8, 12, 17, 37) to 0.0,
            utcMs(2030, 2, 2, 16, 7) to 0.0,
            utcMs(2042, 1, 21, 20, 42) to 0.0,
            utcMs(2063, 2, 28, 7, 38) to 0.0,
            utcMs(2075, 12, 22, 8, 48) to 0.5,
            utcMs(2099, 9, 29, 10, 45) to 0.5,
            utcMs(2100, 12, 1, 13, 0) to 0.0
        )
        // Two minutes is 0.00005 of a lunation.
        for ((ms, expected) in reference) {
            val phase = Astronomy.moonPhase(ms)
            assertTrue("phase at $ms = $phase", phaseDistance(phase, expected) < 0.00005)
        }
    }

    @Test
    fun `phase advances monotonically and wraps once per lunation`() {
        var previous = Astronomy.moonPhase(utcMs(2026, 1, 1, 0, 0))
        var wraps = 0
        var ms = utcMs(2026, 1, 1, 0, 0)
        repeat(24 * 365) {
            ms += 3_600_000L
            val phase = Astronomy.moonPhase(ms)
            if (phase < previous) wraps++ else assertTrue(phase - previous < 0.003)
            previous = phase
        }
        assertEquals(12, wraps)
    }

    @Test
    fun `illumination is dark at new and full at full moon`() {
        assertEquals(0.0, Astronomy.moonIllumination(0.0), 1e-9)
        assertEquals(1.0, Astronomy.moonIllumination(0.5), 1e-9)
        assertEquals(0.5, Astronomy.moonIllumination(0.25), 1e-9)
    }

    @Test
    fun `chinese new year matches published dates including leap-month years`() {
        val expected = mapOf(
            1985 to LocalDate.of(1985, 2, 20), // leap month 10 in 1984 breaks the Jan 21-Feb 20 shortcut
            2025 to LocalDate.of(2025, 1, 29),
            2026 to LocalDate.of(2026, 2, 17),
            2027 to LocalDate.of(2027, 2, 6),
            2028 to LocalDate.of(2028, 1, 26),
            2030 to LocalDate.of(2030, 2, 3), // new moon 7 minutes after midnight China time
            2033 to LocalDate.of(2033, 1, 31),
            2034 to LocalDate.of(2034, 2, 19), // after the 2033 leap 11th month
            2042 to LocalDate.of(2042, 1, 22),
            2050 to LocalDate.of(2050, 1, 23)
        )
        for ((year, date) in expected) {
            assertEquals("CNY $year", date.toEpochDay(), Astronomy.chineseNewYearEpochDay(year))
        }
    }

    @Test
    fun `chinese new year stays in its window for the next century`() {
        for (year in 2026..2130) {
            val date = LocalDate.ofEpochDay(Astronomy.chineseNewYearEpochDay(year))
            assertTrue("CNY $year = $date", date >= LocalDate.of(year, 1, 21) && date <= LocalDate.of(year, 2, 20))
        }
    }

    @Test
    fun `sunrise and sunset match published times for Berlin and Sydney`() {
        // timeanddate.com: Berlin 2026-06-21 04:43/21:33 CEST, 2026-12-21 08:15/15:54 CET
        val summer = Astronomy.sunriseSunset(LocalDate.of(2026, 6, 21).toEpochDay(), 52.52, 13.405)!!
        assertEquals(utcMs(2026, 6, 21, 2, 43).toDouble(), summer.first.toDouble(), 120_000.0)
        assertEquals(utcMs(2026, 6, 21, 19, 33).toDouble(), summer.second.toDouble(), 120_000.0)
        val winter = Astronomy.sunriseSunset(LocalDate.of(2026, 12, 21).toEpochDay(), 52.52, 13.405)!!
        assertEquals(utcMs(2026, 12, 21, 7, 15).toDouble(), winter.first.toDouble(), 120_000.0)
        assertEquals(utcMs(2026, 12, 21, 14, 54).toDouble(), winter.second.toDouble(), 120_000.0)
        // Sydney 2026-06-21 07:00/16:53 AEST: sunrise falls on the previous UTC day.
        val sydney = Astronomy.sunriseSunset(LocalDate.of(2026, 6, 21).toEpochDay(), -33.87, 151.21)!!
        assertEquals(utcMs(2026, 6, 20, 21, 0).toDouble(), sydney.first.toDouble(), 120_000.0)
        assertEquals(utcMs(2026, 6, 21, 6, 53).toDouble(), sydney.second.toDouble(), 120_000.0)
        // Midsummer above the Arctic Circle: no sunset.
        assertEquals(null, Astronomy.sunriseSunset(LocalDate.of(2026, 6, 21).toEpochDay(), 78.2, 15.6))
    }

    @Test
    fun `location is recovered from one day of sun times`() {
        val (rise, set) = Astronomy.sunriseSunset(LocalDate.of(2026, 11, 10).toEpochDay(), -33.87, 151.21)!!
        val (latitude, longitude) = Astronomy.locationFromSunTimes(rise, set)!!
        assertEquals(-33.87, latitude!!, 0.2)
        assertEquals(151.21, longitude, 0.2)
        // Near the equinox day length says nothing about latitude.
        val (eqRise, eqSet) = Astronomy.sunriseSunset(LocalDate.of(2026, 3, 20).toEpochDay(), 52.52, 13.405)!!
        assertEquals(null, Astronomy.locationFromSunTimes(eqRise, eqSet)!!.first)
    }

    @Test
    fun `moon rises with its phase`() {
        // Full moon: up from sunset through midnight to sunrise.
        assertEquals(0.5f, Astronomy.moonArcProgress(0f, 0.5f, 12f)!!, 0.01f)
        assertEquals(null, Astronomy.moonArcProgress(12f, 0.5f, 12f))
        // First quarter: highest at 18:00, set around midnight, visible in the afternoon.
        assertEquals(0.5f, Astronomy.moonArcProgress(18f, 0.25f, 12f)!!, 0.01f)
        assertTrue(Astronomy.moonArcProgress(14f, 0.25f, 12f) != null)
        assertEquals(null, Astronomy.moonArcProgress(3f, 0.25f, 12f))
    }

    @Test
    fun `sun times are projected when provider data is stale or missing`() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val now = utcMs(2026, 12, 21, 12, 0)
        // Fresh provider data is used unchanged.
        val freshRise = utcMs(2026, 12, 21, 7, 0) / 1000
        assertEquals(Pair(freshRise, freshRise + 8 * 3600), SunTimes.resolve(freshRise, freshRise + 8 * 3600, now, zone, null))

        // Midsummer data from Berlin, half a year old: today's short winter day is projected.
        val (oldRise, oldSet) = Astronomy.sunriseSunset(LocalDate.of(2026, 6, 21).toEpochDay(), 52.52, 13.405)!!
        val (rise, set) = SunTimes.resolve(oldRise / 1000, oldSet / 1000, now, zone, null)
        assertEquals(utcMs(2026, 12, 21, 7, 15) / 1000.0, rise.toDouble(), 180.0)
        assertEquals(utcMs(2026, 12, 21, 14, 54) / 1000.0, set.toDouble(), 180.0)

        // No provider data at all: default latitude, longitude from the time zone (15°E).
        val (defRise, defSet) = SunTimes.resolve(-1, -1, now, zone, null)
        assertTrue(defSet - defRise in 7 * 3600L..9 * 3600L)
    }

    @Test
    fun `sun longitude marks equinox, solstice and perseid maximum`() {
        assertEquals(0.0, Astronomy.solarLongitude(utcMs(2026, 3, 20, 14, 46)).let { if (it > 180) it - 360 else it }, 0.02)
        assertEquals(90.0, Astronomy.solarLongitude(utcMs(2026, 6, 21, 8, 24)), 0.02)
        // Perseid maximum (λ☉ 140.0°) stays within Aug 11-13 for the whole century.
        for (year in listOf(2026, 2076, 2126)) {
            val start = Astronomy.solarLongitude(utcMs(year, 8, 11, 0, 0))
            val end = Astronomy.solarLongitude(utcMs(year, 8, 14, 0, 0))
            assertTrue("$year: $start..$end", start < 140.0 && end > 140.0)
        }
    }
}
