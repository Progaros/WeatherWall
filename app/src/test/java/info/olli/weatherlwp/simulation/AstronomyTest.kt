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
