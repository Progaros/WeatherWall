package com.example.weatherlwp.simulation

import com.example.weatherlwp.weather.WeatherType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SimulationState immutable data class.
 */
class SimulationStateTest {

    @Test
    fun `default state is created with sane values`() {
        val state = SimulationState.default()
        
        assertEquals(12f, state.timeOfDayHours, 0.01f)
        assertEquals(0.5f, state.dayProgress, 0.01f)
        assertFalse(state.isNight)
        assertFalse(state.isDawn)
        assertFalse(state.isDusk)
        assertEquals(WeatherType.CLEAR, state.weather)
        assertEquals(CloudDensity.AUTO, state.cloudDensity)
        assertEquals(0.5f, state.windStrength, 0.01f)
        assertEquals(0f, state.windDirection, 0.01f)
        assertEquals(1080f, state.screenWidth, 0.01f)
        assertEquals(1920f, state.screenHeight, 0.01f)
        assertFalse(state.isDebugMode)
    }

    @Test
    fun `getWindX returns correct value for right wind`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 0f
        )
        
        assertEquals(1f, state.getWindX(), 0.01f)
        assertEquals(0f, state.getWindY(), 0.01f)
    }

    @Test
    fun `getWindY returns correct value for up wind`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 90f
        )
        
        assertEquals(0f, state.getWindX(), 0.01f)
        assertEquals(1f, state.getWindY(), 0.01f)
    }

    @Test
    fun `getCloudCount returns 0 for NONE density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.NONE)
        assertEquals(0, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 15 for LIGHT density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.LIGHT)
        assertEquals(15, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 40 for MEDIUM density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.MEDIUM)
        assertEquals(40, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 80 for HEAVY density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.HEAVY)
        assertEquals(80, state.getCloudCount())
    }

    @Test
    fun `getCloudCount returns 150 for OVERCAST density`() {
        val state = SimulationState.default().copy(cloudDensity = CloudDensity.OVERCAST)
        assertEquals(150, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLEAR`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLEAR
        )
        assertEquals(8, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - PARTLY_CLOUDY`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.PARTLY_CLOUDY
        )
        assertEquals(30, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - CLOUDY`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.CLOUDY
        )
        assertEquals(60, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - OVERCAST`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.OVERCAST
        )
        assertEquals(150, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - RAIN`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.RAIN
        )
        assertEquals(100, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - THUNDERSTORM`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.THUNDERSTORM
        )
        assertEquals(100, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - SNOW`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.SNOW
        )
        assertEquals(70, state.getCloudCount())
    }

    @Test
    fun `getCloudCount for AUTO depends on weather - FOG`() {
        val state = SimulationState.default().copy(
            cloudDensity = CloudDensity.AUTO,
            weather = WeatherType.FOG
        )
        assertEquals(120, state.getCloudCount())
    }

    @Test
    fun `state is immutable - copy creates new instance`() {
        val state1 = SimulationState.default()
        val state2 = state1.copy(windStrength = 0.8f)
        
        assertNotSame(state1, state2)
        assertEquals(0.5f, state1.windStrength, 0.01f)
        assertEquals(0.8f, state2.windStrength, 0.01f)
    }

    @Test
    fun `absoluteTimeMs is captured correctly`() {
        val beforeTime = System.currentTimeMillis()
        val state = SimulationState.default()
        val afterTime = System.currentTimeMillis()
        
        assertTrue(state.absoluteTimeMs >= beforeTime)
        assertTrue(state.absoluteTimeMs <= afterTime)
    }

    @Test
    fun `diagonal wind has both X and Y components`() {
        val state = SimulationState.default().copy(
            windStrength = 1f,
            windDirection = 45f
        )
        
        // At 45 degrees, both components should be ~0.707
        val expected = kotlin.math.sqrt(2f) / 2f
        assertEquals(expected, state.getWindX(), 0.01f)
        assertEquals(expected, state.getWindY(), 0.01f)
    }

    @Test
    fun `zero wind strength gives zero components`() {
        val state = SimulationState.default().copy(
            windStrength = 0f,
            windDirection = 45f
        )
        
        assertEquals(0f, state.getWindX(), 0.01f)
        assertEquals(0f, state.getWindY(), 0.01f)
    }
}
