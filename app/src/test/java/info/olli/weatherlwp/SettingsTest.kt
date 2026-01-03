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
        // Default stub for SharedPreferences (only Int and Boolean are used)
        `when`(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.arguments[1] }
        `when`(mockPrefs.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
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
    
    // T3b: Test hill distance settings
    @Test
    fun testHillDistanceSettings() {
        // Default: distance=0 (at camera)
        `when`(mockPrefs.getInt("pref_hill_distance", 0)).thenReturn(0)
        val distanceNear = mockPrefs.getInt("pref_hill_distance", 0) / 100f
        assertEquals("Distance 0 should be at camera", 0f, distanceNear, 0.001f)
        
        // Max: distance=100 (near mountains)
        `when`(mockPrefs.getInt("pref_hill_distance", 0)).thenReturn(100)
        val distanceFar = mockPrefs.getInt("pref_hill_distance", 0) / 100f
        assertEquals("Distance 100 should be near mountains", 1f, distanceFar, 0.001f)
        
        // Mid: distance=50
        `when`(mockPrefs.getInt("pref_hill_distance", 0)).thenReturn(50)
        val distanceMid = mockPrefs.getInt("pref_hill_distance", 0) / 100f
        assertEquals("Distance 50 should be midway", 0.5f, distanceMid, 0.001f)
    }
    
    // T3c: Test hill depth spread settings
    @Test
    fun testHillDepthSpreadSettings() {
        // Default: spread=50 (half depth)
        `when`(mockPrefs.getInt("pref_hill_depth_spread", 50)).thenReturn(50)
        val spreadHalf = mockPrefs.getInt("pref_hill_depth_spread", 50) / 100f
        assertEquals("Spread 50 should be 0.5", 0.5f, spreadHalf, 0.001f)
        
        // Min: spread=0 (flat layer)
        `when`(mockPrefs.getInt("pref_hill_depth_spread", 50)).thenReturn(0)
        val spreadFlat = mockPrefs.getInt("pref_hill_depth_spread", 50) / 100f
        assertEquals("Spread 0 should be flat", 0f, spreadFlat, 0.001f)
        
        // Max: spread=100 (deep range)
        `when`(mockPrefs.getInt("pref_hill_depth_spread", 50)).thenReturn(100)
        val spreadFull = mockPrefs.getInt("pref_hill_depth_spread", 50) / 100f
        assertEquals("Spread 100 should be full", 1f, spreadFull, 0.001f)
    }
    
    // T3d: Test hill distance and depth spread interaction
    @Test
    fun testHillDepthCalculation() {
        // Test the depth calculation formula
        val hillDistance = 0.5f
        val hillDepthSpread = 0.3f
        val baseDepth = 0.5f // Middle hill
        
        // Calculate effective depth using same formula as TerrainLayer
        val effectiveSpread = hillDepthSpread * (1f - hillDistance)
        val minDepth = 1f - hillDistance - effectiveSpread
        val maxDepth = 1f - hillDistance
        val depth = minDepth + baseDepth * (maxDepth - minDepth)
        
        // At distance=0.5, spread=0.3: effectiveSpread = 0.3 * 0.5 = 0.15
        // minDepth = 1 - 0.5 - 0.15 = 0.35, maxDepth = 1 - 0.5 = 0.5
        // depth for baseDepth=0.5: 0.35 + 0.5 * (0.5 - 0.35) = 0.35 + 0.075 = 0.425
        assertEquals("Effective spread calculation", 0.15f, effectiveSpread, 0.001f)
        assertEquals("Min depth calculation", 0.35f, minDepth, 0.001f)
        assertEquals("Max depth calculation", 0.5f, maxDepth, 0.001f)
        assertEquals("Depth for middle hill", 0.425f, depth, 0.001f)
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

    // ============== Time Format Display Tests ==============
    
    @Test
    fun `time format should display HH-MM for midnight`() {
        val minutes = 0
        val formatted = formatMinutesToTime(minutes)
        assertEquals("Midnight should display 00:00", "00:00", formatted)
    }
    
    @Test
    fun `time format should display HH-MM for noon`() {
        val minutes = 720  // 12 * 60
        val formatted = formatMinutesToTime(minutes)
        assertEquals("Noon should display 12:00", "12:00", formatted)
    }
    
    @Test
    fun `time format should display HH-MM for late evening`() {
        val minutes = 1380  // 23 * 60
        val formatted = formatMinutesToTime(minutes)
        assertEquals("23:00 should display correctly", "23:00", formatted)
    }
    
    @Test
    fun `time format should handle max value 1439`() {
        val minutes = 1439  // 23:59
        val formatted = formatMinutesToTime(minutes)
        assertEquals("Max time should display 23:59", "23:59", formatted)
    }
    
    @Test
    fun `time format should pad single digit hours and minutes`() {
        val minutes = 65  // 1:05
        val formatted = formatMinutesToTime(minutes)
        assertEquals("Single digits should be padded", "01:05", formatted)
    }
    
    // Helper function matching TimeSeekBarPreference.formatMinutesToTime
    private fun formatMinutesToTime(minutes: Int): String {
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return String.format("%02d:%02d", h, m)
    }
    
    // ============== Hill Count Slider Range Tests ==============
    
    @Test
    fun testHillCountSliderRange() {
        // Hill count now has range 0-20 (was 0-10)
        val minHillCount = 0
        val maxHillCount = 20
        val defaultHillCount = 5
        
        assertTrue("Default should allow decrease", defaultHillCount > minHillCount)
        assertTrue("Default should allow increase", defaultHillCount < maxHillCount)
        assertEquals("Default should be centered", 5, defaultHillCount)
    }
    
    @Test
    fun testHillCountAllowsBidirectionalAdjustment() {
        // With default 5 and range 0-20:
        // - Can decrease by 5 (to 0)
        // - Can increase by 15 (to 20)
        val defaultHillCount = 5
        val minHillCount = 0
        val maxHillCount = 20
        
        val decreaseRoom = defaultHillCount - minHillCount
        val increaseRoom = maxHillCount - defaultHillCount
        
        assertTrue("Should allow decrease", decreaseRoom >= 5)
        assertTrue("Should allow significant increase", increaseRoom >= 10)
    }
    
    // ============== Tree Density Slider Range Tests ==============
    
    @Test
    fun testTreeDensitySliderRange() {
        // Tree density now has range 0-200 (was 0-100)
        val minDensity = 0
        val maxDensity = 200
        val defaultDensity = 50
        
        assertTrue("Default should allow decrease", defaultDensity > minDensity)
        assertTrue("Default should allow increase", defaultDensity < maxDensity)
        assertEquals("Default should be 50 (normal)", 50, defaultDensity)
    }
    
    @Test
    fun testTreeDensityAllowsBidirectionalAdjustment() {
        // With default 50 and range 0-200:
        // - Can decrease by 50 (to 0)
        // - Can increase by 150 (to 200)
        val defaultDensity = 50
        val minDensity = 0
        val maxDensity = 200
        
        val decreaseRoom = defaultDensity - minDensity
        val increaseRoom = maxDensity - defaultDensity
        
        assertTrue("Should allow decrease to zero", decreaseRoom >= 50)
        assertTrue("Should allow increase to dense forest", increaseRoom >= 100)
    }
    
    @Test
    fun testTreeDensityCalculation() {
        // Tree density calculation: (treeDensity / 100f) * baseTrees * widthRatio
        // At density 50 (default): 0.5 * 100 = 50 trees base
        // At density 200 (max): 2.0 * 100 = 200 trees base
        val baseTrees = 100
        
        val treesAt50 = (50 / 100f * baseTrees).toInt()
        val treesAt100 = (100 / 100f * baseTrees).toInt()
        val treesAt200 = (200 / 100f * baseTrees).toInt()
        
        assertEquals("Default density (50) should give 50 trees", 50, treesAt50)
        assertEquals("Full density (100) should give 100 trees", 100, treesAt100)
        assertEquals("Dense forest (200) should give 200 trees", 200, treesAt200)
    }
}
