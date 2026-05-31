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
}
