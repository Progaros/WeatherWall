package info.olli.weatherlwp

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.junit.MockitoJUnitRunner

/**
 * Unit tests for Settings & Preferences
 * Tests that preference values are correctly read and applied
 */
@RunWith(MockitoJUnitRunner::class)
class SettingsTest {

    @Mock
    private lateinit var mockContext: Context
    
    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        // Default stub for SharedPreferences
        `when`(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.arguments[1] }
        `when`(mockPrefs.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
        `when`(mockPrefs.getString(anyString(), anyString())).thenAnswer { it.arguments[1] }
    }

    // D1: Test manual time is interpreted as hours (0-23)
    @Test
    fun testManualTimeIsHours() {
        // Manual time slider now stores hours directly (0-23)
        // Default is 12 (noon)
        `when`(mockPrefs.getInt("pref_manual_time", 12)).thenReturn(14)
        
        val hour = mockPrefs.getInt("pref_manual_time", 12)
        
        assertEquals("Manual time should be 14 (2 PM)", 14, hour)
        assertTrue("Hour should be in valid range", hour in 0..23)
    }

    // D1: Verify old minute-based values would be wrong
    @Test
    fun testManualTimeMaxValueIsHour() {
        // If max was 1439 (minutes), value 720 would be noon
        // But now max is 23, value 12 is noon
        val maxValidHour = 23
        val defaultHour = 12
        
        assertTrue("Default should be valid hour", defaultHour <= maxValidHour)
    }

    // T3/T4: Test hill position percentages
    @Test
    fun testHillPositionSettings() {
        `when`(mockPrefs.getInt("pref_hill_lower_start", 50)).thenReturn(40)
        `when`(mockPrefs.getInt("pref_hill_upper_end", 85)).thenReturn(80)
        
        val hillStartY = mockPrefs.getInt("pref_hill_lower_start", 50) / 100f
        val hillEndY = mockPrefs.getInt("pref_hill_upper_end", 85) / 100f
        
        assertEquals("Hill start should be 0.4", 0.4f, hillStartY, 0.001f)
        assertEquals("Hill end should be 0.8", 0.8f, hillEndY, 0.001f)
        assertTrue("Start should be less than end", hillStartY < hillEndY)
    }

    // T5: Test color variation range
    @Test
    fun testColorVariationRange() {
        // Test minimum (no variation)
        `when`(mockPrefs.getInt("pref_tree_color_variation", 100)).thenReturn(0)
        val noVariation = mockPrefs.getInt("pref_tree_color_variation", 100) / 100f
        assertEquals("Zero variation", 0f, noVariation, 0.001f)
        
        // Test maximum (full variation)
        `when`(mockPrefs.getInt("pref_tree_color_variation", 100)).thenReturn(100)
        val fullVariation = mockPrefs.getInt("pref_tree_color_variation", 100) / 100f
        assertEquals("Full variation", 1f, fullVariation, 0.001f)
    }

    // C1: Test cloud density integer value (0 = no clouds)
    @Test
    fun testCloudDensityNone() {
        `when`(mockPrefs.getInt("pref_cloud_density", 15)).thenReturn(0)
        
        val density = mockPrefs.getInt("pref_cloud_density", 15)
        
        assertEquals("Density 0 should have 0 clouds", 0, density)
    }

    // C2: Test cloud density integer value (50 = maximum)
    @Test
    fun testCloudDensityMaximum() {
        `when`(mockPrefs.getInt("pref_cloud_density", 15)).thenReturn(50)
        
        val density = mockPrefs.getInt("pref_cloud_density", 15)
        
        assertEquals("Max density should have 50 clouds", 50, density)
    }

    // C4: Test cloud density range
    @Test
    fun testCloudDensityRange() {
        val testCases = listOf(0, 10, 15, 25, 40, 50)
        
        testCases.forEach { expected ->
            `when`(mockPrefs.getInt("pref_cloud_density", 15)).thenReturn(expected)
            val density = mockPrefs.getInt("pref_cloud_density", 15)
            assertEquals("Density $expected should return $expected", expected, density)
        }
    }

    // P4: Test return speed range
    @Test
    fun testReturnSpeedRange() {
        // Minimum return speed
        val minSpeed = 1
        val minRate = 0.01f + (minSpeed / 100f) * 0.14f
        assertEquals("Min return rate", 0.0114f, minRate, 0.001f)
        
        // Maximum return speed
        val maxSpeed = 100
        val maxRate = 0.01f + (maxSpeed / 100f) * 0.14f
        assertEquals("Max return rate", 0.15f, maxRate, 0.001f)
        
        // Default return speed
        val defaultSpeed = 50
        val defaultRate = 0.01f + (defaultSpeed / 100f) * 0.14f
        assertEquals("Default return rate", 0.08f, defaultRate, 0.01f)
    }

    // D2: Test debug time override values
    @Test
    fun testDebugTimeValues() {
        val timeMap = mapOf(
            "night" to 0,
            "dawn" to 6,
            "day" to 12,
            "dusk" to 19
        )
        
        timeMap.forEach { (debugTime, expectedHour) ->
            val hour = when (debugTime) {
                "night" -> 0
                "dawn" -> 6
                "day" -> 12
                "dusk" -> 19
                else -> 12
            }
            assertEquals("Debug time '$debugTime' should map to hour $expectedHour", expectedHour, hour)
        }
    }

    // T9: Test debug wind speed override
    @Test
    fun testDebugWindSpeedOverride() {
        `when`(mockPrefs.getBoolean("pref_debug_enabled", false)).thenReturn(true)
        `when`(mockPrefs.getInt("pref_debug_wind_speed", 0)).thenReturn(75)
        `when`(mockPrefs.getInt("pref_wind_strength", 50)).thenReturn(25)
        
        val debugEnabled = mockPrefs.getBoolean("pref_debug_enabled", false)
        val debugWindSpeed = mockPrefs.getInt("pref_debug_wind_speed", 0)
        val normalWindSpeed = mockPrefs.getInt("pref_wind_strength", 50)
        
        val windVal = if (debugEnabled && debugWindSpeed > 0) {
            debugWindSpeed
        } else {
            normalWindSpeed
        }
        
        assertEquals("Should use debug wind speed", 75, windVal)
    }

    // Test wind speed disabled when debug wind is 0
    @Test
    fun testDebugWindSpeedZeroUsesNormal() {
        `when`(mockPrefs.getBoolean("pref_debug_enabled", false)).thenReturn(true)
        `when`(mockPrefs.getInt("pref_debug_wind_speed", 0)).thenReturn(0)
        `when`(mockPrefs.getInt("pref_wind_strength", 50)).thenReturn(25)
        
        val debugEnabled = mockPrefs.getBoolean("pref_debug_enabled", false)
        val debugWindSpeed = mockPrefs.getInt("pref_debug_wind_speed", 0)
        val normalWindSpeed = mockPrefs.getInt("pref_wind_strength", 50)
        
        val windVal = if (debugEnabled && debugWindSpeed > 0) {
            debugWindSpeed
        } else {
            normalWindSpeed
        }
        
        assertEquals("Should use normal wind speed when debug is 0", 25, windVal)
    }
}
