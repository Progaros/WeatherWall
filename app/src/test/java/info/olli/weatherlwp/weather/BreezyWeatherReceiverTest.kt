package info.olli.weatherlwp.weather

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for BreezyWeatherReceiver.
 *
 * Covers:
 * - kelvinOrCelsiusToC threshold (bug fix: raised from >100 to >200)
 * - Temperature conversion correctness for Kelvin and Celsius inputs
 * - NaN handling
 */
class BreezyWeatherReceiverTest {

    // ============== kelvinOrCelsiusToC Threshold Tests ==============

    @Test
    fun `celsius values below threshold pass through unchanged`() {
        // Normal Celsius temperatures should pass through
        assertEquals(0f, BreezyWeatherReceiver.kelvinOrCelsiusToC(0.0), 0.01f)
        assertEquals(25f, BreezyWeatherReceiver.kelvinOrCelsiusToC(25.0), 0.01f)
        assertEquals(-10f, BreezyWeatherReceiver.kelvinOrCelsiusToC(-10.0), 0.01f)
        assertEquals(45f, BreezyWeatherReceiver.kelvinOrCelsiusToC(45.0), 0.01f)
    }

    @Test
    fun `high celsius values below 200 are not misinterpreted as kelvin`() {
        // Bug regression: threshold was 100, values like 150 were wrongly treated as Kelvin
        // 150°C is impossible outdoors but must not be converted as Kelvin (-123°C)
        val result = BreezyWeatherReceiver.kelvinOrCelsiusToC(150.0)
        assertEquals("150 should be treated as Celsius, not Kelvin", 150f, result, 0.01f)
    }

    @Test
    fun `exactly 200 is treated as celsius`() {
        // Boundary: > 200 means Kelvin, so exactly 200 stays Celsius
        val result = BreezyWeatherReceiver.kelvinOrCelsiusToC(200.0)
        assertEquals(200f, result, 0.01f)
    }

    @Test
    fun `values above 200 are converted from kelvin`() {
        // 273.15 K = 0°C
        assertEquals(0f, BreezyWeatherReceiver.kelvinOrCelsiusToC(273.15), 0.01f)
        // 293.15 K = 20°C
        assertEquals(20f, BreezyWeatherReceiver.kelvinOrCelsiusToC(293.15), 0.01f)
        // 310 K ≈ 36.85°C
        assertEquals(36.85f, BreezyWeatherReceiver.kelvinOrCelsiusToC(310.0), 0.01f)
    }

    @Test
    fun `201 is treated as kelvin`() {
        // Just above threshold: 201K = -72.15°C
        val result = BreezyWeatherReceiver.kelvinOrCelsiusToC(201.0)
        assertEquals(-72.15f, result, 0.01f)
    }

    @Test
    fun `NaN returns NaN`() {
        val result = BreezyWeatherReceiver.kelvinOrCelsiusToC(Double.NaN)
        assertTrue("NaN input should return NaN", result.isNaN())
    }

    @Test
    fun `negative celsius passes through`() {
        assertEquals(-40f, BreezyWeatherReceiver.kelvinOrCelsiusToC(-40.0), 0.01f)
    }

    // ============== Untrusted Broadcast Sanitization ==============

    @Test
    fun `sanitizeTemperature keeps plausible values`() {
        assertEquals(20f, BreezyWeatherReceiver.sanitizeTemperature(20f), 0.01f)
        assertEquals(-40f, BreezyWeatherReceiver.sanitizeTemperature(-40f), 0.01f)
    }

    @Test
    fun `sanitizeTemperature clamps out of range values`() {
        assertEquals(80f, BreezyWeatherReceiver.sanitizeTemperature(5000f), 0.01f)
        assertEquals(-120f, BreezyWeatherReceiver.sanitizeTemperature(-9999f), 0.01f)
    }

    @Test
    fun `sanitizeTemperature maps non-finite input to NaN`() {
        // coerceIn alone would pass NaN straight through to the renderer
        assertTrue(BreezyWeatherReceiver.sanitizeTemperature(Float.NaN).isNaN())
        assertTrue(BreezyWeatherReceiver.sanitizeTemperature(Float.POSITIVE_INFINITY).isNaN())
        assertTrue(BreezyWeatherReceiver.sanitizeTemperature(Float.NEGATIVE_INFINITY).isNaN())
    }

    @Test
    fun `sanitizeWindSpeedToMs converts kmh to ms`() {
        assertEquals(10f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(36.0), 0.01f)
        assertEquals(0f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(0.0), 0.01f)
    }

    @Test
    fun `sanitizeWindSpeedToMs rejects non-finite and negative input`() {
        assertEquals(0f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(Double.NaN), 0.01f)
        assertEquals(0f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(Double.POSITIVE_INFINITY), 0.01f)
        assertEquals(0f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(-50.0), 0.01f)
    }

    @Test
    fun `sanitizeWindSpeedToMs caps absurd speeds`() {
        // 500 km/h ceiling => ~138.9 m/s
        assertEquals(138.9f, BreezyWeatherReceiver.sanitizeWindSpeedToMs(1_000_000.0), 0.1f)
    }

    @Test
    fun `sanitizeEpochSeconds keeps plausible timestamps`() {
        assertEquals(1_700_000_000L, BreezyWeatherReceiver.sanitizeEpochSeconds(1_700_000_000L))
        assertEquals(0L, BreezyWeatherReceiver.sanitizeEpochSeconds(0L))
    }

    @Test
    fun `sanitizeEpochSeconds maps out of range timestamps to unknown`() {
        assertEquals(-1L, BreezyWeatherReceiver.sanitizeEpochSeconds(-1L))
        assertEquals(-1L, BreezyWeatherReceiver.sanitizeEpochSeconds(-999L))
        assertEquals(-1L, BreezyWeatherReceiver.sanitizeEpochSeconds(Long.MAX_VALUE))
    }

    @Test
    fun `condition codes map to weather types`() {
        val expected = mapOf(
            211 to WeatherType.THUNDERSTORM,
            301 to WeatherType.RAIN,
            511 to WeatherType.RAIN,
            601 to WeatherType.SNOW,
            613 to WeatherType.HAIL,
            621 to WeatherType.SNOW,
            701 to WeatherType.FOG,
            741 to WeatherType.FOG,
            762 to WeatherType.FOG,
            771 to WeatherType.THUNDERSTORM,
            781 to WeatherType.THUNDERSTORM,
            800 to WeatherType.CLEAR,
            801 to WeatherType.PARTLY_CLOUDY,
            802 to WeatherType.PARTLY_CLOUDY,
            803 to WeatherType.CLOUDY,
            804 to WeatherType.OVERCAST,
            999 to WeatherType.CLEAR
        )
        expected.forEach { (code, type) ->
            assertEquals("code $code", type, BreezyWeatherReceiver.mapConditionCode(code))
        }
    }
}
