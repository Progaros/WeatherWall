package info.olli.weatherlwp

import android.content.SharedPreferences
import info.olli.weatherlwp.settings.SettingsExporter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [SettingsExporter] — the "Copy All Settings" feature.
 */
@RunWith(MockitoJUnitRunner::class)
class SettingsExporterTest {

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        // Default: empty prefs
        `when`(mockPrefs.all).thenReturn(emptyMap())
    }

    // --- Header ---

    @Test
    fun emptyPrefs_containsHeader() {
        val result = SettingsExporter.export(mockPrefs)
        assertTrue("Should contain header", result.startsWith("# WeatherWall Settings Export"))
    }

    @Test
    fun emptyPrefs_showsNoPreferencesMessage() {
        val result = SettingsExporter.export(mockPrefs)
        assertTrue("Should mention no prefs stored", result.contains("no preferences stored"))
    }

    // --- Basic types ---

    @Test
    fun integerValue_formattedCorrectly() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_cloud_speed" to 5))
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_cloud_speed = 5  # Int"))
    }

    @Test
    fun booleanValue_formattedCorrectly() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_parallax_enabled" to true))
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_parallax_enabled = true  # Boolean"))
    }

    @Test
    fun stringValue_quoted() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_parallax_sensor_mode" to "gyroscope"))
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_parallax_sensor_mode = \"gyroscope\"  # String"))
    }

    @Test
    fun floatValue_formattedCorrectly() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_some_float" to 0.5f))
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_some_float = 0.5  # Float"))
    }

    @Test
    fun longValue_formattedCorrectly() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_some_long" to 123456L))
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_some_long = 123456  # Long"))
    }

    @Test
    fun stringSetValue_formattedAsSortedList() {
        `when`(mockPrefs.all).thenReturn(
            mapOf("pref_tags" to setOf("beta", "alpha", "gamma"))
        )
        val result = SettingsExporter.export(mockPrefs)
        assertTrue(result.contains("pref_tags = [alpha, beta, gamma]  # StringSet"))
    }

    // --- Sorting ---

    @Test
    fun multipleEntries_sortedAlphabetically() {
        `when`(mockPrefs.all).thenReturn(
            mapOf(
                "pref_z_last" to 1,
                "pref_a_first" to 2
            )
        )
        val result = SettingsExporter.export(mockPrefs)
        val indexA = result.indexOf("pref_a_first")
        val indexZ = result.indexOf("pref_z_last")
        assertTrue("'a' key should come before 'z' key", indexA < indexZ)
    }

    // --- Null handling ---

    @Test
    fun nullValue_skipped() {
        val line = SettingsExporter.formatEntry("pref_null", null)
        assertNull("Null value should produce null line", line)
    }

    // --- formatEntry unit tests ---

    @Test
    fun formatEntry_boolean() {
        assertEquals(
            "pref_x = false  # Boolean",
            SettingsExporter.formatEntry("pref_x", false)
        )
    }

    @Test
    fun formatEntry_int() {
        assertEquals(
            "pref_x = 42  # Int",
            SettingsExporter.formatEntry("pref_x", 42)
        )
    }

    @Test
    fun formatEntry_string() {
        assertEquals(
            "pref_x = \"hello\"  # String",
            SettingsExporter.formatEntry("pref_x", "hello")
        )
    }

    // --- Integration-style: realistic prefs ---

    @Test
    fun realisticSnapshot_containsAllEntries() {
        `when`(mockPrefs.all).thenReturn(
            mapOf(
                "pref_parallax_enabled" to true,
                "pref_parallax_strength" to 25,
                "pref_parallax_sensor_mode" to "gyroscope",
                "pref_cloud_speed" to 5,
                "pref_max_fps" to "30"
            )
        )
        val result = SettingsExporter.export(mockPrefs)

        assertTrue(result.contains("pref_cloud_speed = 5  # Int"))
        assertTrue(result.contains("pref_max_fps = \"30\"  # String"))
        assertTrue(result.contains("pref_parallax_enabled = true  # Boolean"))
        assertTrue(result.contains("pref_parallax_sensor_mode = \"gyroscope\"  # String"))
        assertTrue(result.contains("pref_parallax_strength = 25  # Int"))

        // Should contain exactly 5 non-header lines
        val dataLines = result.lines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(5, dataLines.size)
    }

    @Test
    fun exportDoesNotEndWithTrailingNewline() {
        `when`(mockPrefs.all).thenReturn(mapOf("pref_a" to 1))
        val result = SettingsExporter.export(mockPrefs)
        assertFalse("Should not end with newline", result.endsWith("\n"))
    }
}
